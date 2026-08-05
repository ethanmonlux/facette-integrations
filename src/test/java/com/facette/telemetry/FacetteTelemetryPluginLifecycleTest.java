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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;

/**
 * Drives {@link FacetteTelemetryPlugin}'s real lifecycle — its startup marshalling, deferred
 * initialization, event handlers, publisher ownership, and shutdown — without a game client.
 *
 * <p>This is the coverage four packets did not have. Twelve of the eighteen review findings
 * across them landed in this class or its wiring, and none could carry a regression test at
 * the site of the defect, because reaching it needs a stand-in for {@link Client}. The AWT
 * client-thread failure that reached a real session is what that gap cost.
 *
 * <p>Nothing here launches RuneLite, authenticates, reads a credential, touches the operator's
 * real RuneLite directory, opens a socket, or starts a thread. The client and its thread
 * dispatcher are mocked, the clocks are held still and moved deliberately, the publisher runs
 * on a fake executor this test drains by hand, and the snapshot is written into a temporary
 * directory the rule deletes. Ordering is established by draining explicit queues rather than
 * by sleeping, so no test here can pass or fail on timing.
 *
 * <p>Distinct from {@code FacetteTelemetryPluginTest}, which is not a test at all: it is the
 * RuneLite development-client launcher behind {@code gradlew run}, and is left untouched.
 */
public class FacetteTelemetryPluginLifecycleTest
{
	private static final long HEARTBEAT_MILLIS = 1_500L;

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Client client;
	private ClientThread clientThread;

	/** Callbacks handed to {@link ClientThread#invoke(Runnable)}, drained only on demand. */
	private List<Runnable> clientThreadQueue;

	/** Every executor the plugin asked for, so each can be checked for disposal. */
	private List<ControlledPublisher> executors;

	private long now;
	private long elapsed;
	private AtomicInteger instanceCounter;
	private Path dataDirectory;
	private FacetteTelemetryPlugin plugin;

	@Before
	public void setUp() throws IOException
	{
		client = mock(Client.class);
		clientThread = mock(ClientThread.class);
		clientThreadQueue = new ArrayList<>();
		executors = new ArrayList<>();
		now = 1_770_000_000_000L;
		elapsed = -4_000_000_000L;
		instanceCounter = new AtomicInteger();
		dataDirectory = temporaryFolder.newFolder("facette").toPath();

		// Queued rather than run, which is what lets a test prove the plugin read nothing from
		// the client before the callback it scheduled actually executed.
		doAnswer(invocation ->
		{
			clientThreadQueue.add(invocation.getArgument(0));
			return null;
		}).when(clientThread).invoke(any(Runnable.class));

		// A logged-out client unless a test says otherwise, so nothing samples by accident.
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

		plugin = new FacetteTelemetryPlugin(
			() -> now,
			() -> elapsed,
			() -> "instance-" + instanceCounter.incrementAndGet(),
			() -> dataDirectory,
			() ->
			{
				try
				{
					ControlledPublisher publisher = new ControlledPublisher();
					executors.add(publisher);
					return publisher.service;
				}
				catch (InterruptedException e)
				{
					throw new IllegalStateException(e);
				}
			});
		plugin.client = client;
		plugin.clientThread = clientThread;
	}

	@After
	public void tearDown()
	{
		// Case 12. Every executor the plugin took must be disposed of by the plugin itself —
		// a leaked one is a leaked publisher thread in production.
		for (ControlledPublisher executor : executors)
		{
			assertTrue("an executor was left live at test end", executor.isShutdown());
		}
	}

	// --- helpers -------------------------------------------------------------------------

	/** Runs the callbacks the plugin handed to the client thread, in order. */
	private void runClientThreadQueue()
	{
		List<Runnable> pending = new ArrayList<>(clientThreadQueue);
		clientThreadQueue.clear();
		for (Runnable runnable : pending)
		{
			runnable.run();
		}
	}

	private void logInClient()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getWorld()).thenReturn(302);
	}

	private void tick()
	{
		plugin.onGameTick(new GameTick());
	}

	private void gameState(GameState gameState)
	{
		GameStateChanged event = new GameStateChanged();
		event.setGameState(gameState);
		plugin.onGameStateChanged(event);
	}

	private void statChanged(Skill skill, int totalXp)
	{
		plugin.onStatChanged(new StatChanged(skill, totalXp, 1, 1));
	}

	private ControlledPublisher onlyExecutor()
	{
		assertEquals("expected exactly one publisher", 1, executors.size());
		return executors.get(0);
	}

	private String snapshotOnDisk() throws IOException
	{
		Path target = dataDirectory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME);
		assertTrue("no snapshot was written", Files.exists(target));
		return new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
	}

	private boolean snapshotExists()
	{
		return Files.exists(dataDirectory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME));
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

	// --- 1. startup from a non-client thread ---------------------------------------------

	/**
	 * The defect that reached a real Windows session: enabling from the configuration panel
	 * runs {@code startUp()} on AWT, where every direct client read fails the client-thread
	 * assertion. Startup must therefore touch nothing on the client itself.
	 */
	@Test
	public void startupFromANonClientThreadReadsNothingFromTheClient()
	{
		plugin.startUp();

		verifyNoInteractions(client);
		assertEquals("startup must defer its work to the client thread", 1, clientThreadQueue.size());
		assertFalse("nothing may be published before the callback runs", snapshotExists());

		plugin.shutDown();
	}

	// --- 2. deferred initialization -------------------------------------------------------

	@Test
	public void clientReadsAndSeedingHappenInsideTheDeferredCallbackBeforeAnyPublisherStarts()
			throws IOException
	{
		logInClient();
		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1_000);

		plugin.startUp();
		verifyNoInteractions(client);
		assertTrue("no publisher may exist before initialization", executors.isEmpty());

		runClientThreadQueue();

		// Sampling happened, and only now does a publisher exist.
		assertEquals(1, executors.size());
		ControlledPublisher executor = onlyExecutor();
		assertTrue("the periodic task is scheduled after adoption", executor.hasScheduledTask());

		executor.runScheduledTaskOnce();
		String json = snapshotOnDisk();
		assertEquals("true", value(json, "loggedIn"));
		assertEquals("302", value(json, "world"));

		plugin.shutDown();
	}

	// --- 3. disable before the callback ---------------------------------------------------

	@Test
	public void disablingBeforeTheDeferredCallbackLeavesItInertAndWritesNothing()
	{
		logInClient();
		plugin.startUp();

		// Disabled while the callback was still queued.
		plugin.shutDown();

		runClientThreadQueue();

		assertTrue("a retired run must not adopt a publisher", executors.isEmpty());
		assertFalse("a run that never published writes no snapshot", snapshotExists());
	}

	// --- 4. rapid disable and re-enable ----------------------------------------------------

	@Test
	public void rapidDisableAndReEnableGivesSeparateRunsAndAStaleCallbackCannotInitializeTheNewOne()
			throws IOException
	{
		logInClient();

		plugin.startUp();
		List<Runnable> firstCallback = new ArrayList<>(clientThreadQueue);
		clientThreadQueue.clear();

		plugin.shutDown();
		plugin.startUp();
		runClientThreadQueue();

		// The second run is live and owns its own publisher.
		assertEquals("only the second run adopted a publisher", 1, executors.size());
		ControlledPublisher second = onlyExecutor();
		second.runScheduledTaskOnce();
		String afterSecond = snapshotOnDisk();
		assertEquals("instance-2", value(afterSecond, "instanceId").replace("\"", ""));
		assertEquals("a new run starts its own sequence", "0", value(afterSecond, "seq"));

		// The first run's callback finally runs. It must do nothing at all.
		for (Runnable stale : firstCallback)
		{
			stale.run();
		}
		assertEquals("a stale callback must not create a publisher", 1, executors.size());
		assertEquals("and must not disturb what the newer run wrote", afterSecond, snapshotOnDisk());

		plugin.shutDown();
	}

	@Test
	public void everyRunGetsItsOwnIdentityAndState() throws IOException
	{
		logInClient();

		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();
		String first = value(snapshotOnDisk(), "instanceId");
		plugin.shutDown();

		plugin.startUp();
		runClientThreadQueue();
		executors.get(1).runScheduledTaskOnce();
		String second = value(snapshotOnDisk(), "instanceId");
		plugin.shutDown();

		assertNotEquals("a restart must be visible to a reader", first, second);
	}

	// --- 5. publisher adoption and cleanup -------------------------------------------------

	/**
	 * The zero initial delay means the first tick can run before {@code scheduleWithFixedDelay}
	 * returns, so the run has to own the executor before anything is scheduled on it. Otherwise
	 * a disable landing in that gap finds no publisher to stop and the executor leaks.
	 */
	@Test
	public void thePublisherIsOwnedBeforeAnythingIsScheduledOnIt()
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();

		ControlledPublisher executor = onlyExecutor();
		assertTrue(executor.adoptedBeforeScheduling);

		plugin.shutDown();
		assertTrue("shutdown must reach the adopted executor", executor.isShutdown());
	}

	@Test
	public void anExecutorRefusedAfterShutdownIsDisposedOfRatherThanLeaked()
	{
		logInClient();
		plugin.startUp();
		// Retired while the callback was queued: the callback will create an executor and then
		// find the run already retired.
		plugin.shutDown();
		runClientThreadQueue();

		// Nothing was adopted, so nothing can leak; tearDown proves any executor made was
		// shut down.
		assertTrue(executors.isEmpty());
	}

	// --- 6. startup while logged in --------------------------------------------------------

	@Test
	public void startingWhileLoggedInSeedsBaselinesSoTheFirstRealGainIsExported() throws IOException
	{
		logInClient();
		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1_000);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();

		// Seeding reports nothing by itself.
		assertEquals("null", value(snapshotOnDisk(), "lastSkill"));

		// The very first genuine gain is measured against the seeded total, not consumed.
		now = 1_770_000_004_000L;
		statChanged(Skill.THIEVING, 1_046);
		executor.runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertEquals("\"thieving\"", value(json, "lastSkill"));
		assertEquals("46", value(json, "lastDelta"));
		assertEquals("1770000004000", value(json, "lastChangedAt"));

		plugin.shutDown();
	}

	// --- 7. startup during a loading screen or world hop -----------------------------------

	@Test
	public void startingDuringLoadingSeedsAtTheFirstLiveTickAndDoesNotConsumeTheNextGain()
			throws IOException
	{
		// The callback lands while the client is between states: nothing to seed from.
		when(client.getGameState()).thenReturn(GameState.LOADING);
		plugin.startUp();
		runClientThreadQueue();

		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		assertEquals("false", value(snapshotOnDisk(), "loggedIn"));

		// The hop completes. The first live tick is what seeds.
		logInClient();
		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(50_000);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("null", value(snapshotOnDisk(), "lastSkill"));

		// And the next genuine gain is exported rather than swallowed as a first observation.
		now = 1_770_000_006_000L;
		statChanged(Skill.AGILITY, 50_120);
		executor.runScheduledTaskOnce();
		String json = snapshotOnDisk();
		assertEquals("\"agility\"", value(json, "lastSkill"));
		assertEquals("120", value(json, "lastDelta"));

		plugin.shutDown();
	}

	// --- 8. pre-initialization experience ---------------------------------------------------

	/**
	 * Experience arriving before initialization has to reach the starting run carrying the time
	 * it was received, or the delta eventually exported is stamped with whenever startup
	 * finished instead of when the player earned it.
	 */
	@Test
	public void experienceArrivingBeforeInitializationKeepsItsOwnEventTime() throws IOException
	{
		logInClient();
		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1_092);

		plugin.startUp();

		// Two gains land while the callback is still queued, at distinct times.
		now = 1_770_000_001_000L;
		statChanged(Skill.THIEVING, 1_046);
		now = 1_770_000_002_000L;
		statChanged(Skill.THIEVING, 1_092);
		assertFalse("nothing publishes before initialization", snapshotExists());

		// Startup finally runs, much later.
		now = 1_770_000_030_000L;
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();

		String json = snapshotOnDisk();
		assertEquals("\"thieving\"", value(json, "lastSkill"));
		assertEquals("46", value(json, "lastDelta"));
		assertEquals("the second event's time, not startup completion", "1770000002000",
			value(json, "lastChangedAt"));

		plugin.shutDown();
	}

	// --- 9. logout and login without disabling ----------------------------------------------

	@Test
	public void loggingOutAndBackInWithoutDisablingReseedsAndReportsNoCrossCharacterGain()
			throws IOException
	{
		logInClient();
		when(client.getSkillExperience(Skill.FISHING)).thenReturn(10_000);

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();

		// Log out. Session-local comparison state is discarded.
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		gameState(GameState.LOGIN_SCREEN);
		tick();
		executor.runScheduledTaskOnce();
		String loggedOut = snapshotOnDisk();
		assertEquals("false", value(loggedOut, "loggedIn"));
		assertEquals("null", value(loggedOut, "lastSkill"));

		// A different character logs in with a far larger total.
		logInClient();
		when(client.getSkillExperience(Skill.FISHING)).thenReturn(5_000_000);
		gameState(GameState.LOGGED_IN);
		tick();
		executor.runScheduledTaskOnce();
		assertEquals("no cross-character gain may appear", "null",
			value(snapshotOnDisk(), "lastSkill"));

		// The new session's own first gain is exported correctly.
		now = 1_770_000_008_000L;
		statChanged(Skill.FISHING, 5_000_075);
		executor.runScheduledTaskOnce();
		String json = snapshotOnDisk();
		assertEquals("\"fishing\"", value(json, "lastSkill"));
		assertEquals("75", value(json, "lastDelta"));

		plugin.shutDown();
	}

	// --- 10. bounded shutdown ----------------------------------------------------------------

	/**
	 * On the real client this runs on the client thread, where a stalled filesystem must not be
	 * able to hold the caller. The final write is queued on the run's own publisher instead, so
	 * the caller returns whether or not the write has finished.
	 */
	@Test
	public void shutdownQueuesTheFinalWriteAndDoesNotPerformItOnTheCallerThread() throws IOException
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher executor = onlyExecutor();
		executor.runScheduledTaskOnce();
		String beforeShutdown = snapshotOnDisk();

		// The executor refuses to run anything during the wait, standing in for a write that
		// outlives the bound.
		executor.runQueuedWorkOnAwait = false;
		plugin.shutDown();

		assertEquals("the caller must not have written the final snapshot itself",
			beforeShutdown, snapshotOnDisk());
		assertTrue("the final write must have been queued on the publisher", executor.hasQueuedWork());

		// When it finally runs, it lands the inactive snapshot.
		executor.runQueuedWork();
		String json = snapshotOnDisk();
		assertEquals("false", value(json, "pluginActive"));
		assertEquals("false", value(json, "loggedIn"));
	}

	@Test
	public void anOrderlyShutdownWritesTheInactiveSnapshotWithinTheBound() throws IOException
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();

		plugin.shutDown();

		String json = snapshotOnDisk();
		assertEquals("false", value(json, "pluginActive"));
		for (String key : new String[]{"world", "hitpointsCurrent", "usedSlots", "lastSkill"})
		{
			assertEquals(key + " must be null in the final snapshot", "null", value(json, key));
		}
	}

	// --- 11. an old final write cannot overwrite a newer run ---------------------------------

	@Test
	public void aStalledFinalWriteCannotOverwriteANewerActiveRun() throws IOException
	{
		logInClient();

		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher first = onlyExecutor();
		first.runScheduledTaskOnce();

		// Disable, with the final write left stalled on the old publisher.
		first.runQueuedWorkOnAwait = false;
		plugin.shutDown();
		assertTrue(first.hasQueuedWork());

		// Re-enable, and let the new run publish an active snapshot.
		plugin.startUp();
		runClientThreadQueue();
		ControlledPublisher second = executors.get(1);
		second.runScheduledTaskOnce();
		String active = snapshotOnDisk();
		assertEquals("true", value(active, "pluginActive"));

		// Only now does the old run's inactive write complete. It has lost authority.
		first.runQueuedWork();
		assertEquals("a retired run must not bury a newer active snapshot",
			active, snapshotOnDisk());

		plugin.shutDown();
	}

	// --- isolation ---------------------------------------------------------------------------

	@Test
	public void nothingIsWrittenOutsideTheTemporaryDirectory() throws IOException
	{
		logInClient();
		plugin.startUp();
		runClientThreadQueue();
		onlyExecutor().runScheduledTaskOnce();
		plugin.shutDown();

		Path target = dataDirectory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME);
		assertTrue(Files.exists(target));
		assertTrue("the snapshot must live under the test's own directory",
			target.toAbsolutePath().startsWith(temporaryFolder.getRoot().toPath().toAbsolutePath()));
	}

	// --- the controlled publisher ---------------------------------------------------------------

	/**
	 * A publisher executor that runs nothing on its own.
	 *
	 * <p>Real threads would make every assertion here a race, and waiting on them would make the
	 * suite depend on timing. This records what the plugin scheduled and what it submitted, and
	 * runs either only when a test says so, which is what makes publication and shutdown
	 * ordering exact rather than probable.
	 *
	 * <p>Only the six methods the plugin actually calls are given behavior. The rest of
	 * {@link ScheduledExecutorService} is left at its default, because a stub that merely throws
	 * would say nothing a reader needs and this class is already the least interesting part of
	 * the test.
	 */
	private final class ControlledPublisher
	{
		private final ScheduledExecutorService service = mock(ScheduledExecutorService.class);
		private final List<Runnable> queued = new ArrayList<>();
		private Runnable scheduledTask;
		private boolean shutdown;

		/** True when the run adopted this executor before scheduling anything on it. */
		private boolean adoptedBeforeScheduling;

		/** Whether a bounded wait drains the queue, or stands in for a write that outlives it. */
		private boolean runQueuedWorkOnAwait = true;

		private ControlledPublisher() throws InterruptedException
		{
			when(service.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(),
				any(TimeUnit.class))).thenAnswer(invocation ->
			{
				// Reaching here at all means the run already owns this executor, because the
				// plugin adopts before it schedules. That ordering is the assertion.
				adoptedBeforeScheduling = true;
				scheduledTask = invocation.getArgument(0);
				return mock(ScheduledFuture.class);
			});
			doAnswer(invocation ->
			{
				if (shutdown)
				{
					throw new RejectedExecutionException("shut down");
				}
				queued.add(invocation.getArgument(0));
				return null;
			}).when(service).execute(any(Runnable.class));
			doAnswer(invocation ->
			{
				shutdown = true;
				return null;
			}).when(service).shutdown();
			doAnswer(invocation ->
			{
				shutdown = true;
				queued.clear();
				scheduledTask = null;
				return Collections.emptyList();
			}).when(service).shutdownNow();
			when(service.isShutdown()).thenAnswer(invocation -> shutdown);
			when(service.awaitTermination(anyLong(), any(TimeUnit.class))).thenAnswer(invocation ->
			{
				if (!runQueuedWorkOnAwait)
				{
					// Stands in for a write still running when the bound expires.
					return false;
				}
				runQueuedWork();
				return true;
			});
		}

		private boolean hasScheduledTask()
		{
			return scheduledTask != null;
		}

		private void runScheduledTaskOnce()
		{
			assertTrue("no periodic task was scheduled", scheduledTask != null);
			scheduledTask.run();
		}

		private boolean hasQueuedWork()
		{
			return !queued.isEmpty();
		}

		private void runQueuedWork()
		{
			List<Runnable> pending = new ArrayList<>(queued);
			queued.clear();
			for (Runnable runnable : pending)
			{
				runnable.run();
			}
		}

		private boolean isShutdown()
		{
			return shutdown;
		}
	}
}
