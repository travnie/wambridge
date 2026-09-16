# AGENTS.md

For playback/protocol work, first read `docs/WAM_PROTOCOL.md` and
`docs/DEVELOPMENT_STATUS.md`: living lab notes from repeated physical M5 tests,
including failed approaches and measurements superseding earlier assumptions.
Prefer newest measurements over older plausible explanations. This file covers
only easy-to-miss traps.

## Wambridge

### Expensive traps

- **`process_samples` must accept every offered frame.** It returns void, so
  dropped remainders cannot be reported. This bug finished a 220 s track in 22 s
  despite the pipe staying near 1.0x.
- Active PCM playback needs one owner of the persistent TCP `55001` connection
  and one FFmpeg owner of PCM stdin. Extra listeners/encoders have broken or
  starved working streams.
- `console::printf` uses pfc's formatter, not CRT. `%lu`/`%llu` lose the value;
  use `%u`/`%s`.
- In tests, model `Popen().stdout` with real `BytesIO`, not bare `MagicMock`.
- Unimplemented firmware commands get silence, not refusal; each costs a full
  timeout. On the M5, `GetPowerStatus`, `GetLedStatus`, `GetStandbyMode`,
  `GetFeature`, `GetPowerSaving`, `GetAutoPowerDown` and `GetSpkStatus` always
  time out; everything else answered in 0.02-0.2 s. `GetSpkStatus` was added
  2026-08-15 in that same run. Never let any of these decide reading success:
  `get_status` once did, reporting a healthy speaker as unreachable.
- For M5 liveness, still use `GetSpkName`, not `wambridge-control status`.
  Status no longer fails on healthy speakers but takes four round trips, five
  in normal idle submode `cp` (adds `GetRadioInfo`). Its `timeout` is per
  command, not total: unreachable speakers take several times that to report.
  One command answering in 0.14 s is a better test.
- Terminate timed-out child processes. Runaway FFmpeg has exhausted the 8 GB physical
  test machine.
- **Reporting failure throws `exception_output_invalidated`; foobar then
  creates a new output object.** Retry bounds cannot live there: each attempt
  gets a fresh object. Measured 2026-08-16, before the budget moved to file
  scope: 77 helper restarts in 90 seconds, continuing unaided at one death
  every 25 seconds. Each left a socket in `TIME_WAIT` to port 55001; at 29 sockets,
  the speaker stopped answering all commands. A helper that reached `PLAYING`
  then exited is different: the speaker ended a stream. Immediate restart is
  the recovery that works.

### Physical M5

Start hardware tests at raw volume step `3` or lower. Transport changes are
merge-ready after these pass on the physical M5: complete track, stable seekbar,
second track, pause/resume, stop/change and clean process shutdown.

## Code review rules

- Do not comment on README-only, documentation-only, changelog, formatting or
  cosmetic changes unless they introduce factual errors or break
  generated/validated content.
