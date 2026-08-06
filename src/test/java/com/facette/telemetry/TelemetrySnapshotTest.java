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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

/**
 * Pins the exported schema-1 document: its exact keys, its exact shape, its size ceiling,
 * and the absence of anything the plugin is not allowed to export.
 */
public class TelemetrySnapshotTest
{
	private static final String INSTANCE_ID = "0f8b1d3a-6c2e-4a15-9f77-2b8d4e6a1c90";

	private static final List<String> AUTHORIZED_TOP_LEVEL_KEYS = Arrays.asList(
		"schema", "source", "instanceId", "seq", "emittedAt", "session", "vitals", "inventory", "xp");

	private static final List<String> AUTHORIZED_NESTED_KEYS = Arrays.asList(
		"pluginActive", "gameState", "loggedIn", "world",
		"hitpointsCurrent", "hitpointsBase", "prayerCurrent", "prayerBase", "runEnergyPercent",
		"usedSlots", "freeSlots",
		"lastSkill", "lastDelta", "lastChangedAt");

	/**
	 * Key names that must never appear in the document. Schema 1 exports no account
	 * identity, credential, chat, social, wealth, or location data.
	 */
	private static final List<String> FORBIDDEN_KEYS = Arrays.asList(
		"account", "accountId", "accountHash", "accountType", "username", "displayName",
		"playerName", "email", "password", "token", "sessionToken", "credential",
		"chat", "message", "friends", "clan", "players", "nearbyPlayers",
		"bank", "wealth", "gp", "grandExchange", "ge", "totalXp", "experience",
		"location", "worldPoint", "regionId", "coordinates", "latitude", "path", "url");

	private static TelemetrySnapshot.Builder populated()
	{
		return TelemetrySnapshot.builder()
			.instanceId(INSTANCE_ID)
			.seq(7L)
			.emittedAt(1_770_000_000_000L)
			.pluginActive(true)
			.gameState("LOGGED_IN")
			.loggedIn(true)
			.world(302)
			.hitpoints(73, 75)
			.prayer(40, 52)
			.runEnergyPercent(88)
			.inventory(12, 16)
			.xp("woodcutting", 65, 1_769_999_998_000L);
	}

	private static TelemetrySnapshot.Builder inactive()
	{
		return TelemetrySnapshot.builder()
			.instanceId(INSTANCE_ID)
			.seq(42L)
			.emittedAt(1_770_000_000_000L)
			.pluginActive(false)
			.gameState("LOGIN_SCREEN")
			.loggedIn(false)
			.world(null)
			.hitpoints(null, null)
			.prayer(null, null)
			.runEnergyPercent(null)
			.inventory(null, null)
			.xp(null, null, null);
	}

	@Test
	public void populatedSnapshotSerializesToTheExactSchemaOneDocument()
	{
		assertEquals(
			"{\"schema\":1,\"source\":\"runelite\","
				+ "\"instanceId\":\"" + INSTANCE_ID + "\",\"seq\":7,\"emittedAt\":1770000000000,"
				+ "\"session\":{\"pluginActive\":true,\"gameState\":\"LOGGED_IN\",\"loggedIn\":true,\"world\":302},"
				+ "\"vitals\":{\"hitpointsCurrent\":73,\"hitpointsBase\":75,\"prayerCurrent\":40,"
				+ "\"prayerBase\":52,\"runEnergyPercent\":88},"
				+ "\"inventory\":{\"usedSlots\":12,\"freeSlots\":16},"
				+ "\"xp\":{\"lastSkill\":\"woodcutting\",\"lastDelta\":65,\"lastChangedAt\":1769999998000}}",
			populated().build().toJson());
	}

	@Test
	public void inactiveSnapshotKeepsEveryKeyAndNullsEveryGameplayValue()
	{
		assertEquals(
			"{\"schema\":1,\"source\":\"runelite\","
				+ "\"instanceId\":\"" + INSTANCE_ID + "\",\"seq\":42,\"emittedAt\":1770000000000,"
				+ "\"session\":{\"pluginActive\":false,\"gameState\":\"LOGIN_SCREEN\",\"loggedIn\":false,\"world\":null},"
				+ "\"vitals\":{\"hitpointsCurrent\":null,\"hitpointsBase\":null,\"prayerCurrent\":null,"
				+ "\"prayerBase\":null,\"runEnergyPercent\":null},"
				+ "\"inventory\":{\"usedSlots\":null,\"freeSlots\":null},"
				+ "\"xp\":{\"lastSkill\":null,\"lastDelta\":null,\"lastChangedAt\":null}}",
			inactive().build().toJson());
	}

	@Test
	public void topLevelKeysAreExactlyTheAuthorizedNineInOrder()
	{
		assertEquals(AUTHORIZED_TOP_LEVEL_KEYS, keysByDepth(populated().build().toJson()).get(1));
		assertEquals(AUTHORIZED_TOP_LEVEL_KEYS, keysByDepth(inactive().build().toJson()).get(1));
	}

	@Test
	public void nestedKeysAreExactlyTheAuthorizedFourteenInOrder()
	{
		assertEquals(AUTHORIZED_NESTED_KEYS, keysByDepth(populated().build().toJson()).get(2));
		assertEquals(AUTHORIZED_NESTED_KEYS, keysByDepth(inactive().build().toJson()).get(2));
	}

	@Test
	public void documentHasNoKeysBeyondTheAuthorizedSchema()
	{
		Map<Integer, List<String>> keys = keysByDepth(populated().build().toJson());
		assertEquals("no nesting beyond one level of objects", 2, keys.size());
		assertEquals(
			AUTHORIZED_TOP_LEVEL_KEYS.size() + AUTHORIZED_NESTED_KEYS.size(),
			keys.get(1).size() + keys.get(2).size());
	}

	@Test
	public void forbiddenIdentityAndSocialFieldsAreAbsent()
	{
		String json = populated().build().toJson();
		List<String> present = new ArrayList<>();
		for (String key : FORBIDDEN_KEYS)
		{
			if (json.contains("\"" + key + "\":"))
			{
				present.add(key);
			}
		}
		assertEquals("forbidden fields present in schema 1", new ArrayList<String>(), present);
	}

	@Test
	public void serializedSnapshotStaysFarBelowTheSizeCeiling()
	{
		byte[] bytes = populated().build().toJsonBytes();
		assertEquals(bytes.length, populated().build().toJson().getBytes(StandardCharsets.UTF_8).length);
		assertTrue("fixture is " + bytes.length + " bytes", bytes.length < TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES);
		// The bounded schema leaves ample headroom; nothing here can grow with play time.
		assertTrue("fixture is " + bytes.length + " bytes", bytes.length < 512);
	}

	@Test
	public void instanceIdIsCarriedVerbatim()
	{
		String uuid = UUID.randomUUID().toString();
		TelemetrySnapshot snapshot = populated().instanceId(uuid).build();
		assertEquals(uuid, snapshot.getInstanceId());
		assertTrue(snapshot.toJson().contains("\"instanceId\":\"" + uuid + "\""));
	}

	@Test
	public void stringValuesAreJsonEscaped()
	{
		// A quote, a backslash, a newline, and a bare control character. RuneLite's own
		// game state names contain none of these, but a serializer able to emit them raw
		// could produce a document a reader cannot parse.
		String hostile = "a\"b\\c\nd" + (char) 1 + "e";
		String json = populated().gameState(hostile).build().toJson();
		assertTrue(json, json.contains("\"gameState\":\"a\\\"b\\\\c\\nd\\u0001e\""));
	}

	@Test
	public void negativeSequenceIsRejected()
	{
		try
		{
			populated().seq(-1L).build();
			fail("expected a negative sequence to be rejected");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("seq"));
		}
	}

	@Test
	public void sequenceAndTimestampsAreCarriedAsNumbers()
	{
		TelemetrySnapshot snapshot = populated().seq(0L).build();
		assertEquals(0L, snapshot.getSeq());
		assertEquals(1_770_000_000_000L, snapshot.getEmittedAt());
		assertTrue(snapshot.toJson().contains("\"seq\":0,"));
		assertTrue(snapshot.isPluginActive());
		assertTrue(snapshot.isLoggedIn());
		assertFalse(inactive().build().isPluginActive());
		assertFalse(inactive().build().isLoggedIn());
	}

	/**
	 * Collects the object keys of a JSON document by nesting depth, in document order.
	 * Deliberately hand-written so this test depends on nothing the plugin itself uses to
	 * serialize.
	 */
	private static Map<Integer, List<String>> keysByDepth(String json)
	{
		Map<Integer, List<String>> keys = new HashMap<>();
		int depth = 0;
		int i = 0;
		while (i < json.length())
		{
			char c = json.charAt(i);
			if (c == '{')
			{
				depth++;
				i++;
			}
			else if (c == '}')
			{
				depth--;
				i++;
			}
			else if (c == '"')
			{
				StringBuilder literal = new StringBuilder();
				i++;
				while (i < json.length() && json.charAt(i) != '"')
				{
					if (json.charAt(i) == '\\')
					{
						literal.append(json.charAt(i));
						i++;
					}
					literal.append(json.charAt(i));
					i++;
				}
				i++;
				if (i < json.length() && json.charAt(i) == ':')
				{
					keys.computeIfAbsent(depth, d -> new ArrayList<>()).add(literal.toString());
				}
			}
			else
			{
				i++;
			}
		}
		return keys;
	}
}
