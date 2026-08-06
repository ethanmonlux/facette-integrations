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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * One immutable schema-2 telemetry snapshot and its canonical JSON form.
 *
 * <p>This type deliberately holds no RuneLite types: it is a plain value object so the
 * exported contract can be exercised without a game client. The JSON is written by hand
 * rather than reflected out of this object, both to keep key order and key set fixed and
 * because reflective serialization is not permitted in a Plugin Hub plugin.
 *
 * <p>The schema is closed. Every field named here is authorized; nothing else is exported.
 * In particular there is no account identity, credential, chat, social, wealth, bank, price,
 * Grand Exchange, quest, or location data, no player target and no other player's name, no
 * total or historical account experience, no file path or network address, and no field that
 * could carry one.
 *
 * <p>Every collection is fixed-size or enum-bounded: eleven equipment slots, twenty-eight
 * inventory slots, at most one entry per prayer, and at most one entry per real skill. Nothing
 * here grows with how long the player plays, which is what keeps the serialized document below
 * {@link TelemetrySnapshotWriter#MAX_SNAPSHOT_BYTES} for every reachable state.
 */
final class TelemetrySnapshot
{
	/** Schema version of the exported contract. */
	static final int SCHEMA = 2;

	/** Producer of the snapshot. */
	static final String SOURCE = "runelite";

	/**
	 * The eleven visible equipment slots, in exported order.
	 *
	 * <p>These names are the contract, and the order is part of it. The plugin maps RuneLite's
	 * own equipment slots onto this list positionally, and a test pins that the two agree, so
	 * the exported name for a position cannot drift away from the client slot it was read from.
	 * The three RuneLite equipment slots that hold no item — the player model's arms, hair, and
	 * jaw — are deliberately absent.
	 */
	static final List<String> EQUIPMENT_SLOTS = Collections.unmodifiableList(Arrays.asList(
		"head", "cape", "amulet", "weapon", "body", "shield", "legs", "gloves", "boots",
		"ring", "ammo"));

	/** Old School inventory capacity, in slots. */
	static final int INVENTORY_SLOTS = 28;

	/** Bound on the exported instance identity, which is always a canonical UUID. */
	static final int MAX_INSTANCE_ID_CHARS = 36;

	/** Bound on the exported game-state name. */
	static final int MAX_GAME_STATE_CHARS = 32;

	/** Bound on the exported attack-style label. */
	static final int MAX_ATTACK_STYLE_CHARS = 32;

	/** Bound on an exported prayer name. */
	static final int MAX_PRAYER_CHARS = 32;

	/** Bound on an exported skill name. */
	static final int MAX_SKILL_CHARS = 24;

	/** Bound on an exported item name. */
	static final int MAX_ITEM_NAME_CHARS = 48;

	/** Bound on an exported NPC name. */
	static final int MAX_NPC_NAME_CHARS = 48;

	private final String instanceId;
	private final long seq;
	private final long emittedAt;

	private final boolean pluginActive;
	private final String gameState;
	private final boolean loggedIn;
	private final Integer world;
	private final Integer combatLevel;
	private final Long trackingStartedAt;

	private final Integer hitpointsCurrent;
	private final Integer hitpointsBase;
	private final Integer prayerCurrent;
	private final Integer prayerBase;
	private final Integer runEnergyPercent;
	private final Integer specialAttackPercent;
	private final Integer weightKg;

	private final String attackStyle;
	private final List<String> activePrayers;
	private final TelemetryTarget target;

	private final List<TelemetryItemSlot> equipmentSlots;

	private final Integer usedSlots;
	private final Integer freeSlots;
	private final List<TelemetryItemSlot> inventorySlots;

	private final String lastSkill;
	private final Integer lastDelta;
	private final Long lastChangedAt;
	private final List<TelemetrySkillGain> skillGains;

	private TelemetrySnapshot(Builder b)
	{
		this.instanceId = Objects.requireNonNull(b.instanceId, "instanceId");
		this.seq = b.seq;
		this.emittedAt = b.emittedAt;
		this.pluginActive = b.pluginActive;
		this.gameState = Objects.requireNonNull(b.gameState, "gameState");
		this.loggedIn = b.loggedIn;
		this.world = b.world;
		this.combatLevel = b.combatLevel;
		this.hitpointsCurrent = b.hitpointsCurrent;
		this.hitpointsBase = b.hitpointsBase;
		this.prayerCurrent = b.prayerCurrent;
		this.prayerBase = b.prayerBase;
		this.runEnergyPercent = b.runEnergyPercent;
		this.specialAttackPercent = b.specialAttackPercent;
		this.weightKg = b.weightKg;
		this.attackStyle = b.attackStyle;
		this.target = b.target;
		this.usedSlots = b.usedSlots;
		this.freeSlots = b.freeSlots;
		this.lastSkill = b.lastSkill;
		this.lastDelta = b.lastDelta;

		if (seq < 0)
		{
			throw new IllegalArgumentException("seq must be non-negative");
		}
		if (emittedAt < 0)
		{
			throw new IllegalArgumentException("emittedAt must be non-negative");
		}

		// A timestamp later than the moment the document was emitted describes the future. It is
		// not reachable from a clock that only moves forward, but a backward wall-clock
		// adjustment between an event and this build would produce one, so it is pinned here
		// rather than left to whichever caller happened to read the clock first.
		this.trackingStartedAt = atMost(b.trackingStartedAt, emittedAt);
		this.lastChangedAt = atMost(b.lastChangedAt, emittedAt);

		// Deduplicated by construction rather than by trusting the caller, so no arrangement of
		// readings can put one prayer in the document twice. Insertion order — which is the
		// caller's enum order — is preserved.
		this.activePrayers = b.activePrayers == null
			? null
			: Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(b.activePrayers)));

		this.equipmentSlots = copyFixedSlots(b.equipmentSlots, EQUIPMENT_SLOTS.size(), "equipment");
		this.inventorySlots = copyFixedSlots(b.inventorySlots, INVENTORY_SLOTS, "inventory");
		this.skillGains = copySkillGains(b.skillGains, emittedAt);

		if (usedSlots != null || freeSlots != null)
		{
			if (usedSlots == null || freeSlots == null || usedSlots + freeSlots != INVENTORY_SLOTS)
			{
				throw new IllegalArgumentException(
					"used and free inventory slots must be reported together and sum to "
						+ INVENTORY_SLOTS);
			}
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
	 * Every key is written literally exactly once, so a duplicate key is impossible by
	 * construction rather than by validation.
	 */
	String toJson()
	{
		StringBuilder sb = new StringBuilder(4_096);
		sb.append('{');
		appendKey(sb, "schema").append(SCHEMA).append(',');
		appendKey(sb, "source");
		appendString(sb, SOURCE, SOURCE.length()).append(',');
		appendKey(sb, "instanceId");
		appendString(sb, instanceId, MAX_INSTANCE_ID_CHARS).append(',');
		appendKey(sb, "seq").append(seq).append(',');
		appendKey(sb, "emittedAt").append(emittedAt).append(',');

		appendKey(sb, "session").append('{');
		appendKey(sb, "pluginActive").append(pluginActive).append(',');
		appendKey(sb, "gameState");
		appendString(sb, gameState, MAX_GAME_STATE_CHARS).append(',');
		appendKey(sb, "loggedIn").append(loggedIn).append(',');
		appendKey(sb, "world");
		appendNumber(sb, world).append(',');
		appendKey(sb, "combatLevel");
		appendNumber(sb, combatLevel).append(',');
		appendKey(sb, "trackingStartedAt");
		appendNumber(sb, trackingStartedAt);
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
		appendNumber(sb, runEnergyPercent).append(',');
		appendKey(sb, "specialAttackPercent");
		appendNumber(sb, specialAttackPercent).append(',');
		appendKey(sb, "weightKg");
		appendNumber(sb, weightKg);
		sb.append("},");

		appendKey(sb, "combat").append('{');
		appendKey(sb, "attackStyle");
		appendNullableString(sb, attackStyle, MAX_ATTACK_STYLE_CHARS).append(',');
		appendKey(sb, "activePrayers");
		appendPrayers(sb).append(',');
		appendKey(sb, "target");
		appendTarget(sb);
		sb.append("},");

		appendKey(sb, "equipment").append('{');
		appendKey(sb, "slots");
		appendEquipmentSlots(sb);
		sb.append("},");

		appendKey(sb, "inventory").append('{');
		appendKey(sb, "usedSlots");
		appendNumber(sb, usedSlots).append(',');
		appendKey(sb, "freeSlots");
		appendNumber(sb, freeSlots).append(',');
		appendKey(sb, "slots");
		appendInventorySlots(sb);
		sb.append("},");

		appendKey(sb, "xp").append('{');
		appendKey(sb, "lastSkill");
		appendNullableString(sb, lastSkill, MAX_SKILL_CHARS).append(',');
		appendKey(sb, "lastDelta");
		appendNumber(sb, lastDelta).append(',');
		appendKey(sb, "lastChangedAt");
		appendNumber(sb, lastChangedAt).append(',');
		appendKey(sb, "skills");
		appendSkillGains(sb);
		sb.append('}');

		sb.append('}');
		return sb.toString();
	}

	/** Serializes this snapshot to the exact UTF-8 bytes written to disk. */
	byte[] toJsonBytes()
	{
		return toJson().getBytes(StandardCharsets.UTF_8);
	}

	private StringBuilder appendPrayers(StringBuilder sb)
	{
		if (activePrayers == null)
		{
			return sb.append("null");
		}
		sb.append('[');
		for (int i = 0; i < activePrayers.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			appendString(sb, activePrayers.get(i), MAX_PRAYER_CHARS);
		}
		return sb.append(']');
	}

	private StringBuilder appendTarget(StringBuilder sb)
	{
		if (target == null)
		{
			return sb.append("null");
		}
		sb.append('{');
		appendKey(sb, "kind");
		appendString(sb, TelemetryTarget.KIND, TelemetryTarget.KIND.length()).append(',');
		appendKey(sb, "id").append(target.getId()).append(',');
		appendKey(sb, "name");
		appendNullableString(sb, target.getName(), MAX_NPC_NAME_CHARS).append(',');
		appendKey(sb, "combatLevel");
		appendNumber(sb, target.getCombatLevel()).append(',');
		appendKey(sb, "healthRatio");
		appendNumber(sb, target.getHealthRatio()).append(',');
		appendKey(sb, "healthScale");
		appendNumber(sb, target.getHealthScale()).append(',');
		appendKey(sb, "dead").append(target.isDead());
		return sb.append('}');
	}

	private StringBuilder appendEquipmentSlots(StringBuilder sb)
	{
		if (equipmentSlots == null)
		{
			return sb.append("null");
		}
		sb.append('[');
		for (int i = 0; i < equipmentSlots.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append('{');
			appendKey(sb, "slot");
			String name = EQUIPMENT_SLOTS.get(i);
			appendString(sb, name, name.length()).append(',');
			appendItemBody(sb, equipmentSlots.get(i));
			sb.append('}');
		}
		return sb.append(']');
	}

	private StringBuilder appendInventorySlots(StringBuilder sb)
	{
		if (inventorySlots == null)
		{
			return sb.append("null");
		}
		sb.append('[');
		for (int i = 0; i < inventorySlots.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append('{');
			// The position is written from the loop, not from the entry, so an inventory slot
			// number can never disagree with where the entry sits in the array.
			appendKey(sb, "slot").append(i).append(',');
			appendItemBody(sb, inventorySlots.get(i));
			sb.append('}');
		}
		return sb.append(']');
	}

	private static StringBuilder appendItemBody(StringBuilder sb, TelemetryItemSlot slot)
	{
		appendKey(sb, "itemId");
		appendNumber(sb, slot.getItemId()).append(',');
		appendKey(sb, "quantity");
		appendNumber(sb, slot.getQuantity()).append(',');
		appendKey(sb, "name");
		return appendNullableString(sb, slot.getName(), MAX_ITEM_NAME_CHARS);
	}

	private StringBuilder appendSkillGains(StringBuilder sb)
	{
		if (skillGains == null)
		{
			return sb.append("null");
		}
		sb.append('[');
		for (int i = 0; i < skillGains.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			TelemetrySkillGain gain = skillGains.get(i);
			sb.append('{');
			appendKey(sb, "skill");
			appendString(sb, gain.getSkill(), MAX_SKILL_CHARS).append(',');
			appendKey(sb, "gained").append(gain.getGained()).append(',');
			appendKey(sb, "lastDelta").append(gain.getLastDelta()).append(',');
			appendKey(sb, "lastChangedAt").append(gain.getLastChangedAt());
			sb.append('}');
		}
		return sb.append(']');
	}

	private static StringBuilder appendKey(StringBuilder sb, String key)
	{
		appendString(sb, key, key.length());
		return sb.append(':');
	}

	private static StringBuilder appendNumber(StringBuilder sb, Number value)
	{
		return value == null ? sb.append("null") : sb.append(value.longValue());
	}

	private static StringBuilder appendNullableString(StringBuilder sb, String value, int maxChars)
	{
		return value == null ? sb.append("null") : appendString(sb, value, maxChars);
	}

	/**
	 * Appends a JSON string literal, bounded to {@code maxChars} characters and escaping every
	 * character JSON requires plus all remaining control characters.
	 *
	 * <p>Bounding lives here, in the one place every exported string passes through, rather
	 * than at each of the places a string enters the snapshot. That is what makes the document
	 * size provably bounded: no caller can opt out of it by forgetting to truncate.
	 *
	 * <p>A truncation that would land between the two halves of a surrogate pair steps back one
	 * character instead, so the result is never a lone surrogate.
	 */
	private static StringBuilder appendString(StringBuilder sb, String value, int maxChars)
	{
		int length = value.length();
		if (length > maxChars)
		{
			length = maxChars;
			if (length > 0 && Character.isHighSurrogate(value.charAt(length - 1)))
			{
				length--;
			}
		}
		sb.append('"');
		for (int i = 0; i < length; i++)
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

	private static Long atMost(Long value, long ceiling)
	{
		return value == null ? null : Long.valueOf(Math.min(value, ceiling));
	}

	/**
	 * Copies a fixed-size slot collection, refusing any other size.
	 *
	 * <p>The size is the contract, so a collection of the wrong length is a programming error
	 * rather than something to pad or truncate into shape: padding would claim empty slots the
	 * client never reported, and truncating would hide items.
	 */
	private static List<TelemetryItemSlot> copyFixedSlots(List<TelemetryItemSlot> slots,
		int expectedSize, String what)
	{
		if (slots == null)
		{
			return null;
		}
		if (slots.size() != expectedSize)
		{
			throw new IllegalArgumentException(
				what + " must have exactly " + expectedSize + " slots, got " + slots.size());
		}
		List<TelemetryItemSlot> copy = new ArrayList<>(expectedSize);
		for (TelemetryItemSlot slot : slots)
		{
			copy.add(Objects.requireNonNull(slot, "slot"));
		}
		return Collections.unmodifiableList(copy);
	}

	private static List<TelemetrySkillGain> copySkillGains(List<TelemetrySkillGain> gains,
		long emittedAt)
	{
		if (gains == null)
		{
			return null;
		}
		List<TelemetrySkillGain> copy = new ArrayList<>(gains.size());
		for (TelemetrySkillGain gain : gains)
		{
			Objects.requireNonNull(gain, "gain");
			copy.add(gain.getLastChangedAt() <= emittedAt
				? gain
				: new TelemetrySkillGain(gain.getSkill(), gain.getGained(), gain.getLastDelta(),
					emittedAt));
		}
		return Collections.unmodifiableList(copy);
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
		private Integer combatLevel;
		private Long trackingStartedAt;
		private Integer hitpointsCurrent;
		private Integer hitpointsBase;
		private Integer prayerCurrent;
		private Integer prayerBase;
		private Integer runEnergyPercent;
		private Integer specialAttackPercent;
		private Integer weightKg;
		private String attackStyle;
		private List<String> activePrayers;
		private TelemetryTarget target;
		private List<TelemetryItemSlot> equipmentSlots;
		private Integer usedSlots;
		private Integer freeSlots;
		private List<TelemetryItemSlot> inventorySlots;
		private String lastSkill;
		private Integer lastDelta;
		private Long lastChangedAt;
		private List<TelemetrySkillGain> skillGains;

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

		Builder session(Integer world, Integer combatLevel, Long trackingStartedAt)
		{
			this.world = world;
			this.combatLevel = combatLevel;
			this.trackingStartedAt = trackingStartedAt;
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

		Builder specialAttackPercent(Integer v)
		{
			this.specialAttackPercent = v;
			return this;
		}

		Builder weightKg(Integer v)
		{
			this.weightKg = v;
			return this;
		}

		Builder combat(String attackStyle, List<String> activePrayers, TelemetryTarget target)
		{
			this.attackStyle = attackStyle;
			this.activePrayers = activePrayers;
			this.target = target;
			return this;
		}

		Builder equipmentSlots(List<TelemetryItemSlot> v)
		{
			this.equipmentSlots = v;
			return this;
		}

		Builder inventory(Integer used, Integer free, List<TelemetryItemSlot> slots)
		{
			this.usedSlots = used;
			this.freeSlots = free;
			this.inventorySlots = slots;
			return this;
		}

		Builder xp(String skill, Integer delta, Long changedAt)
		{
			this.lastSkill = skill;
			this.lastDelta = delta;
			this.lastChangedAt = changedAt;
			return this;
		}

		Builder xpSkills(List<TelemetrySkillGain> v)
		{
			this.skillGains = v;
			return this;
		}

		TelemetrySnapshot build()
		{
			return new TelemetrySnapshot(this);
		}
	}
}
