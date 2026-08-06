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

import java.util.UUID;
import java.util.function.LongSupplier;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the telemetry rules that decide what may be exported and when: nulling while
 * logged out, inventory occupancy, experience seeding versus a real gain, session reset,
 * run-energy normalization, identity and sequence behavior, and the shutdown snapshot.
 *
 * <p>Needs no account, credential, network service, Facette installation, or game session.
 */
public class TelemetryStateTest
{
	private static final String INSTANCE_ID = "8e5a1c02-3f47-4d6b-9a10-77c2e5b4f831";

	/** Wall-clock milliseconds. Drives exported timestamps only. */
	private long now;

	/**
	 * Monotonic elapsed nanoseconds. Drives cadence only, and is moved independently of
	 * {@link #now} so a test can prove one cannot influence the other.
	 */
	private long elapsed;

	private TelemetryState state;

	@Before
	public void setUp()
	{
		now = 1_770_000_000_000L;
		// Deliberately not zero, and deliberately negative, because System.nanoTime has no
		// meaningful origin and is permitted to be negative. Anything that treats an elapsed
		// reading as an absolute quantity rather than a difference fails here.
		elapsed = -4_000_000_000L;
		LongSupplier wallClock = () -> now;
		LongSupplier elapsedClock = () -> elapsed;
		state = new TelemetryState(INSTANCE_ID, wallClock, elapsedClock);
	}

	/** Advances monotonic elapsed time by a millisecond amount, leaving wall time alone. */
	private void elapseMillis(long millis)
	{
		elapsed += millis * 1_000_000L;
	}

	private void logIn()
	{
		state.updateSession("LOGGED_IN", true);
	}

	private static String value(String json, String key)
	{
		int at = json.indexOf("\"" + key + "\":");
		assertTrue("missing key " + key + " in " + json, at >= 0);
		int start = at + key.length() + 3;
		int end = start;
		while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}')
		{
			end++;
		}
		return json.substring(start, end);
	}

	@Test
	public void loggedOutSnapshotNullsEveryPlayerDerivedField()
	{
		state.updateSession("LOGIN_SCREEN", false);
		String json = state.nextSnapshot(true).toJson();

		assertEquals("false", value(json, "loggedIn"));
		assertEquals("\"LOGIN_SCREEN\"", value(json, "gameState"));
		for (String key : new String[]{"world", "hitpointsCurrent", "hitpointsBase", "prayerCurrent",
			"prayerBase", "runEnergyPercent", "usedSlots", "freeSlots", "lastSkill", "lastDelta",
			"lastChangedAt"})
		{
			assertEquals(key + " should be null while logged out", "null", value(json, key));
		}
	}

	@Test
	public void loggingOutDiscardsPreviousPlayerValuesRatherThanRetainingThem()
	{
		logIn();
		state.updateWorld(302);
		state.updateVitals(73, 75, 40, 52, 8_800);
		state.updateInventory(12);
		state.observeXp("WOODCUTTING", 100_000);
		state.observeXp("WOODCUTTING", 100_065);
		assertTrue(state.nextSnapshot(true).toJson().contains("\"world\":302"));

		state.updateSession("LOGIN_SCREEN", false);
		String loggedOut = state.nextSnapshot(true).toJson();
		assertEquals("null", value(loggedOut, "world"));
		assertEquals("null", value(loggedOut, "hitpointsCurrent"));
		assertEquals("null", value(loggedOut, "usedSlots"));
		assertEquals("null", value(loggedOut, "lastSkill"));

		// Logging back in must not resurrect the stale readings; only fresh samples do.
		logIn();
		String reLoggedIn = state.nextSnapshot(true).toJson();
		assertEquals("null", value(reLoggedIn, "world"));
		assertEquals("null", value(reLoggedIn, "hitpointsCurrent"));
		assertEquals("null", value(reLoggedIn, "usedSlots"));
	}

	@Test
	public void inventoryCountsOccupiedSlotsAndReportsTheFreeRemainder()
	{
		logIn();
		state.updateInventory(12);
		String json = state.nextSnapshot(true).toJson();
		assertEquals("12", value(json, "usedSlots"));
		assertEquals("16", value(json, "freeSlots"));

		state.updateInventory(0);
		json = state.nextSnapshot(true).toJson();
		assertEquals("0", value(json, "usedSlots"));
		assertEquals("28", value(json, "freeSlots"));

		state.updateInventory(TelemetryState.INVENTORY_CAPACITY);
		json = state.nextSnapshot(true).toJson();
		assertEquals("28", value(json, "usedSlots"));
		assertEquals("0", value(json, "freeSlots"));
	}

	@Test
	public void inventoryOccupancyIsClampedIntoTheAuthorizedRange()
	{
		logIn();
		state.updateInventory(9_999);
		String json = state.nextSnapshot(true).toJson();
		assertEquals("28", value(json, "usedSlots"));
		assertEquals("0", value(json, "freeSlots"));

		state.updateInventory(-5);
		json = state.nextSnapshot(true).toJson();
		assertEquals("0", value(json, "usedSlots"));
		assertEquals("28", value(json, "freeSlots"));
	}

	@Test
	public void runEnergyIsNormalizedFromHundredthsOfAPercentIntoZeroToOneHundred()
	{
		logIn();
		assertEquals("100", energyPercentFor(10_000));
		assertEquals("88", energyPercentFor(8_800));
		assertEquals("55", energyPercentFor(5_599));
		assertEquals("0", energyPercentFor(0));
		assertEquals("0", energyPercentFor(99));
		// Out-of-contract readings are clamped rather than exported as-is.
		assertEquals("100", energyPercentFor(12_345));
		assertEquals("0", energyPercentFor(-50));
	}

	private String energyPercentFor(int rawEnergy)
	{
		state.updateVitals(10, 10, 1, 1, rawEnergy);
		return value(state.nextSnapshot(true).toJson(), "runEnergyPercent");
	}

	@Test
	public void firstExperienceObservationSeedsWithoutReportingAGain()
	{
		logIn();
		assertFalse("the first observation is a baseline, not a gain",
			state.observeXp("WOODCUTTING", 1_234_567));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));
		assertEquals("null", value(json, "lastChangedAt"));
	}

	@Test
	public void subsequentExperienceIncreaseReportsSkillDeltaAndTime()
	{
		logIn();
		state.observeXp("WOODCUTTING", 1_234_567);
		now = 1_770_000_005_000L;
		assertTrue(state.observeXp("WOODCUTTING", 1_234_632));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("\"woodcutting\"", value(json, "lastSkill"));
		assertEquals("65", value(json, "lastDelta"));
		assertEquals("1770000005000", value(json, "lastChangedAt"));
		// The total itself is never exported.
		assertFalse(json, json.contains("1234632"));
	}

	@Test
	public void nonIncreasingExperienceIsIgnored()
	{
		logIn();
		state.observeXp("MINING", 500);
		assertFalse(state.observeXp("MINING", 500));
		assertFalse(state.observeXp("MINING", 400));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));
	}

	/**
	 * A reading that has not advanced must not move the baseline. If it did, returning to the
	 * true total would look like a gain the size of the dip.
	 */
	@Test
	public void aLowerReadingDoesNotLowerTheBaseline()
	{
		logIn();
		state.observeXp("MINING", 1_000);
		assertFalse(state.observeXp("MINING", 400));

		// Back to the total we already had: nothing was gained, so nothing is reported.
		assertFalse("returning to a known total is not a gain", state.observeXp("MINING", 1_000));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));
	}

	/**
	 * The case that makes the one above matter: the client can report a zero total while skill
	 * data is still initializing. A baseline that followed it down would fabricate a gain the
	 * size of the player's entire skill on the next real reading.
	 */
	@Test
	public void aTransientZeroReadingCannotFabricateAWholeSkillGain()
	{
		logIn();
		state.observeXp("WOODCUTTING", 1_234_567);
		assertFalse(state.observeXp("WOODCUTTING", 0));

		assertFalse("the real total returning is not a gain",
			state.observeXp("WOODCUTTING", 1_234_567));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));

		// A genuine gain after the dip is measured from the true baseline, not from zero.
		assertTrue(state.observeXp("WOODCUTTING", 1_234_632));
		assertEquals("65", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	/**
	 * A session-ending transition must be a single state change, not a clear followed by a
	 * logout. Applied as two steps, a publication can observe the experience baselines
	 * already discarded while the session still reads as live and still carries the previous
	 * world, vitals, and inventory — a half-transitioned snapshot no real session produces.
	 */
	@Test
	public void aSessionEndingTransitionIsAppliedAtomically()
	{
		logIn();
		state.updateWorld(302);
		state.updateVitals(73, 75, 40, 52, 8_800);
		state.updateInventory(12);
		state.observeXp("FISHING", 10_000);
		state.observeXp("FISHING", 10_050);

		state.updateSession("LOGIN_SCREEN", false, true);

		// There is no observable point between "baselines cleared" and "logged out": the
		// very next snapshot is already fully logged out with nothing player-derived left.
		String json = state.nextSnapshot(true).toJson();
		assertEquals("false", value(json, "loggedIn"));
		assertEquals("\"LOGIN_SCREEN\"", value(json, "gameState"));
		for (String key : new String[]{"world", "hitpointsCurrent", "prayerCurrent",
			"runEnergyPercent", "usedSlots", "freeSlots", "lastSkill", "lastDelta", "lastChangedAt"})
		{
			assertEquals(key + " should be null the moment the session ends", "null", value(json, key));
		}

		// And the baselines really were discarded by that same call.
		logIn();
		assertFalse("the next login's first reading must seed, not report a gain",
			state.observeXp("FISHING", 50_000));
	}

	@Test
	public void aNonSessionEndingTransitionKeepsTheBaselines()
	{
		logIn();
		state.observeXp("FISHING", 10_000);

		// A world hop leaves the logged-in state without ending the session.
		state.updateSession("HOPPING", false, false);
		logIn();

		assertTrue("a hop must not re-seed and swallow the next gain",
			state.observeXp("FISHING", 10_040));
		assertEquals("40", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void endingASessionClearsBaselinesSoALaterLoginCannotInheritADelta()
	{
		logIn();
		state.observeXp("FISHING", 10_000);
		state.observeXp("FISHING", 10_050);
		assertEquals("\"fishing\"", value(state.nextSnapshot(true).toJson(), "lastSkill"));

		state.updateSession("LOGIN_SCREEN", false, true);
		logIn();

		// The next login's very first observation is a fresh baseline, not a 40k gain.
		assertFalse(state.observeXp("FISHING", 50_000));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));

		assertTrue(state.observeXp("FISHING", 50_030));
		assertEquals("30", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	// --- startup baseline seeding (FACETTE-OSRS-PLUGIN-003) ---

	/**
	 * The defect this fixes: enabling the plugin mid-session left the baselines empty, so the
	 * next real gain was consumed as a first observation and never exported.
	 */
	@Test
	public void seedingMidSessionMakesTheNextGainReportable()
	{
		logIn();
		assertTrue("seeding establishes a baseline", state.seedXpBaseline("WOODCUTTING", 1_234_567));

		// Seeding itself reports nothing.
		String afterSeed = state.nextSnapshot(true).toJson();
		assertEquals("null", value(afterSeed, "lastSkill"));
		assertEquals("null", value(afterSeed, "lastDelta"));
		assertEquals("null", value(afterSeed, "lastChangedAt"));

		// The very first gain after enabling is now exported, measured from the seeded total.
		now = 1_770_000_005_000L;
		assertTrue(state.observeXp("WOODCUTTING", 1_234_632));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("\"woodcutting\"", value(json, "lastSkill"));
		assertEquals("65", value(json, "lastDelta"));
		assertEquals("1770000005000", value(json, "lastChangedAt"));
	}

	@Test
	public void withoutSeedingTheFirstGainIsStillConsumedAsTheBaseline()
	{
		// Pins the unseeded behavior the fix works around, so the contrast is explicit and a
		// regression in either direction is visible.
		logIn();
		assertFalse(state.observeXp("WOODCUTTING", 1_234_632));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));
	}

	@Test
	public void seedingNeverOverwritesAnExistingBaseline()
	{
		logIn();
		state.observeXp("MINING", 5_000);

		// A later seed must not move a baseline a real observation already established, in
		// either direction.
		assertFalse("seeding must not replace an existing baseline", state.seedXpBaseline("MINING", 1));
		assertFalse(state.seedXpBaseline("MINING", 9_999_999));

		assertTrue("the original baseline still governs", state.observeXp("MINING", 5_040));
		assertEquals("40", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void seedingIsIdempotentForTheSameSkill()
	{
		logIn();
		assertTrue(state.seedXpBaseline("FISHING", 400));
		assertFalse("a second seed does nothing", state.seedXpBaseline("FISHING", 400));
		assertFalse(state.seedXpBaseline("FISHING", 100));

		assertTrue(state.observeXp("FISHING", 430));
		assertEquals("30", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void zeroIsSeededBecauseAnUntrainedSkillLegitimatelyHasNone()
	{
		logIn();
		assertTrue("zero is a real total for an untrained skill", state.seedXpBaseline("SAILING", 0));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));

		// And the first experience in that skill is then reported in full.
		assertTrue(state.observeXp("SAILING", 120));
		String json = state.nextSnapshot(true).toJson();
		assertEquals("\"sailing\"", value(json, "lastSkill"));
		assertEquals("120", value(json, "lastDelta"));
	}

	@Test
	public void aNegativeTotalIsNeverSeeded()
	{
		logIn();
		assertFalse(state.seedXpBaseline("MINING", -1));

		// Nothing was recorded, so the next reading still behaves as a first observation
		// rather than reporting a gain measured from a nonsense baseline.
		assertFalse(state.observeXp("MINING", 500));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));
	}

	@Test
	public void seededBaselinesAreStillProtectedFromTransientLowReadings()
	{
		logIn();
		state.seedXpBaseline("WOODCUTTING", 1_234_567);

		// The round-4 protection must apply to seeded baselines exactly as to observed ones.
		assertFalse(state.observeXp("WOODCUTTING", 0));
		assertFalse("returning to the seeded total is not a gain",
			state.observeXp("WOODCUTTING", 1_234_567));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));

		assertTrue(state.observeXp("WOODCUTTING", 1_234_632));
		assertEquals("65", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void nothingIsSeededWhileLoggedOut()
	{
		state.updateSession("LOGIN_SCREEN", false);
		assertFalse(state.seedXpBaseline("WOODCUTTING", 1_234_567));

		// Confirmed by behavior, not just the return value: after logging in, the first
		// reading is still a first observation because no baseline was stored.
		logIn();
		assertFalse(state.observeXp("WOODCUTTING", 1_234_567));
	}

	@Test
	public void invalidAndSentinelSkillEntriesAreSkipped()
	{
		logIn();
		assertFalse("a null skill name is refused", state.seedXpBaseline(null, 100));
		assertFalse("the OVERALL sentinel is refused", state.seedXpBaseline("OVERALL", 12_345_678));
		assertFalse("case does not matter", state.seedXpBaseline("overall", 12_345_678));

		// No baseline was stored under any of them.
		assertFalse(state.observeXp("OVERALL", 12_345_678));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));
	}

	@Test
	public void endingASessionClearsSeededBaselines()
	{
		logIn();
		state.seedXpBaseline("FISHING", 10_000);

		state.updateSession("LOGIN_SCREEN", false, true);
		logIn();

		// A seeded baseline must not survive a session end any more than an observed one.
		assertFalse("the next login re-seeds rather than inheriting", state.observeXp("FISHING", 50_000));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));
	}

	@Test
	public void aWorldHopPreservesSeededBaselines()
	{
		logIn();
		state.seedXpBaseline("FISHING", 10_000);

		state.updateSession("HOPPING", false, false);
		logIn();

		// The hop keeps the session, so the first gain after it is still reported.
		assertTrue("a hop must not swallow the next gain", state.observeXp("FISHING", 10_040));
		assertEquals("40", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void aFreshRunReseedsAndReportsTheNextGainCorrectly()
	{
		// Disable/re-enable while logged in: the plugin builds a new TelemetryState, so the
		// new run starts with no baselines and seeds from the client's current totals.
		logIn();
		state.seedXpBaseline("COOKING", 800);
		state.observeXp("COOKING", 850);

		TelemetryState reEnabled = new TelemetryState(INSTANCE_ID, () -> now, () -> elapsed);
		reEnabled.updateSession("LOGGED_IN", true);
		assertEquals("a fresh run starts at sequence zero", 0L, reEnabled.getNextSeq());

		assertTrue(reEnabled.seedXpBaseline("COOKING", 850));
		assertTrue("the first gain after re-enabling is reported",
			reEnabled.observeXp("COOKING", 875));
		assertEquals("25", value(reEnabled.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void seedingDoesNotByItselfMakeAPublicationDue()
	{
		logIn();
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isDirty());

		// Baselines are not exported, so seeding must not trigger a publication.
		assertTrue(state.seedXpBaseline("HERBLORE", 1_000));
		assertFalse("seeding changes nothing exported", state.isDirty());
		assertFalse(state.isPublicationDue(1_500L));
	}

	/**
	 * The defect this fixes: startup is deferred onto the client thread, so experience events
	 * can land before any baseline exists. Seeding afterwards from the live total absorbed
	 * every gain earned in that window, and absorbed more the longer startup stayed queued.
	 */
	@Test
	public void experienceEarnedWhileStartupWasQueuedIsExportedRatherThanAbsorbed()
	{
		// Two gains land before initialization: 1000 -> 1046 -> 1092, at distinct times.
		now = 1_770_000_001_000L;
		assertTrue(state.recordPreInitialXp("THIEVING", 1_046));
		now = 1_770_000_002_000L;
		assertFalse("one aggregate entry per skill", state.recordPreInitialXp("THIEVING", 1_092));

		logIn();
		// Startup finally runs, much later, and seeds from the live total.
		now = 1_770_000_009_000L;
		assertTrue(state.seedXpBaseline("THIEVING", 1_092));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("\"thieving\"", value(json, "lastSkill"));
		assertEquals("the measurable span between the two retained events", "46", value(json, "lastDelta"));
		assertEquals("the second event's time, not the seeding time", "1770000002000",
			value(json, "lastChangedAt"));

		// The baseline still ends at the live total, so the next gain measures from the truth.
		assertTrue(state.observeXp("THIEVING", 1_100));
		assertEquals("8", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	/**
	 * The regression proof for the round-5 finding. Against the behavior at d9cb9ea the delta
	 * was stamped with the wall clock read at seeding, so this asserts the one thing that
	 * distinguishes the two implementations: a long, quiet gap between the last event and
	 * seeding must not move the exported time at all.
	 */
	@Test
	public void aLongStartupDelayDoesNotMoveTheExportedExperienceTime()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("THIEVING", 1_046);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("THIEVING", 1_092);

		logIn();
		// A loading screen or a busy client can hold startup for a long time. Nothing about the
		// experience already earned changes because of it.
		now = 1_770_000_600_000L;
		assertTrue(state.seedXpBaseline("THIEVING", 1_092));

		assertEquals("1770000002000", value(state.nextSnapshot(true).toJson(), "lastChangedAt"));
	}

	/**
	 * Three events, so a first-writer-wins timestamp and a seeding-time timestamp are both
	 * distinguishable failures rather than coincidentally equal to the right answer.
	 */
	@Test
	public void threeRetainedEventsAggregateTheSpanAndUseTheLatestEventTime()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("THIEVING", 1_046);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("THIEVING", 1_092);
		now = 1_770_000_003_000L;
		state.recordPreInitialXp("THIEVING", 1_150);

		logIn();
		now = 1_770_000_050_000L;
		assertTrue(state.seedXpBaseline("THIEVING", 1_150));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("the whole measurable span, 1150 - 1046", "104", value(json, "lastDelta"));
		assertEquals("the third event's time, not the first and not the seed",
			"1770000003000", value(json, "lastChangedAt"));
	}

	/**
	 * Experience earned between the last retained event and seeding has no event of its own.
	 * Stretching the delta to cover it would mean stamping that part with a time no event
	 * happened at, so only the retained span is reported and the baseline absorbs the rest.
	 */
	@Test
	public void experienceWithNoRetainedEventIsNotGivenAnInventedTime()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("MINING", 5_040);

		logIn();
		now = 1_770_000_009_000L;
		// The live total is higher than anything retained: more was earned, unobserved.
		assertTrue(state.seedXpBaseline("MINING", 5_100));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("only the span two events actually bound", "40", value(json, "lastDelta"));
		assertEquals("1770000002000", value(json, "lastChangedAt"));

		// The unreported remainder is absorbed by the baseline, so it is not counted twice.
		assertTrue(state.observeXp("MINING", 5_130));
		assertEquals("30", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	/**
	 * Retained evidence above the total the client now reports means the two disagree.
	 * Reporting a span measured against contradicted evidence would fabricate a gain.
	 */
	@Test
	public void retainedEvidenceAboveTheLiveTotalCannotFabricateADelta()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("MINING", 9_999);

		logIn();
		assertTrue(state.seedXpBaseline("MINING", 5_050));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("no gain is reported from contradicted evidence", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));
		assertEquals("null", value(json, "lastChangedAt"));

		// The live total governs from here.
		assertTrue(state.observeXp("MINING", 5_090));
		assertEquals("40", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	/** A measurable retained span marks the state dirty once, not once per retained event. */
	@Test
	public void measurableRetainedExperienceMarksTheStateDirtyExactlyOnce()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("THIEVING", 1_046);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("THIEVING", 1_092);

		logIn();
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isDirty());

		assertTrue(state.seedXpBaseline("THIEVING", 1_092));
		assertTrue("a measurable retained gain is exported, so it must publish", state.isDirty());

		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse("and it must not keep republishing afterwards", state.isDirty());
	}

	/**
	 * The residual limit, pinned deliberately. Experience events carry a running total and not
	 * a delta, and the pre-gain total cannot be read off the client thread, so the first gain
	 * per skill inside the startup window is unmeasurable however this is arranged. What must
	 * not happen is a fabricated gain in its place.
	 */
	@Test
	public void theFirstGainInsideTheStartupWindowIsUnmeasurableAndIsNotInvented()
	{
		state.recordPreInitialXp("THIEVING", 1_046);
		logIn();
		// Exactly one gain landed, so the earliest total seen already equals the live total.
		assertTrue(state.seedXpBaseline("THIEVING", 1_046));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("no gain is fabricated", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));

		// And the baseline is correct, so the next genuine gain is exported in full.
		assertTrue(state.observeXp("THIEVING", 1_092));
		assertEquals("46", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void aRetainedTotalNeverLowersOrReplacesAnExistingBaseline()
	{
		logIn();
		state.observeXp("MINING", 5_000);

		// A stale retained total must not reopen a baseline a real observation established.
		state.recordPreInitialXp("MINING", 1);
		assertFalse(state.seedXpBaseline("MINING", 5_000));
		assertFalse("the retained total was consumed, not left to act later",
			state.seedXpBaseline("MINING", 5_000));

		assertTrue("the original baseline still governs", state.observeXp("MINING", 5_040));
		assertEquals("40", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void aRetainedTotalAboveTheLiveTotalIsIgnoredRatherThanReportedAsALoss()
	{
		// A transient high reading must not produce a negative delta or a fabricated gain.
		state.recordPreInitialXp("MINING", 9_999);
		logIn();
		assertTrue(state.seedXpBaseline("MINING", 5_000));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));

		// The live total governs from here.
		assertTrue(state.observeXp("MINING", 5_040));
		assertEquals("40", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void retainedEvidenceIsBoundedPerSkillAndRejectsInvalidEntries()
	{
		now = 1_770_000_001_000L;
		assertTrue("the first observation creates the entry", state.recordPreInitialXp("FISHING", 100));
		now = 1_770_000_002_000L;
		assertFalse("a later observation updates that entry rather than adding one",
			state.recordPreInitialXp("FISHING", 200));
		now = 1_770_000_003_000L;
		assertFalse("case does not create a second entry", state.recordPreInitialXp("fishing", 300));
		assertFalse("a null skill name is refused", state.recordPreInitialXp(null, 100));
		assertFalse("the OVERALL sentinel is refused", state.recordPreInitialXp("OVERALL", 12_345_678));
		assertFalse("a negative total is refused", state.recordPreInitialXp("MINING", -1));

		logIn();
		now = 1_770_000_020_000L;
		// One aggregate entry spanning 100..300, whatever the number of observations.
		assertTrue(state.seedXpBaseline("FISHING", 300));
		String json = state.nextSnapshot(true).toJson();
		assertEquals("200", value(json, "lastDelta"));
		assertEquals("1770000003000", value(json, "lastChangedAt"));
	}

	/**
	 * Equal and lower readings are not events the exported span represents, so neither the
	 * totals nor the timestamp may follow them. A transient zero is the case that matters: a
	 * baseline that followed one down would export the whole skill as a single gain.
	 */
	@Test
	public void equalAndLowerRetainedReadingsMoveNeitherTotalNorTimestamp()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("MINING", 5_040);

		// None of these may move anything.
		now = 1_770_000_003_000L;
		state.recordPreInitialXp("MINING", 5_040);
		now = 1_770_000_004_000L;
		state.recordPreInitialXp("MINING", 4_000);
		now = 1_770_000_005_000L;
		state.recordPreInitialXp("MINING", 0);

		logIn();
		now = 1_770_000_030_000L;
		assertTrue(state.seedXpBaseline("MINING", 5_040));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("the span is unchanged by the dips", "40", value(json, "lastDelta"));
		assertEquals("the timestamp stayed on the last increase", "1770000002000",
			value(json, "lastChangedAt"));
	}

	/**
	 * The opposite arrival order, which the non-advancing rule alone does not cover: the
	 * transient zero comes *first*, so it would anchor the span at zero and the real total
	 * would then read as a gain the size of the whole skill.
	 */
	@Test
	public void aLeadingTransientZeroCannotAnchorASpanAndFabricateAWholeSkillGain()
	{
		now = 1_770_000_001_000L;
		assertFalse("a zero must not establish retained evidence",
			state.recordPreInitialXp("WOODCUTTING", 0));
		now = 1_770_000_002_000L;
		assertTrue("the real total establishes it instead",
			state.recordPreInitialXp("WOODCUTTING", 1_234_567));

		logIn();
		assertTrue(state.seedXpBaseline("WOODCUTTING", 1_234_567));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("no whole-skill gain is fabricated", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));

		// The baseline is the truth, so a genuine later gain still measures correctly.
		now = 1_770_000_009_000L;
		assertTrue(state.observeXp("WOODCUTTING", 1_234_632));
		assertEquals("65", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	/**
	 * Seeding walks every skill in enum order, so several can carry a measurable span in one
	 * pass. The exported experience fields mean the most recent gain, so the winner must be
	 * decided by event time rather than by whichever skill the enum happened to visit last.
	 */
	@Test
	public void theNewestRetainedEventWinsRatherThanTheLastSkillSeeded()
	{
		// MINING's span ends later than FISHING's.
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("FISHING", 100);
		now = 1_770_000_003_000L;
		state.recordPreInitialXp("FISHING", 140);
		now = 1_770_000_004_000L;
		state.recordPreInitialXp("MINING", 5_060);

		logIn();
		now = 1_770_000_050_000L;
		// Seeded in an order that puts the older span last, as enum order well might.
		assertTrue(state.seedXpBaseline("MINING", 5_060));
		assertTrue(state.seedXpBaseline("FISHING", 140));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("the most recent gain must win", "\"mining\"", value(json, "lastSkill"));
		assertEquals("60", value(json, "lastDelta"));
		assertEquals("1770000004000", value(json, "lastChangedAt"));
	}

	@Test
	public void theNewestRetainedEventStillWinsWhenSeededInTheOtherOrder()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("FISHING", 100);
		now = 1_770_000_003_000L;
		state.recordPreInitialXp("FISHING", 140);
		now = 1_770_000_004_000L;
		state.recordPreInitialXp("MINING", 5_060);

		logIn();
		assertTrue(state.seedXpBaseline("FISHING", 140));
		assertTrue(state.seedXpBaseline("MINING", 5_060));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("\"mining\"", value(json, "lastSkill"));
		assertEquals("1770000004000", value(json, "lastChangedAt"));
	}

	/**
	 * Events delivered in one game tick routinely share a millisecond, so wall time cannot
	 * separate them. Selection is by arrival position, which has no ties.
	 */
	@Test
	public void twoRetainedSpansInTheSameMillisecondAreSeparatedByArrivalOrder()
	{
		// Every event at the same instant. Only arrival order distinguishes them.
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		state.recordPreInitialXp("FISHING", 100);
		state.recordPreInitialXp("MINING", 5_060);
		// FISHING's span is completed last, so FISHING is the most recent gain.
		state.recordPreInitialXp("FISHING", 140);

		logIn();
		// Seeded oldest-span-first, which is what makes this discriminating: a rule that keeps
		// the incumbent on a tie would leave MINING here, and only arrival order picks FISHING.
		assertTrue(state.seedXpBaseline("MINING", 5_060));
		assertTrue(state.seedXpBaseline("FISHING", 140));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("the later-arriving span wins a tied millisecond", "\"fishing\"",
			value(json, "lastSkill"));
		assertEquals("40", value(json, "lastDelta"));
		assertEquals("1770000001000", value(json, "lastChangedAt"));
	}

	/**
	 * The defect this fixes: selection used the exported wall-clock timestamp, so a backward
	 * clock adjustment between two events gave the *older* one the larger value and let it win.
	 * That is the same class of error the monotonic cadence source exists to prevent, in a
	 * different decision.
	 */
	@Test
	public void aBackwardWallClockJumpBetweenRetainedEventsDoesNotReorderThem()
	{
		now = 1_770_000_005_000L;
		state.recordPreInitialXp("MINING", 5_000);
		state.recordPreInitialXp("MINING", 5_060);

		// The wall clock is corrected backwards, then FISHING's span completes. FISHING is
		// genuinely the more recent gain even though its timestamp is now smaller.
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("FISHING", 100);
		state.recordPreInitialXp("FISHING", 140);

		logIn();
		assertTrue(state.seedXpBaseline("FISHING", 140));
		assertTrue(state.seedXpBaseline("MINING", 5_060));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("arrival order decides, not the adjusted clock", "\"fishing\"",
			value(json, "lastSkill"));
		assertEquals("40", value(json, "lastDelta"));
		assertEquals("and the exported time is still the event's own wall time",
			"1770000001000", value(json, "lastChangedAt"));
	}

	/** A retained event is from the startup window, so it must not displace a newer live gain. */
	@Test
	public void aRetainedEventCannotDisplaceANewerLiveObservation()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("MINING", 5_060);

		logIn();
		// A live gain on another skill is observed first, and is more recent.
		state.observeXp("COOKING", 800);
		now = 1_770_000_020_000L;
		assertTrue(state.observeXp("COOKING", 900));

		// Seeding the older retained span must leave the newer live gain in place.
		assertTrue(state.seedXpBaseline("MINING", 5_060));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("\"cooking\"", value(json, "lastSkill"));
		assertEquals("100", value(json, "lastDelta"));
		assertEquals("1770000020000", value(json, "lastChangedAt"));
	}

	/**
	 * A transient zero during startup must not become the low end of the span and export the
	 * player's entire skill as one gain.
	 */
	@Test
	public void aTransientZeroDuringStartupCannotFabricateAWholeSkillGain()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("WOODCUTTING", 1_234_567);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("WOODCUTTING", 0);

		logIn();
		assertTrue(state.seedXpBaseline("WOODCUTTING", 1_234_567));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("no whole-skill gain is fabricated", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));

		// And the baseline is the truth, so a genuine later gain measures correctly.
		now = 1_770_000_009_000L;
		assertTrue(state.observeXp("WOODCUTTING", 1_234_632));
		assertEquals("65", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void discardingRetainedTotalsStopsThemInfluencingALaterSeed()
	{
		state.recordPreInitialXp("FISHING", 100);
		state.discardPreInitialXp();

		logIn();
		assertTrue(state.seedXpBaseline("FISHING", 250));
		assertEquals("nothing is reported from a discarded total", "null",
			value(state.nextSnapshot(true).toJson(), "lastSkill"));
	}

	@Test
	public void aStartupSpanningALogoutDoesNotCarryItsTotalIntoTheNextLogin()
	{
		state.recordPreInitialXp("FISHING", 100);
		// The session ends before startup finished, discarding session-local experience.
		state.updateSession("LOGIN_SCREEN", false, true);

		logIn();
		assertTrue(state.seedXpBaseline("FISHING", 250));
		assertEquals("a previous session's total cannot become this login's baseline", "null",
			value(state.nextSnapshot(true).toJson(), "lastSkill"));
	}

	/**
	 * The same protection by the route the plugin actually uses during the startup window,
	 * where the transition itself is not applied and only the discard reaches the state. A
	 * retained total surviving a logout would measure one character's experience against
	 * another's and export a gain that never happened.
	 */
	@Test
	public void discardingOnSessionEndProtectsAgainstMeasuringAcrossCharacters()
	{
		state.recordPreInitialXp("FISHING", 100);
		state.discardPreInitialXp();

		// A different character logs in with a far higher total in the same skill.
		logIn();
		assertTrue(state.seedXpBaseline("FISHING", 5_000_000));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("no cross-character gain is exported", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));
	}

	/**
	 * The defect this fixes: seeding used to happen once, in the startup callback, and only if
	 * that callback happened to land on a live session. Landing during a world hop or loading
	 * screen left the whole run with no baselines, so the next genuine gain was consumed as a
	 * first observation — the same loss the mid-session seeding fix exists to prevent.
	 */
	@Test
	public void aSessionThatStartedMidHopStillSeedsOnceItGoesLive()
	{
		// Startup lands during a loading screen: nothing to seed from.
		state.updateSession("LOADING", false);
		assertFalse("no seeding is possible without a live session", state.needsXpBaselineSeeding());

		// The hop completes. The next live sample is what establishes the baselines.
		logIn();
		assertTrue("the first live sample must seed", state.needsXpBaselineSeeding());
		assertTrue(state.seedXpBaseline("AGILITY", 50_000));
		state.markXpBaselinesSeeded();

		assertFalse("seeding happens once per session, not every sample",
			state.needsXpBaselineSeeding());

		// The first genuine gain after the hop is exported rather than consumed.
		now = 1_770_000_005_000L;
		assertTrue(state.observeXp("AGILITY", 50_120));
		assertEquals("120", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void retainedEvidenceBeforeAHopSurvivesUntilTheSessionGoesLive()
	{
		// Two gains land while startup is queued, then startup completes mid-hop.
		now = 1_770_000_001_000L;
		assertTrue(state.recordPreInitialXp("AGILITY", 50_000));
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("AGILITY", 50_120);
		state.updateSession("LOADING", false);

		// The retained evidence must not have been thrown away by initialization completing.
		logIn();
		assertTrue(state.needsXpBaselineSeeding());
		now = 1_770_000_030_000L;
		assertTrue(state.seedXpBaseline("AGILITY", 50_120));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("the window's experience is still measured", "120", value(json, "lastDelta"));
		assertEquals("and still carries its own event time", "1770000002000",
			value(json, "lastChangedAt"));
	}

	@Test
	public void aWorldHopDoesNotCauseTheSessionToReseed()
	{
		logIn();
		state.seedXpBaseline("AGILITY", 50_000);
		state.markXpBaselinesSeeded();

		// A hop is not a session boundary, so the established baselines still govern.
		state.updateSession("LOADING", false);
		state.updateSession("LOGGED_IN", true);
		assertFalse("a hop must not re-read totals or reset the comparison",
			state.needsXpBaselineSeeding());

		now = 1_770_000_005_000L;
		assertTrue(state.observeXp("AGILITY", 50_120));
		assertEquals("120", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void aLogoutAndLoginInsideOneRunSeedsAgainRatherThanConsumingTheNextGain()
	{
		logIn();
		state.seedXpBaseline("AGILITY", 50_000);
		state.markXpBaselinesSeeded();

		// Ending the session discards the baselines, so the next one must establish its own.
		state.updateSession("LOGIN_SCREEN", false, true);
		logIn();
		assertTrue("a new session seeds again", state.needsXpBaselineSeeding());

		assertTrue(state.seedXpBaseline("AGILITY", 60_000));
		now = 1_770_000_005_000L;
		assertTrue("the first gain after logging back in is exported", state.observeXp("AGILITY", 60_075));
		assertEquals("75", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void seedingIsNotMarkedCompleteWhileLoggedOut()
	{
		state.updateSession("LOGIN_SCREEN", false);
		state.markXpBaselinesSeeded();

		logIn();
		assertTrue("a logged-out mark must not suppress the real seeding",
			state.needsXpBaselineSeeding());
	}

	/**
	 * The reachable sequence: a run initializes during a hop, the client goes live, and a
	 * transient zero arrives before the first tick can seed. Anchoring the baseline at zero
	 * would export the character's whole skill total as a single delta.
	 */
	@Test
	public void aTransientZeroObservationNeverBecomesABaseline()
	{
		logIn();
		assertFalse("a zero must not seed a baseline", state.observeXp("WOODCUTTING", 0));

		// The real total arrives. It seeds, and reports nothing.
		assertFalse("the real total seeds instead", state.observeXp("WOODCUTTING", 1_234_567));
		String json = state.nextSnapshot(true).toJson();
		assertEquals("no whole-skill gain is fabricated", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));

		// And the comparison is now against the truth.
		now = 1_770_000_009_000L;
		assertTrue(state.observeXp("WOODCUTTING", 1_234_632));
		assertEquals("65", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	/**
	 * Refusing the zero also leaves the skill claimable by a trusted live seed, which is what
	 * stops the defect being merely deferred: a baseline of zero would have survived seeding,
	 * because seeding never replaces a baseline that already exists.
	 */
	@Test
	public void aRefusedZeroLeavesTheSkillOpenToATrustedLiveSeed()
	{
		logIn();
		state.observeXp("WOODCUTTING", 0);

		// The first live sample seeds from the client's real total.
		assertTrue("no baseline is in the way", state.seedXpBaseline("WOODCUTTING", 1_234_567));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));

		now = 1_770_000_009_000L;
		assertTrue(state.observeXp("WOODCUTTING", 1_234_632));
		assertEquals("65", value(state.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void experienceIsNotObservedWhileLoggedOut()
	{
		state.updateSession("LOGIN_SCREEN", false);
		assertFalse(state.observeXp("SLAYER", 1));
		assertFalse(state.observeXp("SLAYER", 2));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));
	}

	@Test
	public void worldAndVitalsAreNotRecordedWhileLoggedOut()
	{
		state.updateSession("LOGIN_SCREEN", false);
		state.updateWorld(302);
		state.updateVitals(73, 75, 40, 52, 8_800);
		state.updateInventory(12);

		String json = state.nextSnapshot(true).toJson();
		assertEquals("null", value(json, "world"));
		assertEquals("null", value(json, "hitpointsCurrent"));
		assertEquals("null", value(json, "usedSlots"));
	}

	@Test
	public void instanceIdIsFixedForTheLifetimeOfTheStateAndIsNotDerivedFromGameData()
	{
		logIn();
		state.updateWorld(302);
		assertEquals(INSTANCE_ID, state.getInstanceId());
		assertEquals(INSTANCE_ID, state.nextSnapshot(true).getInstanceId());
		state.recordPublished();
		assertEquals(INSTANCE_ID, state.nextSnapshot(true).getInstanceId());

		// A separate start is a separate identity.
		TelemetryState other = new TelemetryState(UUID.randomUUID().toString(), () -> now, () -> elapsed);
		assertFalse(INSTANCE_ID.equals(other.getInstanceId()));
	}

	@Test
	public void sequenceStartsAtZeroAndAdvancesOnlyForAPublishedSnapshot()
	{
		assertEquals(0L, state.getNextSeq());
		assertEquals(0L, state.nextSnapshot(true).getSeq());

		// A snapshot that was built but never written must not consume its number.
		assertEquals(0L, state.nextSnapshot(true).getSeq());

		state.recordPublished();
		assertEquals(1L, state.getNextSeq());
		assertEquals(1L, state.nextSnapshot(true).getSeq());
		state.recordPublished();
		assertEquals(2L, state.nextSnapshot(true).getSeq());
	}

	@Test
	public void publicationIsDueWhenDirtyAndAgainOnceTheHeartbeatIntervalElapses()
	{
		assertTrue("a newly started plugin publishes immediately", state.isPublicationDue(1_500L));
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isDirty());
		assertFalse(state.isPublicationDue(1_500L));

		// Unchanged state still republishes as a heartbeat, driven by elapsed time.
		elapseMillis(1_500L);
		assertTrue(state.isPublicationDue(1_500L));

		elapseMillis(1L);
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isPublicationDue(1_500L));

		// A change makes it due again straight away.
		logIn();
		assertTrue(state.isDirty());
		assertTrue(state.isPublicationDue(1_500L));
	}

	/**
	 * The defect this fixes: cadence was measured against the wall clock, so an adjustment
	 * backwards made the elapsed figure negative and kept it negative until wall time caught
	 * up. A healthy idle plugin stopped heartbeating and its file read as stale.
	 */
	@Test
	public void aBackwardWallClockJumpCannotSuppressAHeartbeat()
	{
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isDirty());
		assertFalse(state.isPublicationDue(1_500L));

		// The wall clock lurches an hour backwards mid-session.
		now -= 3_600_000L;
		assertFalse("a clock adjustment alone is not a heartbeat", state.isPublicationDue(1_500L));

		// Elapsed time is untouched by that, so the heartbeat still arrives on schedule.
		elapseMillis(1_500L);
		assertTrue("the heartbeat must not wait for wall time to catch up",
			state.isPublicationDue(1_500L));
	}

	@Test
	public void aForwardWallClockJumpCannotForceAHeartbeat()
	{
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isPublicationDue(1_500L));

		// A large forward correction must not manufacture cadence that has not elapsed.
		now += 3_600_000L;
		assertFalse("wall time cannot bring a heartbeat forward", state.isPublicationDue(1_500L));

		elapseMillis(1_499L);
		assertFalse("still short of the interval", state.isPublicationDue(1_500L));
		elapseMillis(1L);
		assertTrue("due exactly when the elapsed interval is reached", state.isPublicationDue(1_500L));
	}

	@Test
	public void aDirtyStateIsDueImmediatelyWhateverEitherClockSays()
	{
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isPublicationDue(1_500L));

		logIn();
		assertTrue(state.isDirty());
		assertTrue("a change publishes without consulting any interval",
			state.isPublicationDue(1_500L));

		// Even with the wall clock dragged backwards.
		now -= 3_600_000L;
		assertTrue(state.isPublicationDue(1_500L));
	}

	/**
	 * The heartbeat is measured from the last snapshot a reader could genuinely have seen. A
	 * publication that was refused or failed never called {@link TelemetryState#recordPublished()},
	 * so the interval must keep running rather than restarting on an attempt that wrote nothing.
	 */
	@Test
	public void aPublicationThatNeverReachedTheFileDoesNotRestartTheHeartbeatInterval()
	{
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isPublicationDue(1_500L));

		elapseMillis(1_400L);
		// An attempt that is refused or fails builds a snapshot but never records it.
		state.nextSnapshot(true);
		elapseMillis(100L);

		assertTrue("the interval runs from the last committed publication, not the last attempt",
			state.isPublicationDue(1_500L));
	}

	/** Exported timestamps must keep following wall time, not the cadence source. */
	@Test
	public void exportedTimestampsFollowWallTimeWhileCadenceFollowsElapsedTime()
	{
		logIn();
		state.observeXp("WOODCUTTING", 1_000);

		// Elapsed time races ahead; it must not appear in any exported field.
		elapseMillis(500_000L);
		now = 1_770_000_007_000L;
		assertTrue(state.observeXp("WOODCUTTING", 1_065));

		String json = state.nextSnapshot(true).toJson();
		assertEquals("1770000007000", value(json, "emittedAt"));
		assertEquals("1770000007000", value(json, "lastChangedAt"));

		// And wall time moving does not by itself satisfy the cadence.
		state.recordPublished();
		now += 60_000L;
		assertFalse(state.isPublicationDue(1_500L));
	}

	@Test
	public void aChangeDuringPublicationIsRepublishedRatherThanDropped()
	{
		state.nextSnapshot(true);
		logIn();
		state.recordPublished();
		assertTrue("the change landed after the snapshot was built", state.isDirty());
	}

	@Test
	public void shutdownSnapshotIsInactiveLoggedOutAndFullyNulled()
	{
		logIn();
		state.updateWorld(302);
		state.updateVitals(73, 75, 40, 52, 8_800);
		state.updateInventory(12);
		state.observeXp("COOKING", 1_000);
		state.observeXp("COOKING", 1_100);
		state.recordPublished();

		TelemetrySnapshot shutdown = state.nextSnapshot(false);
		String json = shutdown.toJson();

		assertFalse(shutdown.isPluginActive());
		assertFalse(shutdown.isLoggedIn());
		assertEquals("false", value(json, "pluginActive"));
		assertEquals("false", value(json, "loggedIn"));
		// Lifecycle metadata is retained: same instance, next sequence, current game state.
		assertEquals(INSTANCE_ID, shutdown.getInstanceId());
		assertEquals(1L, shutdown.getSeq());
		assertEquals("\"LOGGED_IN\"", value(json, "gameState"));

		for (String key : new String[]{"world", "hitpointsCurrent", "hitpointsBase", "prayerCurrent",
			"prayerBase", "runEnergyPercent", "usedSlots", "freeSlots", "lastSkill", "lastDelta",
			"lastChangedAt"})
		{
			assertEquals(key + " should be null on shutdown", "null", value(json, key));
		}
	}
}
