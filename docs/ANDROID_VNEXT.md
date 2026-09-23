# Android vNext roadmap

This is the maintained backlog for the next WAM Bridge Android improvements after the
first in-app speaker controls and fresh-install fixes.

## 1. Home / Now Playing

**Foundation shipped 2026-09-22.** Home is now the daily-driver surface with shared Now Playing
state, direct controls and the three speaker-owned physical presets. Artwork/ICY metadata remain
follow-up work.

Turn the main screen into a daily-driver view instead of a service panel.

- artwork or station logo
- source / station / current playback state
- M5 state
- play / pause, mute, volume and Stop
- clear DLNA / Radio state
- move speaker IP, manual discovery and low-level service controls to Settings / Diagnostics

## 2. Android MediaSession

**Shipped 2026-09-22 for radio.** RadioService owns the Android MediaSession and MediaStyle
notification token; DLNA remains owned by the external player.

Use a proper MediaSession for radio playback so controls can appear naturally in Android:

- notification shade
- lock screen / system media controls
- compatible headset and Bluetooth media buttons

DLNA playback should remain owned by the external player that started it, rather than
having two apps fight over the speaker.

## 3. Sleep Timer / Standby

**Software implemented 2026-09-22; Standby now hardware-validated 2026-09-23.** Android exposes
15/30/45/60/Off, reads speaker timer state, and routes commands through the current radio/renderer
owner. On the rolling release, Standby now released app-owned playback, armed the one-second
speaker timer and the M5 front lamp went dark. Timed 15/30/45/60 presets still need a longer
duration/readback pass before that whole path is marked fully hardware-validated.

Expose the measured M5 sleep path through normal UI.

- 15 / 30 / 45 / 60 minute presets
- immediate standby
- clear current timer state where possible
- clean interaction with radio and renderer teardown

## 4. Physical Radio preset manager

Manage the presets cycled by the M5's physical Radio button.

- show physical preset slots
- select a station for a slot
- sync to the speaker
- validate the write-side preset commands safely before shipping the editing UI

## 5. Radio favourites 2.0

**Shipped 2026-09-23.** Android now uses the shared `station_packs.json` favourites pack as
its default library and layers user pin/order/default/recent state on top. The station manager
also supports duplicate/edit plus JSON/M3U/PLS import/export.

Make saved stations pleasant to live with.

- pinning and manual ordering
- recently played
- default station / Play last
- duplicate and edit helpers
- import / export in useful formats such as M3U, PLS and JSON
- clear TuneIn / direct / fallback indicators

## 6. Smarter fallback routing

**Shipped 2026-09-23.** The mobile relay passively remembers successful/failed endpoints,
prefers the last working endpoint on later starts, cools failed URLs for 15 minutes, and
publishes the active fallback position through shared runtime state. It never opens a second
probe beside playback.

Improve radio recovery without adding a second probing client to the active stream.

- remember the last working endpoint
- temporarily de-prioritize recently failed URLs
- surface which fallback is active
- keep ordered fallback behavior deterministic

## 7. ICY metadata pipeline

Extract metadata such as `StreamTitle` from direct radio and keep one shared playback
state that can feed:

- Home / Now Playing
- widgets
- MediaSession / notifications

## 8. Phone-side HLS / Ogg support

Add a dedicated mobile transcoding path so streams currently rejected by the phone relay
can play through the M5.

Keep this isolated from the normal direct relay because it is a larger transport subsystem,
not a small codec toggle.

## 9. Quick actions everywhere

Make common actions reachable without opening the app.

- launcher shortcuts for favourite stations, Stop and Standby
- optional Radio Quick Settings tile
- small one-tap station widgets
- reuse the same command/state routing as the app instead of duplicating control logic

## 10. Human-friendly Diagnostics

**Shipped 2026-09-22.** Diagnostics now exposes the shared speaker/network/runtime state,
copyable text output and controlled Fix connection recovery; manual IP moved under Advanced.

Keep protocol archaeology out of the normal UI while still making failures explainable.

- speaker ID and IP
- Wi-Fi / Android Network binding
- current M5 owner: radio, renderer or idle
- active stream and fallback
- last error
- app version
- Copy diagnostics
- controlled Fix connection flow: stop -> rediscover -> probe -> refresh

## Suggested order

Build the daily-driver layer first:

1. Home / Now Playing
2. MediaSession
3. Sleep Timer / Standby
4. Radio favourites 2.0
5. ICY metadata
6. Quick actions

Then continue with speaker-specific preset management, smarter fallback routing and the
larger HLS/Ogg transport work. Diagnostics should grow alongside those changes rather than
becoming a separate second control stack.

The goal is to keep WAM Bridge small, local and useful: no account system, cloud backend or
framework migration unless a concrete feature eventually requires one.
