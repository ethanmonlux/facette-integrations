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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Covers the guarantee a reader depends on: the target file is always a complete document,
 * replaced in one step, never larger than the ceiling, and never left with our debris.
 *
 * <p>Needs no account, credential, network service, Facette installation, or game session.
 */
public class TelemetrySnapshotWriterTest
{
	private static final long NOW = 1_770_000_000_000L;

	/** Commit authority for the cases that are not about authorization. */
	private static final BooleanSupplier ALWAYS = () -> true;

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Path directory;

	@Before
	public void setUp()
	{
		directory = folder.getRoot().toPath().resolve("facette");
	}

	private static TelemetrySnapshot snapshot(long seq, String gameState)
	{
		return TelemetrySnapshot.builder()
			.instanceId("0f8b1d3a-6c2e-4a15-9f77-2b8d4e6a1c90")
			.seq(seq)
			.emittedAt(NOW)
			.pluginActive(true)
			.gameState(gameState)
			.loggedIn(false)
			.world(null)
			.hitpoints(null, null)
			.prayer(null, null)
			.runEnergyPercent(null)
			.inventory(null, null)
			.xp(null, null, null)
			.build();
	}

	/** Records every move the writer attempts, delegating to the real filesystem. */
	private static final class RecordingMover implements TelemetrySnapshotWriter.Mover
	{
		private final List<List<CopyOption>> attempts = new ArrayList<>();
		private boolean refuseAtomicMove;
		private IOException failWith;

		@Override
		public void move(Path source, Path target, CopyOption... options) throws IOException
		{
			attempts.add(Arrays.asList(options));
			if (failWith != null)
			{
				throw failWith;
			}
			if (refuseAtomicMove && Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
			{
				throw new AtomicMoveNotSupportedException(
					source.toString(), target.toString(), "simulated filesystem");
			}
			Files.move(source, target, options);
		}
	}

	private List<Path> temporaryFiles() throws IOException
	{
		List<Path> found = new ArrayList<>();
		if (!Files.isDirectory(directory))
		{
			return found;
		}
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.tmp"))
		{
			for (Path entry : entries)
			{
				found.add(entry);
			}
		}
		return found;
	}

	@Test
	public void createsTheDirectoryAndWritesTheCompleteTarget() throws IOException
	{
		TelemetrySnapshotWriter writer = new TelemetrySnapshotWriter(directory);
		assertFalse(Files.exists(directory));

		TelemetrySnapshot snapshot = snapshot(0L, "LOGIN_SCREEN");
		int written = writer.write(snapshot, new ReentrantLock(), ALWAYS);

		Path target = directory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME);
		assertEquals(target, writer.getTarget());
		assertTrue(Files.isRegularFile(target));
		assertArrayEquals(snapshot.toJsonBytes(), Files.readAllBytes(target));
		assertEquals(snapshot.toJsonBytes().length, written);
		assertEquals(snapshot.toJson(), new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
		assertEquals("no temporary files survive a successful write", 0, temporaryFiles().size());
	}

	@Test
	public void replacesAnExistingTargetCompletely() throws IOException
	{
		TelemetrySnapshotWriter writer = new TelemetrySnapshotWriter(directory);
		writer.write(snapshot(0L, "LOGIN_SCREEN"), new ReentrantLock(), ALWAYS);

		TelemetrySnapshot second = snapshot(1L, "LOGGED_IN");
		writer.write(second, new ReentrantLock(), ALWAYS);

		Path target = directory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME);
		assertArrayEquals(second.toJsonBytes(), Files.readAllBytes(target));
		assertFalse("the previous document must not survive alongside the new one",
			new String(Files.readAllBytes(target), StandardCharsets.UTF_8).contains("LOGIN_SCREEN"));
		assertEquals(0, temporaryFiles().size());
	}

	@Test
	public void replacementIsAttemptedAtomicallyFirst() throws IOException
	{
		RecordingMover mover = new RecordingMover();
		TelemetrySnapshotWriter writer = new TelemetrySnapshotWriter(directory, mover, () -> NOW);
		writer.write(snapshot(0L, "LOGGED_IN"), new ReentrantLock(), ALWAYS);

		assertEquals(1, mover.attempts.size());
		assertTrue(mover.attempts.get(0).contains(StandardCopyOption.ATOMIC_MOVE));
		assertTrue(mover.attempts.get(0).contains(StandardCopyOption.REPLACE_EXISTING));
	}

	@Test
	public void fallsBackToAReplacingMoveOfTheCompleteFileWhenAtomicMoveIsUnsupported() throws IOException
	{
		RecordingMover mover = new RecordingMover();
		mover.refuseAtomicMove = true;
		TelemetrySnapshotWriter writer = new TelemetrySnapshotWriter(directory, mover, () -> NOW);

		writer.write(snapshot(0L, "LOGIN_SCREEN"), new ReentrantLock(), ALWAYS);
		TelemetrySnapshot second = snapshot(1L, "LOGGED_IN");
		writer.write(second, new ReentrantLock(), ALWAYS);

		// Two publications, each attempting atomic first and then falling back.
		assertEquals(4, mover.attempts.size());
		assertEquals(Arrays.asList(StandardCopyOption.REPLACE_EXISTING), mover.attempts.get(1));
		assertEquals(Arrays.asList(StandardCopyOption.REPLACE_EXISTING), mover.attempts.get(3));

		Path target = directory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME);
		assertArrayEquals(second.toJsonBytes(), Files.readAllBytes(target));
		assertEquals(0, temporaryFiles().size());
	}

	@Test
	public void refusesASnapshotLargerThanTheCeilingAndLeavesTheTargetUntouched() throws IOException
	{
		TelemetrySnapshotWriter writer = new TelemetrySnapshotWriter(directory);
		TelemetrySnapshot good = snapshot(0L, "LOGGED_IN");
		writer.write(good, new ReentrantLock(), ALWAYS);

		StringBuilder oversized = new StringBuilder();
		for (int i = 0; i < TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES + 1; i++)
		{
			oversized.append('A');
		}

		try
		{
			writer.write(snapshot(1L, oversized.toString()), new ReentrantLock(), ALWAYS);
			fail("expected an oversized snapshot to be refused");
		}
		catch (TelemetrySnapshotWriter.SnapshotTooLargeException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("16384"));
		}

		Path target = directory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME);
		assertArrayEquals("the previous good document must remain", good.toJsonBytes(),
			Files.readAllBytes(target));
		assertEquals(0, temporaryFiles().size());
	}

	@Test
	public void aSnapshotExactlyAtTheCeilingIsAccepted() throws IOException
	{
		TelemetrySnapshotWriter writer = new TelemetrySnapshotWriter(directory);
		TelemetrySnapshot probe = snapshot(0L, "");
		int overhead = probe.toJsonBytes().length;

		StringBuilder padding = new StringBuilder();
		for (int i = 0; i < TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES - overhead; i++)
		{
			padding.append('A');
		}

		TelemetrySnapshot exact = snapshot(0L, padding.toString());
		assertEquals(TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES, exact.toJsonBytes().length);
		assertEquals(TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES, writer.write(exact, new ReentrantLock(), ALWAYS));
	}

	@Test
	public void deletesItsTemporaryFileWhenTheReplacementFails() throws IOException
	{
		RecordingMover mover = new RecordingMover();
		mover.failWith = new IOException("simulated replacement failure");
		TelemetrySnapshotWriter writer = new TelemetrySnapshotWriter(directory, mover, () -> NOW);

		try
		{
			writer.write(snapshot(0L, "LOGGED_IN"), new ReentrantLock(), ALWAYS);
			fail("expected the replacement failure to propagate");
		}
		catch (IOException expected)
		{
			assertEquals("simulated replacement failure", expected.getMessage());
		}

		assertEquals("a failed write must not leave debris behind", 0, temporaryFiles().size());
		assertFalse("a failed write must not create a partial target",
			Files.exists(directory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME)));
	}

	@Test
	public void sweepsAbandonedTemporaryFilesButLeavesRecentAndUnrelatedOnesAlone() throws IOException
	{
		Files.createDirectories(directory);
		Path abandoned = directory.resolve("state-v1-9876543210.tmp");
		Path recent = directory.resolve("state-v1-1234567890.tmp");
		Path unrelated = directory.resolve("notes.txt");
		Files.write(abandoned, "stale".getBytes(StandardCharsets.UTF_8));
		Files.write(recent, "in flight".getBytes(StandardCharsets.UTF_8));
		Files.write(unrelated, "not ours".getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(abandoned, FileTime.fromMillis(NOW - 600_000L));
		Files.setLastModifiedTime(recent, FileTime.fromMillis(NOW - 1_000L));

		TelemetrySnapshotWriter writer =
			new TelemetrySnapshotWriter(directory, Files::move, () -> NOW);
		writer.write(snapshot(0L, "LOGGED_IN"), new ReentrantLock(), ALWAYS);

		assertFalse("an abandoned temporary file should be removed", Files.exists(abandoned));
		assertTrue("another client's in-flight file must be left alone", Files.exists(recent));
		assertTrue("a file this writer does not own must be left alone", Files.exists(unrelated));
		assertTrue(Files.exists(directory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME)));
	}
	@Test
	public void losingAuthorizationAfterStagingLeavesTheTargetUntouched() throws IOException
	{
		TelemetrySnapshotWriter writer = new TelemetrySnapshotWriter(directory);
		TelemetrySnapshot good = snapshot(0L, "LOGGED_IN");
		writer.write(good, new ReentrantLock(), ALWAYS);

		// A newer run took over while this publication was staging.
		TelemetrySnapshot superseded = snapshot(1L, "LOGIN_SCREEN");
		try
		{
			writer.write(superseded, new ReentrantLock(), () -> false);
			fail("expected an unauthorized commit to be refused");
		}
		catch (TelemetrySnapshotWriter.CommitNotAuthorizedException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("state-v1.json"));
		}

		Path target = directory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME);
		assertArrayEquals("the earlier document must survive untouched",
			good.toJsonBytes(), Files.readAllBytes(target));
		assertEquals("the staged file must be cleaned up", 0, temporaryFiles().size());
	}

	@Test
	public void authorizationIsCheckedOnlyAfterTheFileIsFullyStaged() throws IOException
	{
		// Proves the check is at the commit boundary rather than at the start: by the time it
		// is consulted, a complete temporary sibling already exists in the directory.
		TelemetrySnapshotWriter writer = new TelemetrySnapshotWriter(directory);
		List<Integer> stagedWhenAsked = new ArrayList<>();

		try
		{
			writer.write(snapshot(0L, "LOGGED_IN"), new ReentrantLock(), () ->
			{
				try
				{
					stagedWhenAsked.add(temporaryFiles().size());
				}
				catch (IOException e)
				{
					throw new IllegalStateException(e);
				}
				return false;
			});
			fail("expected refusal");
		}
		catch (TelemetrySnapshotWriter.CommitNotAuthorizedException expected)
		{
			// expected
		}

		assertEquals(Arrays.asList(1), stagedWhenAsked);
		assertFalse("nothing may reach the target",
			Files.exists(directory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME)));
		assertEquals(0, temporaryFiles().size());
	}

	@Test
	public void anAuthorizedCommitStillReplacesTheTarget() throws IOException
	{
		TelemetrySnapshotWriter writer = new TelemetrySnapshotWriter(directory);
		writer.write(snapshot(0L, "LOGGED_IN"), new ReentrantLock(), ALWAYS);

		TelemetrySnapshot inactive = snapshot(1L, "LOGIN_SCREEN");
		assertEquals(inactive.toJsonBytes().length, writer.write(inactive, new ReentrantLock(), () -> true));
		assertArrayEquals(inactive.toJsonBytes(),
			Files.readAllBytes(directory.resolve(TelemetrySnapshotWriter.TARGET_FILE_NAME)));
	}

}
