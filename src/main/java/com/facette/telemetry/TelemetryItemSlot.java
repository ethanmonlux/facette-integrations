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
 * What one fixed item slot currently holds — an item identity, a quantity, and a name, or
 * nothing at all.
 *
 * <p>The slot's own position is deliberately <em>not</em> stored here. An equipment entry is
 * identified by a canonical slot name and an inventory entry by its array position, and both
 * are fixed by the containing collection rather than carried per entry, so no entry can ever
 * disagree with where it sits. That is what makes "slot equals the entry's position" true by
 * construction instead of by assertion.
 *
 * <p>Two states, and no third: empty, where all three exported values are null, or occupied,
 * where the identity and quantity are both positive. {@link #of(int, int, String)} is the only
 * way to reach the occupied state and it refuses any reading that would sit between the two.
 *
 * <p>Nothing here is priced, valued, or aggregated. No price, tradeability, examine text,
 * Grand Exchange data, bank ownership, wealth total, sprite, or artwork is held or derivable.
 *
 * <p>Holds no RuneLite types, so every rule here is exercisable without a game client.
 */
final class TelemetryItemSlot
{
	/**
	 * The empty slot. Shared rather than allocated per empty position: it carries no state, and
	 * a logged-in player commonly has dozens of them.
	 */
	static final TelemetryItemSlot EMPTY = new TelemetryItemSlot(null, null, null);

	/**
	 * The name the game cache reports for an item it has no name for. Treated as no name at
	 * all rather than exported as the four characters {@code null} inside a JSON string.
	 */
	private static final String ABSENT_NAME = "null";

	private final Integer itemId;
	private final Integer quantity;
	private final String name;

	private TelemetryItemSlot(Integer itemId, Integer quantity, String name)
	{
		this.itemId = itemId;
		this.quantity = quantity;
		this.name = name;
	}

	/**
	 * The slot as read from the client.
	 *
	 * @param itemId   the item identity; a non-positive value is not an item
	 * @param quantity the stack size; a non-positive value is not an item. Stack size does not
	 *                 affect whether the slot counts as occupied — one slot holding a million
	 *                 coins is one occupied slot
	 * @param name     the item's name as the client reports it, or null when it has none
	 * @return an occupied slot, or {@link #EMPTY} when the reading does not describe an item
	 */
	static TelemetryItemSlot of(int itemId, int quantity, String name)
	{
		if (itemId <= 0 || quantity <= 0)
		{
			return EMPTY;
		}
		return new TelemetryItemSlot(itemId, quantity, normalizeName(name));
	}

	boolean isOccupied()
	{
		return itemId != null;
	}

	Integer getItemId()
	{
		return itemId;
	}

	Integer getQuantity()
	{
		return quantity;
	}

	/**
	 * The item's name, or null when the client had none to give.
	 *
	 * <p>Null here is the one degenerate case an occupied slot can carry. It is not reachable
	 * for an item the client has a definition for, and inventing a name — or, worse, reporting
	 * the slot as empty — would be less honest than saying the name is unknown.
	 */
	String getName()
	{
		return name;
	}

	/**
	 * Trims the client's name and reduces a blank or absent one to null. Length bounding is
	 * deliberately left to serialization, so that every exported string is bounded in exactly
	 * one place.
	 */
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
		if (!(other instanceof TelemetryItemSlot))
		{
			return false;
		}
		TelemetryItemSlot that = (TelemetryItemSlot) other;
		return Objects.equals(itemId, that.itemId)
			&& Objects.equals(quantity, that.quantity)
			&& Objects.equals(name, that.name);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(itemId, quantity, name);
	}
}
