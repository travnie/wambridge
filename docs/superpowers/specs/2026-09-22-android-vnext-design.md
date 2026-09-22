# WAM Bridge Android vNext design

Date: 2026-09-22

## Goal

Turn the Android adapter into a daily-driver control app for the Samsung Shape M5 without
turning it into a cloud service or rewriting the working transport stack.

The app should feel like one coherent remote:

- Home shows what the M5 is doing now.
- Radio owns station discovery, saved stations and the three physical Radio presets.
- Settings owns setup, manual recovery and diagnostics.
- Widgets and the Quick Settings tile use the same discovery and control paths as the app.
- One runtime state model feeds Home, widgets, future MediaSession controls and diagnostics.

The existing WAM protocol, renderer, radio relay and speaker-control code remain the
foundations. This project is a UI/state/discovery consolidation, not a transport rewrite.

## Non-goals

Do not:

- migrate the Android app to Compose;
- add accounts, cloud sync or a backend;
- replace working RadioService, RendererService or SpeakerControls logic just for style;
- duplicate station or speaker state in a second persistent store;
- make the Home screen expose protocol details such as port 55001;
- solve HLS/Ogg transcoding inside this first vNext slice.

Those remain separate roadmap items unless a concrete implementation dependency appears.

## Navigation

The root app uses three persistent primary destinations:

- **Home**
- **Radio**
- **Settings**

Home is the default destination.

Keep this inside the existing classic Android Views stack. Avoid a framework migration or
a new fragment-heavy architecture. Existing deeper screens such as TuneIn catalogue/search
and station editing may remain separate Activities.

Expected navigation:

```text
Home <-> Radio <-> Settings

Radio -> TuneIn catalogue / search / station editor
Settings -> Diagnostics / Advanced
```

Back from a deeper Activity returns to the destination that opened it. Back from a primary
destination exits the app rather than creating a history maze between the three tabs.

## Home 3.5

Home uses the balanced mockup as the base, with the stronger Now Playing emphasis from the
player-first concept.

### Now Playing

Show:

- larger artwork or station logo;
- source type;
- station name;
- current track/metadata when available;
- M5 connection state;
- Stop;
- Play/Pause;
- Mute;
- raw volume control.

Home must render from shared runtime state. It must not infer playback by reading a collection
of service globals independently.

### Physical Radio presets

Show three always-visible preset tiles directly below Now Playing.

These tiles are **the same three presets stored in the M5 for its physical Radio button**.
There is only one meaning of "preset 1-3":

- Home preset 1 == M5 physical Radio preset 1;
- Home preset 2 == M5 physical Radio preset 2;
- Home preset 3 == M5 physical Radio preset 3.

The Radio screen shows the same three slots.

The speaker is authoritative for these three slots. The app reads them from the M5 rather than
maintaining an unrelated local favourites trio. Editing a slot writes the corresponding speaker
preset only after the write-side preset commands have been hardware-validated safely.

### Quick actions

Home also exposes:

- Stations
- Sleep
- Reconnect

Reconnect runs the same shared discovery/recovery pipeline described below.

### Discovery presentation

Do not show a red error on the first network hiccup.

Normal transient states are calm and compact:

- Connecting...
- Checking saved speaker...
- Searching...
- Scanning local network...
- Recovering...

A final human-facing error appears only after the full recovery path has been exhausted.
Technical details live in Diagnostics.

## Radio

Radio is the station library and discovery surface, not a second unrelated player.

Order:

1. **Physical presets**: the three M5-owned slots described above.
2. **Saved stations**: an unlimited local list.
3. **Recent**: recently played stations.
4. **Explore**: TuneIn browse and search.

The existing TuneInActivity, CatalogueActivity and RadioStationsActivity may remain in place
initially and be reached through the new Radio destination. Consolidation should happen only
when it removes real duplication.

Later roadmap work may add ordering, pinning, import/export, richer badges, fallback policy,
ICY metadata and transcoding without changing this information architecture.

## Settings

Settings moves technical setup out of the daily-driver Home screen.

### Speaker

Show:

- speaker name/model when known;
- connection state;
- current IP;
- device ID when known;
- Discover;
- Test connection.

Manual IP entry remains available but moves under Advanced. It is an escape hatch, not the main
setup path.

### Playback & system

Show controls/configuration for:

- Quick Settings tile;
- home-screen widgets;
- notification/media controls;
- launcher visibility;
- startup behavior where applicable.

### Radio

Provide configuration entry points for:

- physical preset management;
- station import/export when implemented;
- fallback policy when implemented;
- phone-side HLS/Ogg settings when that later subsystem exists.

### Diagnostics

Expose useful support data without leaking protocol noise onto Home:

- speaker ID and IP;
- active Wi-Fi / Android Network binding;
- current speaker owner: idle, radio or renderer;
- active stream/source and fallback;
- last error;
- app version;
- Copy diagnostics;
- Fix connection.

Fix connection is a controlled recovery action, not a random command shotgun. Its intended
sequence is stop/release when safe, rediscover, probe and refresh state.

## Shared runtime state

Add a lightweight in-process `SpeakerStateStore`.

Do not add coroutine/Flow dependencies only for this. A small immutable snapshot plus listener
registration is sufficient for the current app.

### Snapshot responsibilities

The snapshot should be able to represent at least:

- owner: `IDLE | RADIO | RENDERER`;
- playback state;
- speaker IP and device ID when known;
- volume;
- mute state;
- active station/source;
- Now Playing metadata;
- active source/fallback identity;
- discovery/recovery state;
- last user-relevant error.

The exact data-class split may evolve during implementation, but these responsibilities remain.

### Ownership boundaries

`SpeakerStateStore` is **state, not command logic**.

- RadioService remains responsible for radio playback and recovery.
- RendererService remains responsible for the UPnP renderer.
- SpeakerControls remains the single router for direct speaker actions.
- SpeakerControlGate remains the serialization mechanism protecting M5 control ownership.
- RadioStationStore and existing preferences remain the persistent stores for their current
  concerns.

Services publish state changes. Home, widgets, future MediaSession controls and Diagnostics
observe the same snapshot instead of reconstructing their own worldview.

### Process restart

Runtime playback state is not restored blindly from disk. After process restart the store starts
neutral and is rebuilt from current services/speaker reality. Do not resurrect stale "playing"
state from a previous process.

## Unified discovery and recovery

Automatic discovery must behave like the successful manual Discover path, but automatically.

Today the automatic and manual paths differ. vNext consolidates them into one discovery state
machine used by every entry point.

### Common sequence

When a usable speaker is not already verified on the current Wi-Fi endpoint:

1. probe the saved IP;
2. if that is not usable, run SSDP discovery;
3. if SSDP finds nothing usable, run the existing LAN scan fallback;
4. select the intended M5;
5. persist validated IP plus device ID when available;
6. publish READY/connected state;
7. continue the original action that needed the speaker.

A temporary device-ID read failure must not become an immediate terminal failure when the M5
itself is reachable. Continue through the shared discovery path.

### Candidate selection

When one speaker is found, use it automatically.

When several are found, prefer in order:

1. matching saved stable device ID;
2. matching saved IP;
3. otherwise require explicit user selection rather than silently picking an arbitrary M5.

### Entry points

The same state machine is used by:

- automatic app startup/reconnect;
- manual Discover;
- Home Reconnect;
- Home DLNA start;
- widget DLNA toggle/start;
- Quick Settings DLNA tile;
- other renderer-start surfaces.

Manual Discover is therefore a forced retry of the same pipeline, not a different algorithm.

### DLNA start behavior

Starting DLNA from any surface means:

```text
tap/start DLNA
    -> verify current M5
    -> if needed: saved-IP probe -> SSDP -> LAN scan
    -> persist resolved target
    -> start renderer
```

The widget or Quick Settings tile must not tell the user to open the app and press Discover just
because no target is currently resolved.

Surface state may progress as:

```text
DLNA ○ -> DLNA ◐ Finding M5... -> DLNA ●
```

Only after the full sequence fails should the control return to off/error state.

### Busy speaker behavior

Do not aggressively probe or scan the M5 control port while radio or renderer ownership is active.

Discovery/reconnect should defer, reuse known safe state, or pass through the existing
SpeakerControlGate/ownership rules as appropriate. The unified path must preserve the current
protection against competing control connections on port 55001.

## Errors and user messaging

Separate transient recovery from terminal failure.

Transient conditions include:

- Wi-Fi endpoint change;
- SSDP timeout;
- saved IP no longer answering;
- temporary identity-read failure.

These publish recovery states rather than immediate red alerts.

Final failure is reported only after the complete applicable discovery sequence is exhausted.

Home gets short messages such as:

- M5 not found
- Waiting for Wi-Fi
- Reconnecting to M5

Diagnostics retains the technical reason and last error for troubleshooting.

## Future MediaSession boundary

A later PR adds Android MediaSession for radio playback using the shared runtime snapshot.

It should provide:

- system media controls;
- notification controls;
- lock-screen controls;
- compatible headset/Bluetooth actions.

External DLNA playback remains owned by the player that started it. WAM Bridge must not create
a competing media session that pretends to own another player's transport.

## Testing strategy

### JVM unit tests

Cover pure logic, including:

- state-store transitions and snapshot updates;
- owner selection;
- discovery decision tree;
- candidate preference by device ID/IP;
- transient versus terminal failure;
- retry/defer decisions;
- fallback-state representation.

Keep the current lightweight JVM test approach where Android framework behavior is not required.

### Contract tests

Cover wiring that is easy to regress:

- widget and Quick Settings actions route through renderer start;
- renderer start enters the shared discovery path;
- manifest/provider declarations remain present;
- Home/Radio/Settings navigation entry points are wired correctly.

### Hardware validation

Do not treat mocks as proof of firmware behavior.

Require explicit physical-M5 validation for:

- write-side physical preset editing/sync;
- discovery/reconnect across real Wi-Fi changes;
- widget and Quick Settings start when no target is already resolved;
- ownership transitions involving real radio/renderer playback;
- any behavior relying on recovered Samsung commands.

Record measured outcomes in the existing protocol/development documentation.

## Incremental delivery

Keep one logical change per PR and leave the app usable after every merge.

Suggested sequence:

1. **Shared state + unified discovery**
   - add SpeakerStateStore;
   - consolidate auto/manual/reconnect resolution;
   - route renderer starts from app/widget/tile through the common path;
   - add tests.

2. **Root navigation**
   - Home / Radio / Settings;
   - move technical setup out of the start screen;
   - preserve existing deep Activities.

3. **Home 3.5 + physical preset display**
   - Now Playing;
   - transport/volume controls;
   - three M5-owned physical preset tiles;
   - quick actions.

4. **Radio and Settings cleanup**
   - physical presets / saved / recent / explore structure;
   - Advanced and Diagnostics organization.

5. **MediaSession + notification controls**
   - consume shared state;
   - keep DLNA ownership boundaries intact.

6. **Follow-on roadmap items**
   - sleep timer;
   - ICY metadata;
   - richer quick actions;
   - smarter fallbacks;
   - physical preset writes after hardware validation;
   - HLS/Ogg transcoding as a separate subsystem.

Each PR must run the relevant Mobile CI on the final head. Hardware-dependent claims remain
explicitly unverified until tested on the physical M5.

## Success criteria

The vNext foundation is successful when:

- opening the app lands on a useful Home screen instead of a technical setup panel;
- Home, widgets and diagnostics agree on one runtime state;
- the same three physical Radio presets appear consistently on the M5, Home and Radio;
- automatic discovery recovers in the same cases where manual Discover succeeds;
- tapping DLNA from Home, widget or Quick Settings can find the M5 automatically before starting;
- transient network failures do not immediately become alarming terminal errors;
- technical setup and recovery remain available in Settings/Diagnostics;
- existing radio and renderer behavior remains intact.
