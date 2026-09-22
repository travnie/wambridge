# Android Radio MediaSession Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans and test-driven-development task-by-task.

**Goal:** Give WAM Bridge radio proper Android system media controls, lock-screen/headset/Bluetooth actions and a MediaStyle notification backed by one RadioService-owned MediaSession, while leaving DLNA playback owned by the external player.

**Architecture:** Use the framework `android.media.session.MediaSession` and `android.media.session.PlaybackState` already available at minSdk 26. `RadioService` is the only MediaSession owner. It projects the existing `SpeakerStateStore` / radio runtime state into Android media state and routes session callbacks back through the existing RadioService actions. `RendererService` never creates or activates a MediaSession.

**Spec:** `docs/superpowers/specs/2026-09-22-android-vnext-design.md`

## Global constraints

- No new AndroidX/media runtime dependency for this slice.
- MediaSession is active only for WAM Bridge radio ownership/start/recovery.
- DLNA renderer playback remains represented by the external player that started it.
- Session callbacks reuse existing `ACTION_TOGGLE_PAUSE`, `ACTION_STOP`, mute/volume routing where Android exposes them; no second playback-control implementation.
- Notification remains foreground-service-safe.
- Release/deactivate the session on radio teardown and service destruction.
- Final verification: `cd mobile && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`.

## Review focus

1. Starting DLNA alone must not create/activate a WAM Bridge MediaSession.
2. Pause/resume from headset/lock screen must drive the same RadioService state as notification controls.
3. Recovery/start states must not claim `PLAYING` before radio ownership actually reaches playback.
4. Stopping radio deactivates the session so stale system controls disappear.
5. Notification compact actions and MediaSession token remain synchronized.

### Task 1: Add pure radio → Android playback-state mapping

**Files**
- Create `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/RadioMediaState.kt`
- Create `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/RadioMediaStateTest.kt`

**TDD cases**
- starting/recovering → BUFFERING
- running + paused → PAUSED
- running + not paused → PLAYING
- inactive → STOPPED
- allowed actions contain play/pause/stop appropriately

Keep Android constants behind a tiny pure result model if necessary so JVM tests remain framework-light.

### Task 2: Add one RadioMediaSession owner

**Files**
- Create `RadioMediaSession.kt`
- Create `RadioMediaSessionContractTest.kt`

**Responsibilities**
- construct `MediaSession(context, "WAM Bridge Radio")`;
- callbacks dispatch existing RadioService intents/actions;
- publish metadata title/station/source from shared snapshot/current station;
- publish PlaybackState from Task 1;
- expose session token for notification;
- activate only while radio owner is active;
- deactivate/release on teardown.

### Task 3: Integrate RadioService lifecycle and notification

**Files**
- Modify `RadioService.kt`
- Extend service-state/contract tests.

**Steps**
- create session in `onCreate`;
- update session from the same `publishRuntimeState` path that updates `SpeakerStateStore`;
- add media-session token to `Notification.MediaStyle`;
- release in `onDestroy`;
- ensure `stopRadio` publishes STOPPED and deactivates the session.

### Task 4: System-control polish

**Files**
- Modify notification/controller code only as needed.
- Tests pin callbacks and no RendererService MediaSession.

Verify:
- lock-screen/headset play/pause;
- stop;
- notification compact action selection;
- no MediaSession references in RendererService.

### Task 5: Docs/review/PR

Update `mobile/README.md`, `docs/ANDROID_VNEXT.md`, and `docs/DEVELOPMENT_STATUS.md`.

Branch: `feat/android-radio-mediasession`

PR title: `feat(android): add radio MediaSession controls`

Hardware follow-up: start a radio station on the physical M5 and verify notification, lock-screen and Bluetooth/headset play/pause/stop without disturbing DLNA ownership.
