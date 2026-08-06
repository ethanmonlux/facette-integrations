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

/**
 * The NPC the local player is currently interacting with, reduced to what an observer of the
 * game world can already see.
 *
 * <p>Only an NPC can ever be represented. {@link #KIND} is a constant for that reason: it is
 * not a discriminator between several possible target kinds but a statement that there is
 * exactly one permitted kind, so a player target has no representation here to be reached even
 * by mistake. No other player's name, identity, or state passes through this type.
 *
 * <p>Health is the ratio and scale the server actually transmits, and nothing more. The server
 * does not send an NPC's real hitpoints, so none is estimated, interpolated, or reconstructed
 * from a maximum. The two health values are populated together or not at all, because a ratio
 * without its scale means nothing and a scale without a ratio says nothing.
 *
 * <p>Holds no RuneLite types, so every rule here is exercisable without a game client.
 */
final class TelemetryTarget
{
	/** The only permitted target kind. */
	static final String KIND = "npc";

	/**
	 * The name the game cache reports for an actor it has no name for, treated as no name at
	 * all rather than exported as the four characters {@code null} inside a JSON string.
	 */
	private static final String ABSENT_NAME = "null";

	private final int id;
	private final String name;
	private final Integer combatLevel;
	private final Integer healthRatio;
	private final Integer healthScale;
	private final boolean dead;

	private TelemetryTarget(int id, String name, Integer combatLevel, Integer healthRatio,
		Integer healthScale, boolean dead)
	{
		this.id = id;
		this.name = name;
		this.combatLevel = combatLevel;
		this.healthRatio = healthRatio;
		this.healthScale = healthScale;
		this.dead = dead;
	}

	/**
	 * The interacted-with NPC as read from the client.
	 *
	 * @param id          the NPC identity; a negative value describes no NPC
	 * @param name        the NPC's current display name, or null when it has none
	 * @param combatLevel the NPC's combat level; a non-positive level means the NPC has none
	 * @param healthRatio the transmitted health, in {@code healthScale} units, or a negative
	 *                    value when the server sends none for this actor
	 * @param healthScale the maximum {@code healthRatio} can take, or a non-positive value when
	 *                    the server sends none
	 * @param dead        the observable dead state of the actor
	 * @return the target, or null when the reading does not describe an NPC at all
	 */
	static TelemetryTarget npc(int id, String name, int combatLevel, int healthRatio,
		int healthScale, boolean dead)
	{
		if (id < 0)
		{
			return null;
		}
		// Both or neither. A scale of zero or less is no scale, a negative ratio is the client's
		// own "not transmitted" signal, and a ratio above its scale is not a proportion — none
		// of the three can be turned into an honest pair, so all three yield no health at all.
		boolean healthReported = healthScale > 0 && healthRatio >= 0 && healthRatio <= healthScale;
		return new TelemetryTarget(
			id,
			normalizeName(name),
			combatLevel > 0 ? Integer.valueOf(combatLevel) : null,
			healthReported ? Integer.valueOf(healthRatio) : null,
			healthReported ? Integer.valueOf(healthScale) : null,
			dead);
	}

	int getId()
	{
		return id;
	}

	String getName()
	{
		return name;
	}

	Integer getCombatLevel()
	{
		return combatLevel;
	}

	Integer getHealthRatio()
	{
		return healthRatio;
	}

	Integer getHealthScale()
	{
		return healthScale;
	}

	boolean isDead()
	{
		return dead;
	}

	private static String normalizeName(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty() || ABSENT_NAME.equals(trimmed))
		{
			return null;
		}
		return trimmed;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof TelemetryTarget))
		{
			return false;
		}
		TelemetryTarget that = (TelemetryTarget) other;
		return id == that.id
			&& dead == that.dead
			&& Objects.equals(name, that.name)
			&& Objects.equals(combatLevel, that.combatLevel)
			&& Objects.equals(healthRatio, that.healthRatio)
			&& Objects.equals(healthScale, that.healthScale);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(id, name, combatLevel, healthRatio, healthScale, dead);
	}
}
