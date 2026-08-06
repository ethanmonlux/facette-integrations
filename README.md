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

While enabled, the plugin keeps one small JSON file up to date with a bounded view of your
own character's live state, so a companion app on the same machine can show it on a second
screen. Facette reads that file independently, whenever it likes. If Facette is not installed
or not running, the plugin is unaffected and simply keeps the file current.

**Read-only.** The plugin reads game state through the RuneLite API and writes one local
file. It performs no clicks, no keystrokes, no menu actions, no automation, and no window
manipulation, and it does not read any command channel, so nothing outside the game can act
on the game through it. There is no reverse path.

**No network communication.** The plugin opens no socket and makes no HTTP, WebSocket, or
other remote request. Your game state does not leave your computer because of this plugin.

**No account credentials.** The plugin never reads, stores, or exports your account name,
account hash, email, password, session token, or any other credential. It also exports no
chat, friends, clan data, other players, bank contents, Grand Exchange data, wealth, or
location history.

## Exactly what is exported

The file is one UTF-8 JSON object, schema version 1. These are all of the fields — the list
is closed, not a starting point:

| Field | Type | Meaning |
|---|---|---|
| `schema` | integer | Always `1`. |
| `source` | string | Always `"runelite"`. |
| `instanceId` | string | A random UUID generated fresh each time the plugin starts. Not derived from your account, profile, machine, or any game state; it only lets a reader notice a restart. |
| `seq` | integer | Increases by one for each snapshot actually written, starting at `0`. |
| `emittedAt` | integer | Unix time in milliseconds when the snapshot was written. |
| `session.pluginActive` | boolean | Whether the plugin is running. |
| `session.gameState` | string | The RuneLite game-state name, e.g. `LOGGED_IN`, `LOGIN_SCREEN`. |
| `session.loggedIn` | boolean | Whether this snapshot carries live logged-in player data. |
| `session.world` | integer or null | World number. |
| `vitals.hitpointsCurrent` | integer or null | Current Hitpoints level. |
| `vitals.hitpointsBase` | integer or null | Base Hitpoints level. |
| `vitals.prayerCurrent` | integer or null | Current Prayer points. |
| `vitals.prayerBase` | integer or null | Base Prayer level. |
| `vitals.runEnergyPercent` | integer or null | Run energy, `0`–`100`. |
| `inventory.usedSlots` | integer or null | Inventory slots holding an item, `0`–`28`. Slot occupancy, not item quantity. |
| `inventory.freeSlots` | integer or null | Empty inventory slots, `0`–`28`. |
| `xp.lastSkill` | string or null | Lowercase name of the skill that most recently gained experience. |
| `xp.lastDelta` | integer or null | Size of that most recent gain. |
| `xp.lastChangedAt` | integer or null | Unix time in milliseconds of that gain. |

Total experience is never exported. The first experience reading for a skill in a session
only establishes a comparison point; it never reports a gain. Those comparison points are
discarded when the session ends, so a later login cannot inherit them.

**Whenever you are not logged in, every player-derived field above is `null`** — the plugin
does not keep showing your last known values to a reader after you log out.

A representative snapshot, 433 bytes:

```json
{"schema":1,"source":"runelite","instanceId":"0f8b1d3a-6c2e-4a15-9f77-2b8d4e6a1c90","seq":0,"emittedAt":1770000000000,"session":{"pluginActive":true,"gameState":"LOGGED_IN","loggedIn":true,"world":302},"vitals":{"hitpointsCurrent":73,"hitpointsBase":75,"prayerCurrent":40,"prayerBase":52,"runEnergyPercent":88},"inventory":{"usedSlots":12,"freeSlots":16},"xp":{"lastSkill":"woodcutting","lastDelta":65,"lastChangedAt":1769999998000}}
```

The file is capped at 16,384 bytes and is replaced whole each time — a reader never sees a
half-written document.

## Where the file is written

On Windows:

```text
%USERPROFILE%\.runelite\facette\state-v1.json
```

On other platforms it is the same location relative to RuneLite's own data directory:

```text
~/.runelite/facette/state-v1.json
```

That is the only path the plugin writes to. It creates the `facette` directory if it is
missing, writes nothing inside your Old School RuneScape installation, and does not scan your
filesystem.

Values are resampled each game tick and republished when they change, at most four times per
second, plus a heartbeat at least every two seconds so a reader can tell a live plugin from a
stale file.

## Stopping the export and removing the data

To stop the export, **disable the Facette Telemetry plugin** in RuneLite's plugin list. The
plugin writes one final snapshot on the way out, reporting `pluginActive: false`,
`loggedIn: false`, and every gameplay-derived field `null`, and then writes nothing further.

To remove the exported data, delete the directory:

```text
%USERPROFILE%\.runelite\facette
```

Deleting it removes the single `state-v1.json` file and nothing else — the plugin keeps no
history, database, log of your play, or copy of the data anywhere else. If the plugin is
still enabled, it will recreate the directory and the file on its next publication.

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
`state-v1.json` appears at the path above.

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
