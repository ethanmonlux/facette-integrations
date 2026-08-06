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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
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
 * exported. Neither is the arrival-order counter, the change counter, or the elapsed reading
 * used for cadence.
 */
final class TelemetryState
{
	/** Old School inventory capacity, in slots. */
	static final int INVENTORY_CAPACITY = TelemetrySnapshot.INVENTORY_SLOTS;

	/** Divisor converting RuneLite's 1/100th-of-a-percent run energy into whole percent. */
	private static final int RUN_ENERGY_SCALE = 100;

	/**
	 * Divisor converting the client's 1/10th-of-a-percent special attack energy into whole
	 * percent, matching how the client's own interface scales it.
	 */
	private static final int SPECIAL_ATTACK_SCALE = 10;

	/**
	 * Bounds outside which a reported weight is not treated as a weight at all.
	 *
	 * <p>Generous by a wide margin: every item in the game carried at once falls inside this,
	 * and so does a full set of weight-reducing equipment at its negative extreme. The bounds
	 * exist to keep the exported number finite and short rather than to second-guess the
	 * client, so a reading outside them is reported as unavailable rather than clamped into a
	 * plausible-looking lie.
	 */
	private static final int WEIGHT_MIN_KG = -1_000;

	private static final int WEIGHT_MAX_KG = 1_000_000;

	private static final String UNKNOWN_GAME_STATE = "UNKNOWN";

	/**
	 * RuneLite's aggregate-experience sentinel, which is not a real trainable skill. Compared
	 * lowercase, matching how skill names are normalized here.
	 */
	private static final String OVERALL_SKILL_NAME = "overall";

	/**
	 * Hard ceiling on the number of skills the exported session-gain collection can describe.
	 *
	 * <p>Old School has fewer skills than this and the caller only ever passes real ones, so it
	 * is unreachable in practice. It is here because that collection is the one exported thing
	 * keyed by something the client supplies, and a bound that does not depend on the caller
	 * behaving is what makes the document size provably independent of play duration.
	 */
	private static final int MAX_TRACKED_SKILLS = 32;

	private final String instanceId;

	/**
	 * Wall-clock milliseconds. The only source for values that leave this process as
	 * timestamps — {@code emittedAt}, {@code trackingStartedAt}, and the experience
	 * {@code lastChangedAt}. Never used to measure an interval, because it can jump in either
	 * direction.
	 */
	private final LongSupplier wallClockMillis;

	/**
	 * Monotonic elapsed nanoseconds. The only source for interval decisions. Its absolute
	 * value is meaningless and is never exported; only differences between two readings mean
	 * anything, and those differences are unaffected by any adjustment to wall time.
	 */
	private final LongSupplier elapsedNanos;

	/** Session-local total-experience baselines, keyed by lowercase skill name. */
	private final Map<String, Integer> xpBaselines = new HashMap<>();

	/**
	 * Cumulative session-local experience gains, keyed by lowercase skill name.
	 *
	 * <p>One entry per skill that has actually advanced during the tracked session, so the
	 * collection is bounded by the number of skills rather than by the number of gains. Entries
	 * are discarded wholesale at a session boundary, which is what stops a later login from
	 * inheriting another character's totals.
	 */
	private final Map<String, TrackedSkillGain> sessionXpGains = new HashMap<>();

	/**
	 * Experience evidence retained per skill while the run was still starting, keyed by
	 * lowercase skill name.
	 *
	 * <p>Startup is deferred onto the client thread, so experience events can arrive before
	 * any baseline exists. They are held here rather than dropped, because seeding afterwards
	 * from the live totals would silently absorb every gain that landed in that window — and
	 * the window is as long as the client takes to drain its queue, not a fixed tick. One
	 * aggregate entry per skill, so the map is bounded by the number of skills no matter how
	 * long startup stays queued or how many events arrive.
	 */
	private final Map<String, RetainedXp> preInitialXp = new HashMap<>();

	/**
	 * One skill's accumulated session gain, mutable because it is updated in place on every
	 * gain rather than replaced.
	 *
	 * <p>Carries the caller's enum position so the exported collection can be ordered by it.
	 * That position is never exported; it exists only to make the order deterministic and
	 * independent of the order gains happened to arrive in.
	 */
	private static final class TrackedSkillGain
	{
		private final String skill;
		private final int order;
		private int gained;
		private int lastDelta;
		private long lastChangedAt;

		private TrackedSkillGain(String skill, int order)
		{
			this.skill = skill;
			this.order = order;
		}

		private void add(int delta, long atMillis)
		{
			gained += delta;
			lastDelta = delta;
			lastChangedAt = atMillis;
		}

		private TelemetrySkillGain exported()
		{
			return new TelemetrySkillGain(skill, gained, lastDelta, lastChangedAt);
		}
	}

	/**
	 * The experience evidence one skill accumulated before the run finished initializing,
	 * reduced to the three values a measurable delta needs and nothing else.
	 *
	 * <p>Deliberately not an event list. Retaining events would grow without bound while
	 * startup stayed queued, and nothing downstream can use more than the span's endpoints.
	 * The earliest total is the low endpoint, the latest increasing total is the high one, and
	 * the latest increasing event's wall time is when the span last advanced — which is what
	 * the exported delta is stamped with.
	 *
	 * <p>The two totals move under different rules on purpose: the earliest is fixed by the
	 * first observation and never moves again, while the latest tracks each strict increase.
	 * Collapsing them into one first-writer-wins pair would stamp a multi-event span with its
	 * first event's time, which is the defect this type exists to prevent.
	 */
	private static final class RetainedXp
	{
		/** Total at the first observation. Fixed for the life of the entry. */
		private final int earliestTotal;

		/** Total at the most recent strictly increasing observation. */
		private int latestTotal;

		/**
		 * Wall-clock time of the observation that last advanced {@link #latestTotal}. Exported
		 * as {@code lastChangedAt}, and used for nothing else — deciding which span is most
		 * recent is {@link #latestEventOrder}'s job.
		 */
		private long latestEventAtMillis;

		/**
		 * Arrival position of the observation that last advanced {@link #latestTotal}, from the
		 * owning state's monotonic counter. Never exported.
		 */
		private long latestEventOrder;

		private RetainedXp(int total, long atMillis, long order)
		{
			this.earliestTotal = total;
			this.latestTotal = total;
			this.latestEventAtMillis = atMillis;
			this.latestEventOrder = order;
		}

		/**
		 * Applies a later observation.
		 *
		 * <p>Only a strict increase moves anything. An equal or lower total is ignored
		 * entirely — it does not lower a total, and it does not move the timestamp, because a
		 * dip or a transient reading is not an event the exported delta represents.
		 */
		private void observe(int total, long atMillis, long order)
		{
			if (total <= latestTotal)
			{
				return;
			}
			latestTotal = total;
			latestEventAtMillis = atMillis;
			latestEventOrder = order;
		}

		/** Whether two distinct increasing totals bound a span that can actually be measured. */
		private boolean hasMeasurableSpan()
		{
			return latestTotal > earliestTotal;
		}
	}

	/**
	 * Whether this session's experience baselines have been established from the client's live
	 * totals. Reset when a session ends, so a later login seeds again rather than inheriting a
	 * previous session's comparison.
	 */
	private boolean xpBaselinesSeeded;

	/**
	 * Monotonic counter over experience events this state has recorded, used only to decide
	 * which gain is most recent. Never exported and never compared against a clock.
	 *
	 * <p>Recency cannot be decided from {@code lastChangedAt}. Two skills whose events arrive
	 * in one game tick commonly share a millisecond, and a wall clock can be adjusted
	 * backwards between two events, which would make the older one look newer. Both would
	 * silently put the wrong skill in the exported experience fields. Arrival position has
	 * neither problem: it is exact, it has no ties, and no clock adjustment can reorder it.
	 */
	private long xpEventOrder;

	/**
	 * Arrival position of the gain the exported experience fields currently describe. Zero
	 * when they describe nothing, which is why the counter starts at one.
	 */
	private long lastReportedXpOrder;

	private boolean dirty = true;

	/** Monotonic counter incremented whenever a change marks the state dirty. */
	private long version;

	/** Change counter the snapshot handed out by {@link #nextSnapshot(boolean)} reflects. */
	private long pendingVersion;

	private long nextSeq;

	/**
	 * Monotonic elapsed reading at the last publication that actually reached the file. Not a
	 * timestamp and never exported — only its difference from a later reading is meaningful.
	 * A refused or failed publication leaves it alone, so a heartbeat is measured from the
	 * last snapshot a reader could genuinely have seen.
	 */
	private long lastPublishAtElapsedNanos;

	private String gameState = UNKNOWN_GAME_STATE;
	private boolean loggedIn;

	/**
	 * Whether a live sample has populated every player-derived value the schema requires while
	 * logged in.
	 *
	 * <p>This is what {@code session.loggedIn} actually reports: not "the client is at the
	 * logged-in game state" but "the player-derived data in this document is valid". The two
	 * differ for up to one tick after a login or a world hop, and reporting them as the same
	 * thing would leave exactly one class of dishonest document — one claiming a live session
	 * while its inventory, equipment, and prayer collections had never been read. Publishing
	 * empty collections in that window would be worse still: it would assert an empty
	 * inventory and no active prayers rather than admitting nothing had been read yet.
	 */
	private boolean playerStateComplete;

	private Integer world;
	private Integer combatLevel;

	/**
	 * When this plugin instance established its baselines for the current logged-in session.
	 *
	 * <p>Not a login time and not claimed to be one: it is when this plugin started tracking,
	 * which is later than the login whenever the plugin was enabled mid-session. Survives a
	 * world hop with the session and is discarded at a session boundary.
	 */
	private Long trackingStartedAt;

	private Integer hitpointsCurrent;
	private Integer hitpointsBase;
	private Integer prayerCurrent;
	private Integer prayerBase;
	private Integer runEnergyPercent;
	private Integer specialAttackPercent;
	private Integer weightKg;

	private String attackStyle;
	private List<String> activePrayers;
	private TelemetryTarget target;

	private List<TelemetryItemSlot> equipmentSlots;

	private Integer usedSlots;
	private Integer freeSlots;
	private List<TelemetryItemSlot> inventorySlots;

	private String lastSkill;
	private Integer lastDelta;
	private Long lastChangedAt;

	/**
	 * @param wallClockMillis wall-clock milliseconds, for exported timestamps only
	 * @param elapsedNanos    monotonic elapsed nanoseconds, for interval decisions only. Kept
	 *                        separate from the wall clock rather than derived from it, because
	 *                        an interval measured against wall time stops elapsing when wall
	 *                        time is adjusted backwards
	 */
	TelemetryState(String instanceId, LongSupplier wallClockMillis, LongSupplier elapsedNanos)
	{
		this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
		this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
		this.elapsedNanos = Objects.requireNonNull(elapsedNanos, "elapsedNanos");
		// Anchored at construction rather than left at zero. Elapsed readings carry no
		// meaningful origin — System.nanoTime may legitimately start negative — so measuring
		// the first interval from zero would compare against an arbitrary point.
		this.lastPublishAtElapsedNanos = elapsedNanos.getAsLong();
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
	 * previous world, vitals, inventory, equipment, prayers, and target. One transition makes
	 * that unobservable by construction rather than by the caller happening to order the two
	 * correctly.
	 *
	 * @param sessionEnded whether reaching this state ends the play session, discarding the
	 *                     session-local experience baselines and accumulated gains so a later
	 *                     login cannot inherit a previous session's comparison and report a
	 *                     fabricated gain
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
			// The next live session seeds its own baselines rather than running without any.
			xpBaselinesSeeded = false;
			if (!sessionXpGains.isEmpty())
			{
				markDirty();
				sessionXpGains.clear();
			}
			markIfChanged(lastSkill, null);
			lastSkill = null;
			lastDelta = null;
			lastChangedAt = null;
			// Tracking belongs to the session that ended; the next one starts its own.
			markIfChanged(trackingStartedAt, null);
			trackingStartedAt = null;
			// The fields describe nothing again, so nothing outranks the next gain.
			lastReportedXpOrder = 0L;
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
	 * Records the local player's combat level.
	 *
	 * <p>A non-positive level is not a combat level; it is what the client reports before the
	 * local player has been resolved, and is exported as unavailable rather than as zero.
	 */
	synchronized void updateCombatLevel(int level)
	{
		if (!loggedIn)
		{
			return;
		}
		Integer next = level > 0 ? Integer.valueOf(level) : null;
		markIfChanged(combatLevel, next);
		combatLevel = next;
	}

	/**
	 * Records the player's vitals.
	 *
	 * @param rawRunEnergy      run energy exactly as RuneLite reports it, in units of 1/100th
	 *                          of a percent; normalized here to whole percent in 0..100
	 * @param rawSpecialAttack  special attack energy exactly as the client reports it, in units
	 *                          of 1/10th of a percent; normalized here to whole percent in
	 *                          0..100
	 * @param rawWeightKg       the weight the client reports, in kilograms; exported as
	 *                          unavailable rather than clamped when it falls outside the
	 *                          bounds a real load can reach
	 */
	synchronized void updateVitals(int hpCurrent, int hpBase, int prayCurrent, int prayBase,
		int rawRunEnergy, int rawSpecialAttack, int rawWeightKg)
	{
		if (!loggedIn)
		{
			return;
		}
		Integer energy = clamp(rawRunEnergy / RUN_ENERGY_SCALE, 0, 100);
		Integer special = clamp(rawSpecialAttack / SPECIAL_ATTACK_SCALE, 0, 100);
		Integer weight = rawWeightKg >= WEIGHT_MIN_KG && rawWeightKg <= WEIGHT_MAX_KG
			? Integer.valueOf(rawWeightKg)
			: null;
		markIfChanged(hitpointsCurrent, hpCurrent);
		markIfChanged(hitpointsBase, hpBase);
		markIfChanged(prayerCurrent, prayCurrent);
		markIfChanged(prayerBase, prayBase);
		markIfChanged(runEnergyPercent, energy);
		markIfChanged(specialAttackPercent, special);
		markIfChanged(weightKg, weight);
		hitpointsCurrent = hpCurrent;
		hitpointsBase = hpBase;
		prayerCurrent = prayCurrent;
		prayerBase = prayBase;
		runEnergyPercent = energy;
		specialAttackPercent = special;
		weightKg = weight;
	}

	/**
	 * Records the player's current combat configuration.
	 *
	 * @param style          the normalized attack-style label, or null when no trustworthy
	 *                       reading exists. Null is a legitimate steady state, not a failure
	 * @param prayers        the active prayer names in the caller's deterministic order; empty
	 *                       when none is active. A null collection is refused rather than
	 *                       treated as none active, because "not read" and "none active" are
	 *                       different claims and only one of them is ever true
	 * @return true when the prayer collection was accepted
	 */
	synchronized boolean updateCombat(String style, List<String> prayers)
	{
		if (!loggedIn || prayers == null)
		{
			return false;
		}
		List<String> nextPrayers = Collections.unmodifiableList(new ArrayList<>(prayers));
		markIfChanged(attackStyle, style);
		markIfChanged(activePrayers, nextPrayers);
		attackStyle = style;
		activePrayers = nextPrayers;
		return true;
	}

	/**
	 * Records the NPC the local player is interacting with, or its absence.
	 *
	 * <p>Passing null is a real update rather than a no-op: the target has to disappear the
	 * moment the interaction ends, the actor becomes impermissible, or the actor is gone.
	 */
	synchronized void updateTarget(TelemetryTarget npcTarget)
	{
		if (!loggedIn)
		{
			return;
		}
		markIfChanged(target, npcTarget);
		target = npcTarget;
	}

	/**
	 * Records the eleven visible equipment slots.
	 *
	 * @param slots exactly one entry per exported equipment slot, in exported order. Any other
	 *              size is refused rather than padded or truncated, because padding would
	 *              claim empty slots the client never reported
	 * @return true when the reading was accepted
	 */
	synchronized boolean updateEquipment(List<TelemetryItemSlot> slots)
	{
		if (!loggedIn || slots == null || slots.size() != TelemetrySnapshot.EQUIPMENT_SLOTS.size())
		{
			return false;
		}
		List<TelemetryItemSlot> next = Collections.unmodifiableList(new ArrayList<>(slots));
		markIfChanged(equipmentSlots, next);
		equipmentSlots = next;
		return true;
	}

	/**
	 * Records all twenty-eight inventory slots, and the occupancy they imply.
	 *
	 * <p>Occupancy counts slots holding an item, never item quantity: one slot holding a
	 * million coins is one used slot.
	 *
	 * @param slots exactly {@link #INVENTORY_CAPACITY} entries, in ascending slot order
	 * @return true when the reading was accepted
	 */
	synchronized boolean updateInventory(List<TelemetryItemSlot> slots)
	{
		if (!loggedIn || slots == null || slots.size() != INVENTORY_CAPACITY)
		{
			return false;
		}
		List<TelemetryItemSlot> next = Collections.unmodifiableList(new ArrayList<>(slots));
		int occupied = 0;
		for (TelemetryItemSlot slot : next)
		{
			if (slot.isOccupied())
			{
				occupied++;
			}
		}
		Integer used = Integer.valueOf(occupied);
		Integer free = Integer.valueOf(INVENTORY_CAPACITY - occupied);
		markIfChanged(usedSlots, used);
		markIfChanged(freeSlots, free);
		markIfChanged(inventorySlots, next);
		usedSlots = used;
		freeSlots = free;
		inventorySlots = next;
		return true;
	}

	/**
	 * Records that the current live sample populated every player-derived value the schema
	 * requires, so a snapshot may now report the session as carrying valid player data.
	 *
	 * <p>Refused while any of those values is still missing. That refusal is the whole point:
	 * it is what makes "logged in implies a complete player block" true by construction rather
	 * than by the sampler happening to have read everything before the publisher woke up.
	 *
	 * @return true when the state now carries a complete player block
	 */
	synchronized boolean markPlayerStateComplete()
	{
		if (!loggedIn
			|| world == null
			|| combatLevel == null
			|| hitpointsCurrent == null
			|| hitpointsBase == null
			|| prayerCurrent == null
			|| prayerBase == null
			|| runEnergyPercent == null
			|| specialAttackPercent == null
			|| activePrayers == null
			|| equipmentSlots == null
			|| inventorySlots == null)
		{
			return false;
		}
		markIfChanged(playerStateComplete, true);
		playerStateComplete = true;
		return true;
	}

	/**
	 * Establishes a skill's comparison baseline from the client's current total, without
	 * treating it as an observation.
	 *
	 * <p>This exists because the plugin can be enabled while the player is already logged in.
	 * The login-time experience events have long since fired, so nothing has filled the
	 * baselines, and the next real gain would otherwise be consumed by
	 * {@link #observeXp(String, int, int)}'s first-observation rule and never exported. Seeding
	 * from the live totals means that gain is measured against the truth instead.
	 *
	 * <p>Deliberately separate from {@code observeXp} rather than reusing it: this is not an
	 * event, it can never report a gain by itself, and it must never move a baseline that
	 * already exists. Sharing one method would make the caller's intent — and those
	 * invariants — depend on which branch happened to be taken.
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
	 * @param skillOrder the caller's enum position for this skill, used only to order the
	 *                   exported session-gain collection and never exported itself
	 * @param totalXp    the client's current total for the skill; zero is legitimate for an
	 *                   untrained skill and is seeded, while a negative total is refused
	 * @return true when this call established a new baseline
	 */
	synchronized boolean seedXpBaseline(String skillName, int skillOrder, int totalXp)
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
		// Consumed whether or not it is used, so evidence held for a skill that already has a
		// baseline cannot survive to influence anything later.
		RetainedXp retained = preInitialXp.remove(skill);
		// Never lowers or replaces: a baseline already established by a real observation, or
		// by an earlier seed, is the more trustworthy one.
		if (xpBaselines.containsKey(skill))
		{
			return false;
		}

		// The baseline always ends at the trusted live total, whatever the retained evidence
		// said. That is the value later observations must be measured against.
		xpBaselines.put(skill, totalXp);

		if (retained == null || !retained.hasMeasurableSpan())
		{
			// Either nothing arrived while starting, or exactly one observation did. A single
			// observation reports the total *after* whichever gain produced it, and the total
			// before that gain is not knowable — experience events carry a running total, not a
			// delta, and the client cannot be read from a thread this run has not reached. So
			// the first gain is unmeasurable and is left unreported rather than invented.
			//
			// Deliberately not marked dirty: seeding alone changes nothing that is exported.
			return true;
		}

		if (retained.latestTotal > totalXp)
		{
			// Retained evidence sits above the total the client now reports, so the two
			// disagree. Reporting a span measured against evidence the live client contradicts
			// would be fabricating a gain out of an inconsistency. The baseline above still
			// holds; nothing is exported.
			return true;
		}

		// A span bounded by two increasing observations, consistent with the live total. Only
		// the retained span is reported: any experience between the last retained event and
		// this seed has no event of its own, and stretching the delta to cover it would mean
		// stamping that portion with a time no event happened at. The baseline already sits at
		// the live total, so nothing measured here is counted twice later.
		int span = retained.latestTotal - retained.earliestTotal;
		// The skill's own session total always takes the span: it is that skill's gain whether
		// or not it is also the most recent gain in the session.
		accumulateSessionGain(skill, skillOrder, span, retained.latestEventAtMillis);

		// The latest-gain triple, by contrast, describes one gain and can hold only the newest.
		// Seeding walks every skill in enum order, so several skills can have measurable spans
		// in one pass; assigning unconditionally would leave whichever skill happened to come
		// last in that order, not the one whose gain was most recent.
		//
		// Compared by arrival position rather than by the exported timestamp. Wall time cannot
		// decide this: events delivered in one game tick routinely share a millisecond, which
		// would silently fall back to enum order, and an adjustment of the wall clock between
		// two events would make the older one carry the larger timestamp and win outright. The
		// same comparison also stops a retained event — always from the startup window, so
		// always earlier — from displacing a newer live observation.
		if (retained.latestEventOrder <= lastReportedXpOrder)
		{
			return true;
		}

		lastSkill = skill;
		lastDelta = span;
		lastChangedAt = retained.latestEventAtMillis;
		lastReportedXpOrder = retained.latestEventOrder;
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
	 * <p>One aggregate entry per skill, updated in place. The first observation fixes the
	 * earliest total and can never report a gain by itself, for the reason given in
	 * {@link #seedXpBaseline(String, int, int)}. Each later strict increase extends the span and
	 * moves its event time, so the delta eventually exported is stamped with the event that
	 * last contributed to it rather than with whenever seeding happened to run. An equal or
	 * lower total moves neither.
	 *
	 * <p>The time recorded is wall-clock, read when the event is received, because it is
	 * exported as a timestamp. It is not elapsed time.
	 *
	 * @return true when this call created the entry for a skill that had none
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
		long atMillis = wallClockMillis.getAsLong();
		long order = ++xpEventOrder;
		RetainedXp existing = preInitialXp.get(skill);
		if (existing == null)
		{
			if (totalXp == 0)
			{
				// A zero is not trusted to establish the low end of a span. The client can
				// report zero for a skill while its data is still initializing, and that is
				// most likely exactly here, in the window before the plugin has sampled
				// anything. Anchoring a span at zero and then seeing the real total would
				// export the player's entire skill as a single gain — the same fabrication the
				// non-advancing rule already prevents, arriving in the opposite order.
				//
				// The cost is that a genuine gain on a skill that really was at zero is not
				// exported. That is the unmeasurable-first-gain limit this window already has,
				// and losing one export is worth never inventing a whole skill.
				return false;
			}
			preInitialXp.put(skill, new RetainedXp(totalXp, atMillis, order));
			return true;
		}
		existing.observe(totalXp, atMillis, order);
		return false;
	}

	/**
	 * Drops any retained pre-initialization totals.
	 *
	 * <p>Called only when the totals can no longer refer to the session they were taken from —
	 * a session ending during a startup that had not yet applied the transition. Deliberately
	 * <em>not</em> called merely because initialization finished: a startup that completes
	 * during a loading screen or world hop has no live session to seed from yet, and the totals
	 * have to survive until the one that follows.
	 */
	synchronized void discardPreInitialXp()
	{
		preInitialXp.clear();
	}

	/**
	 * Whether this session still needs its experience baselines established from the client's
	 * live totals.
	 *
	 * <p>Seeding cannot be a one-shot performed at startup. The startup callback is deferred
	 * onto the client thread and can land while the client is between states — a world hop, a
	 * loading screen — where there is no live session to read totals from. Tying seeding to
	 * that one moment means a callback that lands unluckily leaves the session with no
	 * baselines at all, and the first genuine gain is then consumed as a first observation.
	 * Asking on every live sample instead makes seeding happen at the first moment it can.
	 *
	 * <p>Also covers a logout and login inside one plugin run: ending the session clears the
	 * baselines, and this is what causes the next live session to establish its own.
	 */
	synchronized boolean needsXpBaselineSeeding()
	{
		return loggedIn && !xpBaselinesSeeded;
	}

	/**
	 * Records that this session's baselines have been seeded, so the client is not re-read on
	 * every subsequent sample, and stamps when this plugin instance started tracking the
	 * session. Refused while logged out, where any totals read would not belong to a live
	 * session.
	 */
	synchronized void markXpBaselinesSeeded()
	{
		if (!loggedIn)
		{
			return;
		}
		xpBaselinesSeeded = true;
		if (trackingStartedAt == null)
		{
			trackingStartedAt = wallClockMillis.getAsLong();
			markDirty();
		}
	}

	/**
	 * Observes a skill's total experience.
	 *
	 * <p>The first observation for a skill in a session only seeds the comparison; it never
	 * reports a gain. Only a subsequent increase updates the exported experience fields and
	 * the skill's session total. The total itself is never exported.
	 *
	 * @param skillOrder the caller's enum position for this skill, used only to order the
	 *                   exported session-gain collection and never exported itself
	 * @return true when this observation produced a reportable gain
	 */
	synchronized boolean observeXp(String skillName, int skillOrder, int totalXp)
	{
		if (!loggedIn || skillName == null)
		{
			return false;
		}
		String skill = skillName.toLowerCase(Locale.ROOT);
		if (OVERALL_SKILL_NAME.equals(skill))
		{
			// Refused here as well as in seeding. A real experience event always carries a real
			// skill, so this is unreachable from the client — but the aggregate sentinel must have
			// no path into the exported experience fields at all, not merely no path into the
			// per-skill collection.
			return false;
		}
		Integer previous = xpBaselines.get(skill);
		if (previous == null)
		{
			if (totalXp == 0)
			{
				// A zero never becomes a baseline. The client can report zero for a skill while
				// its data is still initializing, and an event can reach here before the first
				// live sample has seeded — a run that initialized mid-hop is logged in and
				// observing before any trusted total has been read. Anchoring at zero would then
				// export the character's entire skill total as one delta, which is both a
				// fabricated gain and, because that delta equals the total, the total itself
				// leaving the client through a field that is only ever meant to carry a change.
				//
				// Leaving the skill unseeded is safe: the next non-zero observation seeds it, and
				// a live seed can still claim it because no baseline is in the way. The cost is
				// that a genuine gain on a skill truly at zero is not exported, the same bounded
				// loss the retained-evidence path accepts for the same reason.
				return false;
			}
			// First trustworthy reading this session: seed the comparison, report nothing.
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

		int delta = totalXp - previous;
		long atMillis = wallClockMillis.getAsLong();
		accumulateSessionGain(skill, skillOrder, delta, atMillis);

		lastSkill = skill;
		lastDelta = delta;
		lastChangedAt = atMillis;
		// A live observation is always the newest thing seen: retained evidence is only ever
		// recorded before initialization, and this counter only moves forward. So no comparison
		// is needed here — but the position is still recorded, because it is what a later
		// retained span is measured against.
		lastReportedXpOrder = ++xpEventOrder;
		markDirty();
		return true;
	}

	/**
	 * Adds one positive gain to a skill's session total.
	 *
	 * <p>Refuses the aggregate sentinel and refuses to create an entry beyond the tracked-skill
	 * ceiling, so the exported collection stays bounded whatever it is fed. An existing entry
	 * is always updated, so a bound that has been reached cannot stop a real skill's total from
	 * continuing to advance.
	 */
	private void accumulateSessionGain(String skill, int skillOrder, int delta, long atMillis)
	{
		if (delta <= 0 || OVERALL_SKILL_NAME.equals(skill))
		{
			return;
		}
		TrackedSkillGain gain = sessionXpGains.get(skill);
		if (gain == null)
		{
			if (sessionXpGains.size() >= MAX_TRACKED_SKILLS)
			{
				return;
			}
			gain = new TrackedSkillGain(skill, skillOrder);
			sessionXpGains.put(skill, gain);
		}
		gain.add(delta, atMillis);
		markDirty();
	}

	/**
	 * The session's gains, ordered by the caller's enum position.
	 *
	 * <p>Ordering by that position rather than by arrival, name, or size is what makes the
	 * exported array deterministic: the same set of gains always serializes identically, so a
	 * reader diffing two snapshots sees only real changes.
	 */
	private List<TelemetrySkillGain> exportedSkillGains()
	{
		Map<Integer, TelemetrySkillGain> ordered = new TreeMap<>();
		for (TrackedSkillGain gain : sessionXpGains.values())
		{
			ordered.put(gain.order, gain.exported());
		}
		return new ArrayList<>(ordered.values());
	}

	/**
	 * Whether a publication is due: either the state changed, or the last publication is old
	 * enough that a reader needs a fresh heartbeat to distinguish a live plugin from a stale
	 * file.
	 *
	 * <p>The interval is measured against monotonic elapsed time, never the wall clock.
	 * Measured against wall time, an adjustment backwards — an NTP correction, a manual
	 * change, a resume from sleep — makes the elapsed figure negative and keeps it negative
	 * until wall time catches back up, so a perfectly healthy idle plugin stops heartbeating
	 * and its file reads as stale for the size of the jump. Elapsed time cannot move
	 * backwards, so no clock adjustment in either direction can suppress a heartbeat or
	 * trigger one early.
	 *
	 * <p>The comparison is written as a subtraction of two elapsed readings so that it stays
	 * correct across the wraparound {@code System.nanoTime} is permitted to have: the
	 * difference remains accurate even when the raw readings straddle the numeric limit.
	 */
	synchronized boolean isPublicationDue(long heartbeatIntervalMillis)
	{
		if (dirty)
		{
			// A change always publishes immediately; no interval is consulted at all.
			return true;
		}
		// Saturating rather than overflowing: an absurd interval yields an unreachable
		// threshold instead of a wrapped negative one that would make everything due.
		long heartbeatIntervalNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatIntervalMillis);
		return elapsedNanos.getAsLong() - lastPublishAtElapsedNanos >= heartbeatIntervalNanos;
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

		// Defense in depth: a snapshot never carries player-derived values unless the client is
		// live, the plugin is running, and a sample has actually populated them, whatever the
		// fields currently hold.
		boolean live = pluginActive && loggedIn && playerStateComplete;
		TelemetrySnapshot.Builder b = TelemetrySnapshot.builder()
			.instanceId(instanceId)
			.seq(nextSeq)
			.emittedAt(wallClockMillis.getAsLong())
			.pluginActive(pluginActive)
			.gameState(gameState)
			.loggedIn(live);

		if (live)
		{
			b.session(world, combatLevel, trackingStartedAt)
				.hitpoints(hitpointsCurrent, hitpointsBase)
				.prayer(prayerCurrent, prayerBase)
				.runEnergyPercent(runEnergyPercent)
				.specialAttackPercent(specialAttackPercent)
				.weightKg(weightKg)
				.combat(attackStyle, activePrayers, target)
				.equipmentSlots(equipmentSlots)
				.inventory(usedSlots, freeSlots, inventorySlots)
				.xp(lastSkill, lastDelta, lastChangedAt)
				.xpSkills(exportedSkillGains());
		}
		else
		{
			b.session(null, null, null)
				.hitpoints(null, null)
				.prayer(null, null)
				.runEnergyPercent(null)
				.specialAttackPercent(null)
				.weightKg(null)
				.combat(null, null, null)
				.equipmentSlots(null)
				.inventory(null, null, null)
				.xp(null, null, null)
				.xpSkills(null);
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
		lastPublishAtElapsedNanos = elapsedNanos.getAsLong();
		if (version == pendingVersion)
		{
			dirty = false;
		}
	}

	private void clearPlayerDerived()
	{
		markIfChanged(playerStateComplete, false);
		markIfChanged(world, null);
		markIfChanged(combatLevel, null);
		markIfChanged(hitpointsCurrent, null);
		markIfChanged(hitpointsBase, null);
		markIfChanged(prayerCurrent, null);
		markIfChanged(prayerBase, null);
		markIfChanged(runEnergyPercent, null);
		markIfChanged(specialAttackPercent, null);
		markIfChanged(weightKg, null);
		markIfChanged(attackStyle, null);
		markIfChanged(activePrayers, null);
		markIfChanged(target, null);
		markIfChanged(equipmentSlots, null);
		markIfChanged(usedSlots, null);
		markIfChanged(freeSlots, null);
		markIfChanged(inventorySlots, null);

		playerStateComplete = false;
		world = null;
		combatLevel = null;
		hitpointsCurrent = null;
		hitpointsBase = null;
		prayerCurrent = null;
		prayerBase = null;
		runEnergyPercent = null;
		specialAttackPercent = null;
		weightKg = null;
		attackStyle = null;
		activePrayers = null;
		target = null;
		equipmentSlots = null;
		usedSlots = null;
		freeSlots = null;
		inventorySlots = null;

		// The experience fields and the tracking stamp are deliberately left alone. Every other
		// value here is re-read from the client by the next live sample, so discarding it costs
		// nothing — but a gain is an event, and no sample can reconstruct one, and when tracking
		// began is a fact about the session rather than about the tick. A world hop passes
		// through here with the session still intact, and clearing them would mean the snapshot
		// claimed there had been no recent gain, and no accumulated session experience, for the
		// rest of that session, when the baselines it is measured against were deliberately
		// kept.
		//
		// Nothing leaks by keeping them: nextSnapshot exports them only when the client is live
		// and the player block is complete, and forces them null otherwise, whatever these
		// hold. A genuine session end still discards them, in the sessionEnded branch of
		// updateSession.
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
