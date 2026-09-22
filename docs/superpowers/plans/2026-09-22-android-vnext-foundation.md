# Android vNext Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one shared Android runtime state model and one discovery/recovery path so automatic discovery succeeds in the same cases as manual Discover, including DLNA starts from the app, widget and Quick Settings tile.

**Architecture:** Keep the existing `RadioService`, `RendererService`, `SpeakerControls`, `SpeakerControlGate` and transport code as owners of behavior. Add a lightweight in-process `SpeakerStateStore` for immutable runtime snapshots, then refactor `SpeakerTarget` so automatic discovery, manual Discover and renderer start all use the same detailed resolution pipeline: saved-IP verification → SSDP → LAN scan → deterministic candidate selection. Existing helper methods become thin adapters over that pipeline so callers do not grow parallel logic.

**Tech Stack:** Kotlin/JVM 17, Android Views/services, Android SDK 37, JUnit 4.13.2, existing Gradle Android application module. No new runtime dependencies.

**Spec:** `docs/superpowers/specs/2026-09-22-android-vnext-design.md`

## Global Constraints

- Keep classic Android Views; do not migrate to Compose.
- Add no account system, cloud backend or new persistence layer.
- Keep `RadioService`, `RendererService`, `SpeakerControls` and `SpeakerControlGate` as behavior/ownership authorities.
- `SpeakerStateStore` is runtime state only; existing SharedPreferences and `RadioStationStore` remain persistent sources of truth.
- Automatic discovery and manual Discover must use the same algorithm.
- Common discovery order: saved-IP probe → SSDP → existing LAN scan fallback.
- A temporary device-ID read failure must not be terminal if the M5 is otherwise reachable.
- Never choose arbitrarily between multiple unmatched M5 speakers.
- Do not aggressively probe the speaker control port while radio or renderer ownership is active.
- DLNA start from Home, widget or Quick Settings must be able to find the M5 before renderer startup without asking the user to open the app.
- Final-head validation: `cd mobile && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`.
- Hardware-dependent claims remain unverified until tested on the physical M5.

## Review Focus

1. **Saved IP now belongs to another device while the real M5 moved:** reject the identity mismatch and continue discovery until the saved device ID is found at its new address.
2. **The M5 answers but `GetDeviceId` times out:** preserve a usable speaker resolution instead of converting a reachable M5 into a terminal failure.
3. **Two M5 speakers are visible and neither matches saved identity/IP:** automatic callers must not guess; manual Discover must receive the candidates so UI can ask the user.
4. **Wi-Fi/input state changes while discovery is running:** cancellation must prevent stale results from being persisted or painted into the UI.
5. **Widget/Quick Settings starts DLNA with no resolved target:** both surfaces must still enter the renderer's shared resolution path and reach saved-IP → SSDP → LAN scan before reporting failure.

---

### Task 1: Add the shared runtime state store

**Files:**
- Create: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerStateStore.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerStateStoreTest.kt`

**Interfaces:**
- Produces: `SpeakerDiscoveryStage`, `SpeakerOwner`, `SpeakerPlaybackState`, `SpeakerSnapshot`, and `SpeakerStateStore`.
- Produces: `SpeakerStateStore.current(): SpeakerSnapshot`.
- Produces: `SpeakerStateStore.update(transform: (SpeakerSnapshot) -> SpeakerSnapshot): SpeakerSnapshot`.
- Produces: `SpeakerStateStore.subscribe(listener: (SpeakerSnapshot) -> Unit): AutoCloseable`; subscription immediately emits the current snapshot and later emits only changed snapshots.
- Produces: `SpeakerStateStore.resetForTests()`, internal and test-only in intent.

- [ ] **Step 1: Write the failing state-store tests**

Create `SpeakerStateStoreTest.kt`:

```kotlin
package io.github.trvny.wambridge.mobile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerStateStoreTest {
    @After
    fun reset() {
        SpeakerStateStore.resetForTests()
    }

    @Test
    fun subscriptionImmediatelyReceivesCurrentSnapshotAndThenChanges() {
        val seen = mutableListOf<SpeakerSnapshot>()
        val subscription = SpeakerStateStore.subscribe(seen::add)

        SpeakerStateStore.update {
            it.copy(
                owner = SpeakerOwner.RENDERER,
                discovery = SpeakerDiscoveryStage.CHECKING_SAVED,
            )
        }

        subscription.close()

        assertEquals(2, seen.size)
        assertEquals(SpeakerOwner.IDLE, seen[0].owner)
        assertEquals(SpeakerOwner.RENDERER, seen[1].owner)
        assertEquals(SpeakerDiscoveryStage.CHECKING_SAVED, seen[1].discovery)
    }

    @Test
    fun unchangedSnapshotDoesNotNotifyListenersTwice() {
        var calls = 0
        val subscription = SpeakerStateStore.subscribe { calls++ }

        SpeakerStateStore.update { it }

        subscription.close()
        assertEquals(1, calls)
    }

    @Test
    fun readySpeakerClearsPreviousTerminalError() {
        SpeakerStateStore.update {
            it.copy(
                discovery = SpeakerDiscoveryStage.FAILED,
                lastError = "No WAM speaker found",
            )
        }

        SpeakerStateStore.publishSpeaker("10.0.0.44", "A1B2C3D4E5F6")

        val snapshot = SpeakerStateStore.current()
        assertEquals(SpeakerDiscoveryStage.READY, snapshot.discovery)
        assertEquals("10.0.0.44", snapshot.speakerIp)
        assertEquals("A1B2C3D4E5F6", snapshot.deviceId)
        assertNull(snapshot.lastError)
    }
}
```

- [ ] **Step 2: Run the focused tests and verify they fail**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.SpeakerStateStoreTest'
```

Expected: FAIL because `SpeakerStateStore` and model types do not exist.

- [ ] **Step 3: Implement the minimal thread-safe store**

Create `SpeakerStateStore.kt` with:

```kotlin
package io.github.trvny.wambridge.mobile

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

internal enum class SpeakerOwner { IDLE, RADIO, RENDERER }

internal enum class SpeakerPlaybackState {
    STOPPED, STARTING, PLAYING, PAUSED, STOPPING, UNKNOWN,
}

internal enum class SpeakerDiscoveryStage {
    IDLE, WAITING_FOR_WIFI, CHECKING_SAVED, SSDP, LAN_SCAN, READY, FAILED,
}

internal data class SpeakerSnapshot(
    val owner: SpeakerOwner = SpeakerOwner.IDLE,
    val playback: SpeakerPlaybackState = SpeakerPlaybackState.STOPPED,
    val speakerIp: String? = null,
    val deviceId: String? = null,
    val volume: Int? = null,
    val muted: Boolean? = null,
    val stationAlias: String? = null,
    val metadata: String? = null,
    val source: String? = null,
    val fallback: String? = null,
    val discovery: SpeakerDiscoveryStage = SpeakerDiscoveryStage.IDLE,
    val status: String = "Idle",
    val lastError: String? = null,
)

internal object SpeakerStateStore {
    private val state = AtomicReference(SpeakerSnapshot())
    private val listeners = CopyOnWriteArraySet<(SpeakerSnapshot) -> Unit>()

    fun current(): SpeakerSnapshot = state.get()

    fun update(transform: (SpeakerSnapshot) -> SpeakerSnapshot): SpeakerSnapshot {
        while (true) {
            val previous = state.get()
            val next = transform(previous)
            if (next == previous) return previous
            if (state.compareAndSet(previous, next)) {
                listeners.forEach { it(next) }
                return next
            }
        }
    }

    fun subscribe(listener: (SpeakerSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        listener(state.get())
        return AutoCloseable { listeners -= listener }
    }

    fun publishSpeaker(ip: String, deviceId: String?) {
        update {
            it.copy(
                speakerIp = ip,
                deviceId = deviceId ?: it.deviceId,
                discovery = SpeakerDiscoveryStage.READY,
                lastError = null,
            )
        }
    }

    internal fun resetForTests() {
        listeners.clear()
        state.set(SpeakerSnapshot())
    }
}
```

Keep callbacks synchronous on the caller thread. UI consumers later marshal to the main thread.

- [ ] **Step 4: Run the tests and verify they pass**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.SpeakerStateStoreTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerStateStore.kt         mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerStateStoreTest.kt
git commit -m "feat(android): add shared speaker state store"
```

### Task 2: Make one detailed speaker-resolution pipeline

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerTarget.kt` (`resolve*`, candidate selection, persistence)
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/WamDiscovery.kt` (`discover` stage reporting)
- Modify: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerTargetTest.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerDiscoveryContractTest.kt`

**Interfaces:**
- Consumes: `SpeakerDiscoveryStage`, `SpeakerStateStore.publishSpeaker`.
- Produces: `SpeakerTarget.ResolveOutcome` with `Found`, `Ambiguous`, `NotFound`, `Cancelled`.
- Produces: `SpeakerTarget.resolveDetailed(context, persist, shouldContinue, onStage): ResolveOutcome`.
- Produces: `SpeakerTarget.acceptDiscovered(context, speaker): Resolution`.
- Existing `resolve`, `resolveUnpersisted`, `resolveBound` delegate to the detailed pipeline.
- `WamDiscovery.discover` gains `onStage: (SpeakerDiscoveryStage) -> Unit = {}`.

- [ ] **Step 1: Extend candidate-selection tests**

Add to `SpeakerTargetTest.kt`:

```kotlin
@Test
fun oneDiscoveredSpeakerIsUsableWhenIdentityReadTemporarilyFails() {
    val selected = SpeakerTarget.selectCandidate(
        savedIp = "",
        savedId = "",
        speakers = listOf(moved),
        identify = { null },
    )
    assertEquals(moved, selected)
}

@Test
fun savedDeviceIdMismatchStillAllowsMatchingMovedSpeaker() {
    val third = WamDiscovery.Speaker("10.0.0.55", "LAN scan")
    val identities = mapOf(
        old.ip to "WRONG",
        moved.ip to "A1B2C3D4E5F6",
        third.ip to null,
    )

    val selected = SpeakerTarget.selectCandidate(
        savedIp = old.ip,
        savedId = "A1B2C3D4E5F6",
        speakers = listOf(old, third, moved),
        identify = identities::get,
    )

    assertEquals(moved, selected)
}
```

Keep the existing tests for wrong single speaker and ambiguous legacy discovery.

- [ ] **Step 2: Add a contract test preventing a second discovery implementation**

Create `SpeakerDiscoveryContractTest.kt`:

```kotlin
package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerDiscoveryContractTest {
    @Test
    fun speakerTargetOwnsTheFullSsdpAndLanFallbackPipeline() {
        val target = File(
            "src/main/java/io/github/trvny/wambridge/mobile/SpeakerTarget.kt",
        ).readText()
        val discovery = File(
            "src/main/java/io/github/trvny/wambridge/mobile/WamDiscovery.kt",
        ).readText()

        assertTrue(target.contains("resolveDetailed"))
        assertTrue(target.contains("WamDiscovery.discover"))
        assertTrue(discovery.contains("SpeakerDiscoveryStage.SSDP"))
        assertTrue(discovery.contains("SpeakerDiscoveryStage.LAN_SCAN"))
    }

    @Test
    fun mainActivityDoesNotCallLowLevelDiscoveryDirectly() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
        ).readText()

        assertFalse(source.contains("WamDiscovery.discover("))
    }
}
```

The second test remains red until Task 4; run only the first method during Task 2.

- [ ] **Step 3: Run the focused test and verify the resolver contract is red**

```bash
cd mobile
./gradlew :app:testDebugUnitTest   --tests 'io.github.trvny.wambridge.mobile.SpeakerTargetTest'   --tests 'io.github.trvny.wambridge.mobile.SpeakerDiscoveryContractTest.speakerTargetOwnsTheFullSsdpAndLanFallbackPipeline'
```

Expected: FAIL on the contract because `resolveDetailed` and stage reporting do not exist.

- [ ] **Step 4: Add detailed outcomes and one common resolver**

In `SpeakerTarget.kt` add:

```kotlin
sealed interface ResolveOutcome {
    data class Found(val resolution: Resolution) : ResolveOutcome
    data class Ambiguous(
        val speakers: List<WamDiscovery.Speaker>,
        val scan: WamDiscovery.Scan,
    ) : ResolveOutcome
    data class NotFound(val scan: WamDiscovery.Scan) : ResolveOutcome
    data object Cancelled : ResolveOutcome
}
```

Add:

```kotlin
fun resolveDetailed(
    context: Context,
    persist: Boolean = true,
    shouldContinue: () -> Boolean = { true },
    onStage: (SpeakerDiscoveryStage) -> Unit = {},
): ResolveOutcome = withDiscoveryLock {
    // implementation follows the behavior rules below
}
```

Behavior requirements:

- Emit `CHECKING_SAVED` before saved-target verification.
- If no active Wi-Fi target exists, emit `WAITING_FOR_WIFI` and return `NotFound(WamDiscovery.Scan.NotRun)`.
- Saved IP + saved device ID is accepted only when identity matches.
- Saved IP without device ID may be accepted when identity succeeds or the existing WAM probe succeeds.
- If the saved path fails, continue to `WamDiscovery.discover(... allowScan = true ...)` unless cancelled.
- A single discovered WAM speaker remains usable when the follow-up identity read returns null; its `Resolution.deviceId` may be null.
- Multiple unmatched speakers return `Ambiguous`; never pick the first.
- Persist only an accepted `Found` result and only if `persist == true`.
- Re-check `shouldContinue()` before every persistence/state publication boundary.
- Publish the accepted identity with `SpeakerStateStore.publishSpeaker`.

Make `resolve` and `resolveUnpersisted` thin adapters around `resolveDetailed`.

For `resolveBound`, call `resolveDetailed(... persist = false ...)`, then retain the current preferred-Wi-Fi endpoint verification and `BOUND_RESOLVE_ATTEMPTS` behavior before remembering the final bound result. Do not weaken network binding.

Add:

```kotlin
fun acceptDiscovered(
    context: Context,
    speaker: WamDiscovery.Speaker,
): Resolution {
    val resolution = Resolution(
        speaker.ip,
        identify(context.applicationContext, speaker.ip),
    )
    rememberResolved(context.applicationContext, resolution)
    SpeakerStateStore.publishSpeaker(resolution.ip, resolution.deviceId)
    return resolution
}
```

- [ ] **Step 5: Report SSDP and LAN-scan stages from WamDiscovery**

Change `discover` to:

```kotlin
fun discover(
    context: Context,
    allowScan: Boolean,
    ssdpTimeoutMs: Long = 2_500,
    shouldContinue: () -> Boolean = { true },
    onStage: (SpeakerDiscoveryStage) -> Unit = {},
): Result
```

Before SSDP:

```kotlin
onStage(SpeakerDiscoveryStage.SSDP)
val ssdp = discoverSsdp(targets, ssdpTimeoutMs, shouldContinue)
```

Before fallback scanning:

```kotlin
onStage(SpeakerDiscoveryStage.LAN_SCAN)
scanLocalLan(targets, shouldContinue)
```

Do not change SSDP timing, scan width, multicast locking, probe concurrency, or `Scan.Full/Narrowed/Overlapping` semantics.

- [ ] **Step 6: Run focused tests**

```bash
cd mobile
./gradlew :app:testDebugUnitTest   --tests 'io.github.trvny.wambridge.mobile.SpeakerTargetTest'   --tests 'io.github.trvny.wambridge.mobile.SpeakerDiscoveryContractTest.speakerTargetOwnsTheFullSsdpAndLanFallbackPipeline'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerTarget.kt         mobile/app/src/main/java/io/github/trvny/wambridge/mobile/WamDiscovery.kt         mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerTargetTest.kt         mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerDiscoveryContractTest.kt
git commit -m "refactor(android): unify speaker discovery"
```

### Task 3: Publish service ownership into the shared snapshot

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerStateStore.kt`
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/RendererService.kt` (`setPhase`, startup resolution, failure publication)
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/RadioService.kt` (`publish`, start/stop/control state)
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerServiceStateTest.kt`

**Interfaces:**
- Consumes: shared snapshot/store and detailed resolver.
- Produces: owner/playback/status/volume/mute/station state for later Home/MediaSession work.
- Existing service companion flags remain during this PR for compatibility.

- [ ] **Step 1: Write pure mapping tests**

Create `SpeakerServiceStateTest.kt`:

```kotlin
package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerServiceStateTest {
    @Test
    fun rendererStartingOwnsTheControlPlane() {
        val snapshot = speakerSnapshotForRenderer(
            phase = RendererService.Phase.STARTING,
            status = "Finding WAM speaker on Wi-Fi…",
            speakerIp = null,
        )

        assertEquals(SpeakerOwner.RENDERER, snapshot.owner)
        assertEquals(SpeakerPlaybackState.STARTING, snapshot.playback)
    }

    @Test
    fun stoppedRendererDoesNotEraseActiveRadioOwnership() {
        val current = SpeakerSnapshot(
            owner = SpeakerOwner.RADIO,
            playback = SpeakerPlaybackState.PLAYING,
            stationAlias = "trojka",
        )

        val snapshot = speakerSnapshotForRenderer(
            phase = RendererService.Phase.STOPPED,
            status = "Stopped",
            speakerIp = null,
            current = current,
        )

        assertEquals(SpeakerOwner.RADIO, snapshot.owner)
        assertEquals("trojka", snapshot.stationAlias)
    }

    @Test
    fun pausedRadioPublishesRadioOwnerAndPausedPlayback() {
        val snapshot = speakerSnapshotForRadio(
            active = true,
            paused = true,
            muted = false,
            volume = 3,
            stationAlias = "bbc1",
            status = "BBC Radio 1 · paused",
        )

        assertEquals(SpeakerOwner.RADIO, snapshot.owner)
        assertEquals(SpeakerPlaybackState.PAUSED, snapshot.playback)
        assertEquals(3, snapshot.volume)
    }
}
```

- [ ] **Step 2: Run and verify red**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.SpeakerServiceStateTest'
```

Expected: FAIL because mapping helpers do not exist.

- [ ] **Step 3: Implement pure mapping helpers**

Add to `SpeakerStateStore.kt`:

```kotlin
internal fun speakerSnapshotForRenderer(
    phase: RendererService.Phase,
    status: String,
    speakerIp: String?,
    current: SpeakerSnapshot = SpeakerStateStore.current(),
): SpeakerSnapshot = when (phase) {
    RendererService.Phase.STARTING -> current.copy(
        owner = SpeakerOwner.RENDERER,
        playback = SpeakerPlaybackState.STARTING,
        status = status,
        speakerIp = speakerIp ?: current.speakerIp,
        lastError = null,
    )
    RendererService.Phase.RUNNING -> current.copy(
        owner = SpeakerOwner.RENDERER,
        playback = SpeakerPlaybackState.PLAYING,
        status = status,
        speakerIp = speakerIp ?: current.speakerIp,
        lastError = null,
    )
    RendererService.Phase.STOPPING -> current.copy(
        owner = SpeakerOwner.RENDERER,
        playback = SpeakerPlaybackState.STOPPING,
        status = status,
    )
    RendererService.Phase.STOPPED ->
        if (current.owner == SpeakerOwner.RADIO) current
        else current.copy(
            owner = SpeakerOwner.IDLE,
            playback = SpeakerPlaybackState.STOPPED,
            status = status,
        )
}

internal fun speakerSnapshotForRadio(
    active: Boolean,
    paused: Boolean,
    muted: Boolean,
    volume: Int,
    stationAlias: String?,
    status: String,
    current: SpeakerSnapshot = SpeakerStateStore.current(),
): SpeakerSnapshot =
    if (active) {
        current.copy(
            owner = SpeakerOwner.RADIO,
            playback = if (paused) SpeakerPlaybackState.PAUSED else SpeakerPlaybackState.PLAYING,
            muted = muted,
            volume = volume,
            stationAlias = stationAlias,
            status = status,
            lastError = null,
        )
    } else if (current.owner == SpeakerOwner.RENDERER) {
        current
    } else {
        current.copy(
            owner = SpeakerOwner.IDLE,
            playback = SpeakerPlaybackState.STOPPED,
            stationAlias = null,
            status = status,
        )
    }
```

Do not clear speaker identity when playback stops.

- [ ] **Step 4: Wire RendererService**

At the single phase mutation path (`setPhase` or equivalent), update the store through `speakerSnapshotForRenderer`.

Pass `onStage` into `SpeakerTarget.resolveBound`:

```kotlin
val boundTarget = SpeakerTarget.resolveBound(
    applicationContext,
    shouldContinue = { shouldKeepStarting(generation) },
    onStage = { stage ->
        SpeakerStateStore.update {
            it.copy(
                discovery = stage,
                status = discoveryStatus(stage),
                lastError = null,
            )
        }
    },
)
```

Task 2 must therefore extend `resolveBound` with the same optional `onStage` callback.

On successful resolution, publish speaker IP/device ID. On true terminal target failure, publish `FAILED` and `lastError`; do not mark `WAITING_FOR_WIFI` as terminal.

- [ ] **Step 5: Wire RadioService**

At existing `publish(message)`, update the shared store first:

```kotlin
SpeakerStateStore.update {
    speakerSnapshotForRadio(
        active = active,
        paused = paused,
        muted = muted,
        volume = targetVolume,
        stationAlias = station?.alias,
        status = message,
        current = it,
    )
}
```

Preserve all current radio Wi-Fi retry budgets and teardown rules.

- [ ] **Step 6: Run focused and full JVM tests**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.SpeakerServiceStateTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerStateStore.kt         mobile/app/src/main/java/io/github/trvny/wambridge/mobile/RendererService.kt         mobile/app/src/main/java/io/github/trvny/wambridge/mobile/RadioService.kt         mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerServiceStateTest.kt
git commit -m "feat(android): publish shared speaker state"
```

### Task 4: Route automatic and manual discovery through the same Activity path

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt` (auto/manual discovery orchestration)
- Modify: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerDiscoveryContractTest.kt`

**Interfaces:**
- Consumes: `SpeakerTarget.resolveDetailed`, `SpeakerTarget.acceptDiscovered`, detailed outcomes.
- Produces: one `runDiscovery(manual: Boolean)` used by launch auto-discovery and the Discover button.
- Preserves current generation/input-revision guards against stale async results.

- [ ] **Step 1: Add Activity contract test**

Append:

```kotlin
@Test
fun autoAndManualActivityDiscoveryUseTheSameDetailedResolver() {
    val source = File(
        "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
    ).readText()

    assertTrue(source.contains("runDiscovery(manual = false)"))
    assertTrue(source.contains("runDiscovery(manual = true)"))
    assertTrue(source.contains("SpeakerTarget.resolveDetailed("))
    assertFalse(source.contains("WamDiscovery.discover("))
}
```

- [ ] **Step 2: Run and verify red**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.SpeakerDiscoveryContractTest'
```

Expected: FAIL because MainActivity still has parallel auto/manual paths.

- [ ] **Step 3: Replace both implementations with one orchestration method**

Keep `autoDiscoveryGeneration`, `speakerInputRevision`, `autoDiscoveryStillCurrent`, and executor cancellation behavior.

Implement:

```kotlin
private fun runDiscovery(manual: Boolean) {
    if (isFinishing || isDestroyed || discoveryExecutor.isShutdown) return
    if (deferAutoDiscoveryWhileBusy()) return

    val previous = speakerIp.text.toString().trim()
    val savedBefore = preferences.getString(
        RendererService.KEY_SPEAKER_IP,
        "",
    ).orEmpty().trim()
    val inputRevision = speakerInputRevision.get()
    val generation = autoDiscoveryGeneration.incrementAndGet()

    MobileUi.setEnabled(discoverButton, false)
    MobileUi.setStatus(
        statusView,
        if (manual) "Discovering WAM speakers on Wi-Fi…"
        else "Finding M5 on Wi-Fi…",
    )

    discoveryExecutor.execute {
        val outcome = SpeakerTarget.resolveDetailed(
            context = applicationContext,
            persist = false,
            shouldContinue = {
                autoDiscoveryStillCurrent(
                    generation,
                    inputRevision,
                    savedBefore,
                )
            },
            onStage = { stage ->
                SpeakerStateStore.update {
                    it.copy(
                        discovery = stage,
                        status = discoveryStatus(stage),
                        lastError = null,
                    )
                }
            },
        )
        runOnUiThread {
            applyDiscoveryOutcome(
                manual,
                generation,
                inputRevision,
                savedBefore,
                previous,
                outcome,
            )
        }
    }
}
```

Wire:

```kotlin
private fun autoDiscoverSpeaker() = runDiscovery(manual = false)
```

and:

```kotlin
discoverButton = MobileUi.button(this, "Discover") {
    runDiscovery(manual = true)
}
```

Remove `allowScan` from Activity orchestration; the common resolver owns fallback behavior.

- [ ] **Step 4: Handle Found/Ambiguous/NotFound/Cancelled without stale writes**

In `applyDiscoveryOutcome`:

- Check Activity lifetime, generation and input revision before any UI/persistence action.
- `Found`: verify saved preference has not changed underneath the job, then call `rememberResolved`, `publishSpeaker`, update the field and show success.
- `Ambiguous`: manual mode opens the existing chooser; automatic mode says `Multiple WAM speakers found. Tap Discover to choose one.`
- `NotFound`: reuse `emptyScanMessage(outcome.scan)`.
- `Cancelled`: do nothing.

Use string concatenation in success copy so the implementation is unambiguous:

```kotlin
"M5 ready at " + outcome.resolution.ip + "."
"Found M5 at " + outcome.resolution.ip + " and updated the saved address."
```

Update `useDiscoveredSpeaker` to call `SpeakerTarget.acceptDiscovered` so a manually chosen candidate also records stable identity when available.

- [ ] **Step 5: Run the discovery contract and full JVM suite**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.SpeakerDiscoveryContractTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt         mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerDiscoveryContractTest.kt
git commit -m "fix(android): share automatic and manual discovery"
```

### Task 5: Make widget and Quick Settings reflect shared discovery

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerStateStore.kt`
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/WamBridgeWidget.kt`
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/WamBridgeTileService.kt`
- Modify: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/AndroidUxContractTest.kt`

**Interfaces:**
- Consumes: `SpeakerStateStore.current()`.
- Widget/tile continue to start DLNA only via `RendererService.ACTION_START`; neither may call `WamDiscovery` or `SpeakerTarget` directly.
- Produces: one `discoveryStatus(stage)` mapping reused by widget, tile and later Home.

- [ ] **Step 1: Add widget/tile routing contract**

Append to `AndroidUxContractTest.kt`:

```kotlin
@Test
fun widgetAndTileStartOnlyTheRendererAndDoNotOwnDiscovery() {
    val widget = File(
        "src/main/java/io/github/trvny/wambridge/mobile/WamBridgeWidget.kt",
    ).readText()
    val tile = File(
        "src/main/java/io/github/trvny/wambridge/mobile/WamBridgeTileService.kt",
    ).readText()

    for (source in listOf(widget, tile)) {
        assertTrue(source.contains("RendererService.ACTION_START"))
        assertTrue(source.contains("SpeakerStateStore.current()"))
        assertTrue(!source.contains("WamDiscovery.discover("))
        assertTrue(!source.contains("SpeakerTarget.resolve"))
    }
}
```

- [ ] **Step 2: Run and verify red**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.AndroidUxContractTest.widgetAndTileStartOnlyTheRendererAndDoNotOwnDiscovery'
```

Expected: FAIL because the current surfaces do not read the store.

- [ ] **Step 3: Add one shared discovery-label helper**

In `SpeakerStateStore.kt`:

```kotlin
internal fun discoveryStatus(stage: SpeakerDiscoveryStage): String = when (stage) {
    SpeakerDiscoveryStage.WAITING_FOR_WIFI -> "Waiting for Wi-Fi…"
    SpeakerDiscoveryStage.CHECKING_SAVED -> "Checking M5…"
    SpeakerDiscoveryStage.SSDP -> "Finding M5…"
    SpeakerDiscoveryStage.LAN_SCAN -> "Scanning Wi-Fi…"
    SpeakerDiscoveryStage.READY -> "M5 ready"
    SpeakerDiscoveryStage.FAILED -> "M5 not found"
    SpeakerDiscoveryStage.IDLE -> "Starting…"
}
```

- [ ] **Step 4: Use the shared state in widget and tile**

In `WamBridgeWidget.updateControls`, read once:

```kotlin
val snapshot = SpeakerStateStore.current()
```

While renderer is transitioning, show `discoveryStatus(snapshot.discovery)` rather than a generic status when that is more specific.

In `WamBridgeTileService.refreshTile`, while `RendererService.Phase.STARTING`:

```kotlin
val snapshot = SpeakerStateStore.current()
tile.state = Tile.STATE_UNAVAILABLE
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    tile.subtitle = discoveryStatus(snapshot.discovery)
}
```

Do not start discovery directly from either surface. Their existing RendererService delegation is the desired path.

- [ ] **Step 5: Run contract and final debug verification**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.AndroidUxContractTest'
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS and `mobile/app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 6: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerStateStore.kt         mobile/app/src/main/java/io/github/trvny/wambridge/mobile/WamBridgeWidget.kt         mobile/app/src/main/java/io/github/trvny/wambridge/mobile/WamBridgeTileService.kt         mobile/app/src/test/java/io/github/trvny/wambridge/mobile/AndroidUxContractTest.kt
git commit -m "feat(android): surface discovery state in quick controls"
```

### Task 6: Document, verify, and open the foundation PR

**Files:**
- Modify: `mobile/README.md`
- Modify: `docs/DEVELOPMENT_STATUS.md`
- Reference: `docs/superpowers/specs/2026-09-22-android-vnext-design.md`

**Interfaces:**
- Documents only behavior actually implemented in Tasks 1-5.
- Does not claim physical-M5 validation unless it was performed.

- [ ] **Step 1: Update mobile README**

Add:

```markdown
### Speaker discovery

All Android start surfaces use the same target resolution path:

1. verify the saved M5 address;
2. fall back to SSDP;
3. fall back to the bounded LAN scan;
4. persist the resolved address/device identity;
5. continue the requested action.

Automatic startup, manual Discover, renderer start, the home-screen widget and
Quick Settings no longer maintain separate discovery algorithms.
```

Also note that multiple unmatched speakers require explicit selection and manual IP remains an Advanced escape hatch.

- [ ] **Step 2: Update development status carefully**

Add a dated Android note:

```markdown
**Unified 2026-09-22:** automatic discovery, manual Discover and renderer startup now
share one saved-IP -> SSDP -> bounded-LAN-scan resolver. Widget and Quick Settings
continue to delegate renderer start to RendererService, so they inherit the same
recovery path without owning network discovery themselves. A shared in-process
speaker snapshot now carries owner/discovery/status for later Home and MediaSession work.

JVM/CI coverage is complete. Real-device validation still required: start DLNA from
the widget and Quick Settings after clearing/staling the saved target, and repeat
across a real Wi-Fi/DHCP move on the physical M5.
```

If hardware validation has actually occurred, replace the final paragraph with the measured result/date.

- [ ] **Step 3: Run final verification on the exact candidate head**

```bash
cd mobile
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 4: Check scope**

```bash
git status --short
git diff --stat main...HEAD
git diff main...HEAD --   mobile/app/src/main   mobile/app/src/test   mobile/README.md   docs/DEVELOPMENT_STATUS.md
```

Expected: shared-state/discovery/surface-status/docs only. No navigation redesign, MediaSession, sleep timer, ICY metadata, preset writes or HLS/Ogg work.

- [ ] **Step 5: Push and open one PR**

Branch:

```text
feat/android-shared-state-discovery
```

Title:

```text
feat(android): unify speaker state and discovery
```

Brief body:

```markdown
Unify Android speaker resolution behind one saved-IP -> SSDP -> LAN-scan path and add
a lightweight shared runtime speaker snapshot.

- auto/manual discovery use the same resolver
- renderer starts from app/widget/tile inherit the same recovery path
- widget/tile surface discovery progress
- service owner/status are published for the upcoming Home screen

Validation:
- `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`

Hardware follow-up: verify stale/cleared target recovery from widget and Quick Settings on the physical M5.
```

Open as `trvny`. Inspect relevant CI and review threads after opening. Apply valid findings directly, react 👍 to useful review comments, and merge only when final-head relevant checks are green and actionable threads are resolved.
