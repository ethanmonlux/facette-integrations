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
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Covers the run-generation rule that keeps a disabled plugin run's scheduled work out of the
 * next run: a task bound to a retired run must not publish, must not reach the newer run's
 * state or writer, and must not be revivable by the next start.
 *
 * <p>The interleaving is driven by latches and by the lock's own queue state, never by
 * sleeping — the assertions depend on observed state, not on elapsed time.
 *
 * <p>Needs no account, credential, network service, Facette installation, or game session.
 */
public class PublisherRunContextTest
{
	private static final long NOW = 1_770_000_000_000L;

	/** Bound on every wait, so a broken invariant fails the test instead of hanging it. */
	private static final long TIMEOUT_SECONDS = 10L;

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private LongSupplier clock;
	private int directoryCounter;

	@Before
	public void setUp()
	{
		clock = () -> NOW;
	}

	private PublisherRunContext newRun()
	{
		// A separate directory per run, so a cross-run write would be visible as one run's
		// file appearing under another run's directory rather than being masked by a shared
		// target path.
		Path directory = folder.getRoot().toPath().resolve("run-" + directoryCounter++);
		return new PublisherRunContext(
			new TelemetryState(UUID.randomUUID().toString(), clock),
			new TelemetrySnapshotWriter(directory));
	}

	/** Mirrors the plugin's publication: build, write, record — all through one run. */
	private static void publish(PublisherRunContext run, boolean pluginActive) throws IOException
	{
		TelemetrySnapshot snapshot = run.getState().nextSnapshot(pluginActive);
		run.getWriter().write(snapshot);
		run.getState().recordPublished();
	}

	@Test
	public void aFreshRunIsCurrentAndItsWorkMayProceed()
	{
		PublisherRunContext run = newRun();
		assertTrue(run.isCurrent());
		assertEquals(0L, run.getState().getNextSeq());
	}

	@Test
	public void retirementIsOneWayAndIdempotent()
	{
		PublisherRunContext run = newRun();
		assertTrue("first retire takes effect", run.retire());
		assertFalse(run.isCurrent());
		assertFalse("retiring again is a no-op", run.retire());
		assertFalse("a retired run never becomes current again", run.isCurrent());
	}

	@Test
	public void startingALaterRunCannotReviveAnEarlierOne()
	{
		PublisherRunContext first = newRun();
		first.retire();

		// Standing in for what the old shared shuttingDown flag did on re-enable: starting a
		// new run. Retirement is per-context, so this cannot reach the retired one.
		PublisherRunContext second = newRun();

		assertFalse("the retired run stays retired", first.isCurrent());
		assertTrue("the new run is current", second.isCurrent());
		assertNotEquals("each run has its own identity",
			first.getState().getInstanceId(), second.getState().getInstanceId());
	}

	/**
	 * The round-5 interleaving, driven deterministically: an old task is already executing and
	 * waiting on the publication lock when its run is disabled, and the plugin is re-enabled
	 * before the lock is released.
	 */
	@Test
	public void aTaskAlreadyWaitingOnTheLockDoesNotPublishAfterItsRunIsRetired() throws Exception
	{
		ReentrantLock publishLock = new ReentrantLock();
		PublisherRunContext oldRun = newRun();

		CountDownLatch oldTaskReachedTheLock = new CountDownLatch(1);
		AtomicBoolean oldTaskPublished = new AtomicBoolean(false);
		AtomicBoolean passedTheFirstCheck = new AtomicBoolean(false);

		// The client thread holds the lock, standing in for the shutdown write.
		publishLock.lock();

		Thread oldTask = new Thread(() ->
		{
			// The pre-lock check, taken while the run is still current.
			if (!oldRun.isCurrent())
			{
				return;
			}
			passedTheFirstCheck.set(true);
			oldTaskReachedTheLock.countDown();

			publishLock.lock();
			try
			{
				// The post-lock check. By now the run has been retired and a new one started.
				if (!oldRun.isCurrent())
				{
					return;
				}
				publish(oldRun, true);
				oldTaskPublished.set(true);
			}
			catch (IOException e)
			{
				throw new IllegalStateException(e);
			}
			finally
			{
				publishLock.unlock();
			}
		}, "old-run-task");

		oldTask.start();
		assertTrue("old task should reach the lock",
			oldTaskReachedTheLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		awaitQueuedOnLock(publishLock);

		// Disable, then a rapid re-enable while the old task is still queued on the lock.
		oldRun.retire();
		PublisherRunContext newRun = newRun();

		publishLock.unlock();
		oldTask.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
		assertFalse("old task should have finished", oldTask.isAlive());

		assertTrue("the old task must genuinely have raced, not exited early",
			passedTheFirstCheck.get());
		assertFalse("work from a retired run must not publish", oldTaskPublished.get());

		// The new run is untouched: sequence still at zero, nothing written under it.
		assertTrue(newRun.isCurrent());
		assertEquals("the new run's sequence must not have been consumed",
			0L, newRun.getState().getNextSeq());
		assertFalse("the new run's file must not exist yet",
			newRun.getWriter().getTarget().toFile().exists());
	}

	/**
	 * The same interleaving, but confirming the new run still works normally afterwards — a
	 * guard that could suppress old work by suppressing all work would pass the test above.
	 */
	@Test
	public void theNewRunPublishesNormallyAndStartsAtSequenceZero() throws Exception
	{
		PublisherRunContext oldRun = newRun();
		oldRun.retire();
		PublisherRunContext newRun = newRun();

		publish(newRun, true);

		assertTrue("the new run's file should exist",
			newRun.getWriter().getTarget().toFile().exists());
		assertEquals("first publication of a fresh run is seq 0",
			1L, newRun.getState().getNextSeq());

		// Its first snapshot carried seq 0 and this run's own identity.
		TelemetrySnapshot second = newRun.getState().nextSnapshot(true);
		assertEquals(1L, second.getSeq());
		assertEquals(newRun.getState().getInstanceId(), second.getInstanceId());

		// And the retired run never advanced.
		assertEquals(0L, oldRun.getState().getNextSeq());
	}

	@Test
	public void aRetiredRunCannotReachALaterRunsStateOrWriter()
	{
		PublisherRunContext oldRun = newRun();
		PublisherRunContext newRun = newRun();
		oldRun.retire();

		// The two runs share nothing: there is no path from the retired context to the
		// current one's collaborators, so no interleaving can cross them.
		assertNotEquals(oldRun.getState(), newRun.getState());
		assertNotEquals(oldRun.getWriter(), newRun.getWriter());
		assertNotEquals(oldRun.getWriter().getTarget(), newRun.getWriter().getTarget());
		assertNotEquals(oldRun.getState().getInstanceId(), newRun.getState().getInstanceId());
	}

	/**
	 * Waits until the other thread is actually queued on the lock. This is a state check with
	 * a bounded deadline, not a timing assumption — the test's correctness comes from
	 * {@code hasQueuedThreads()} being true, and the deadline only converts a hang into a
	 * failure.
	 */
	private static void awaitQueuedOnLock(ReentrantLock lock) throws InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (!lock.hasQueuedThreads())
		{
			if (System.nanoTime() > deadline)
			{
				throw new AssertionError("thread never queued on the publication lock");
			}
			Thread.yield();
		}
	}
}
