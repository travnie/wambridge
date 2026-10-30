![Samsung](https://img.shields.io/badge/Samsung-1428A0?logo=samsung&logoColor=fff&style=for-the-badge) ![Python](https://img.shields.io/badge/Python-3776AB?logo=python&logoColor=fff&style=for-the-badge)

[![Release](https://github.com/travnie/wambridge/actions/workflows/release.yml/badge.svg)](https://github.com/travnie/wambridge/actions/workflows/release.yml) [![code license](https://img.shields.io/github/license/travnie/wambridge?label=OSI&logo=opensourceinitiative&logoColor=white&color=6f42c1&style=flat-square)](https://spdx.org/licenses/ISC) <a href="https://deepwiki.com/travnie/wambridge"><img src="https://img.shields.io/badge/DeepWiki-wambridge-blue.svg?logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACwAAAAyCAYAAAAnWDnqAAAAAXNSR0IArs4c6QAAA05JREFUaEPtmUtyEzEQhtWTQyQLHNak2AB7ZnyXZMEjXMGeK/AIi+QuHrMnbChYY7MIh8g01fJoopFb0uhhEqqcbWTp06/uv1saEDv4O3n3dV60RfP947Mm9/SQc0ICFQgzfc4CYZoTPAswgSJCCUJUnAAoRHOAUOcATwbmVLWdGoH//PB8mnKqScAhsD0kYP3j/Yt5LPQe2KvcXmGvRHcDnpxfL2zOYJ1mFwrryWTz0advv1Ut4CJgf5uhDuDj5eUcAUoahrdY/56ebRWeraTjMt/00Sh3UDtjgHtQNHwcRGOC98BJEAEymycmYcWwOprTgcB6VZ5JK5TAJ+fXGLBm3FDAmn6oPPjR4rKCAoJCal2eAiQp2x0vxTPB3ALO2CRkwmDy5WohzBDwSEFKRwPbknEggCPB/imwrycgxX2NzoMCHhPkDwqYMr9tRcP5qNrMZHkVnOjRMWwLCcr8ohBVb1OMjxLwGCvjTikrsBOiA6fNyCrm8V1rP93iVPpwaE+gO0SsWmPiXB+jikdf6SizrT5qKasx5j8ABbHpFTx+vFXp9EnYQmLx02h1QTTrl6eDqxLnGjporxl3NL3agEvXdT0WmEost648sQOYAeJS9Q7bfUVoMGnjo4AZdUMQku50McDcMWcBPvr0SzbTAFDfvJqwLzgxwATnCgnp4wDl6Aa+Ax283gghmj+vj7feE2KBBRMW3FzOpLOADl0Isb5587h/U4gGvkt5v60Z1VLG8BhYjbzRwyQZemwAd6cCR5/XFWLYZRIMpX39AR0tjaGGiGzLVyhse5C9RKC6ai42ppWPKiBagOvaYk8lO7DajerabOZP46Lby5wKjw1HCRx7p9sVMOWGzb/vA1hwiWc6jm3MvQDTogQkiqIhJV0nBQBTU+3okKCFDy9WwferkHjtxib7t3xIUQtHxnIwtx4mpg26/HfwVNVDb4oI9RHmx5WGelRVlrtiw43zboCLaxv46AZeB3IlTkwouebTr1y2NjSpHz68WNFjHvupy3q8TFn3Hos2IAk4Ju5dCo8B3wP7VPr/FGaKiG+T+v+TQqIrOqMTL1VdWV1DdmcbO8KXBz6esmYWYKPwDL5b5FA1a0hwapHiom0r/cKaoqr+27/XcrS5UwSMbQAAAABJRU5ErkJggg==" alt="DeepWiki"></a>

# WAM Bridge

<img src="https://images.samsung.com/is/image/samsung/pl_WAM550-EN_014_Front_black?$330_330_JPG$" width="128" alt="Samsung Shape M5"><img src="https://images.samsung.com/is/image/samsung/pl_WAM551-EN_014_Front_white?$330_330_JPG$" width="128" alt="Samsung Shape M5">

Windows-first bridge for streaming audio over Wi-Fi to Samsung Wireless Audio
Multiroom speakers, including Shape M5 (`WAM550`/`WAM551`).

The CLI serves a tokenized local stream and starts it through Samsung's
`SetUrlPlayback` API. The foobar2000 output component sends whatever foobar is
playing, including internet radio. Finite share/DLNA playback is available
through `wambridge-share` and foobar's **Send to WAM (Share)** track context menu,
outside the normal PCM output path. The CLI path is hardware-validated; the
foobar context-menu integration still needs a physical M5 pass.

Everything here was measured against one physical Shape M5 (`SPK-WAM550`,
firmware `WAM550WWB-3117.1`). Other models in the family are untested.

## Speaker compatibility

WAM Bridge uses Samsung's local UIC/CPM API on TCP `55001`; URL playback
uses `SetUrlPlayback` to make the speaker pull an HTTP audio stream.
Shared API support makes other models plausible candidates, but does not
establish working audio, timing or controls.

| Model | Evidence and WAM Bridge status |
| --- | --- |
| Shape M5 `SPK-WAM550`, firmware `WAM550WWB-3117.1` | Hardware-tested here; see the dated stability results below. |
| R1 `WAM1500` | Matching API documented in [independent R1 research](https://github.com/bacl/WAM_API_DOC). Candidate; untested here. |
| Shape M5 `WAM551`, M3 `WAM350/351`, M7 `WAM750/751` | Model entries in an [external WAM integration](https://github.com/balmli/com.samsung.wam/blob/master/lib/input_sources.js). Candidates; untested here. |
| Selected Multiroom soundbars, e.g. `HW-K650` / `HW-MS650` | API used by [K650](https://github.com/macbury/ha_samsung_multi_room) and [Multiroom](https://github.com/DaveGut/DEPRECATED-SmartThings_Samsung-WiFi-Audio-Unofficial) integrations. Candidates; WAM Bridge playback untested. |
| Other brands or devices without this Samsung API | Require a separate transport implementation. Wi-Fi or DLNA support alone is insufficient. |

Without owning another model, compare its protocol captures, integration code,
firmware notes and codec support. Owner-supplied read-only API responses can
confirm individual commands; they cannot confirm audible playback. External
integration support is evidence to investigate, not a WAM Bridge support claim.

Model-specific differences may include volume range, standby, events, codecs and
buffering. The measured M5 uses raw volume steps `0..30`; the K650 integration
documents `0..100`. Do not assume one model's volume scale applies to another.
An API `ok` response alone does not prove that audio plays.

To mark a model as tested, record its exact model and firmware, playback path
(Android radio/renderer, foobar PCM or Share), format and a physical playback
result: complete track at wall-clock speed, controls, next track and clean
shutdown. Validate each path separately against the
[protocol acceptance checklist](docs/WAM_PROTOCOL.md#safety-and-acceptance).

## Status: working [rolling release](https://github.com/travnie/wambridge/releases/tag/latest)

Both paths play audio on real hardware. The foobar component passed its full
physical checklist on 2026-08-02: a complete 213-second track start to finish at
a median 1.00x with every sample between 0.9x and 1.1x, seek, pause and resume,
an unattended transition into the next track, internet radio across a 44.1 to
48 kHz switch, and a clean shutdown leaving no FFmpeg or helper behind.

A later full-track M5 recheck on 2026-10-21 did **not** reproduce that stability
on the then-current connection. The older successful run remains historical evidence;
see the [physical M5 status](foobar/README.md#physical-m5-checklist) for the newer
failed baseline and what still needs validation.

What works:

- SSDP discovery, saved devices resolved by stable device ID, playback control,
  custom radio stations and native TuneIn presets from the CLI.
- Android adapter with automatic speaker discovery, Wi-Fi handoff recovery, UPnP renderer,
  Quick Settings tile and widgets, plus native TuneIn browsing with artwork and speaker-side
  playback controls.
- foobar2000 2.x x64 output: `f32le → FFmpeg FLAC → local HTTP → speaker`.
- `Playback → WAM Bridge` with emergency stop, standby and raw volume steps.
- Configuration through `%LOCALAPPDATA%\WAMBridge\foobar.ini`.

### The one limitation worth knowing before you install

**The last end-to-end FLAC measurement was 6.7 seconds, before the host buffer was
cut by another two seconds.** On 2026-08-27 the physical M5 stayed stable with
`buffer_extra=0`: the host queue held about 1.9 s instead of 3.9 s. A fresh
ear-to-clock total has not been measured yet, so the old 6.7 s figure is historical
rather than a claim about current stock behavior.

Earlier clean runs showed a correct host clock and seekbar, but those results do not
establish uninterrupted speaker playback on the current network link. **Pause, stop and skip still carry pipeline latency** because they act
through the PCM path; with `hardware_volume=1`, the volume slider is routed to the speaker instead. Lowering
the bitrate makes the speaker prebuffer worse rather than better, and raising it helped by
about a second in the older measurements. The remaining latency-sensitive controls belong
on the speaker's own `55001` command path, which answers in about a second, rather
than to keep shortening the audio path; that work is in progress.

Everything else is documented honestly, including the approaches that failed:

- helper isolation (PR #2) and the output clock (PR #21) are merged,
- manual pacing (PR #4) and the large share experiment (PR #7) are closed, with
  their measured conclusions kept.

Current architecture, failed approaches and continuation notes are in
[`docs/DEVELOPMENT_STATUS.md`](docs/DEVELOPMENT_STATUS.md). Measured protocol
facts from a physical `SPK-WAM550` are in
[`docs/WAM_PROTOCOL.md`](docs/WAM_PROTOCOL.md). For the maintained guides,
checklists and publishing instructions, use the [documentation index](docs/README.md).

## Foobar2000 output

Normal rolling release:
[`latest`](https://github.com/travnie/wambridge/releases/tag/latest), rebuilt whenever tested
`main` moves. It is a regular GitHub Release, not a pre-release. The tag and download links
stay put while the component/APK assets are replaced from the same commit. The displayed
version still comes from `version` in `pyproject.toml`; Android `versionCode` keeps rising
independently for upgrades.

Each rolling release contains:

- `foo_out_wam.fb2k-component` - the foobar2000 output
- `wambridge-<version>.apk` - the signed Android adapter
- a source archive

Open the `.fb2k-component` file with foobar2000 2.x x64, then select:

```text
Preferences → Playback → Output → Samsung M5 (Wi-Fi)
```

This project is built primarily against one physical M5. The core transport has
repeated hardware validation and Android now covers discovery, renderer/radio controls,
MediaSession, widgets and speaker-side sleep controls, but other Shape models remain largely
untested and some hardware-specific follow-up is still documented. If you own a Shape speaker
and were looking for exactly this, read the limitation above and the release notes first.

Configuration, known limitations and the physical checklist are documented in
[`foobar/README.md`](foobar/README.md).

## Requirements

- Python 3.14+
- FFmpeg available in `PATH`
- computer and speaker reachable in the same LAN
- Windows 10/11 and foobar2000 2.x x64 for the native output component
- Windows Firewall access for the bridge on private networks

## Install the CLI

```powershell
git clone https://github.com/travnie/wambridge.git
cd wambridge
py -m venv .venv
.\.venv\Scripts\Activate.ps1
py -m pip install -e .
```

Confirm FFmpeg is visible:

```powershell
ffmpeg -version
```

## Discover speakers

```powershell
wambridge --discover
```

Discovery sends repeated SSDP requests through active IPv4 adapters. When old
firmware stays silent, it can fall back to checking Samsung's API on port
`55001` in nearby `/24` networks.

Useful diagnostics:

```powershell
wambridge --discover --verbose
wambridge --discover --interface 192.168.1.25
wambridge --discover --no-scan
```

## Saved devices

Save a speaker under an alias instead of relying on its DHCP address:

```powershell
wambridge --speaker 10.0.0.118 --remember M5
wambridge --list-devices
wambridge --device M5 --probe
wambridge "D:\Music\track.opus" --device M5
```

The profile stores the stable Samsung `device_id` and caches the latest working
IP. If the address changes, WAM Bridge searches the LAN for the same device and
updates the profile.

On Windows profiles are stored in:

```text
%LOCALAPPDATA%\WAMBridge\devices.json
```

The foobar2000 component passes the configured alias to the same helper and
profile resolver.

Remove a saved profile:

```powershell
wambridge --forget M5
```

## Startup volume safety

The tested Shape M5 firmware uses raw API volume steps `0..30`. Values above 30
are silently clamped to maximum while still returning success. The current
client has not yet implemented model-aware percentage conversion, so treat
volume arguments as raw M5 steps:

- `3` is approximately 10 percent,
- `6` is approximately 20 percent,
- `30` is maximum.

Old WAM firmware may jump to a high level while switching to URL playback. WAM
Bridge mutes the speaker, starts the stream with 1.5 seconds of silence and then
applies the requested bounded step after decoding begins.

Choose a cautious explicit level:

```powershell
wambridge "D:\Music\track.opus" --device M5 --volume 3
```

Change only the startup ceiling while preserving quieter current settings:

```powershell
wambridge "D:\Music\track.opus" --device M5 --max-start-volume 3
```

## Remote control

```powershell
wambridge --device M5 --status
wambridge --device M5 --set-volume 3
wambridge --device M5 --mute
wambridge --device M5 --unmute
wambridge --device M5 --pause
wambridge --device M5 --play
wambridge --device M5 --stop
wambridge --device M5 --standby
```

Native providers such as TuneIn use their CPM commands. DLNA uses UIC pause and
resume. Samsung URL playback cannot be resumed reliably, so starting the source
again creates a new session.

## Radio stations

Save a direct HTTP or HTTPS audio stream. Fallback URLs are tried in order:

```powershell
wambridge --radio-add paradise "https://primary.example/radio.mp3" `
  "https://backup.example/radio.ogg"
wambridge --radio-list
wambridge --radio-play paradise --device M5 --volume 3
wambridge --radio-remove paradise
```

A station can also carry its TuneIn id. The id is resolved when the station is played, so it
follows the broadcaster if the stream address moves - the URLs saved beside it stay as
fallbacks for when TuneIn cannot be reached or offers nothing usable:

```powershell
wambridge --radio-add trojka "http://41.dktr.pl:8000/trojka.ogg" --tunein-id s15984
```

Station definitions are stored in:

```text
%LOCALAPPDATA%\WAMBridge\stations.json
```

Import the bundled BBC Radio 1, PR3 Trójka and PR4 Czwórka pack:

```powershell
wambridge --radio-import top3
wambridge --radio-list
wambridge --radio-play bbc1 --device M5 --volume 3
wambridge --radio-play trojka --device M5 --volume 3
wambridge --radio-play czworka --device M5 --volume 3
```

These are WAM Bridge stations and do not overwrite the three presets selected
by the physical button on the speaker.

## Native TuneIn presets

Read and start TuneIn presets already stored by the speaker:

```powershell
wambridge --device M5 --tunein-list
wambridge --device M5 --tunein-play 0 --volume 3
wambridge --device M5 --tunein-play "Radio Paradise" --volume 3
```

Preset write commands are available: adjacent `my` moves were hardware-tested,
but saving, removing and moving into physical slots 0-2 remain unverified. Back up
`--tunein-list` first; `--tunein-remove` has no undo:

```powershell
wambridge --device M5 --tunein-save
wambridge --device M5 --tunein-remove 3
wambridge --device M5 --tunein-move 1 2 0
```

Android has no equivalent yet; writing presets from the phone still goes
through Samsung's own app.

## Browsing and searching TuneIn

The whole TuneIn catalogue is reachable without saving anything as a preset.
`--tunein-browse` walks the tree; a path is the row numbers of the pages above,
separated by slashes. `--tunein-search` finds a station by name. Both are
paginated, so `--tunein-start` moves the window.

```powershell
wambridge --device M5 --tunein-browse
wambridge --device M5 --tunein-browse 1/0
wambridge --device M5 --tunein-search "Radio Paradise"
wambridge --device M5 --tunein-search "jazz" --tunein-start 30
```

Rows print as `index<TAB>kind<TAB>mediaid<TAB>title`. The `mediaid` is the stable
TuneIn station id, so it is what goes into `--radio-add --tunein-id`; the leading
index only means "this row on this page" and changes as you move.

The speaker keeps the browse cursor itself, so a search leaves it in TuneIn's
search results rather than the catalogue. `--tunein-browse` notices and steps
back on its own.

## Direct playback

```powershell
wambridge --speaker 192.168.1.50 --probe
wambridge "https://example.net/radio-stream" --speaker 192.168.1.50
wambridge "D:\Music\track.opus" --speaker 192.168.1.50
wambridge "D:\Music\track.ogg" --speaker 192.168.1.50
```

Use MP3 output when FLAC is unstable on a particular firmware:

```powershell
wambridge "D:\Music\track.opus" --speaker 192.168.1.50 --format mp3
```

When exactly one WAM speaker is discovered, `--speaker` may be omitted.

## Notes

- The local URL stream uses HTTP/1.0 without chunked transfer for old-firmware
  compatibility.
- The URL contains a random session token and exists only while the command is
  running.
- Do not expose port `55001` or a bridge HTTP port to the internet.
- The tested M5 does not expose a standard UPnP AVTransport renderer. See the
  protocol notes before restarting generic UPnP work.
- `SetUrlPlayback` may freeze malformed firmware when the served body is not
  playable audio. The bridge exposes only FFmpeg output and returns `404` for
  other paths.

## Validate

```powershell
py -m unittest discover -s tests -v
```

---

## 📰 Mininewsy

<!--README_FEED:START-->
- [Star History Monthly October 2026 | Execution](https://www.star-history.com/blog/execution)
- [Remont się kończy, a piesi będą dalej ryzykować? Radni walczą o brakujący chodnik w Libiążu - Przelom.pl - portal ziemi chrzanowskiej](https://news.google.com/atom/articles/CBMiogFBVV95cUxONncyYU05SWZkVURkNDE5Sk9wZk1uMmNrN1VCUmRralZJTTZkbTlCUnFHbDN0UExxRGxoY3A3QUdUTVRGRENVWUV1dFFVM01uY3VUbTJhc0NubExOazctNUxDVTlaYXo0d3NKMnNkekY4SkRRa3VHTU5ZdHpxQ1Z5VFFVNFpybVlUWkkwX2NqWi0wNFFpWjRWdUlkYWg1M19TVWc?oc=5)
- [The Elder Scrolls VI pokażą już za chwilę? Data i tytuł wydają się pewne](https://antyweb.pl/the-elder-scrolls-vi-pokaza-juz-za-chwile-data-i-tytul-wydaja-sie-pewne)
- [Za te funkcje w przeglądarce kilka lat temu dałbym wszystko](https://antyweb.pl/za-te-funkcje-w-przegladarce-kilka-lat-temu-dalbym-wszystko)
- [Twój telewizor Samsung może cię podsłuchiwać? Niepokojące ustalenia badacza](https://antyweb.pl/masz-ten-telewizor-uwazaj-na-podsluch)
- [Ile kosztują chryzantemy w Jaworznie? Sprawdziliśmy przed dniem Wszystkich Świętych. - jaw.pl](https://news.google.com/atom/articles/CBMiZ0FVX3lxTE1MN3lCRTZ0MXFVV0FiSzd0aEFDSnFHTTlYQ0p4VnR6Mm9wNExnQUdsMEdJS2JhRUNDNlNKbzNfdkhyUlpsNUtYaC03UjhMSmlWYUJGaUhxWklPQVZwNFJIWDcxT0xRN00?oc=5)
<!--README_FEED:END-->

## 💬 Cytat z szuflady

<!-- markdownlint-disable MD033 -->
<!--STARTS_HERE_QUOTE_README-->
<i>❝In Windows 98, minimized windows are actually moved far away outside the average monitor’s resolution.❞</i>
<!--ENDS_HERE_QUOTE_README-->
<!-- markdownlint-enable MD033 -->

## Python package / PyPI

The Python CLI is prepared for public release as `trvny-wambridge`. Build artifacts and Trusted Publishing setup live in [PyPI publishing guide](docs/PUBLISHING.md). After the first upload to PyPI, install with `uv tool install trvny-wambridge`; the foobar2000 plugin remains a separate `.fb2k-component` release.
