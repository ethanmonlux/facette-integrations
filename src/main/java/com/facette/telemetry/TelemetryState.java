/*
 * Copyright (c) 2026, Ethan Monlux
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.facette.telemetry;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The single in-memory current snapshot, plus the sequencing and refresh rules that decide
 * when it is published.
 *
 * <p>Holds no RuneLite types on purpose: {@link FacetteTelemetryPlugin} translates game
 * events into the calls below, so every normalization, nulling, and sequencing rule here is
 * exercisable without a game client.
 *
 * <p>Threading: mutators are called from the RuneLite client thread and the publication
 * methods from a single publisher thread, so every method is synchronized on this instance.
 * {@link #nextSnapshot(boolean)} and {@link #recordPublished()} are a pair and assume one
 * publisher at a time, which is what {@link FacetteTelemetryPlugin} provides.
 *
 * <p>The per-skill experience baselines are session-local comparison state and are never
 * exported.
 */
final class TelemetryState
{
	/** Old School inventory capacity, in slots. */
	static final int INVENTORY_CAPACITY = 28;

	/** Divisor converting RuneLite's 1/100th-of-a-percent run energy into whole percent. */
	private static final int RUN_ENERGY_SCALE = 100;

	private static final String UNKNOWN_GAME_STATE = "UNKNOWN";

	private final String instanceId;
	private final LongSupplier clock;

	/** Session-local total-experience baselines, keyed by lowercase skill name. */
	private final Map<String, Integer> xpBaselines = new HashMap<>();

	private boolean dirty = true;

	/** Monotonic counter incremented whenever a change marks the state dirty. */
	private long version;

	/** Change counter the snapshot handed out by {@link #nextSnapshot(boolean)} reflects. */
	private long pendingVersion;

	private long nextSeq;
	private long lastPublishAtMillis;

	private String gameState = UNKNOWN_GAME_STATE;
	private boolean loggedIn;
	private Integer world;

	private Integer hitpointsCurrent;
	private Integer hitpointsBase;
	private Integer prayerCurrent;
	private Integer prayerBase;
	private Integer runEnergyPercent;

	private Integer usedSlots;
	private Integer freeSlots;

	private String lastSkill;
	private Integer lastDelta;
	private Long lastChangedAt;

	TelemetryState(String instanceId, LongSupplier clock)
	{
		this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	String getInstanceId()
	{
		return instanceId;
	}

	synchronized long getNextSeq()
	{
		return nextSeq;
	}

	synchronized boolean isDirty()
	{
		return dirty;
	}

	/**
	 * Records the current client game state.
	 *
	 * <p>Leaving the logged-in state discards every player-derived value rather than letting
	 * the last live reading persist into a logged-out snapshot.
	 */
	synchronized void updateSession(String gameStateName, boolean nowLoggedIn)
	{
		String name = gameStateName == null ? UNKNOWN_GAME_STATE : gameStateName;
		markIfChanged(gameState, name);
		markIfChanged(loggedIn, nowLoggedIn);
		gameState = name;
		loggedIn = nowLoggedIn;

		if (!loggedIn)
		{
			clearPlayerDerived();
		}
	}

	/**
	 * Discards the session-local experience baselines so a later login cannot inherit a
	 * previous session's comparison and report a fabricated gain.
	 */
	synchronized void endSession()
	{
		xpBaselines.clear();
		markIfChanged(lastSkill, null);
		lastSkill = null;
		lastDelta = null;
		lastChangedAt = null;
	}

	synchronized void updateWorld(Integer newWorld)
	{
		if (!loggedIn)
		{
			return;
		}
		markIfChanged(world, newWorld);
		world = newWorld;
	}

	/**
	 * Records the player's vitals.
	 *
	 * @param rawRunEnergy run energy exactly as RuneLite reports it, in units of 1/100th of
	 *                     a percent; normalized here to whole percent in 0..100
	 */
	synchronized void updateVitals(int hpCurrent, int hpBase, int prayCurrent, int prayBase, int rawRunEnergy)
	{
		if (!loggedIn)
		{
			return;
		}
		Integer energy = clamp(rawRunEnergy / RUN_ENERGY_SCALE, 0, 100);
		markIfChanged(hitpointsCurrent, hpCurrent);
		markIfChanged(hitpointsBase, hpBase);
		markIfChanged(prayerCurrent, prayCurrent);
		markIfChanged(prayerBase, prayBase);
		markIfChanged(runEnergyPercent, energy);
		hitpointsCurrent = hpCurrent;
		hitpointsBase = hpBase;
		prayerCurrent = prayCurrent;
		prayerBase = prayBase;
		runEnergyPercent = energy;
	}

	/**
	 * Records inventory occupancy.
	 *
	 * @param occupiedSlots number of inventory slots holding an item, not a total item
	 *                      quantity
	 */
	synchronized void updateInventory(int occupiedSlots)
	{
		if (!loggedIn)
		{
			return;
		}
		Integer used = clamp(occupiedSlots, 0, INVENTORY_CAPACITY);
		Integer free = INVENTORY_CAPACITY - used;
		markIfChanged(usedSlots, used);
		markIfChanged(freeSlots, free);
		usedSlots = used;
		freeSlots = free;
	}

	/**
	 * Observes a skill's total experience.
	 *
	 * <p>The first observation for a skill in a session only seeds the comparison; it never
	 * reports a gain. Only a subsequent increase updates the exported experience fields. The
	 * total itself is never exported.
	 *
	 * @return true when this observation produced a reportable gain
	 */
	synchronized boolean observeXp(String skillName, int totalXp)
	{
		if (!loggedIn || skillName == null)
		{
			return false;
		}
		String skill = skillName.toLowerCase(Locale.ROOT);
		Integer previous = xpBaselines.put(skill, totalXp);
		if (previous == null || totalXp <= previous)
		{
			return false;
		}

		lastSkill = skill;
		lastDelta = totalXp - previous;
		lastChangedAt = clock.getAsLong();
		markDirty();
		return true;
	}

	/**
	 * Whether a publication is due: either the state changed, or the last publication is old
	 * enough that a reader needs a fresh heartbeat to distinguish a live plugin from a stale
	 * file.
	 */
	synchronized boolean isPublicationDue(long heartbeatIntervalMillis)
	{
		return dirty || clock.getAsLong() - lastPublishAtMillis >= heartbeatIntervalMillis;
	}

	/**
	 * Builds the next snapshot to publish, carrying the sequence number that will be
	 * consumed only if it reaches the file.
	 *
	 * @param pluginActive whether the plugin is still running; a final shutdown snapshot
	 *                     passes false, which also forces every gameplay-derived field null
	 */
	synchronized TelemetrySnapshot nextSnapshot(boolean pluginActive)
	{
		pendingVersion = version;

		// Defense in depth: a snapshot never carries player-derived values unless the client
		// is live and the plugin is running, whatever the fields currently hold.
		boolean live = pluginActive && loggedIn;
		TelemetrySnapshot.Builder b = TelemetrySnapshot.builder()
			.instanceId(instanceId)
			.seq(nextSeq)
			.emittedAt(clock.getAsLong())
			.pluginActive(pluginActive)
			.gameState(gameState)
			.loggedIn(live);

		if (live)
		{
			b.world(world)
				.hitpoints(hitpointsCurrent, hitpointsBase)
				.prayer(prayerCurrent, prayerBase)
				.runEnergyPercent(runEnergyPercent)
				.inventory(usedSlots, freeSlots)
				.xp(lastSkill, lastDelta, lastChangedAt);
		}
		else
		{
			b.world(null)
				.hitpoints(null, null)
				.prayer(null, null)
				.runEnergyPercent(null)
				.inventory(null, null)
				.xp(null, null, null);
		}

		return b.build();
	}

	/**
	 * Records that the snapshot from the preceding {@link #nextSnapshot(boolean)} call
	 * reached the file. The sequence advances only here, so a refused or failed write leaves
	 * the number to be reused by the retry.
	 *
	 * <p>The dirty flag is cleared only when nothing changed while that snapshot was being
	 * written; otherwise the change is republished rather than dropped.
	 */
	synchronized void recordPublished()
	{
		nextSeq++;
		lastPublishAtMillis = clock.getAsLong();
		if (version == pendingVersion)
		{
			dirty = false;
		}
	}

	private void clearPlayerDerived()
	{
		markIfChanged(world, null);
		markIfChanged(hitpointsCurrent, null);
		markIfChanged(usedSlots, null);
		markIfChanged(lastSkill, null);

		world = null;
		hitpointsCurrent = null;
		hitpointsBase = null;
		prayerCurrent = null;
		prayerBase = null;
		runEnergyPercent = null;
		usedSlots = null;
		freeSlots = null;
		lastSkill = null;
		lastDelta = null;
		lastChangedAt = null;
	}

	private void markIfChanged(Object current, Object next)
	{
		if (!Objects.equals(current, next))
		{
			markDirty();
		}
	}

	private void markDirty()
	{
		dirty = true;
		version++;
	}

	private static Integer clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}
}
