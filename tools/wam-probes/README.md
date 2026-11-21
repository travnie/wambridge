# WAM probes

One-shot diagnostic scripts that produced the measured facts in
`docs/WAM_PROTOCOL.md`. They are kept because physical M5 measurements are expensive and
several probes disproved assumptions that had already reached code or documentation.

These are not supported user tools. Comments may be in Polish and the test suite does not
cover them.

## Configuration

| Variable | Meaning | Default |
| --- | --- | --- |
| `WAM_SPEAKER` | speaker address | `192.168.1.50` (the M5 has since moved to another subnet - always pass this) |
| `WAM_HOST` | this machine as seen by the speaker | `192.168.1.10` |
| `WAM_MEDIA` | audio file | `sample.mp3` |
| `WAM_SCRATCH` | generated files | `_scratch/` |

## What the probes settled

| Script | Result |
| --- | --- |
| `probe_share.py` | Share playback requires raw client UUID and `/DLNA/<objectid>` |
| `probe_playertype.py` | `playertype`/`sourcename` do not decide success |
| `probe_dlna_headers.py` | Share playback requires the measured DLNA response shape |
| `probe_confirm_audio.py` | Working share configuration was confirmed by ear |
| `probe_formats.py` | Share path plays FLAC through 96/24 and MP4 with Range |
| `probe_livestream.py` | `SetUrlPlayback` accepts streams with no known length |
| `probe_backpressure.py` | Speaker HTTP throughput converges toward real time without `-re` |
| `probe_clock_drift.py` | A run settles near 1.00x; the old `+21..23 s` number includes startup and is not a speaker-buffer target |
| `probe_rung1_reads.py` | Rung 1 read sweep; three EQ commands and `SpkInGroup` are silent on this firmware |
| `probe_radio_browse.py` | The radio catalogue is a tree walked with `GetSelectRadioList`; browsing leaves the submode untouched |
| `probe_cp_escape.py` | `cp` is the submode URL playback runs in, and `SetFunc bt` then `SetFunc wifi` returns the speaker to `dlna` |
| `probe_radio_search.py` | `SearchQuery` works and is paginated; results mix categories, stations (`s…`) and podcast episodes (`t…`) |
| `capture.py` | Produced the preserved official-client session in `capture.log` |
| `wamtap.py` | Standalone event and port diagnostics |

## Interpretation rules

- Share/DLNA verdicts use `StartPlaybackEvent`.
- URL/PCM does not hard-gate on that event. Physical foobar runs were audible after
  `AUDIO_STARTED` without a matching event before the former timeout.
- Do not run `probe_clock_drift.py`, `wamtap sniff` or another 55001 listener beside active
  `pcm_cli`; the extra connection can disrupt playback.
- `cp` is not damage. It is the submode `SetUrlPlayback` runs in, audible playback and all, and
  `SetFunc bt` then `SetFunc wifi` returns the speaker to `dlna`. An earlier revision of this
  file blamed `probe_radio_browse.py` for stranding the speaker there; re-measured, the whole
  browse leaves the submode alone.
- While in `cp`, `GetPlayStatus` omits `playstatus` entirely and `GetMusicInfo` answers
  `errCode: Wifi Sub Mode is CP`. Neither means playback failed.
- The CPM endpoint wedges under a fast series of calls: empty lists first, then 20-30 s of
  silence on `CPM` while `UIC` still answers. An empty list is not evidence of an empty level.
- A process-start-relative drift value includes discovery, URL handoff and helper startup.
  Do not publish it as a measured M5 cushion.
- `ReadTransferCount` and the tested process I/O counters are unusable on the current test
  machine. Use beefweb position, process trees and TCP connection state instead.

## capture.log

The preserved Samsung Multiroom session is not reproducible with the equipment still
available. Identifiers and addresses were replaced consistently; timings, protocol fields
and track titles remain intact.


## Native control latency experiment (hardware pending)

The foobar PCM helper already routes pause/resume through raw M5 volume on its
existing port-55001 connection. It retains paced PCM silence as the fallback.
Do **not** replace this with UIC pause (acknowledged but inaudible on URL/PCM)
or SetMute (closes the active HTTP pull on the measured M5). Stop/discard must
still release the speaker session without opening another control connection.

Every authenticated helper command now logs, for example:

    INFO: WAMBRIDGE CONTROL_TIMING command=pause dispatch_ms=125.000

This measures time inside the helper's **serialized callback**, not time from
a foobar click, actual loudness change, or speaker acknowledgement in every
case. Pause/resume may wait up to one second for a volume response; regular
volume dispatch only sends. A successful dispatch is **not** an acoustic
success signal. No extra probe connection is opened.

Capture the *current, unmodified* playback as the baseline before experimenting
with stop/skip. Save foobar/helper logs separately before and after any code
change. Run the comparison offline (no M5 access needed for the script):

    python -m wambridge.control_latency baseline.log candidate.log

Manual audible measurements may instead be saved in either file as CSV:

    command,metric,ms
    pause,audible_ms,5030
    resume,audible_ms,5910

Use screen/audio recordings or another consistent synchronized method; record
from button press to **audible** transition. Repeat at least five trials per
command, on the same track, profile, volume, network and warmed-up conditions.
Keep the baseline and candidate methods identical. The report groups command
and metric separately and outputs sample counts, median, nearest-rank p95 and
candidate-minus-baseline median in milliseconds. Negative deltas mean faster.
Never compare a helper dispatch measurement to an audible one.

The historical 2026-08-08 roughly 5 s pause / 6 s resume observations describe
the **older PCM-only path**, not the current raw-volume pause implementation.
No current hardware acoustic baseline has been captured. Keep raw M5 volume
at 3 or below for initial physical tests. Before shipping any stop/skip
transport change, run complete track, seekbar, next track, pause/resume,
stop/change and clean-shutdown checks on the physical M5.
