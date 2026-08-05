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

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One immutable schema-1 telemetry snapshot and its canonical JSON form.
 *
 * <p>This type deliberately holds no RuneLite types: it is a plain value object so the
 * exported contract can be exercised without a game client. The JSON is written by hand
 * rather than reflected out of this object, both to keep key order and key set fixed and
 * because reflective serialization is not permitted in a Plugin Hub plugin.
 *
 * <p>The schema is closed. Every field named here is authorized; nothing else is exported.
 * In particular there is no account identity, credential, chat, social, wealth, bank, or
 * location data, and there is no field that could carry one.
 */
final class TelemetrySnapshot
{
	/** Schema version of the exported contract. */
	static final int SCHEMA = 1;

	/** Producer of the snapshot. */
	static final String SOURCE = "runelite";

	private final String instanceId;
	private final long seq;
	private final long emittedAt;

	private final boolean pluginActive;
	private final String gameState;
	private final boolean loggedIn;
	private final Integer world;

	private final Integer hitpointsCurrent;
	private final Integer hitpointsBase;
	private final Integer prayerCurrent;
	private final Integer prayerBase;
	private final Integer runEnergyPercent;

	private final Integer usedSlots;
	private final Integer freeSlots;

	private final String lastSkill;
	private final Integer lastDelta;
	private final Long lastChangedAt;

	private TelemetrySnapshot(Builder b)
	{
		this.instanceId = Objects.requireNonNull(b.instanceId, "instanceId");
		this.seq = b.seq;
		this.emittedAt = b.emittedAt;
		this.pluginActive = b.pluginActive;
		this.gameState = Objects.requireNonNull(b.gameState, "gameState");
		this.loggedIn = b.loggedIn;
		this.world = b.world;
		this.hitpointsCurrent = b.hitpointsCurrent;
		this.hitpointsBase = b.hitpointsBase;
		this.prayerCurrent = b.prayerCurrent;
		this.prayerBase = b.prayerBase;
		this.runEnergyPercent = b.runEnergyPercent;
		this.usedSlots = b.usedSlots;
		this.freeSlots = b.freeSlots;
		this.lastSkill = b.lastSkill;
		this.lastDelta = b.lastDelta;
		this.lastChangedAt = b.lastChangedAt;

		if (seq < 0)
		{
			throw new IllegalArgumentException("seq must be non-negative");
		}
	}

	static Builder builder()
	{
		return new Builder();
	}

	String getInstanceId()
	{
		return instanceId;
	}

	long getSeq()
	{
		return seq;
	}

	long getEmittedAt()
	{
		return emittedAt;
	}

	boolean isPluginActive()
	{
		return pluginActive;
	}

	boolean isLoggedIn()
	{
		return loggedIn;
	}

	/**
	 * Serializes this snapshot to its canonical compact JSON form.
	 *
	 * <p>Key order and key set are fixed by this method and are the contract Facette reads.
	 */
	String toJson()
	{
		StringBuilder sb = new StringBuilder(512);
		sb.append('{');
		appendKey(sb, "schema").append(SCHEMA).append(',');
		appendKey(sb, "source");
		appendString(sb, SOURCE).append(',');
		appendKey(sb, "instanceId");
		appendString(sb, instanceId).append(',');
		appendKey(sb, "seq").append(seq).append(',');
		appendKey(sb, "emittedAt").append(emittedAt).append(',');

		appendKey(sb, "session").append('{');
		appendKey(sb, "pluginActive").append(pluginActive).append(',');
		appendKey(sb, "gameState");
		appendString(sb, gameState).append(',');
		appendKey(sb, "loggedIn").append(loggedIn).append(',');
		appendKey(sb, "world");
		appendNumber(sb, world);
		sb.append("},");

		appendKey(sb, "vitals").append('{');
		appendKey(sb, "hitpointsCurrent");
		appendNumber(sb, hitpointsCurrent).append(',');
		appendKey(sb, "hitpointsBase");
		appendNumber(sb, hitpointsBase).append(',');
		appendKey(sb, "prayerCurrent");
		appendNumber(sb, prayerCurrent).append(',');
		appendKey(sb, "prayerBase");
		appendNumber(sb, prayerBase).append(',');
		appendKey(sb, "runEnergyPercent");
		appendNumber(sb, runEnergyPercent);
		sb.append("},");

		appendKey(sb, "inventory").append('{');
		appendKey(sb, "usedSlots");
		appendNumber(sb, usedSlots).append(',');
		appendKey(sb, "freeSlots");
		appendNumber(sb, freeSlots);
		sb.append("},");

		appendKey(sb, "xp").append('{');
		appendKey(sb, "lastSkill");
		appendNullableString(sb, lastSkill).append(',');
		appendKey(sb, "lastDelta");
		appendNumber(sb, lastDelta).append(',');
		appendKey(sb, "lastChangedAt");
		appendNumber(sb, lastChangedAt);
		sb.append('}');

		sb.append('}');
		return sb.toString();
	}

	/** Serializes this snapshot to the exact UTF-8 bytes written to disk. */
	byte[] toJsonBytes()
	{
		return toJson().getBytes(StandardCharsets.UTF_8);
	}

	private static StringBuilder appendKey(StringBuilder sb, String key)
	{
		appendString(sb, key);
		return sb.append(':');
	}

	private static StringBuilder appendNumber(StringBuilder sb, Number value)
	{
		return value == null ? sb.append("null") : sb.append(value.longValue());
	}

	private static StringBuilder appendNullableString(StringBuilder sb, String value)
	{
		return value == null ? sb.append("null") : appendString(sb, value);
	}

	/**
	 * Appends a JSON string literal, escaping every character JSON requires plus all
	 * remaining control characters.
	 */
	private static StringBuilder appendString(StringBuilder sb, String value)
	{
		sb.append('"');
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			switch (c)
			{
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\b':
					sb.append("\\b");
					break;
				case '\f':
					sb.append("\\f");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20)
					{
						sb.append(String.format("\\u%04x", (int) c));
					}
					else
					{
						sb.append(c);
					}
					break;
			}
		}
		return sb.append('"');
	}

	static final class Builder
	{
		private String instanceId;
		private long seq;
		private long emittedAt;
		private boolean pluginActive;
		private String gameState;
		private boolean loggedIn;
		private Integer world;
		private Integer hitpointsCurrent;
		private Integer hitpointsBase;
		private Integer prayerCurrent;
		private Integer prayerBase;
		private Integer runEnergyPercent;
		private Integer usedSlots;
		private Integer freeSlots;
		private String lastSkill;
		private Integer lastDelta;
		private Long lastChangedAt;

		private Builder()
		{
		}

		Builder instanceId(String v)
		{
			this.instanceId = v;
			return this;
		}

		Builder seq(long v)
		{
			this.seq = v;
			return this;
		}

		Builder emittedAt(long v)
		{
			this.emittedAt = v;
			return this;
		}

		Builder pluginActive(boolean v)
		{
			this.pluginActive = v;
			return this;
		}

		Builder gameState(String v)
		{
			this.gameState = v;
			return this;
		}

		Builder loggedIn(boolean v)
		{
			this.loggedIn = v;
			return this;
		}

		Builder world(Integer v)
		{
			this.world = v;
			return this;
		}

		Builder hitpoints(Integer current, Integer base)
		{
			this.hitpointsCurrent = current;
			this.hitpointsBase = base;
			return this;
		}

		Builder prayer(Integer current, Integer base)
		{
			this.prayerCurrent = current;
			this.prayerBase = base;
			return this;
		}

		Builder runEnergyPercent(Integer v)
		{
			this.runEnergyPercent = v;
			return this;
		}

		Builder inventory(Integer used, Integer free)
		{
			this.usedSlots = used;
			this.freeSlots = free;
			return this;
		}

		Builder xp(String skill, Integer delta, Long changedAt)
		{
			this.lastSkill = skill;
			this.lastDelta = delta;
			this.lastChangedAt = changedAt;
			return this;
		}

		TelemetrySnapshot build()
		{
			return new TelemetrySnapshot(this);
		}
	}
}
