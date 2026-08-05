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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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

	@Inject
	private Client client;

	/**
	 * Serializes publications. Held across the whole build-write-record sequence so that two
	 * threads can never interleave snapshots, reorder them on disk, or share a sequence
	 * number — in particular the publisher thread and the client thread performing the final
	 * shutdown write.
	 */
	private final ReentrantLock publishLock = new ReentrantLock();

	/**
	 * The run currently being published. Replaced, never mutated, on each start; the previous
	 * run is retired first and can never become current again.
	 */
	private volatile PublisherRunContext currentRun;

	private ScheduledExecutorService executor;
	private ScheduledFuture<?> publishTask;

	@Override
	protected void startUp()
	{
		// A fresh identity every start, derived from nothing: not the account, the profile,
		// the machine, or any game state. It only lets a reader notice a restart. The new
		// state also starts the sequence at zero with no experience baselines.
		String instanceId = UUID.randomUUID().toString();
		PublisherRunContext run = new PublisherRunContext(
			new TelemetryState(instanceId, System::currentTimeMillis),
			new TelemetrySnapshotWriter(dataDirectory()));
		currentRun = run;

		// Ordering matters, and all of it happens on the client thread before the publisher
		// exists. The run and its state are created first; sampling then records whether this
		// is a live session; seeding fills the experience baselines only if it is. Because the
		// executor is not started until after all of that, no publication can observe a
		// half-initialized run, and none of these client calls happens under the publication
		// lock — nothing here can block the client thread on the publisher.
		if (sampleClientState())
		{
			seedXpBaselines(run.getState());
		}

		executor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "facette-telemetry-publisher");
			thread.setDaemon(true);
			return thread;
		});
		// The task is bound to this run for its whole life. It never reads the field, so a
		// later start cannot redirect it at a newer run's state or writer.
		// A zero initial delay publishes the active snapshot as soon as the plugin starts.
		publishTask = executor.scheduleWithFixedDelay(
			() -> publishTick(run), 0L, PUBLISH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);

		log.debug("Facette Telemetry started; publishing to {}", run.getWriter().getTarget());
	}

	@Override
	protected void shutDown()
	{
		PublisherRunContext run = currentRun;

		// Order matters. Retiring the run first means a task already waiting on the
		// publication lock abandons itself when it finally acquires the lock, rather than
		// writing an active snapshot after the final inactive one — and because retirement
		// is scoped to this context, a rapid re-enable creates a *different* run and cannot
		// bring this one back. Stopping the schedule prevents new ticks being queued at all.
		if (run != null)
		{
			run.retire();
		}

		if (publishTask != null)
		{
			publishTask.cancel(false);
			publishTask = null;
		}
		if (executor != null)
		{
			// Non-blocking: ordering against an in-flight publication comes from the lock
			// below, not from waiting for the executor to terminate.
			executor.shutdown();
			executor = null;
		}

		if (run != null)
		{
			writeFinalSnapshot(run);
		}

		log.debug("Facette Telemetry stopped");
	}

	/**
	 * Writes the final snapshot on the same instance, reporting the plugin as inactive and
	 * logged out with every gameplay-derived field null.
	 *
	 * <p>Taking the publication lock is what makes this write last: a publication already
	 * inside {@link TelemetrySnapshotWriter#write} finishes first, and one that has not
	 * started yet finds its run retired and does nothing. If the in-flight write is stalled
	 * beyond the timeout the final snapshot is skipped rather than raced onto disk out of
	 * order — the file then stops advancing, and a reader detects it as stale by its
	 * timestamp exactly as it would after a hard process termination.
	 *
	 * <p>The run is passed in rather than read from the field, because that skipped case is
	 * exactly when a disable/re-enable can replace it while the stalled publication is still
	 * running. This write deliberately does not check {@link PublisherRunContext#isCurrent()}
	 * — the run it reports as inactive is the one being retired, and it is the whole point of
	 * the call.
	 */
	private void writeFinalSnapshot(PublisherRunContext run)
	{
		boolean acquired = false;
		try
		{
			acquired = publishLock.tryLock(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}

		if (!acquired)
		{
			log.warn("Timed out waiting for an in-flight publication; skipping the final "
				+ "snapshot. {} will stop advancing and reads as stale.", run.getWriter().getTarget());
			return;
		}

		try
		{
			publish(run, false);
		}
		finally
		{
			publishLock.unlock();
		}
	}

	/**
	 * The telemetry state of the run currently being published, or null before the first
	 * start. Called from the RuneLite client thread only, which is also the thread that
	 * replaces the run, so a handler never observes a half-started one.
	 */
	private TelemetryState currentState()
	{
		PublisherRunContext run = currentRun;
		return run == null ? null : run.getState();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		TelemetryState state = currentState();
		if (state == null)
		{
			return;
		}
		GameState gameState = gameStateChanged.getGameState();
		// One atomic transition. Applied as two calls, a publication could slip between them
		// and observe the experience baselines already discarded while the session still read
		// as live and still carried the previous world, vitals, and inventory.
		state.updateSession(
			gameState.name(), gameState == GameState.LOGGED_IN, endsSession(gameState));
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		sampleClientState();
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		TelemetryState state = currentState();
		Skill skill = statChanged.getSkill();
		if (state == null || skill == null)
		{
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
	private boolean sampleClientState()
	{
		TelemetryState state = currentState();
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
	 * start, and reports nothing.
	 *
	 * <p>Only called when {@link #sampleClientState()} reports a live session, so the totals
	 * read here belong to a real logged-in character rather than an empty or half-loaded one.
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
	 * <p>The run is checked twice on purpose. Once before contending for the lock, so a
	 * retired run does not queue behind a shutdown write it has no business completing. Once
	 * again after acquiring it, because the interesting case is the task that was *already*
	 * waiting when its run was disabled: by the time it wins the lock the plugin may have
	 * been re-enabled, and only the second check can see that.
	 */
	private void publishTick(PublisherRunContext run)
	{
		if (!run.isCurrent())
		{
			return;
		}

		publishLock.lock();
		try
		{
			// Re-checked under the lock: a tick that was waiting here while the plugin was
			// disabled — and possibly re-enabled — must not publish. Retirement is scoped to
			// this run, so restarting cannot clear it.
			if (run.isCurrent() && run.getState().isPublicationDue(HEARTBEAT_INTERVAL_MILLIS))
			{
				publish(run, true);
			}
		}
		finally
		{
			publishLock.unlock();
		}
	}

	/**
	 * Publishes one snapshot through the run that owns it. Callers must hold
	 * {@link #publishLock}.
	 *
	 * <p>Everything this touches comes from {@code run}, so a publication can only ever reach
	 * the state, writer, sequence, and bookkeeping of the run that issued it — never a later
	 * one's, whatever the interleaving.
	 */
	private void publish(PublisherRunContext run, boolean pluginActive)
	{
		TelemetryState publishingState = run.getState();
		TelemetrySnapshot snapshot = publishingState.nextSnapshot(pluginActive);
		try
		{
			int bytes = run.getWriter().write(snapshot);
			// The sequence advances only for a snapshot that actually reached the file, and
			// only on the state that issued it.
			publishingState.recordPublished();
			log.debug("Published telemetry snapshot seq={} ({} bytes)", snapshot.getSeq(), bytes);
		}
		catch (IOException e)
		{
			// Logged without the payload, and without advancing the sequence: the next
			// publication retries the same sequence number.
			log.warn("Unable to publish telemetry snapshot to {}", run.getWriter().getTarget(), e);
		}
	}

	/** The plugin's data directory inside RuneLite's canonical data directory. */
	private static Path dataDirectory()
	{
		return new File(RuneLite.RUNELITE_DIR, DATA_SUBDIRECTORY).toPath();
	}
}
