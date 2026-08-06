# Facette Integrations — Agent Operating Rules

Read this file before changing the repository. Then read the active task packet and every
document it names.

This repository is **public**. Everything committed here is source-visible and reviewable by
anyone, including people who do not use Facette. That is the point: a game-side integration
that reads a player's live game state has to be auditable by the player.

## What this repository is

Open-source game-side integrations for Facette. Today that is one integration: a RuneLite
plugin that writes a sanitized local telemetry snapshot for the Facette companion
application to read independently.

The RuneLite Gradle project sits at the repository root because the RuneLite Plugin Hub
builds a plugin repository from its root. A second integration does not get added by
rearranging this layout — it requires its own approved packet that resolves the layout
question first.

## Boundaries

These hold for every packet. A packet cannot waive them; a packet that requires crossing one
is a stop-and-escalate, not a permission.

- **No secrets.** No credentials, tokens, keys, account identifiers, or private endpoints are
  committed here, referenced here, or read by code here. Never ask an operator to paste a
  credential, and never commit one they paste anyway.
- **No gameplay control.** Integrations read. They do not click, type, move, use a menu
  action, synthesize mouse, keyboard, touch, or controller input, automate play, or move or
  focus a window. There is no reverse path from Facette into a game.
- **No network transport.** No socket, HTTP, WebSocket, or UDP client or server, and no
  remote transmission of game data. Integrations write local files; the Facette agent reads
  them separately.
- **No arbitrary execution.** No `Runtime.exec`, no `ProcessBuilder`, no reflection, no JNI
  or native loading, no runtime downloading, and no vendoring of code at runtime.
- **No unapproved dependencies.** Use only what the upstream project's official template
  already provides, plus that project's own transitive dependencies. A new dependency is a
  founder decision.
- **No redistributed game assets.** No artwork, logos, sounds, fonts, or extracted assets
  from a game or its client.
- **No player-identifying or social data.** No account names or hashes, chat, friends, clan
  data, nearby players, bank contents, Grand Exchange data, wealth, or precise location
  history. An exported schema is a closed list, not a starting point.
- **No publication.** No Plugin Hub or store submission, no GitHub release, no tag, no
  package publication, and no prebuilt binary — each requires its own founder-approved
  packet.
- **No unrelated Facette material.** Private product strategy, internal roadmaps, and
  internal operational documents stay in the private repository.

## Authority

- Agents may commit, push, and open **draft** pull requests when a packet authorizes it.
- **Merge, deployment, publication, and release are founder-only.** No clean review, no
  passing CI, and no empty finding list authorizes any of them.
- Independent review of the **exact PR head** is required. A review is current only when the
  reviewed commit SHA equals the PR head SHA; any push after a review makes that review stale
  and not a review of the current code at all. Do not report a requested review as a
  completed one.
- Do not edit outside the active packet's owned paths, and do not silently broaden scope.
  Unexpected required scope is a proposal, not an unapproved edit.

## Evidence

State what was actually verified and what was not. In particular:

- A Linux CI build is not evidence of live in-game behavior on Windows.
- Unit tests are not evidence that a plugin loads in a real client.
- Live game, launcher, account, and hardware validation are the operator's, and are reported
  as outstanding until the operator provides them against the exact reviewed head.

Never imply that live-game, Windows, launcher, signing, or store-approval validation occurred
when it did not, and never claim approval or endorsement by a game's publisher or by an
upstream client project.
