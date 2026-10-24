# Codebase Concerns

## 1) Top Risks (Prioritized)

| Severity | Concern | Evidence | Impact | Suggested action |
|---|---|---|---|---|
| high | Dead `SetUrlPlayback` target can wedge recovery commands until power-cycle | `samsung.py`, `SamsungWamChannel.kt` | user-visible hard failure | preserve reachability guard on every URL-offer path |
| high | Physical firmware is stateful and timing-sensitive; extra 55001 clients/CPM bursts change behavior | `docs/WAM_PROTOCOL.md`, `AGENTS.md` | regressions can look nondeterministic | keep one connection where possible and validate transport changes on M5 |
| medium | Large transport/lifecycle state machines concentrate responsibility | `pcm_cli.py`, `foo_out_wam.cpp`, `UpnpRenderer.kt` | risky edits and race regressions | change narrowly and pin every observed failure with tests |
| medium | Compatibility evidence is one physical M5 (`SPK-WAM550`) | `README.md`, `docs/WAM_PROTOCOL.md` | behavior on WAM551 and other WAM units is unknown | stay physical-M5/WAM550-first; generalize only after measurements on additional hardware |
| medium | A historical clean run can be mistaken for current playback stability | `foobar/README.md`, `docs/DEVELOPMENT_STATUS.md`, physical PR reports | incorrect release or debugging decisions | date and qualify measured claims; link newer contradictory evidence rather than deleting old measurements |

## 2) Technical Debt
Finite share/DLNA playback works through the optional CLI and foobar context-menu
helper, but is not the default output transport; the foobar integration still needs
a physical pass, and native share seek/progress is not implemented. TuneIn preset
save/remove and moves across physical slots are still unproven on hardware.

## 3) Security Concerns

| Risk | Category | Evidence | Current mitigation | Gap |
|---|---|---|---|---|
| LAN control and audio are cleartext/no-auth by protocol | trust-boundary | `AndroidManifest.xml`, `samsung.py` | same-LAN design, random stream/control tokens, Android peer routing | hostile LAN can observe traffic; speaker API itself offers no auth |
| untrusted network/XML inputs | injection/resource abuse | `samsung.py` | host/XML-name validation, escaping, bounded 1 MiB reads, timeouts | keep every new raw command on these helpers |
| raw probe data may contain TuneIn partner/serial values | secret leakage | `catalogue.py`, `.gitignore` | scratch directories ignored; docs warn not to log `stationurl` | manual captures still require care |

## 4) Performance and Scaling Concerns
The product is latency-sensitive rather than throughput-scaled. The dominant delay lives in speaker buffering and TCP backpressure, not Python HTTP throughput. Local-/24 fallback discovery may probe up to 254 hosts with up to 64 workers. `STARTUP_SILENCE_MS` is intentionally 0; adding artificial startup buffering directly worsens audible latency.

## 5) Fragile/High-Churn Areas
Playback and lifecycle changes cluster in `foobar/foo_out_wam.cpp`, `src/wambridge/samsung.py`, `src/wambridge/pcm_cli.py` and Android service/activity code. Before editing them, read the dated experiments in `docs/DEVELOPMENT_STATUS.md` and `docs/WAM_PROTOCOL.md`, preserve measured conclusions, then run unit/source-contract checks and the relevant [physical M5 checklist](../foobar/README.md#physical-m5-checklist). Avoid treating an old churn snapshot or a green CI build as proof of hardware behavior.

## 6) Resolved Project Decisions
1. The physical M5 (`SPK-WAM550`) is the compatibility target now. WAM551 and other Samsung WAM models come later, after measured hardware evidence rather than speculative compatibility layers.
2. Python 3.14 is the current supported baseline across package metadata, Ruff and CI; older Python compatibility is not a project goal.
3. `foobar/README.md` is living documentation and should describe the current merged/validated state, not remain a historical PR #21 snapshot.

## 7) Evidence
- `docs/WAM_PROTOCOL.md`
- `docs/DEVELOPMENT_STATUS.md`
- `foobar/README.md`
- `.github/workflows/build.yml`
- scan/churn from `git log` on `origin/main`
