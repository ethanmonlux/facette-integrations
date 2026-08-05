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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One plugin run: the telemetry state and writer it publishes through, and whether it is
 * still the run the plugin is on.
 *
 * <p>RuneLite reuses a plugin instance across disable and enable, so a scheduled task from a
 * disabled run can still be executing — or waiting on the publication lock — while the next
 * run starts. Each start creates a new context and each scheduled task is bound to the
 * context that created it, which gives two independent guarantees:
 *
 * <ul>
 *   <li><b>Retirement is per-run and permanent.</b> {@link #retire()} affects only this
 *       context. Starting a later run creates a different object and cannot revive this one,
 *       so there is no shared flag whose clearing could let retired work back in.</li>
 *   <li><b>The state and writer are reachable only through the run that owns them.</b> Work
 *       bound to a retired context has no path to a later run's {@link TelemetryState} or
 *       {@link TelemetrySnapshotWriter} at all, so it cannot consume that run's sequence,
 *       clear its dirty flag, disturb its heartbeat, or write through its writer — whatever
 *       the interleaving. That is structural, not a check that has to fire in time.</li>
 * </ul>
 *
 * <p>Holds no RuneLite types, so the lifecycle rule is exercisable without a game client.
 * This is deliberately not a scheduler, an executor, or a general-purpose guard: it carries
 * one run's identity, its two collaborators, and whether it is current.
 */
final class PublisherRunContext
{
	private final TelemetryState state;
	private final TelemetrySnapshotWriter writer;

	/** False once this run has been retired. Never returns to true. */
	private final AtomicBoolean current = new AtomicBoolean(true);

	PublisherRunContext(TelemetryState state, TelemetrySnapshotWriter writer)
	{
		this.state = Objects.requireNonNull(state, "state");
		this.writer = Objects.requireNonNull(writer, "writer");
	}

	TelemetryState getState()
	{
		return state;
	}

	TelemetrySnapshotWriter getWriter()
	{
		return writer;
	}

	/**
	 * Whether work bound to this run may still act.
	 *
	 * <p>Checked before contending for the publication lock, again after acquiring it, and
	 * before any write or bookkeeping — the window this closes is precisely the one where a
	 * task waits on the lock while its run is disabled and the next run starts.
	 */
	boolean isCurrent()
	{
		return current.get();
	}

	/**
	 * Retires this run. Idempotent, one-way, and scoped to this context alone.
	 *
	 * @return true if this call retired the run, false if it was already retired
	 */
	boolean retire()
	{
		return current.compareAndSet(true, false);
	}
}
