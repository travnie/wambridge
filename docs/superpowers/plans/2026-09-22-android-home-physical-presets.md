# Android Home 3.5 + Physical Presets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder Home/Radio content with a daily-driver Now Playing surface and three speaker-owned physical Radio preset slots that read and play the M5's real presets.

**Architecture:** Keep the existing single-Activity Home · Radio · Settings shell and classic Android Views. Add one runtime `PhysicalPresetStore` plus blocking preset operations that callers run on the existing background executors. The M5 remains authoritative: the app derives slots 1-3 from `SamsungTuneIn.getPresets()`, never persists a second local copy, and uses the measured `SamsungTuneIn.playSafely()` path to start a preset. `SpeakerStateStore` remains the only shared playback/runtime state.

**Tech Stack:** Kotlin/JVM 17, classic Android Views, Android SDK 37, JUnit 4.13.2, existing `SamsungTuneIn`, `SpeakerControls`, `SpeakerStateStore`, `SpeakerControlGate`. No new runtime dependency.

**Spec:** `docs/superpowers/specs/2026-09-22-android-vnext-design.md`

## Global Constraints

- Home stays the default destination in the existing `MainActivity` shell.
- Exactly three physical preset slots are always represented on Home and Radio.
- Physical preset slots come from the M5's `kind=speaker` presets ordered by numeric `contentid`; no local duplicate persistence.
- Do not add preset editing/writes in this PR. `SetSavePreset`/`SetMovePreset` remain hardware-validation follow-up.
- Do not load or control the M5 on the Android main thread.
- Do not open a competing control connection while `RendererService` or `RadioService` owns the speaker.
- Existing deep Activities (`TuneInActivity`, `CatalogueActivity`, `RadioStationsActivity`) remain.
- Home controls continue to route through `SpeakerControls`; do not duplicate mute/volume/play-pause routing.
- No Compose migration, cloud state, account state, or new image-loading dependency.
- Final verification: `cd mobile && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`.

## Review Focus

1. **M5 returns more/fewer than three `speaker` presets:** render exactly three slots in numeric content order, using empty placeholders only for missing slots.
2. **Preset list also contains `my` presets:** never let them displace the physical three.
3. **Renderer/radio owns the M5 when Home refreshes:** preserve current cached slots and report busy; do not probe/read presets through a competing connection.
4. **Preset start succeeds but UI state is stale:** publish native TuneIn playing state to `SpeakerStateStore` before reporting success.
5. **Activity is destroyed while preset load/play finishes:** background completion must not touch dead views.

---

### Task 1: Add one runtime source of truth for physical preset slots

**Files:**
- Create: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/PhysicalPresetStore.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/PhysicalPresetStoreTest.kt`

**Interfaces:**
- Produces `PhysicalPresetSnapshot(slots, loading, error)`.
- Produces `PhysicalPresetStore.current()`, `publishLoading()`, `publishPresets()`, `publishError()`, `subscribe()`.
- Produces pure `physicalPresetSlots(presets): List<SamsungTuneIn.Preset?>`.
- Later tasks consume the same store from Home and Radio.

- [ ] **Step 1: Write failing unit tests**

Cover:
- `kind=my` is ignored.
- `speaker` presets are ordered by numeric `contentId`.
- result always contains exactly three entries.
- store subscription emits current state then changed state.
- failed refresh preserves the previous slots while surfacing an error.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.PhysicalPresetStoreTest'
```

Expected: compile/test failure because the store and slot function do not exist.

- [ ] **Step 3: Implement minimal runtime store**

Use an `AtomicReference<PhysicalPresetSnapshot>` plus `CopyOnWriteArraySet`, matching `SpeakerStateStore` style. `publishPresets` maps through `physicalPresetSlots`; `publishError` keeps the current slots.

- [ ] **Step 4: Run focused tests and verify GREEN**

Same command. Expected: PASS.

- [ ] **Step 5: Commit**

```text
feat(android): add physical preset runtime store
```

### Task 2: Add blocking physical-preset operations without duplicating playback ownership

**Files:**
- Create: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/PhysicalPresetController.kt`
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerControls.kt`
- Modify: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerServiceStateTest.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/PhysicalPresetContractTest.kt`

**Interfaces:**
- Produces `PhysicalPresetController.refresh(context): List<SamsungTuneIn.Preset?>`.
- Produces `PhysicalPresetController.play(context, preset): Unit`.
- Consumes `SamsungTuneIn.getPresets`, `SamsungTuneIn.playSafely`, `SpeakerTarget.resolve`, `SpeakerControlGate`, service ownership flags, and the two runtime stores.
- `SpeakerControls` publishes direct native play/pause, mute and volume outcomes into `SpeakerStateStore`.

- [ ] **Step 1: Write failing contract/state tests**

Pin these source/behavior rules:
- controller refuses refresh when `RendererService.busy || RadioService.active`;
- controller resolves the M5 and calls `SamsungTuneIn.getPresets`;
- play path releases service owners before entering the native preset command;
- play path calls `SamsungTuneIn.playSafely`;
- successful native preset play maps to `SpeakerOwner.RADIO`, `PLAYING`, preset title and source `TuneIn preset`;
- direct `SpeakerControls` native mute/volume/play-pause update the snapshot rather than only returning text.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
cd mobile
./gradlew :app:testDebugUnitTest   --tests 'io.github.trvny.wambridge.mobile.PhysicalPresetContractTest'   --tests 'io.github.trvny.wambridge.mobile.SpeakerServiceStateTest'
```

Expected: FAIL because controller/native preset state helpers do not exist.

- [ ] **Step 3: Implement controller and state helpers**

Rules:
- `refresh` throws `IllegalStateException("Speaker is busy")` if renderer/radio service is active; it does not stop playback to read labels.
- Otherwise resolve speaker, call `SamsungTuneIn.getPresets`, publish to `PhysicalPresetStore`, return three slots.
- `play` first requests stop on active Renderer/Radio services and waits up to the existing 2.5 s ownership budget per owner.
- Then under `SpeakerControlGate.serial`, resolve the current speaker and call `SamsungTuneIn.playSafely`.
- On success publish a native-radio snapshot with the preset title and `source = "TuneIn preset"`.
- Do not add write/edit preset commands.
- Extend `SpeakerControls.perform` only where it has concrete direct-speaker results: paused/playing, mute boolean, volume integer.

- [ ] **Step 4: Run focused tests and full JVM suite**

```bash
cd mobile
./gradlew :app:testDebugUnitTest   --tests 'io.github.trvny.wambridge.mobile.PhysicalPresetContractTest'   --tests 'io.github.trvny.wambridge.mobile.SpeakerServiceStateTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```text
feat(android): wire physical M5 preset controls
```

### Task 3: Build Home 3.5 around shared state and the three preset slots

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt`
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MobileUi.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/HomeSurfaceContractTest.kt`

**Interfaces:**
- Consumes `SpeakerStateStore`, `PhysicalPresetStore`, `PhysicalPresetController`, `SpeakerControls`.
- Home owns no speaker protocol/network code.

- [ ] **Step 1: Write failing Home surface contract tests**

Require source-level wiring for:
- four Now Playing controls: play/pause, mute, volume down, volume up;
- exactly three preset buttons/slots created by a loop over `PHYSICAL_PRESET_SLOTS = 3`, not three independent data sources;
- Home preset click delegates to `PhysicalPresetController.play`;
- Home refresh delegates to `PhysicalPresetController.refresh`;
- Home state renders station/title, owner/playback and connection status from `SpeakerSnapshot`;
- Settings remains the destination for manual-IP/discovery details.

- [ ] **Step 2: Run contract tests and verify RED**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.HomeSurfaceContractTest'
```

Expected: FAIL because Home is still the placeholder.

- [ ] **Step 3: Replace placeholder Home with daily-driver UI**

Home structure:
- header;
- large Now Playing card with a simple built-in music/app badge, title and secondary line from shared state;
- compact playback row: Play/Pause, Mute, Volume −, Volume +;
- section `Physical presets` with three always-visible buttons;
- compact connection/discovery status;
- quick actions: `Stations` → Radio destination and `Reconnect` → existing forced discovery path. Sleep is intentionally not a dead button; its implementation remains the separate roadmap item.

Use the existing `controlExecutor` for Home controls and a dedicated single-thread `presetExecutor` for preset refresh/play. Never perform M5 I/O on main.

Subscribe/unsubscribe to both runtime stores in `onStart/onStop`. UI completions check `isFinishing || isDestroyed`.

- [ ] **Step 4: Run Home contract and full JVM suite**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.HomeSurfaceContractTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```text
feat(android): build Home now playing controls
```

### Task 4: Put the same physical three at the top of Radio and finish PR validation

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt`
- Modify: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/HomeSurfaceContractTest.kt`
- Modify: `mobile/README.md`
- Modify: `docs/DEVELOPMENT_STATUS.md`

**Interfaces:**
- Radio consumes the same `PhysicalPresetStore`; it does not fetch or persist its own copy.
- Existing Presets/Browse/Stations deep screens remain accessible.

- [ ] **Step 1: Extend failing contract test**

Require:
- Radio renders its top physical-preset section from the same store/list as Home;
- Radio retains Saved/Explore entry points;
- no `SetSavePreset`, `SetMovePreset`, or `SetRemovePreset` is added to mobile production code in this PR.

- [ ] **Step 2: Run and verify RED**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.HomeSurfaceContractTest'
```

Expected: FAIL until Radio consumes the shared preset slots.

- [ ] **Step 3: Implement Radio top section and docs**

Radio order:
1. Physical presets 1-3 from `PhysicalPresetStore`;
2. Saved stations entry;
3. TuneIn browse/search entry.

Document that the three shown slots are speaker-owned/read-only in this release and that write-side preset editing remains gated on hardware validation.

- [ ] **Step 4: Run final verification**

```bash
cd mobile
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 5: Scope check**

Diff must contain Home/Radio preset/runtime/UI/docs work only. No MediaSession, sleep timer, ICY metadata, preset writes, HLS/Ogg transcoder, or framework migration.

- [ ] **Step 6: Open PR**

Branch: `feat/android-home-physical-presets`

Title: `feat(android): add Home and physical M5 presets`

PR body should state:
- Home now consumes shared runtime state;
- three physical presets come directly from M5;
- Home and Radio share one runtime preset store;
- preset editing remains intentionally read-only;
- final Mobile CI command/result;
- hardware follow-up: verify the three slots match the physical Radio button and start each preset on the M5.

After opening: review CI and all review threads, apply valid findings, react 👍 to useful comments, and squash-merge only on a green final head with no actionable threads.
