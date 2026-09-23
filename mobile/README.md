![DLNA](https://img.shields.io/badge/DLNA-48A842?logo=dlna&logoColor=fff&style=for-the-badge) ![Android](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=fff&style=for-the-badge)

# WAM Bridge Mobile Adapter

Android-first adapter for exposing Samsung WAM speakers to mobile players without changing the existing WAM Bridge playback/research code.

## Scope

- Uses measured WAM behavior as a protocol specification, not as a code dependency.
- Confirmed target: Neutron Music Player -> UPnP/DLNA -> Samsung Shape M5; other local UPnP/DLNA players can use the same renderer.

## Current state

The Android adapter provides:

- WAM speaker autodiscovery via SSDP with prefix-aware LAN fallback; starting the renderer
  from the app, Quick Settings tile or widget resolves the saved target and discovers it
  automatically when needed;
- endpoint-aware Wi-Fi recovery: discovery, WAM control and the local renderer/radio proxy are
  bound to the same Android `Network` + IPv4 target; a changed network identity triggers a
  rebuild/reconnect even when DHCP reuses the same address, while temporary Wi-Fi loss waits
  and retries instead of silently abandoning the requested session;
- UPnP MediaRenderer services: AVTransport, RenderingControl and ConnectionManager;
- a local WAV/LPCM, MP3 and FLAC proxy handed to the M5 through `SetUrlPlayback`;
- safe first-start volume capped at M5 raw step `3`;
- idle/session release so stopped playback does not keep the M5 awake;
- two Quick Settings tiles: the existing DLNA tile toggles the renderer, while the Radio tile
  starts the last/default station when idle and cycles the shared `station_packs.json` `top3`
  pack while radio is active;
- launcher long-press actions publish the same shared `top3` stations plus Stop and Standby,
  capped by the launcher's per-app shortcut limit;
- a daily-driver Home screen with shared Now Playing state, play/pause, mute and raw-volume
  controls, TuneIn artwork when the M5 exposes a thumbnail, plus the M5's three speaker-owned
  physical Radio presets;
- an in-app speaker remote plus three home-screen widget choices: a compact DLNA toggle,
  a full remote and a configurable 1×1 station button backed by the shared `top3` pack;
  the app and remote widget share play/pause, mute and raw-volume routing for radio and
  native speaker playback;
- optional launcher-icon hiding;
- native TuneIn preset browsing and safe playback through the speaker CPM API, with station
  artwork/metadata when TuneIn exposes it and play/pause, mute, raw-volume and confirmed Stop
  controls on the standalone screen;
- saved direct radio stations with optional TuneIn station IDs, resolved at play time ahead of
  ordered fallback URLs and relayed locally by the phone; mobile defaults now come from the
  shared `favorites` pack in `station_packs.json`, filtered by `mobile_supported`;
- radio favourites support pinning, drag ordering, recently played, a default station,
  Play last, duplicate/edit helpers and Storage Access Framework import/export for JSON,
  M3U and PLS;
- radio fallback routing remembers the last endpoint that actually opened, temporarily
  de-prioritizes failed endpoints for 15 minutes, and exposes active fallback position through
  the shared runtime/Diagnostics state without probing streams in parallel;
- direct radio requests ICY metadata on the same upstream connection, strips metadata blocks
  before forwarding audio to the M5, and publishes `StreamTitle` plus image-like `StreamUrl`
  artwork through the shared Now Playing state used by Home, the controls widget,
  MediaSession/notification and Diagnostics;
- Radio Paradise additionally uses its small now-playing JSON endpoint for track metadata and
  per-track cover art, refreshing from the API's remaining-track hint rather than polling the
  audio stream a second time;
- radio playback owns an Android MediaSession, so its play/pause/stop state can appear in the
  notification shade, lock screen and compatible headset/Bluetooth controls; DLNA still belongs
  to the external player that started it;
- speaker-owned sleep controls expose 15/30/45/60 minute presets, Off and Standby now; timer
  requests stay on the current radio/renderer control owner and confirmed state is read back
  from the M5 instead of running a phone-side countdown;
- Android 13+ notification permission is requested on launch so renderer/radio foreground
  controls can actually appear in the notification shade;
- an M5-style app/renderer icon exposed through UPnP for players such as Neutron;

Physical phone + M5 playback through Neutron is confirmed. The direct mobile radio relay intentionally rejects HLS and Ogg until a phone-side transcoding layer exists; the desktop bridge remains the fully transcoding radio path.

### Android navigation

The launcher now opens a persistent **Home · Radio · Settings** shell. Home is the default
daily-driver screen: Now Playing, shared play/pause/mute/raw-volume controls, connection state,
and three always-visible physical preset slots. Radio shows the same three slots above Saved
stations and TuneIn Explore; the slots are read directly from the M5 and match the speaker's
physical Radio button. This release intentionally keeps those physical slots read-only until
the write-side preset commands are hardware-validated. Settings keeps normal speaker/system
controls up front, while Diagnostics exposes copyable runtime/network state and a controlled
Fix connection action. Manual IP and dedicated troubleshooting controls live under Advanced. Switching
root destinations reuses the same panes instead of recreating Activities.

### Home and physical presets

Home renders Now Playing from the same runtime snapshot used by renderer/radio controls.
Native TuneIn playback carries the preset thumbnail in that shared snapshot, and Home reuses
the same Wi-Fi-bound, size-limited artwork cache as the standalone TuneIn browser. Saved radio
stations with a validated TuneIn station ID derive their logo from TuneIn's station CDN even
when playback falls back to a direct URL. Missing or failed artwork, and URL-only stations,
fall back to the app icon; artwork is never guessed from arbitrary stream URLs.

Home shows exactly three physical preset slots read from the M5. These are the speaker-owned
`kind=speaker` presets cycled by the physical Radio button, not another local favourites list.
Radio renders the same runtime preset snapshot above Saved stations and TuneIn Explore.

The slots are read-only in this release. Android can play them through the already measured
`SetPlayPreset` path, but preset editing stays disabled until the write-side
`SetSavePreset`/`SetMovePreset` behavior is hardware-validated on the physical M5.

### Radio favourites

The Android station library uses the shared `station_packs.json` `favorites` pack as its
default source instead of maintaining a second Kotlin list. User changes are overlays: hidden
bundled stations, pins, custom order, default station and recent history. Drag ordering is kept
locally while newly-added bundled stations append without resetting it.

JSON import/export preserves TuneIn IDs and ordered fallback URLs. M3U/PLS exchange the primary
direct URL for compatibility with normal playlist tools. Recent history is recorded only after
the radio proxy actually opens a source, so a failed tap does not become “last played”.

### Smart radio fallback routing

Fallback learning is passive: the app records only what the existing radio proxy already sees.
A successfully opened endpoint becomes the preferred candidate for the next start/recovery.
An endpoint that fails is moved behind healthy candidates for 15 minutes, while the original
station order stays the deterministic tie-breaker. No second HTTP probe is opened beside active
playback.

When a non-primary candidate is active, the radio status and shared speaker snapshot report
`fallback N/M`; Diagnostics therefore shows both the active source URL and fallback position.

### ICY now-playing metadata

For direct streams that return `icy-metaint`, the phone asks for ICY metadata with
`Icy-MetaData: 1`. The relay parses the interleaved metadata blocks, removes them from the
audio sent to the M5, and publishes `StreamTitle` into the existing shared speaker snapshot.
The same value therefore reaches Home, the expanded controls widget, MediaSession/system media
controls, the foreground notification and Diagnostics without opening a second HTTP client.

Streams without ICY metadata stay on the normal byte-for-byte relay path. Repeated identical
titles are suppressed, an empty `StreamTitle` clears stale track text, and metadata decoding
accepts UTF-8 with ISO-8859-1 fallback for older stations.

### Quick actions

Launcher shortcuts are generated at runtime from the shared `top3` station pack, so BBC1,
Trójka and Czwórka are not duplicated in Kotlin. Stop and Standby use the same app command
paths as Home. Android launchers impose a device-specific shortcut cap, so WAM Bridge publishes
as many of the ordered station/control actions as the launcher reports it can hold.

A second Quick Settings tile is radio-specific: when idle it starts last-played, then the
configured default, then the first `top3` station. While WAM Bridge radio is already active,
successive taps cycle through that same `top3` list. The original DLNA tile remains unchanged.

The 1×1 Station widget uses the same `top3` source. Each widget instance chooses one station
during setup and starts it with one tap; active playback is marked directly on the widget.
If a selected station later disappears from the local library, the widget falls back to a
Choose state instead of becoming a dead button.

### Radio system controls

RadioService owns a single Android MediaSession while radio is starting, recovering or playing.
The session mirrors the same radio runtime state used by Home and the foreground notification,
and routes system play/pause/stop actions back through the existing RadioService commands.
It becomes inactive when radio stops. Renderer/DLNA playback deliberately creates no WAM Bridge
MediaSession, so the external player remains the only system-media owner for DLNA.

### Sleep timer and standby

Home exposes **Sleep** with 15/30/45/60 minute presets, Off and Standby now. The M5 owns the
countdown through the measured `SetSleepTimer` / `GetSleepTimer` path. While radio or the
renderer owns port 55001, sleep commands stay inside that service instead of opening a competing
client; idle commands use the normal shared target/gate path. Settings shows speaker-confirmed
timer state when it can be read safely.

**Standby now** releases WAM Bridge radio/renderer ownership and then arms a one-second speaker
timer without waking the M5. The rolling release was hardware-checked on 2026-09-23: the physical
M5 front lamp went dark after Standby now. The longer 15/30/45/60-minute presets still need a
duration/readback pass.

### Settings and diagnostics

Settings keeps discovery, connection testing, renderer controls, Quick Settings, launcher
visibility and Radio entry points in the normal surface. **Diagnostics** reports speaker IP /
device ID, Android Wi-Fi endpoint, owner/playback/discovery, active source/fallback, renderer
and radio state, last error and app version. The report can be copied as plain text.

**Fix connection** stops active renderer/radio ownership, then runs the shared forced discovery
path and refreshes the report. Manual IPv4 setup and dedicated low-level troubleshooting are kept under **Advanced** instead
of occupying the daily Settings screen.

### Speaker discovery

All Android start surfaces use the same target resolution path:

1. verify the saved M5 address;
2. fall back to SSDP;
3. fall back to the bounded LAN scan;
4. persist the resolved address/device identity;
5. continue the requested action.

Automatic startup, manual Discover and renderer start share this resolver. The home-screen
widget and Quick Settings tile continue to start the renderer through `RendererService`, so
they inherit the same recovery path instead of owning network discovery themselves. Multiple
unmatched speakers require an explicit choice; manual IP remains available as an escape hatch.

## Architecture

```text
Local UPnP/DLNA player
            |
            v
 Android MediaRenderer facade
            |
            v
      local WAV proxy
            |
            v
  Samsung WAM control client
            |
            v
        Shape M5
```
