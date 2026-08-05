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

	private long now;
	private TelemetryState state;

	@Before
	public void setUp()
	{
		now = 1_770_000_000_000L;
		LongSupplier clock = () -> now;
		state = new TelemetryState(INSTANCE_ID, clock);
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

	@Test
	public void endingASessionClearsBaselinesSoALaterLoginCannotInheritADelta()
	{
		logIn();
		state.observeXp("FISHING", 10_000);
		state.observeXp("FISHING", 10_050);
		assertEquals("\"fishing\"", value(state.nextSnapshot(true).toJson(), "lastSkill"));

		state.endSession();
		state.updateSession("LOGIN_SCREEN", false);
		logIn();

		// The next login's very first observation is a fresh baseline, not a 40k gain.
		assertFalse(state.observeXp("FISHING", 50_000));
		assertEquals("null", value(state.nextSnapshot(true).toJson(), "lastSkill"));

		assertTrue(state.observeXp("FISHING", 50_030));
		assertEquals("30", value(state.nextSnapshot(true).toJson(), "lastDelta"));
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
		TelemetryState other = new TelemetryState(UUID.randomUUID().toString(), () -> now);
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

		// Unchanged state still republishes as a heartbeat.
		now += 1_500L;
		assertTrue(state.isPublicationDue(1_500L));

		now += 1L;
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isPublicationDue(1_500L));

		// A change makes it due again straight away.
		logIn();
		assertTrue(state.isDirty());
		assertTrue(state.isPublicationDue(1_500L));
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
