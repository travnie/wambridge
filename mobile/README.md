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
- a Quick Settings tile: tap toggles the renderer, long-press opens settings;
- a daily-driver Home screen with shared Now Playing state, play/pause, mute and raw-volume
  controls, plus the M5's three speaker-owned physical Radio presets;
- an in-app speaker remote plus two explicit home-screen widget choices: a compact
  DLNA toggle and a full remote; the app and remote widget share play/pause, mute and
  raw-volume routing for radio and native speaker playback;
- optional launcher-icon hiding;
- native TuneIn preset browsing and safe playback through the speaker CPM API, with station
  artwork/metadata when TuneIn exposes it and play/pause, mute, raw-volume and confirmed Stop
  controls on the standalone screen;
- saved direct radio stations with optional TuneIn station IDs, resolved at play time ahead of
  ordered fallback URLs and relayed locally by the phone; a fresh install starts with the
  BBC Radio 1, Trójka and Czwórka bundle;
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

Home renders Now Playing from the same runtime snapshot used by renderer/radio controls and
shows exactly three physical preset slots read from the M5. These are the speaker-owned
`kind=speaker` presets cycled by the physical Radio button, not another local favourites list.
Radio renders the same runtime preset snapshot above Saved stations and TuneIn Explore.

The slots are read-only in this release. Android can play them through the already measured
`SetPlayPreset` path, but preset editing stays disabled until the write-side
`SetSavePreset`/`SetMovePreset` behavior is hardware-validated on the physical M5.

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
timer without waking the M5. The software path is implemented, but that one-second standby
sequence remains hardware-unverified until the physical M5 checklist is completed.

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
