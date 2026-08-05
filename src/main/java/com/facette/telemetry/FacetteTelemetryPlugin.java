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
	 * Set before shutdown takes the lock, so a publication already queued behind it does not
	 * write an active snapshot after the final inactive one.
	 */
	private volatile boolean shuttingDown;

	private TelemetryState state;
	private TelemetrySnapshotWriter writer;
	private ScheduledExecutorService executor;
	private ScheduledFuture<?> publishTask;

	@Override
	protected void startUp()
	{
		// A fresh identity every start, derived from nothing: not the account, the profile,
		// the machine, or any game state. It only lets a reader notice a restart. The new
		// state also starts the sequence at zero with no experience baselines.
		String instanceId = UUID.randomUUID().toString();
		state = new TelemetryState(instanceId, System::currentTimeMillis);
		writer = new TelemetrySnapshotWriter(dataDirectory());
		// RuneLite reuses this instance across disable/enable, so the previous shutdown's
		// flag must not suppress this run's publications.
		shuttingDown = false;

		sampleClientState();

		executor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "facette-telemetry-publisher");
			thread.setDaemon(true);
			return thread;
		});
		// A zero initial delay publishes the active snapshot as soon as the plugin starts.
		publishTask = executor.scheduleWithFixedDelay(
			this::publishTick, 0L, PUBLISH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);

		log.debug("Facette Telemetry started; publishing to {}", writer.getTarget());
	}

	@Override
	protected void shutDown()
	{
		// Order matters. The flag is raised first so that any publication still waiting on
		// the lock abandons itself rather than writing an active snapshot after the final
		// inactive one. Stopping the schedule prevents new ones from being queued at all.
		shuttingDown = true;

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

		TelemetryState finalState = state;
		TelemetrySnapshotWriter finalWriter = writer;
		if (finalState != null && finalWriter != null)
		{
			writeFinalSnapshot(finalState, finalWriter);
		}

		log.debug("Facette Telemetry stopped");
	}

	/**
	 * Writes the final snapshot on the same instance, reporting the plugin as inactive and
	 * logged out with every gameplay-derived field null.
	 *
	 * <p>Taking the publication lock is what makes this write last: a publication already
	 * inside {@link TelemetrySnapshotWriter#write} finishes first, and one that has not
	 * started yet sees {@link #shuttingDown} and does nothing. If the in-flight write is
	 * stalled beyond the timeout the final snapshot is skipped rather than raced onto disk
	 * out of order — the file then stops advancing, and a reader detects it as stale by its
	 * timestamp exactly as it would after a hard process termination.
	 *
	 * <p>The state and writer are passed in rather than read from the fields, because that
	 * skipped case is exactly when a disable/re-enable can replace them while the stalled
	 * publication is still running.
	 */
	private void writeFinalSnapshot(TelemetryState finalState, TelemetrySnapshotWriter finalWriter)
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
				+ "snapshot. {} will stop advancing and reads as stale.", finalWriter.getTarget());
			return;
		}

		try
		{
			publish(finalState, finalWriter, false);
		}
		finally
		{
			publishLock.unlock();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
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
		Skill skill = statChanged.getSkill();
		if (skill == null)
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
	 */
	private void sampleClientState()
	{
		GameState gameState = client.getGameState();
		boolean loggedIn = gameState == GameState.LOGGED_IN;
		state.updateSession(gameState.name(), loggedIn);
		if (!loggedIn)
		{
			return;
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

	private void publishTick()
	{
		publishLock.lock();
		try
		{
			// Bound once, then used throughout: a stalled write can outlive shutDown(), and a
			// disable/re-enable in that window replaces these fields. A publication must
			// finish against the instances that produced it, never a later run's.
			TelemetryState publishingState = state;
			TelemetrySnapshotWriter publishingWriter = writer;

			// Re-checked under the lock: a tick that was waiting here while shutdown ran
			// must not write an active snapshot over the final inactive one.
			if (!shuttingDown && publishingState.isPublicationDue(HEARTBEAT_INTERVAL_MILLIS))
			{
				publish(publishingState, publishingWriter, true);
			}
		}
		finally
		{
			publishLock.unlock();
		}
	}

	/**
	 * Publishes one snapshot. Callers must hold {@link #publishLock} and must pass the state
	 * and writer they intend the publication to belong to, rather than letting it re-read
	 * fields a concurrent restart may have replaced.
	 */
	private void publish(TelemetryState publishingState, TelemetrySnapshotWriter publishingWriter,
		boolean pluginActive)
	{
		TelemetrySnapshot snapshot = publishingState.nextSnapshot(pluginActive);
		try
		{
			int bytes = publishingWriter.write(snapshot);
			// The sequence advances only for a snapshot that actually reached the file, and
			// only on the state that issued it.
			publishingState.recordPublished();
			log.debug("Published telemetry snapshot seq={} ({} bytes)", snapshot.getSeq(), bytes);
		}
		catch (IOException e)
		{
			// Logged without the payload, and without advancing the sequence: the next
			// publication retries the same sequence number.
			log.warn("Unable to publish telemetry snapshot to {}", publishingWriter.getTarget(), e);
		}
	}

	/** The plugin's data directory inside RuneLite's canonical data directory. */
	private static Path dataDirectory()
	{
		return new File(RuneLite.RUNELITE_DIR, DATA_SUBDIRECTORY).toPath();
	}
}
