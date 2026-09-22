# Android Settings + Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans and test-driven-development task-by-task.

**Goal:** Turn Settings into a clean user-facing control surface, move manual IP/protocol details under Advanced, and add a human-readable Diagnostics screen with copyable state and controlled connection recovery.

**Architecture:** Keep the existing classic Views / Activity structure. `MainActivity` keeps the three root panes. Add two deeper Activities: `DiagnosticsActivity` for read-only runtime/network state plus recovery actions, and `AdvancedSettingsActivity` for manual IP and low-level renderer/service controls. Reuse `SpeakerStateStore`, `SpeakerTarget`, `WifiLan`, `SpeakerControlGate`, `RendererService`, and `RadioService`; do not create a second diagnostics state model.

**Spec:** `docs/superpowers/specs/2026-09-22-android-vnext-design.md`

## Global constraints

- Settings stays a normal-user screen; raw IP and protocol details move to Advanced.
- Diagnostics reads existing runtime/network state. It does not poll the M5 continuously.
- `Copy diagnostics` copies one deterministic plain-text report.
- `Fix connection` performs a controlled stop → rediscover/probe → refresh sequence on a background executor.
- Do not scan while radio/renderer still owns the M5 control channel.
- Direct playback controls remain on Home; do not duplicate them in Settings.
- No Compose, database, account/cloud state, or new runtime dependencies.
- Final verification: `cd mobile && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`.

## Review focus

1. Manual IP is no longer visible in normal Settings but remains reachable under Advanced.
2. Diagnostics survives missing Wi-Fi/speaker state and prints useful placeholders instead of crashing.
3. Fix connection stops active owners before discovery and never blocks the UI thread.
4. Copy diagnostics excludes secrets and includes only app/speaker/network/runtime facts already available locally.
5. Existing Quick Settings / launcher controls remain reachable.

### Task 1: Add pure diagnostics report model

**Files**
- Create: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/DiagnosticsReport.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/DiagnosticsReportTest.kt`

**Behavior**
- Build a deterministic report from `SpeakerSnapshot`, preferred Wi-Fi endpoint, app version, renderer phase/status, radio state, and last error.
- Expose both structured rows for UI and `toPlainText()` for clipboard.
- No Android network calls in the pure formatter.

**TDD**
- RED tests for idle/no-Wi-Fi, active radio, active renderer, fallback/error text, stable line ordering.
- Implement minimal formatter.
- GREEN focused tests.

### Task 2: Add DiagnosticsActivity

**Files**
- Create: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/DiagnosticsActivity.kt`
- Modify: `mobile/app/src/main/AndroidManifest.xml`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/DiagnosticsSurfaceContractTest.kt`

**UI**
- Speaker: IP, device ID, discovery state.
- Network: preferred Wi-Fi endpoint/address.
- Runtime: owner, playback, renderer phase/status, radio status, active source/fallback.
- Last error.
- Buttons: `Copy diagnostics`, `Fix connection`.

**Fix connection**
- Background executor only.
- Stop renderer if busy and wait for release using existing service state.
- Stop radio if active and wait for release.
- Run `SpeakerTarget.resolveDetailed(forceDiscovery = true)`.
- Publish accepted result through existing state APIs.
- Render success/failure and refresh the report.

### Task 3: Add AdvancedSettingsActivity and move manual IP there

**Files**
- Create: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/AdvancedSettingsActivity.kt`
- Modify: `mobile/app/src/main/AndroidManifest.xml`
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt`
- Extend contract tests.

**Advanced contains**
- manual IPv4 field;
- Save + test;
- explicit renderer Start / Stop;
- current technical renderer status.

Reuse current MainActivity implementations rather than duplicating validation rules.

### Task 4: Simplify root Settings

**Files**
- Modify: `MainActivity.kt`
- Extend Settings contract tests.

**Root Settings sections**
- Speaker: model/status, Discover, Test connection.
- Playback & system: renderer status/start-stop, Quick Settings, launcher visibility.
- Radio: Physical presets, Saved stations.
- Diagnostics: Diagnostics, Advanced.

Remove the duplicate direct speaker-control card because Home owns playback controls.

### Task 5: Docs, final review and PR

**Files**
- Modify: `mobile/README.md`
- Modify: `docs/DEVELOPMENT_STATUS.md`

**Validation**
- Full Gradle validation.
- Scope diff.
- Open PR `feat/android-settings-diagnostics` titled `feat(android): add Settings diagnostics and advanced setup`.
- Review CI/comments once per new head, not by tight polling.
- Squash only with final-head green checks and no actionable threads.
