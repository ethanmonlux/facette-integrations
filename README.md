# Facette Integrations

Open-source game-side integrations for [Facette](https://github.com/ethanmonlux/facette), a
Windows-first second-screen companion for games.

Each integration does one narrow thing: read state a game already exposes through a permitted
extension surface, sanitize it, and hand it to Facette locally. Nothing here plays the game,
and nothing here sends game data anywhere.

This repository is public so that anyone whose game state is being read can read the code
doing the reading.

## Contents

| Integration | Game | Status |
|---|---|---|
| Facette Telemetry | Old School RuneScape, via [RuneLite](https://runelite.net) | Technical alpha — source only |

The RuneLite plugin lives at the **repository root** rather than in a subdirectory. That is
not a stylistic choice: the RuneLite Plugin Hub builds a plugin from the root of the
repository it is pointed at, so a RuneLite plugin project has to be the root project. A
second integration will need its own approved change that resolves this layout question
first; do not assume it can simply be dropped into a sibling folder.

---

# Facette Telemetry (RuneLite plugin)

**Technical alpha.** This plugin is not approved by, submitted to, or distributed through the
RuneLite Plugin Hub, and it is not affiliated with or endorsed by Jagex or RuneLite. There is
no prebuilt JAR here and no release to download. The only way to run it today is to build it
from this source yourself.

## What it does

While enabled, the plugin keeps one small JSON file up to date with a bounded view of your own
character's live state, so a companion app on the same machine can show it on a second screen.
Facette reads that file independently, whenever it likes. If Facette is not installed or not
running, the plugin is unaffected and simply keeps the file current.

**Read-only.** The plugin reads game state through the RuneLite API and writes one local file. It
performs no clicks, no keystrokes, no menu actions, no automation, and no window manipulation, and
it does not read any command channel, so nothing outside the game can act on the game through it.
There is no reverse path.

**No network communication.** The plugin opens no socket and makes no HTTP, WebSocket, or other
remote request. Your game state does not leave your computer because of this plugin.

**No account credentials.** The plugin never reads, stores, or exports your account name, account
hash, email, password, session token, or any other credential. It also exports no chat, friends,
clan data, other players, bank contents, Grand Exchange data, prices, wealth, quest or Slayer
state, or location.

## Exactly what is exported

The file is one UTF-8 JSON object, **schema version 2**. Schema 2 is a **closed contract**: the
fields below are all of them, the key order is fixed, and no other key ever appears. It is a list,
not a starting point — anything not named here is not exported, and adding to it is a deliberate
change to this document and to the version number, not an implementation detail.

### Envelope

| Field | Type | Meaning |
|---|---|---|
| `schema` | integer | Always `2`. |
| `source` | string | Always `"runelite"`. |
| `instanceId` | string | A random UUID generated fresh each time the plugin starts. Not derived from your account, profile, machine, or any game state; it only lets a reader notice a restart. |
| `seq` | integer | Increases by one for each snapshot actually written, starting at `0`. |
| `emittedAt` | integer | Unix time in milliseconds when the snapshot was written. |

### `session`

| Field | Type | Meaning |
|---|---|---|
| `session.pluginActive` | boolean | Whether the plugin is running. |
| `session.gameState` | string | The RuneLite game-state name, e.g. `LOGGED_IN`, `LOGIN_SCREEN`. |
| `session.loggedIn` | boolean | Whether this snapshot carries valid live player data. See the note below — this is not simply a copy of `gameState`. |
| `session.world` | integer or null | World number. |
| `session.combatLevel` | integer or null | Your combat level. |
| `session.trackingStartedAt` | integer or null | Unix time in milliseconds when **this plugin instance** established its comparison points for the current session. This is *not* your account's login time, and is not claimed to be: if you enable the plugin an hour into a session, this is that moment, not the login. It resets when the session ends. |

### `vitals`

| Field | Type | Meaning |
|---|---|---|
| `vitals.hitpointsCurrent` | integer or null | Current Hitpoints level. |
| `vitals.hitpointsBase` | integer or null | Base Hitpoints level. |
| `vitals.prayerCurrent` | integer or null | Current Prayer points. |
| `vitals.prayerBase` | integer or null | Base Prayer level. |
| `vitals.runEnergyPercent` | integer or null | Run energy, `0`–`100`. |
| `vitals.specialAttackPercent` | integer or null | Special attack energy, `0`–`100`. |
| `vitals.weightKg` | integer or null | The weight the client reports, in kilograms. Negative when weight-reducing equipment outweighs what you carry. `null` if the client reports a value outside the range a real load can reach. |

### `combat`

| Field | Type | Meaning |
|---|---|---|
| `combat.attackStyle` | string or null | The currently selected attack style, lowercased — for example `accurate`, `aggressive`, `controlled`, `defensive`, `ranging`, `longrange`, `casting`. The label is the game's own, read from the game's style data for your equipped weapon. `null` whenever no trustworthy reading exists; that is a normal steady state, not an error. |
| `combat.activePrayers` | array of strings, or null | Lowercase RuneLite prayer names for the prayers currently active, in RuneLite's own prayer order, with no duplicates. An **empty array** means no prayer is active. `null` means the snapshot carries no player data at all. |
| `combat.target` | object or null | The NPC you are currently interacting with. `null` when there is none. |

`combat.target`, when present:

| Field | Type | Meaning |
|---|---|---|
| `target.kind` | string | Always `"npc"`. There is no other permitted kind. |
| `target.id` | integer | The NPC's identifier. |
| `target.name` | string or null | The NPC's current display name. |
| `target.combatLevel` | integer or null | The NPC's combat level, or `null` when it has none. |
| `target.healthRatio` | integer or null | The health the server actually transmits, in `healthScale` units. |
| `target.healthScale` | integer or null | The maximum `healthRatio` can be for this actor. |
| `target.dead` | boolean | The observable dead state of the actor. |

**Only the NPC you are interacting with can appear as a target.** A player can be interacted with
too — followed, traded, attacked — and **no player target is ever exported, and no other player's
name ever leaves the client**. If what you are interacting with is not an NPC, `combat.target` is
`null` and nothing about it is read.

**No exact target hitpoints are exported or estimated.** The server does not transmit an NPC's real
health; it transmits the ratio and scale above, and that is all this file carries. `healthRatio` and
`healthScale` are always either both present or both `null`.

### `equipment`

`equipment.slots` is `null` when the snapshot carries no player data, and otherwise an array of
**exactly eleven** entries, always in this order:

`head`, `cape`, `amulet`, `weapon`, `body`, `shield`, `legs`, `gloves`, `boots`, `ring`, `ammo`

### `inventory`

| Field | Type | Meaning |
|---|---|---|
| `inventory.usedSlots` | integer or null | Inventory slots holding an item, `0`–`28`. Slot occupancy, not item quantity. |
| `inventory.freeSlots` | integer or null | Empty inventory slots. `usedSlots` and `freeSlots` always sum to `28`. |
| `inventory.slots` | array or null | **Exactly twenty-eight** entries, in ascending slot order `0`–`27`. |

### Item slot entries

Every equipment and inventory entry has the same four fields, in this order:

| Field | Type | Meaning |
|---|---|---|
| `slot` | string or integer | The canonical slot name for equipment, or the slot's own position `0`–`27` for inventory. |
| `itemId` | integer or null | The item's identifier. |
| `quantity` | integer or null | The stack size. A stack of a million coins is still **one** occupied slot. |
| `name` | string or null | The item's name as the game reports it. |

An **empty** slot has `itemId`, `quantity`, and `name` all `null`. An **occupied** slot has a
positive `itemId` and a positive `quantity`. There is nothing in between. (`name` is `null` in the
one degenerate case where the client has no name for an item it is holding; inventing one would be
worse than saying so.)

**Item IDs, names, and quantities are exported. Prices are not, and neither is aggregate wealth.**
No price, high-alchemy value, Grand Exchange data, tradeability, examine text, bank content, total
value, loadout history, sprite, or artwork is read or exported. Nothing in this file lets a reader
work out what your items are worth.

### `xp`

| Field | Type | Meaning |
|---|---|---|
| `xp.lastSkill` | string or null | Lowercase name of the skill that most recently gained experience. |
| `xp.lastDelta` | integer or null | Size of that most recent gain. |
| `xp.lastChangedAt` | integer or null | Unix time in milliseconds of that gain. |
| `xp.skills` | array or null | One entry per skill that has gained experience during the current tracked session. Ordered by RuneLite's own skill order. Possibly empty. |

Each `xp.skills` entry:

| Field | Type | Meaning |
|---|---|---|
| `skill` | string | Lowercase skill name. |
| `gained` | integer | Experience gained in that skill **during this tracked session only**. |
| `lastDelta` | integer | That skill's most recent single gain. |
| `lastChangedAt` | integer | Unix time in milliseconds of that gain. |

**Total experience is never exported.** Neither is your starting total, your historical experience,
your level history, or any daily figure. Every number under `xp` is a difference between two
readings this plugin instance took itself, so a session gain of `130` says exactly the same thing
about a level 3 character and a maxed one.

The first experience reading for a skill in a session only establishes a comparison point; it never
reports a gain. Those comparison points, and the accumulated session totals, are discarded when the
session ends, so a later login cannot inherit them.

### Current state versus session totals

Two different things live in this file, and the difference matters:

- **Current state** — `session`, `vitals`, `combat`, `equipment`, and `inventory` describe the game
  *right now*. Each is replaced wholesale on the next sample. The inventory is a snapshot of what
  you are carrying, not a log of what you have carried; nothing here accumulates and nothing here
  becomes a history.
- **Session-local accumulated experience** — `xp.skills` is the one place anything adds up, and it
  only adds up within the session this plugin instance has been tracking. It resets to nothing when
  the session ends.

### When you are not logged in

**Whenever `session.loggedIn` is `false`, every player-derived value above is `null`** — every
scalar, `combat.activePrayers`, `combat.target`, `equipment.slots`, all three inventory fields, and
all four experience fields. The plugin does not keep showing your last known values to a reader
after you log out, and the transition is atomic: there is no snapshot with `loggedIn: false` that
still carries gameplay values.

`session.loggedIn` reports **whether the player data in this document is valid**, which is not quite
the same question as which game state the client is in. For up to one game tick after a login or a
world hop, `session.gameState` reads `LOGGED_IN` while `session.loggedIn` is still `false`, because
the plugin has not yet sampled that session. That is deliberate: the alternative would be a document
claiming a live session while asserting an empty inventory and no active prayers, which would be a
guess dressed up as a reading.

A snapshot with `loggedIn: true` can still legitimately contain no attack-style reading, no active
prayers, no target, empty equipment slots, an entirely empty inventory, and no session experience.
Those are real states, and each is distinguishable from the logged-out nulling above: an empty array
is not `null`.

### A representative snapshot

Exactly the document above, with obviously synthetic values, at 3,343 bytes. This is the same file
committed at `src/test/resources/facette-osrs-state-v2.json`, and a test fails if the two ever
disagree:

```json
{"schema":2,"source":"runelite","instanceId":"0f8b1d3a-6c2e-4a15-9f77-2b8d4e6a1c90","seq":7,"emittedAt":1770000000000,"session":{"pluginActive":true,"gameState":"LOGGED_IN","loggedIn":true,"world":302,"combatLevel":87,"trackingStartedAt":1769999940000},"vitals":{"hitpointsCurrent":73,"hitpointsBase":75,"prayerCurrent":40,"prayerBase":52,"runEnergyPercent":88,"specialAttackPercent":65,"weightKg":12},"combat":{"attackStyle":"accurate","activePrayers":["protect_from_melee","piety"],"target":{"kind":"npc","id":4001,"name":"Sample target dummy","combatLevel":21,"healthRatio":18,"healthScale":30,"dead":false}},"equipment":{"slots":[{"slot":"head","itemId":1101,"quantity":1,"name":"Sample helm"},{"slot":"cape","itemId":1102,"quantity":1,"name":"Sample cape"},{"slot":"amulet","itemId":1103,"quantity":1,"name":"Sample amulet"},{"slot":"weapon","itemId":1104,"quantity":1,"name":"Sample blade"},{"slot":"body","itemId":1105,"quantity":1,"name":"Sample platebody"},{"slot":"shield","itemId":null,"quantity":null,"name":null},{"slot":"legs","itemId":1107,"quantity":1,"name":"Sample platelegs"},{"slot":"gloves","itemId":1108,"quantity":1,"name":"Sample gloves"},{"slot":"boots","itemId":1109,"quantity":1,"name":"Sample boots"},{"slot":"ring","itemId":null,"quantity":null,"name":null},{"slot":"ammo","itemId":1111,"quantity":350,"name":"Sample bolts"}]},"inventory":{"usedSlots":12,"freeSlots":16,"slots":[{"slot":0,"itemId":2001,"quantity":1,"name":"Sample pickaxe"},{"slot":1,"itemId":2002,"quantity":1,"name":"Sample hatchet"},{"slot":2,"itemId":2003,"quantity":4,"name":"Sample loaf"},{"slot":3,"itemId":2004,"quantity":1500,"name":"Sample coin pile"},{"slot":4,"itemId":2005,"quantity":3,"name":"Sample potion"},{"slot":5,"itemId":2006,"quantity":1,"name":"Sample teleport tablet"},{"slot":6,"itemId":2007,"quantity":27,"name":"Sample logs"},{"slot":7,"itemId":2008,"quantity":12,"name":"Sample ore"},{"slot":8,"itemId":2009,"quantity":1,"name":"Sample gem"},{"slot":9,"itemId":2010,"quantity":6,"name":"Sample herb"},{"slot":10,"itemId":2011,"quantity":2,"name":"Sample plank"},{"slot":11,"itemId":2012,"quantity":1,"name":"Sample seed pouch"},{"slot":12,"itemId":null,"quantity":null,"name":null},{"slot":13,"itemId":null,"quantity":null,"name":null},{"slot":14,"itemId":null,"quantity":null,"name":null},{"slot":15,"itemId":null,"quantity":null,"name":null},{"slot":16,"itemId":null,"quantity":null,"name":null},{"slot":17,"itemId":null,"quantity":null,"name":null},{"slot":18,"itemId":null,"quantity":null,"name":null},{"slot":19,"itemId":null,"quantity":null,"name":null},{"slot":20,"itemId":null,"quantity":null,"name":null},{"slot":21,"itemId":null,"quantity":null,"name":null},{"slot":22,"itemId":null,"quantity":null,"name":null},{"slot":23,"itemId":null,"quantity":null,"name":null},{"slot":24,"itemId":null,"quantity":null,"name":null},{"slot":25,"itemId":null,"quantity":null,"name":null},{"slot":26,"itemId":null,"quantity":null,"name":null},{"slot":27,"itemId":null,"quantity":null,"name":null}]},"xp":{"lastSkill":"woodcutting","lastDelta":65,"lastChangedAt":1769999998000,"skills":[{"skill":"attack","gained":240,"lastDelta":40,"lastChangedAt":1769999990000},{"skill":"woodcutting","gained":130,"lastDelta":65,"lastChangedAt":1769999998000},{"skill":"fishing","gained":90,"lastDelta":30,"lastChangedAt":1769999995000}]}}
```

The same document with no player data, committed at
`src/test/resources/facette-osrs-state-v2-logged-out.json`:

```json
{"schema":2,"source":"runelite","instanceId":"0f8b1d3a-6c2e-4a15-9f77-2b8d4e6a1c90","seq":42,"emittedAt":1770000000000,"session":{"pluginActive":true,"gameState":"LOGIN_SCREEN","loggedIn":false,"world":null,"combatLevel":null,"trackingStartedAt":null},"vitals":{"hitpointsCurrent":null,"hitpointsBase":null,"prayerCurrent":null,"prayerBase":null,"runEnergyPercent":null,"specialAttackPercent":null,"weightKg":null},"combat":{"attackStyle":null,"activePrayers":null,"target":null},"equipment":{"slots":null},"inventory":{"usedSlots":null,"freeSlots":null,"slots":null},"xp":{"lastSkill":null,"lastDelta":null,"lastChangedAt":null,"skills":null}}
```

The file is capped at 16,384 bytes and is replaced whole each time — a reader never sees a
half-written document. Every collection in it is fixed-size or bounded by a game enumeration, and
every exported string has a maximum length, so the document cannot grow with how long you play.

## Where the file is written

On Windows:

```text
%USERPROFILE%\.runelite\facette\state-v2.json
```

On other platforms it is the same location relative to RuneLite's own data directory:

```text
~/.runelite/facette/state-v2.json
```

That is the only path the plugin writes to. It creates the `facette` directory if it is missing,
writes nothing inside your Old School RuneScape installation, and does not scan your filesystem.

Values are resampled each game tick and republished when they change, at most four times per second,
plus a heartbeat at least every two seconds so a reader can tell a live plugin from a stale file.

### Schema 1 and `state-v1.json`

Schema 1 is **superseded**: the current source writes schema 2 only, to `state-v2.json`. It does not
write `state-v1.json`, and it does not write both.

If an earlier build of this plugin left a `state-v1.json` behind, this one **does not read it, does
not migrate it, and does not delete it**. It stays exactly as it was and simply stops being updated,
so anything still reading it can see for itself that it has gone stale. Removing it is yours to do,
and deleting the whole `facette` directory (below) removes it along with everything else.

## Stopping the export and removing the data

To stop the export, **disable the Facette Telemetry plugin** in RuneLite's plugin list. The plugin
writes one final snapshot on the way out, reporting `pluginActive: false`, `loggedIn: false`, and
every gameplay-derived field `null`, and then writes nothing further. If RuneLite is killed rather
than closed, no final snapshot is written and the file simply goes stale — it never pretends an
orderly shutdown happened.

To remove the exported data, delete the directory:

```text
%USERPROFILE%\.runelite\facette
```

Deleting it removes the exported file and nothing else — the plugin keeps no history, database, log
of your play, or copy of the data anywhere else. If the plugin is still enabled, it will recreate the
directory and the file on its next publication.

## Running it from source

Requires JDK 11 (Eclipse Temurin recommended) and this repository checked out. The Gradle
wrapper is included; no separate Gradle install is needed.

Run the tests:

```sh
./gradlew clean test
```

Build the plugin:

```sh
./gradlew clean build
```

Launch a RuneLite development client with the plugin loaded, using the official template's
run task:

```sh
./gradlew run
```

Then enable **Facette Telemetry** in the client's plugin list and confirm that
`state-v2.json` appears at the path above.

If your account requires Jagex Account authorization for the development client, that is
yours to handle privately in your own environment. **Never paste an account credential,
token, or session file into an issue, a pull request, a log, or this repository**, and note
that nothing in this project will ever ask you for one.

## Reporting a problem

Open an issue. When attaching a snapshot, the whole file is safe to share by design — it
contains no account identity or credential — but read it first and satisfy yourself of that
rather than taking this README's word for it.

## License

Source code in this repository is licensed under the BSD 2-Clause License unless a
subdirectory states otherwise. See [LICENSE](LICENSE).

"Facette" and associated branding are not granted under this license.

Old School RuneScape is a trademark of Jagex Ltd. RuneLite is an independent open-source
project. This repository is affiliated with neither, and nothing here is approved or
endorsed by Jagex, RuneLite, or the RuneLite Plugin Hub.
