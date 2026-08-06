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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Exports a small, sanitized, read-only view of the local player's live state to a single
 * local JSON file for the Facette companion application to read independently.
 *
 * <p>The plugin is one-directional by construction. It reads approved client state through
 * the RuneLite API, normalizes it, and replaces one file inside RuneLite's own data
 * directory. It opens no socket, sends nothing anywhere, reads no command channel, invokes
 * no menu action, and synthesizes no input, so nothing outside the game can act on the game
 * through it.
 *
 * <p>Only the fields listed in {@link TelemetrySnapshot} leave the client. Account identity,
 * credentials, chat, friends and clan data, other players, bank and Grand Exchange contents,
 * wealth, and location are neither read nor exported.
 */
@Slf4j
@PluginDescriptor(
	name = "Facette Telemetry",
	description = "Writes a sanitized local telemetry snapshot for the Facette companion application",
	tags = {"facette", "telemetry", "companion"}
)
public class FacetteTelemetryPlugin extends Plugin
{
	/** How often the publisher wakes; also the floor on the interval between file replacements. */
	private static final long PUBLISH_INTERVAL_MILLIS = 250L;

	/**
	 * Age at which an unchanged snapshot is republished so a reader can tell a live plugin
	 * from a stale file. Kept below two seconds so that, including one publish interval, the
	 * gap between publications stays under the two-second heartbeat bound.
	 */
	private static final long HEARTBEAT_INTERVAL_MILLIS = 1_500L;

	/** Directory created inside RuneLite's canonical data directory. */
	private static final String DATA_SUBDIRECTORY = "facette";

	/** Bound on how long an orderly shutdown waits for an in-flight publication. */
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

	/**
	 * Package-private rather than private so a same-package test can supply a stand-in without
	 * reflection. Still {@code @Inject}, so RuneLite's own field injection is unchanged, and
	 * still invisible outside this package.
	 */
	@Inject
	Client client;

	/**
	 * RuneLite's client-thread dispatcher. Every direct {@link Client} read has to happen on
	 * the client thread; enabling the plugin from the configuration panel calls
	 * {@link #startUp()} on Swing's AWT thread, so startup work is handed here rather than
	 * run on whichever thread happened to call.
	 *
	 * <p>Package-private for the same reason as {@link #client}.
	 */
	@Inject
	ClientThread clientThread;

	/**
	 * Wall-clock milliseconds, for exported timestamps only. Injectable so a test can hold time
	 * still or move it deliberately; in production it is always the real system clock.
	 */
	private final LongSupplier wallClockMillis;

	/**
	 * Monotonic elapsed nanoseconds, for interval decisions only. Separate from the wall clock
	 * so a test can advance cadence without moving exported timestamps, and vice versa.
	 */
	private final LongSupplier elapsedNanos;

	/**
	 * Supplies each run's instance identity. Injectable only so a test can assert that separate
	 * runs get separate identities; in production it is a fresh random UUID per start, derived
	 * from nothing about the account, machine, or game state.
	 */
	private final Supplier<String> instanceIds;

	/**
	 * Supplies the directory the snapshot is written to. Injectable so a test writes into an
	 * isolated temporary directory instead of the operator's real RuneLite data directory. In
	 * production it always resolves to that real directory and nothing else — this seam does
	 * not change the destination, and it is not reachable from configuration, an environment
	 * variable, or a system property.
	 */
	private final Supplier<Path> dataDirectories;

	/**
	 * Supplies each run's publisher executor. Injectable so a test can drive publication on a
	 * thread it controls and assert the executor is disposed of; in production it is always a
	 * single daemon thread, as before.
	 */
	private final Supplier<ScheduledExecutorService> executors;

	/**
	 * Serializes the <em>commit</em> step of publications across runs — the authority check and
	 * the target replacement, nothing else. Within a run the single publisher thread already
	 * orders publications; this matters when a retired run's final write and a new run's
	 * publisher are alive at the same time, on different threads, and it is what stops a
	 * retired run from being authorized and then having a newer run's snapshot overwritten by
	 * its move. Staging is deliberately outside it, so a stalled write delays nobody.
	 */
	private final ReentrantLock publishLock = new ReentrantLock();

	/**
	 * Generation counter shared by every run of this plugin instance. A run may replace the
	 * target file only while it is still the newest generation started.
	 */
	private final AtomicLong newestGeneration = new AtomicLong();

	/**
	 * The run currently being published. Replaced, never mutated, on each start; the previous
	 * run is retired first and can never become current again.
	 */
	private volatile PublisherRunContext currentRun;

	/**
	 * The constructor RuneLite and Guice use. Public and no-argument, exactly as before, so the
	 * normal construction path is unchanged: collaborators still arrive by field injection.
	 */
	public FacetteTelemetryPlugin()
	{
		this(
			System::currentTimeMillis,
			System::nanoTime,
			() -> UUID.randomUUID().toString(),
			FacetteTelemetryPlugin::runeLiteDataDirectory,
			FacetteTelemetryPlugin::newPublisherExecutor);
	}

	/**
	 * Test seam. Package-private, so nothing outside this package can reach it, and it is not
	 * exposed as plugin configuration, an environment variable, or a system property. Every
	 * argument the no-argument constructor passes is the real production implementation, so
	 * the seam changes no production behavior, destination, or schema — it only lets a test
	 * make the lifecycle deterministic without launching a client.
	 */
	FacetteTelemetryPlugin(LongSupplier wallClockMillis, LongSupplier elapsedNanos,
		Supplier<String> instanceIds, Supplier<Path> dataDirectories,
		Supplier<ScheduledExecutorService> executors)
	{
		this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
		this.elapsedNanos = Objects.requireNonNull(elapsedNanos, "elapsedNanos");
		this.instanceIds = Objects.requireNonNull(instanceIds, "instanceIds");
		this.dataDirectories = Objects.requireNonNull(dataDirectories, "dataDirectories");
		this.executors = Objects.requireNonNull(executors, "executors");
	}

	@Override
	protected void startUp()
	{
		// A fresh identity every start, derived from nothing: not the account, the profile,
		// the machine, or any game state. It only lets a reader notice a restart. The new
		// state also starts the sequence at zero with no experience baselines.
		String instanceId = instanceIds.get();
		PublisherRunContext run = PublisherRunContext.begin(
			newestGeneration,
			new TelemetryState(instanceId, wallClockMillis, elapsedNanos),
			new TelemetrySnapshotWriter(dataDirectories.get()));
		currentRun = run;

		// Nothing here touches the client. Enabling from the configuration panel runs this on
		// AWT-EventQueue-0, where any Client read fails the client-thread assertion, so the
		// reads are deferred instead. invoke() runs the callback inline when the caller is
		// already the client thread and queues it otherwise, so one path serves both.
		clientThread.invoke(() -> initializeOnClientThread(run));
	}

	/**
	 * Completes startup on RuneLite's client thread: sample, seed, then publish.
	 *
	 * <p>Runs later than {@link #startUp()} when the plugin was enabled from the configuration
	 * panel, so the first thing it does is establish that the run it was created for is still
	 * the one that matters. A user who disabled the plugin in the meantime — or disabled and
	 * re-enabled it — leaves this callback bound to a retired run, and it must do nothing at
	 * all rather than sample state, seed baselines, start a publisher, or write over whatever
	 * the newer run has already put on disk.
	 */
	private void initializeOnClientThread(PublisherRunContext run)
	{
		if (!run.isCurrent())
		{
			log.debug("Skipping startup for a run retired before its client-thread callback ran");
			return;
		}

		try
		{
			// Seeding is folded into the sample rather than performed here, so that a callback
			// landing during a world hop or loading screen — where there is no live session to
			// read totals from — leaves the seeding to the first live sample instead of
			// skipping it for the whole run. Retained totals are likewise not discarded here;
			// they have to survive until that sample.
			sampleClientState(run.getState());
			// Only now may anything publish: the state has been sampled, so no snapshot can be
			// built from a partly initialized run.
			run.markInitialized();
			startPublisher(run);
		}
		catch (RuntimeException | Error e)
		{
			// Leave nothing half-started. The run is retired so any straggler is inert, no
			// publisher is left behind, and no active snapshot is written — the file simply
			// stops advancing and reads as stale, which is honest about a failed start.
			run.retire();
			run.abandonPublisher();
			log.error("Facette Telemetry failed to start; no telemetry will be published", e);
			throw e;
		}
	}

	private void startPublisher(PublisherRunContext run)
	{
		ScheduledExecutorService executor = executors.get();
		// Adopted before anything is scheduled on it. The first tick has a zero initial delay
		// and can run before scheduleWithFixedDelay even returns, so a disable landing in that
		// gap has to find a publisher to stop — otherwise the executor leaks and the run ends
		// with no final snapshot while a tick already in flight commits an active one.
		if (!run.attachPublisherIfCurrent(executor))
		{
			// Disabled while this callback was sampling and seeding. Shutdown has already run
			// and found no publisher to stop, so nothing else will ever dispose of this
			// executor — do it here rather than leak a thread per disable/re-enable.
			executor.shutdownNow();
			log.debug("Run was retired during startup; publisher discarded");
			return;
		}
		try
		{
			// The task is bound to this run for its whole life. It never reads a field, so a
			// later start cannot redirect it at a newer run's state or writer.
			ScheduledFuture<?> publishTask = executor.scheduleWithFixedDelay(
				() -> publishTick(run), 0L, PUBLISH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
			run.attachPublishTask(publishTask);
		}
		catch (RejectedExecutionException e)
		{
			// shutDown() claimed the executor between adoption and scheduling. It already owns
			// the cleanup and the final write, so there is nothing to schedule and nothing to
			// repair here.
			log.debug("Publisher was shut down while starting; no periodic task scheduled");
			return;
		}

		log.debug("Facette Telemetry started; publishing to {}", run.getWriter().getTarget());
	}

	@Override
	protected void shutDown()
	{
		PublisherRunContext run = currentRun;
		if (run == null)
		{
			return;
		}

		// Retiring first means a pending startup callback, and any tick already waiting on the
		// publication lock, both become inert. Retirement is scoped to this context, so a rapid
		// re-enable creates a different run and cannot bring this one back.
		run.retire();

		if (!run.hasPublisher())
		{
			// Never got as far as publishing — a startup that failed, or one disabled before
			// its client-thread callback ran. Deliberately writes nothing: a run that never
			// published has no state worth reporting, and emitting an inactive seq 0 snapshot
			// for every failed start would churn the file and tell a reader nothing true.
			run.abandonPublisher();
			log.debug("Facette Telemetry stopped before it published; no final snapshot written");
			return;
		}

		// The final write goes to the run's own publisher thread, not this one. On the real
		// client this method runs on the client thread (or AWT), and the write can stall for
		// as long as the filesystem takes — directory creation, force(true), and the target
		// move are not interruptible in any way this code could rely on. So it is queued, and
		// this thread waits only a bounded time for an acknowledgement.
		run.submitFinalWrite(() -> publish(run, false));

		if (run.awaitPublisherTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
		{
			log.debug("Facette Telemetry stopped");
			return;
		}

		// Returning here is the point: the caller is released on time. The write continues on
		// a daemon thread and will replace the target only if it still holds authority when it
		// gets there, so if the plugin is re-enabled first it is refused rather than landing an
		// inactive snapshot on top of the new run.
		log.warn("Final telemetry snapshot did not complete within {}s; continuing off-thread. "
			+ "It will be abandoned if the plugin is re-enabled first.", SHUTDOWN_TIMEOUT_SECONDS);
	}

	/**
	 * The telemetry state of the run currently publishing, or null when there is none yet or
	 * it has not finished initializing.
	 *
	 * <p>Returning null until initialization completes is what keeps events out of a partly
	 * built run: an event that arrives between {@code startUp()} and the client-thread callback
	 * is dropped rather than queued, and no backlog can build up. For game state, world, vitals,
	 * and inventory nothing is lost by that, because the first periodic sample after
	 * initialization reads the live client state and reconciles whatever those events carried.
	 *
	 * <p>Experience is the exception, and is handled separately in
	 * {@link #onStatChanged(StatChanged)}: experience gains are event-only and a later sample
	 * cannot reconstruct them, so dropping those events outright would lose them.
	 *
	 * <p>Called from the RuneLite client thread only, which is also the thread that replaces
	 * the run, so a handler never observes a half-started one.
	 */
	private TelemetryState currentState()
	{
		PublisherRunContext run = currentRun;
		return run != null && run.isCurrent() && run.isInitialized() ? run.getState() : null;
	}

	/**
	 * The telemetry state of a run that has started but not yet finished initializing, or null
	 * when there is no such run.
	 *
	 * <p>The counterpart to {@link #currentState()}, and deliberately a separate accessor: the
	 * only thing permitted to reach a run through it is retaining an experience total, which
	 * publishes nothing and builds no snapshot.
	 */
	private TelemetryState startingState()
	{
		PublisherRunContext run = currentRun;
		return run != null && run.isCurrent() && !run.isInitialized() ? run.getState() : null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState gameState = gameStateChanged.getGameState();
		TelemetryState state = currentState();
		if (state == null)
		{
			// Still starting. The transition itself needs no handling — the first sample after
			// initialization reads the live state — but a session *ending* has to be acted on
			// now. Experience totals retained during startup belong to the session that ended,
			// and if startup spans a logout and a new login, seeding against them would measure
			// one character's total against another's and export a gain that never happened.
			TelemetryState starting = startingState();
			if (starting != null && endsSession(gameState))
			{
				starting.discardPreInitialXp();
			}
			return;
		}
		// One atomic transition. Applied as two calls, a publication could slip between them
		// and observe the experience baselines already discarded while the session still read
		// as live and still carried the previous world, vitals, and inventory.
		state.updateSession(
			gameState.name(), gameState == GameState.LOGGED_IN, endsSession(gameState));
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		// Also the reconciliation point for anything that happened before initialization
		// finished: this reads the live client state, so events dropped in that window leave
		// nothing stale behind.
		sampleClientState(currentState());
	}

	/**
	 * Records an experience total, either as an observation or — before the run has finished
	 * initializing — as a total to be measured against once it has.
	 *
	 * <p>Experience is the one thing a later sample cannot reconstruct. World, vitals, and
	 * inventory are all re-read on the next periodic sample, so an event dropped during the
	 * deferred startup window costs nothing. A gain is not a value the client holds; it is the
	 * difference between two totals, and if the earlier total is discarded the gain is gone.
	 * Seeding afterwards from the live totals would then quietly absorb everything earned while
	 * startup was queued, and absorb more the longer the client took to run the callback.
	 */
	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		Skill skill = statChanged.getSkill();
		if (skill == null)
		{
			return;
		}
		TelemetryState state = currentState();
		if (state == null)
		{
			// Still starting. The total is retained rather than acted on: no baseline exists to
			// measure it against yet, and nothing here publishes or builds a snapshot.
			TelemetryState starting = startingState();
			if (starting != null)
			{
				starting.recordPreInitialXp(skill.name(), statChanged.getXp());
			}
			return;
		}
		// Only the skill and the increase are kept; the total is used as a comparison
		// baseline and is never exported.
		state.observeXp(skill.name(), statChanged.getXp());
	}

	/**
	 * Reads the approved client state. Called from the client thread only, so the values
	 * are consistent with the tick that produced them.
	 *
	 * @return true when the client is in a live logged-in session, so callers needing that
	 *         condition reuse this one definition rather than restating it
	 */
	private boolean sampleClientState(TelemetryState state)
	{
		if (state == null)
		{
			return false;
		}

		GameState gameState = client.getGameState();
		boolean loggedIn = gameState == GameState.LOGGED_IN;
		state.updateSession(gameState.name(), loggedIn);
		if (!loggedIn)
		{
			return false;
		}

		// The first live sample of a session establishes its experience baselines, whether that
		// is the one taken during startup or a later one after a hop or loading screen. Reading
		// the totals on a tick rather than on the logged-in transition also means skill data is
		// loaded, so a transient zero cannot become a baseline and fabricate a gain the size of
		// the whole skill when the real total arrives.
		if (state.needsXpBaselineSeeding())
		{
			seedXpBaselines(state);
			state.markXpBaselinesSeeded();
		}

		state.updateWorld(client.getWorld());
		state.updateVitals(
			client.getBoostedSkillLevel(Skill.HITPOINTS),
			client.getRealSkillLevel(Skill.HITPOINTS),
			client.getBoostedSkillLevel(Skill.PRAYER),
			client.getRealSkillLevel(Skill.PRAYER),
			client.getEnergy());

		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory != null)
		{
			// Occupied slots, not total item quantity.
			state.updateInventory(inventory.count());
		}
		return true;
	}

	/**
	 * Seeds the experience baselines from the client's current totals, for the case where the
	 * plugin is enabled while the player is already logged in.
	 *
	 * <p>In that case RuneLite's login-time experience events fired before the plugin was
	 * running, so nothing has filled the baselines and the next real gain would be consumed
	 * as a first observation and never exported. Seeding costs one read per skill, once per
	 * session, and reports nothing by itself.
	 *
	 * <p>Called from the first live sample of a session rather than from startup — see
	 * {@link TelemetryState#needsXpBaselineSeeding()} for why tying it to startup left a
	 * callback that landed mid-hop with no baselines at all. Reached only once
	 * {@link #sampleClientState(TelemetryState)} has established a live session, so the totals
	 * read here belong to a real logged-in character rather than an empty or half-loaded one.
	 *
	 * <p>Where {@link TelemetryState#recordPreInitialXp(String, int)} retained an earlier total
	 * for a skill, seeding measures against that instead and exports the difference, so
	 * experience earned while startup was still queued is not absorbed.
	 */
	private void seedXpBaselines(TelemetryState state)
	{
		for (Skill skill : Skill.values())
		{
			if (!isSeedableSkill(skill))
			{
				continue;
			}
			// Read per skill rather than through getSkillExperiences(), whose array would have
			// to be mapped back by ordinal — the kind of positional assumption that breaks
			// silently when the enum changes.
			state.seedXpBaseline(skill.name(), client.getSkillExperience(skill));
		}
	}

	/**
	 * Whether a skill enumeration entry is a real trainable skill the client will accept.
	 *
	 * <p>In current RuneLite {@code Skill.OVERALL} is a deprecated {@code null} constant
	 * declared after the enum body, so it never appears in {@link Skill#values()} and this
	 * filter is a no-op. It is written anyway because older RuneLite releases did expose
	 * {@code OVERALL} as a real enum constant, the plugin builds against
	 * {@code latest.release}, and the cost of being wrong is passing a non-skill — or a
	 * null — to {@code getSkillExperience}. The check is on the entry itself and its name,
	 * not on ordinal position or array length.
	 */
	private static boolean isSeedableSkill(Skill skill)
	{
		return skill != null && !"OVERALL".equals(skill.name());
	}

	/**
	 * Whether reaching this game state ends the play session, discarding the experience
	 * baselines so a later login cannot inherit them. Every login passes through
	 * {@link GameState#LOGGING_IN}, so a world hop or a brief loading screen keeps its
	 * baselines while a new login never does.
	 */
	private static boolean endsSession(GameState gameState)
	{
		return gameState == GameState.LOGIN_SCREEN
			|| gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| gameState == GameState.LOGGING_IN;
	}

	/**
	 * One scheduled publication attempt, permanently bound to the run that scheduled it.
	 *
	 * <p>A retired run stops here rather than doing the work of building and staging a snapshot
	 * it would not be allowed to commit. This check is an optimization, not the safeguard: a
	 * run retired after it passes still cannot land on a newer run's file, because the writer
	 * re-checks commit authority under the lock immediately before replacing the target.
	 */
	private void publishTick(PublisherRunContext run)
	{
		if (!run.isCurrent())
		{
			return;
		}
		// No lock is needed for the due-check: the commit decision is made later, inside the
		// writer. A change landing between here and there only costs a redundant publication.
		if (run.getState().isPublicationDue(HEARTBEAT_INTERVAL_MILLIS))
		{
			publish(run, true);
		}
	}

	/**
	 * Publishes one snapshot through the run that owns it.
	 *
	 * <p>Everything this touches comes from {@code run}, so a publication can only ever reach
	 * the state, writer, sequence, and bookkeeping of the run that issued it — never a later
	 * one's, whatever the interleaving.
	 *
	 * <p>{@link #publishLock} is not held across this method. It is handed to the writer, which
	 * takes it only across the authorization check and the target replacement — the pair that
	 * has to be indivisible — and never across staging. Holding it through staging would let
	 * one run's stalled write block a newly enabled run from publishing or heartbeating at all,
	 * which is a worse failure than the one the lock exists to prevent.
	 */
	private void publish(PublisherRunContext run, boolean pluginActive)
	{
		TelemetryState publishingState = run.getState();
		TelemetrySnapshot snapshot = publishingState.nextSnapshot(pluginActive);
		try
		{
			// The authority check runs inside the writer, immediately before it replaces the
			// target — not here, where a slow write could make the answer stale before it
			// mattered. A run that lost authority while staging throws below.
			int bytes = run.getWriter().write(snapshot, publishLock, run::isCommitAuthorized);
			// The sequence advances only for a snapshot that actually reached the file, and
			// only on the state that issued it.
			publishingState.recordPublished();
			log.debug("Published telemetry snapshot seq={} ({} bytes)", snapshot.getSeq(), bytes);
		}
		catch (TelemetrySnapshotWriter.CommitNotAuthorizedException e)
		{
			// Expected whenever a newer run started while this one was writing — the staged
			// file has been discarded and the target left alone. Not a failure, and not worth
			// a warning; the sequence and bookkeeping are untouched because recordPublished
			// was skipped.
			log.debug("Abandoned a telemetry snapshot superseded by a newer plugin run");
		}
		catch (IOException e)
		{
			// Logged without the payload, and without advancing the sequence: the next
			// publication retries the same sequence number.
			log.warn("Unable to publish telemetry snapshot to {}", run.getWriter().getTarget(), e);
		}
	}

	/**
	 * The plugin's data directory inside RuneLite's canonical data directory. The production
	 * destination, and the only one the no-argument constructor ever supplies.
	 */
	private static Path runeLiteDataDirectory()
	{
		return new File(RuneLite.RUNELITE_DIR, DATA_SUBDIRECTORY).toPath();
	}

	/**
	 * The production publisher: one daemon thread per run, named so it is identifiable in a
	 * thread dump, and daemon so it can never hold the client open.
	 */
	private static ScheduledExecutorService newPublisherExecutor()
	{
		return Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "facette-telemetry-publisher");
			thread.setDaemon(true);
			return thread;
		});
	}
}
