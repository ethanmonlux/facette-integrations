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
 * methods from whichever thread is publishing, so every method is synchronized on this
 * instance. {@link #nextSnapshot(boolean)} and {@link #recordPublished()} are a pair that
 * requires one publication at a time; {@link FacetteTelemetryPlugin} guarantees that with a
 * lock held across the whole build-write-record sequence, including the final write on
 * shutdown, which otherwise could race the publisher thread.
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

	/**
	 * RuneLite's aggregate-experience sentinel, which is not a real trainable skill. Compared
	 * lowercase, matching how skill names are normalized here.
	 */
	private static final String OVERALL_SKILL_NAME = "overall";

	private final String instanceId;
	private final LongSupplier clock;

	/** Session-local total-experience baselines, keyed by lowercase skill name. */
	private final Map<String, Integer> xpBaselines = new HashMap<>();

	/**
	 * Earliest total experience seen per skill while the run was still starting, keyed by
	 * lowercase skill name and emptied once initialization completes.
	 *
	 * <p>Startup is deferred onto the client thread, so experience events can arrive before
	 * any baseline exists. Their totals are held here rather than dropped, because seeding
	 * afterwards from the live totals would silently absorb every gain that landed in that
	 * window — and the window is as long as the client takes to drain its queue, not a fixed
	 * tick. One entry per skill, first writer wins, so the map is bounded by the number of
	 * skills no matter how long startup stays queued.
	 */
	private final Map<String, Integer> preInitialXp = new HashMap<>();

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
		updateSession(gameStateName, nowLoggedIn, false);
	}

	/**
	 * Applies a client game-state transition as one atomic step.
	 *
	 * <p>Ending the session is folded in here rather than exposed as a second call, because
	 * two separately synchronized calls leave a window the publisher can be released into:
	 * discarding the experience baselines marks the state dirty, so a publication could
	 * observe cleared experience while the session still read as live and still carried the
	 * previous world, vitals, and inventory. One transition makes that unobservable by
	 * construction rather than by the caller happening to order the two correctly.
	 *
	 * @param sessionEnded whether reaching this state ends the play session, discarding the
	 *                     session-local experience baselines so a later login cannot inherit
	 *                     a previous session's comparison and report a fabricated gain
	 */
	synchronized void updateSession(String gameStateName, boolean nowLoggedIn, boolean sessionEnded)
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

		if (sessionEnded)
		{
			xpBaselines.clear();
			// A total observed during a startup that spanned a logout belongs to the session
			// that ended, and must not become a later login's baseline.
			preInitialXp.clear();
			markIfChanged(lastSkill, null);
			lastSkill = null;
			lastDelta = null;
			lastChangedAt = null;
		}
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
	 * Establishes a skill's comparison baseline from the client's current total, without
	 * treating it as an observation.
	 *
	 * <p>This exists because the plugin can be enabled while the player is already logged in.
	 * The login-time experience events have long since fired, so nothing has filled the
	 * baselines, and the next real gain would otherwise be consumed by
	 * {@link #observeXp(String, int)}'s first-observation rule and never exported. Seeding
	 * from the live totals means that gain is measured against the truth instead.
	 *
	 * <p>Deliberately separate from {@code observeXp} rather than reusing it: this is not an
	 * event, it can never report a gain, and it must never move a baseline that already
	 * exists. Sharing one method would make the caller's intent — and those invariants —
	 * depend on which branch happened to be taken.
	 *
	 * <p>Unlike {@code observeXp}, this rejects the {@code OVERALL} sentinel, because the
	 * caller enumerates skills programmatically and a non-real entry could appear in that
	 * enumeration; experience events, by contrast, always carry a real skill.
	 *
	 * <p>When {@link #recordPreInitialXp(String, int)} captured an earlier total for the skill,
	 * that total is the baseline instead of the live one, and the difference is reported as a
	 * genuine gain. Without this, experience earned while startup was still queued would be
	 * absorbed into the baseline and never exported, and the amount absorbed would grow with
	 * however long the client took to run the callback.
	 *
	 * @param totalXp the client's current total for the skill; zero is legitimate for an
	 *                untrained skill and is seeded, while a negative total is refused
	 * @return true when this call established a new baseline
	 */
	synchronized boolean seedXpBaseline(String skillName, int totalXp)
	{
		if (!loggedIn || skillName == null || totalXp < 0)
		{
			return false;
		}
		String skill = skillName.toLowerCase(Locale.ROOT);
		if (OVERALL_SKILL_NAME.equals(skill))
		{
			return false;
		}
		// Consumed whether or not it is used, so a total held for a skill that already has a
		// baseline cannot survive to influence anything later.
		Integer earliest = preInitialXp.remove(skill);
		// Never lowers or replaces: a baseline already established by a real observation, or
		// by an earlier seed, is the more trustworthy one.
		if (xpBaselines.containsKey(skill))
		{
			return false;
		}

		if (earliest == null || earliest >= totalXp)
		{
			xpBaselines.put(skill, totalXp);
			// Deliberately not marked dirty. Seeding alone changes nothing that is exported, so
			// it must not by itself cause a publication or advance the sequence.
			return true;
		}

		// Experience arrived while startup was still queued. The earliest total seen is the
		// closest thing to a pre-gain baseline this run will ever have, so the measurable part
		// of that experience is reported rather than buried. The baseline still advances to the
		// live total, exactly as an ordinary observation would leave it.
		xpBaselines.put(skill, totalXp);
		lastSkill = skill;
		lastDelta = totalXp - earliest;
		lastChangedAt = clock.getAsLong();
		markDirty();
		return true;
	}

	/**
	 * Retains a skill's total experience reported before the run finished initializing.
	 *
	 * <p>Deliberately does not require {@code loggedIn}: during this window the client has not
	 * been sampled yet, so the session is not yet known to be live, and refusing on that basis
	 * would discard exactly the events this exists to keep.
	 *
	 * <p>Only the first total per skill is kept. A later one is not more useful — the baseline
	 * wanted is the earliest — and ignoring it is what bounds the map at one entry per skill.
	 *
	 * <p>This can never report a gain by itself. The total it holds is already the total
	 * <em>after</em> whichever gain prompted the event, and the total before it is not
	 * knowable: experience events carry a running total, not a delta, and reading the client
	 * requires the client thread this run has not reached yet. So the first gain per skill
	 * inside the startup window is unmeasurable by construction; every later one is preserved.
	 *
	 * @return true when this call retained a total for a skill that had none
	 */
	synchronized boolean recordPreInitialXp(String skillName, int totalXp)
	{
		if (skillName == null || totalXp < 0)
		{
			return false;
		}
		String skill = skillName.toLowerCase(Locale.ROOT);
		if (OVERALL_SKILL_NAME.equals(skill))
		{
			return false;
		}
		return preInitialXp.putIfAbsent(skill, totalXp) == null;
	}

	/**
	 * Drops any retained pre-initialization totals that seeding did not consume — in practice
	 * a startup that found no live session to seed from. Nothing may carry into the initialized
	 * run, which from then on gets its baselines from observations alone.
	 */
	synchronized void discardPreInitialXp()
	{
		preInitialXp.clear();
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
		Integer previous = xpBaselines.get(skill);
		if (previous == null)
		{
			// First reading this session: seed the comparison, report nothing.
			xpBaselines.put(skill, totalXp);
			return false;
		}
		if (totalXp <= previous)
		{
			// A total that has not advanced is ignored *without* moving the baseline. Lowering
			// it would make the eventual return to the true total look like a gain the size of
			// the dip — and a transient zero, which the client can report while skill data is
			// still initializing, would then fabricate a gain the size of the whole skill.
			return false;
		}
		xpBaselines.put(skill, totalXp);

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
