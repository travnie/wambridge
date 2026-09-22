# Android Sleep Timer + Standby Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans and test-driven-development task-by-task.

**Goal:** Add speaker-side 15/30/45/60 minute sleep timers, timer cancellation/status, and a clean “Standby now” action without opening a competing M5 control connection.

**Architecture:** Keep the M5 authoritative. Use the measured `SetSleepTimer` / `GetSleepTimer` protocol, with one shared semantic builder for `option` + `sleeptime`. When radio or renderer owns the WAM control channel, route timer mutation through that owner service. Only idle mode may use a direct request. “Standby now” first releases app-owned playback, then arms the measured speaker timer with a one-second countdown so nothing in WAM Bridge can immediately reassert playback.

**Measured protocol:** `sleeptime` is seconds; `option=start` arms; `option=off,sleeptime=0` clears; the timer clears itself after firing; `GetSleepTimer` reports current state but cannot reveal why an already-sleeping speaker went dark. Commands must not add `pwron`.

**Spec:** `docs/superpowers/specs/2026-09-22-android-vnext-design.md`

## Constraints

- Presets: 15 / 30 / 45 / 60 minutes plus Off and Standby now.
- Accept 0..86400 seconds internally, matching the measured desktop bound.
- No second control connection while RadioService or RendererService owns port 55001.
- Do not claim an exact remaining countdown when `GetSleepTimer` cannot be read safely.
- Standby now must release active WAM Bridge playback ownership before the final timer command.
- No alarm/cloud/background scheduler is needed for the actual sleep event; the speaker owns the countdown.
- Final verification: `cd mobile && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`.

## Review focus

1. `0` cancels; it never means immediate standby.
2. Minutes are converted to seconds exactly once.
3. Active radio/renderer receives timer commands through its existing channel.
4. Idle direct control uses shared discovery/gating.
5. Standby now cannot be followed by WAM Bridge reconnecting itself.
6. UI labels distinguish speaker-confirmed status from local “request sent” state.

### Task 1: Shared sleep-timer protocol model

Create `SleepTimerCommand.kt` + JVM tests.

- `sleepTimerCommand(seconds)` validates 0..86400.
- returns `option=off, seconds=0` for zero.
- returns `option=start` for positive values.
- `sleepTimerSeconds(minutes)` covers 15/30/45/60 without duplicating conversion in UI.

### Task 2: Add measured WAM read/write operations

Modify `SamsungWamChannel.kt` and `SpeakerRemote.kt`.

- persistent channel: `setSleepTimer(seconds)` using shared semantics, no `pwron`;
- idle HTTP path: set + `GetSleepTimer` read;
- parse `sleepoption` and `sleeptime`;
- add JVM/source contract tests pinning method/argument spelling.

### Task 3: Route through the current owner

Add `SleepTimerControls.kt` and service actions.

- RadioService: `ACTION_SET_SLEEP_TIMER`, seconds extra, send on its active channel.
- RendererService: same concept on its active WAM channel.
- idle: resolve speaker under existing gates and use direct request.
- cancellation routes exactly the same way with seconds=0.
- never probe/discover through a second client while an owner is active.

### Task 4: Standby now

Implement controlled sequence:

1. request Stop from active renderer/radio;
2. wait bounded time for ownership release;
3. resolve current M5;
4. arm `SetSleepTimer(option=start,sleeptime=1)`;
5. do not restart a prior owner.

Mark the one-second value as hardware-validation-sensitive until the physical M5 confirms it reaches standby as expected.

### Task 5: Home/Settings UI

- Home quick action opens a small timer chooser: 15 / 30 / 45 / 60 / Off / Standby now.
- Settings Radio/Playback area shows current timer state when safely readable.
- no fake ticking local countdown unless backed by a read timestamp and clearly marked approximate.
- failures go through normal human-readable status.

### Task 6: Docs, CI, review

Update README, roadmap and development status with only measured/implemented claims.

Branch: `feat/android-sleep-timer`

PR title: `feat(android): add M5 sleep timer controls`

Hardware checklist:
- arm 15-minute timer and confirm `GetSleepTimer` state;
- cancel and confirm off/0;
- arm during native radio;
- arm during renderer ownership;
- Standby now reaches dark state and does not trigger WAM Bridge recovery/reconnect.
