# Android Sleep Timer + Standby Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add speaker-side 15/30/45/60 minute sleep timers, cancellation/status, and a controlled **Standby now** action while preserving the existing single-owner rule for the M5 control channel.

**Architecture:** Keep the M5 authoritative for the countdown. Add a small sleep-timer model to the existing shared speaker snapshot, teach the existing persistent WAM channel to send/read the measured timer commands, and route commands through whichever service already owns the speaker. Idle mode may use the existing direct HTTP control path behind `SpeakerControlGate`. **Standby now** first disables active radio/renderer ownership, waits for release, then arms a one-second speaker timer without `pwron`; that one-second value remains hardware-validation-sensitive until the physical M5 confirms it.

**Tech Stack:** Android classic Views, Kotlin/JVM, framework Services/Intents, existing `SamsungWamChannel`, `SpeakerRemote`, `SpeakerStateStore`, JUnit 4, existing Mobile GitHub Actions workflow.

**Spec:** `docs/superpowers/specs/2026-09-22-android-vnext-design.md`

## Global Constraints

- Presets are exactly **15 / 30 / 45 / 60 minutes**, plus **Off** and **Standby now**.
- Internal timer values accept **0..86400 seconds**, matching the measured desktop implementation.
- `0` means **cancel/off**. It never means immediate standby.
- `SetSleepTimer` arguments are measured as `option=start|off` plus `sleeptime=<seconds>`.
- `GetSleepTimer` / `SetSleepTimer` replies use method `SleepTime` and fields `sleepoption` / `sleeptime`.
- Timer commands must **not** prepend `pwron`.
- Do not open a second control connection while `RadioService` or `RendererService` owns the M5 control plane.
- Do not add AlarmManager, WorkManager, cloud state, or a phone-side timer for the actual sleep event; the speaker owns the countdown.
- Do not show a fake ticking countdown. A value is “confirmed” only when it came back from the speaker; otherwise the UI says the request is pending/sent.
- Standby now must stop/release WAM Bridge playback ownership before its final timer command and must not restart the prior owner.
- No Compose/coroutines/new runtime dependency is introduced.
- Final verification command: `cd mobile && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`.

## Review Focus

1. Repeated taps on the same timer preset must stay idempotent and must not create parallel control clients.
2. A timer request during radio/renderer startup or recovery must stay inside that owner service rather than falling back to a direct connection.
3. Cancelling during an owner transition must preserve `0 = off` and must not be lost behind an older queued non-zero request.
4. `SleepTime` replies from retired WAM channels must not overwrite state for a newer owner/session.
5. Standby now must clear the service's own recovery/start intent before waiting for release, otherwise the app could wake the speaker again after arming the one-second timer.

---

### Task 1: Add the pure sleep-timer model to shared runtime state

**Files:**
- Create: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SleepTimerState.kt`
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerStateStore.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SleepTimerStateTest.kt`
- Modify: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerStateStoreTest.kt`

**Interfaces:**
- Produces: `SleepTimerCommand(option: String, seconds: Int)`
- Produces: `sleepTimerCommand(seconds: Int): SleepTimerCommand`
- Produces: `sleepTimerSeconds(minutes: Int): Int`
- Produces: `SleepTimerPhase { UNKNOWN, REQUESTED, OFF, ARMED }`
- Produces: `SleepTimerState(phase: SleepTimerPhase, seconds: Int? = null)`
- Produces: `sleepTimerState(values: Map<String, String>): SleepTimerState`
- Adds: `SpeakerSnapshot.sleepTimer: SleepTimerState`

- [ ] **Step 1: Write failing pure-model tests**

Create `SleepTimerStateTest.kt`:

```kotlin
package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SleepTimerStateTest {
    @Test
    fun zeroBuildsMeasuredOffCommand() {
        assertEquals(
            SleepTimerCommand(option = "off", seconds = 0),
            sleepTimerCommand(0),
        )
    }

    @Test
    fun positiveSecondsBuildMeasuredStartCommand() {
        assertEquals(
            SleepTimerCommand(option = "start", seconds = 900),
            sleepTimerCommand(900),
        )
    }

    @Test
    fun timerRangeMatchesDesktopBound() {
        sleepTimerCommand(86_400)
        assertThrows(IllegalArgumentException::class.java) { sleepTimerCommand(-1) }
        assertThrows(IllegalArgumentException::class.java) { sleepTimerCommand(86_401) }
    }

    @Test
    fun presetsConvertMinutesExactlyOnce() {
        assertEquals(900, sleepTimerSeconds(15))
        assertEquals(1_800, sleepTimerSeconds(30))
        assertEquals(2_700, sleepTimerSeconds(45))
        assertEquals(3_600, sleepTimerSeconds(60))
        assertThrows(IllegalArgumentException::class.java) { sleepTimerSeconds(10) }
    }

    @Test
    fun speakerReplyParsesArmedAndOffStates() {
        assertEquals(
            SleepTimerState(SleepTimerPhase.ARMED, 837),
            sleepTimerState(mapOf("sleepoption" to "start", "sleeptime" to "837")),
        )
        assertEquals(
            SleepTimerState(SleepTimerPhase.OFF, 0),
            sleepTimerState(mapOf("sleepoption" to "off", "sleeptime" to "0")),
        )
    }

    @Test
    fun malformedReplyStaysUnknown() {
        assertEquals(
            SleepTimerState(),
            sleepTimerState(mapOf("sleepoption" to "start", "sleeptime" to "banana")),
        )
    }
}
```

Add to `SpeakerStateStoreTest.kt`:

```kotlin
@Test
fun sleepTimerLivesInTheSharedSnapshot() {
    SpeakerStateStore.update {
        it.copy(
            sleepTimer = SleepTimerState(
                phase = SleepTimerPhase.ARMED,
                seconds = 900,
            ),
        )
    }

    assertEquals(
        SleepTimerState(SleepTimerPhase.ARMED, 900),
        SpeakerStateStore.current().sleepTimer,
    )
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests '*SleepTimerStateTest' --tests '*SpeakerStateStoreTest'
```

Expected: FAIL because the sleep-timer types/functions and `SpeakerSnapshot.sleepTimer` do not exist.

- [ ] **Step 3: Implement the minimal model**

Create `SleepTimerState.kt`:

```kotlin
package io.github.trvny.wambridge.mobile

internal const val MAX_SLEEP_TIMER_SECONDS = 86_400
private val SLEEP_TIMER_PRESET_MINUTES = setOf(15, 30, 45, 60)

internal data class SleepTimerCommand(
    val option: String,
    val seconds: Int,
)

internal enum class SleepTimerPhase {
    UNKNOWN,
    REQUESTED,
    OFF,
    ARMED,
}

internal data class SleepTimerState(
    val phase: SleepTimerPhase = SleepTimerPhase.UNKNOWN,
    val seconds: Int? = null,
)

internal fun sleepTimerCommand(seconds: Int): SleepTimerCommand {
    require(seconds in 0..MAX_SLEEP_TIMER_SECONDS) {
        "Sleep timer seconds must be 0..$MAX_SLEEP_TIMER_SECONDS"
    }
    return SleepTimerCommand(
        option = if (seconds == 0) "off" else "start",
        seconds = seconds,
    )
}

internal fun sleepTimerSeconds(minutes: Int): Int {
    require(minutes in SLEEP_TIMER_PRESET_MINUTES) {
        "Sleep timer preset must be 15, 30, 45 or 60 minutes"
    }
    return minutes * 60
}

internal fun sleepTimerState(values: Map<String, String>): SleepTimerState {
    val option = values["sleepoption"]?.trim()?.lowercase() ?: return SleepTimerState()
    val seconds = values["sleeptime"]?.trim()?.toIntOrNull() ?: return SleepTimerState()
    if (seconds !in 0..MAX_SLEEP_TIMER_SECONDS) return SleepTimerState()
    return when {
        option == "off" && seconds == 0 ->
            SleepTimerState(SleepTimerPhase.OFF, 0)
        option == "start" && seconds > 0 ->
            SleepTimerState(SleepTimerPhase.ARMED, seconds)
        else -> SleepTimerState()
    }
}
```

Add to `SpeakerSnapshot`:

```kotlin
val sleepTimer: SleepTimerState = SleepTimerState(),
```

Do not reset timer state from unrelated renderer/radio snapshot helpers.

- [ ] **Step 4: Run GREEN and full unit suite**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests '*SleepTimerStateTest' --tests '*SpeakerStateStoreTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SleepTimerState.kt \
        mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerStateStore.kt \
        mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SleepTimerStateTest.kt \
        mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SpeakerStateStoreTest.kt
git commit -m "feat(android): model M5 sleep timer state"
```

---

### Task 2: Teach both WAM paths the measured sleep-timer protocol

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SamsungWamChannel.kt`
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerRemote.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SleepTimerProtocolContractTest.kt`

**Interfaces:**
- `SamsungWamChannel.setSleepTimer(seconds: Int)`
- `SamsungWamChannel.requestSleepTimer()`
- Listener callback: `onSleepTimerChanged(source: Any, state: SleepTimerState)`
- `SpeakerRemote.setSleepTimer(context: Context, speakerIp: String, seconds: Int): SleepTimerState`
- `SpeakerRemote.readSleepTimer(context: Context, speakerIp: String): SleepTimerState`

- [ ] **Step 1: Write failing protocol contract tests**

Create `SleepTimerProtocolContractTest.kt`:

```kotlin
package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerProtocolContractTest {
    private val channel = File(
        "src/main/java/io/github/trvny/wambridge/mobile/SamsungWamChannel.kt",
    ).readText()
    private val remote = File(
        "src/main/java/io/github/trvny/wambridge/mobile/SpeakerRemote.kt",
    ).readText()

    @Test
    fun persistentChannelUsesMeasuredCommandAndReplySpelling() {
        assertTrue(channel.contains("fun setSleepTimer(seconds: Int)"))
        assertTrue(channel.contains("\"SetSleepTimer\""))
        assertTrue(channel.contains("Argument(\"option\", command.option, Kind.STR)"))
        assertTrue(channel.contains("Argument(\"sleeptime\", command.seconds.toString(), Kind.DEC)"))
        assertTrue(channel.contains("fun requestSleepTimer()"))
        assertTrue(channel.contains("\"GetSleepTimer\""))
        assertTrue(channel.contains("method.equals(\"SleepTime\", ignoreCase = true)"))
        assertTrue(channel.contains("onSleepTimerChanged"))
    }

    @Test
    fun sleepCommandsNeverWakeTheSpeaker() {
        val start = channel.indexOf("fun setSleepTimer(seconds: Int)")
        val end = channel.indexOf("fun ", start + 4)
        val block = channel.substring(start, end)
        assertFalse(block.contains("powerOn = true"))
    }

    @Test
    fun idleRemoteCanSetAndReadTheSameState() {
        assertTrue(remote.contains("fun setSleepTimer("))
        assertTrue(remote.contains("fun readSleepTimer("))
        assertTrue(remote.contains("\"SetSleepTimer\""))
        assertTrue(remote.contains("\"GetSleepTimer\""))
        assertTrue(remote.contains("sleepTimerState("))
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests '*SleepTimerProtocolContractTest'
```

Expected: FAIL because the APIs are not present.

- [ ] **Step 3: Implement persistent-channel commands and parsing**

In `SamsungWamChannel.Listener`, add the default callback:

```kotlin
fun onSleepTimerChanged(source: Any, state: SleepTimerState) {}
```

Add:

```kotlin
fun setSleepTimer(seconds: Int) {
    val command = sleepTimerCommand(seconds)
    send(
        method = "SetSleepTimer",
        arguments = listOf(
            Argument("option", command.option, Kind.STR),
            Argument("sleeptime", command.seconds.toString(), Kind.DEC),
        ),
    )
}

fun requestSleepTimer() {
    send(method = "GetSleepTimer")
}
```

Extend response parsing for method `SleepTime`. Reuse the same XML body already in `handleResponseBody`; extract `sleepoption` and `sleeptime`, call `sleepTimerState(...)`, and invoke `listener?.onSleepTimerChanged(this, state)`. Keep source identity in the callback so services can reject delayed replies from retired channels.

- [ ] **Step 4: Implement idle HTTP set/read**

In `SpeakerRemote` add:

```kotlin
fun setSleepTimer(context: Context, speakerIp: String, seconds: Int): SleepTimerState {
    val command = sleepTimerCommand(seconds)
    val values = request(
        context,
        speakerIp,
        method = "SetSleepTimer",
        arguments = listOf(
            Argument("option", command.option, Kind.STR),
            Argument("sleeptime", command.seconds.toString(), Kind.DEC),
        ),
    )
    return sleepTimerState(values)
}

fun readSleepTimer(context: Context, speakerIp: String): SleepTimerState =
    sleepTimerState(
        request(
            context,
            speakerIp,
            method = "GetSleepTimer",
        ),
    )
```

Do not pass `powerOn = true`.

- [ ] **Step 5: Run GREEN and suite**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests '*SleepTimerProtocolContractTest' --tests '*SleepTimerStateTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SamsungWamChannel.kt \
        mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SpeakerRemote.kt \
        mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SleepTimerProtocolContractTest.kt
git commit -m "feat(android): add M5 sleep timer protocol"
```

---

### Task 3: Route timer mutations and reads through the current owner

**Files:**
- Create: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SleepTimerControls.kt`
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/RadioService.kt`
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/RendererService.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SleepTimerControlsContractTest.kt`

**Interfaces:**
- `SleepTimerControls.set(context: Context, seconds: Int): Outcome`
- `SleepTimerControls.refresh(context: Context): Outcome`
- `Outcome(message: String, state: SleepTimerState)`
- Service actions: `ACTION_SET_SLEEP_TIMER`, `ACTION_GET_SLEEP_TIMER`
- Extra: `EXTRA_SLEEP_TIMER_SECONDS`

- [ ] **Step 1: Write failing routing contract tests**

Create `SleepTimerControlsContractTest.kt` with source-level contracts:

```kotlin
package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerControlsContractTest {
    private val controls = File(
        "src/main/java/io/github/trvny/wambridge/mobile/SleepTimerControls.kt",
    )
    private val radio = File(
        "src/main/java/io/github/trvny/wambridge/mobile/RadioService.kt",
    ).readText()
    private val renderer = File(
        "src/main/java/io/github/trvny/wambridge/mobile/RendererService.kt",
    ).readText()

    @Test
    fun activeOwnersReceiveTimerCommandsInsideTheirServices() {
        val text = controls.readText()
        assertTrue(text.contains("RadioService.active"))
        assertTrue(text.contains("RadioService.ACTION_SET_SLEEP_TIMER"))
        assertTrue(text.contains("RendererService.busy"))
        assertTrue(text.contains("RendererService.ACTION_SET_SLEEP_TIMER"))
        assertTrue(radio.contains("ACTION_SET_SLEEP_TIMER"))
        assertTrue(renderer.contains("ACTION_SET_SLEEP_TIMER"))
    }

    @Test
    fun idlePathRechecksOwnershipInsideSpeakerGate() {
        val text = controls.readText()
        assertTrue(text.contains("SpeakerControlGate.serial"))
        assertTrue(text.contains("RadioService.active"))
        assertTrue(text.contains("RendererService.busy"))
        assertTrue(text.contains("SpeakerTarget.resolve("))
        assertTrue(text.contains("SpeakerRemote.setSleepTimer"))
    }

    @Test
    fun servicesCanQueueTimerIntentUntilTheirChannelExists() {
        assertTrue(radio.contains("pendingSleepTimerSeconds"))
        assertTrue(renderer.contains("pendingSleepTimerSeconds"))
        assertTrue(radio.contains("applyPendingSleepTimer"))
        assertTrue(renderer.contains("applyPendingSleepTimer"))
    }

    @Test
    fun retiredChannelsCannotPublishSleepState() {
        assertTrue(radio.contains("source !== channel"))
        assertTrue(renderer.contains("source !== wamChannel"))
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests '*SleepTimerControlsContractTest'
```

Expected: FAIL because routing does not exist.

- [ ] **Step 3: Implement `SleepTimerControls`**

Use this routing rule:

```text
set/refresh
  -> radio active: service intent
  -> else renderer busy: service intent
  -> else SpeakerControlGate.serial {
       re-check radio/renderer
       resolve saved/current M5
       direct SpeakerRemote operation
     }
```

For `set(...)`, publish `SleepTimerPhase.REQUESTED` with the requested seconds before asynchronous service dispatch. For a direct idle command, replace REQUESTED with the speaker-confirmed `OFF`/`ARMED` result returned by `SpeakerRemote.setSleepTimer`.

For `refresh(...)`, active services receive `ACTION_GET_SLEEP_TIMER`; idle mode calls `SpeakerRemote.readSleepTimer` and publishes the confirmed result.

If ownership changes between the first check and the gated idle check, dispatch to the now-active service instead of opening the direct client.

- [ ] **Step 4: Add service-owned timer state and pending commands**

Both services get:

```kotlin
private var pendingSleepTimerSeconds: Int? = null
```

Their `ACTION_SET_SLEEP_TIMER` handlers replace the pending value, so the newest request wins, including `0` cancellation. The worker then calls `applyPendingSleepTimer()`.

`applyPendingSleepTimer()` behavior:
- if the current owner channel exists, consume the latest pending value and call `channel.setSleepTimer(seconds)`;
- if the service is still STARTING/recovering and no channel exists yet, keep the value pending;
- when a new owner channel is established, call `applyPendingSleepTimer()` before publishing a settled state;
- never fall back to `SpeakerRemote` from inside an active service.

`ACTION_GET_SLEEP_TIMER` calls `channel.requestSleepTimer()` when a channel exists; if the service is still starting, it may leave the shared state unchanged rather than claim a result.

Implement `onSleepTimerChanged(source, state)` in each service. Reject the callback unless `source` is the currently owned channel, then publish:

```kotlin
SpeakerStateStore.update { it.copy(sleepTimer = state) }
```

- [ ] **Step 5: Run GREEN and suite**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests '*SleepTimerControlsContractTest' --tests '*SleepTimerProtocolContractTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SleepTimerControls.kt \
        mobile/app/src/main/java/io/github/trvny/wambridge/mobile/RadioService.kt \
        mobile/app/src/main/java/io/github/trvny/wambridge/mobile/RendererService.kt \
        mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SleepTimerControlsContractTest.kt
git commit -m "feat(android): route sleep timer through speaker owner"
```

---

### Task 4: Add controlled Standby now

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SleepTimerControls.kt`
- Modify: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SleepTimerControlsContractTest.kt`

**Interfaces:**
- Adds: `SleepTimerControls.standbyNow(context: Context): Outcome`

- [ ] **Step 1: Add a failing standby contract**

Add:

```kotlin
@Test
fun standbyStopsOwnersBeforeOneSecondSpeakerTimer() {
    val text = controls.readText()
    assertTrue(text.contains("fun standbyNow("))
    assertTrue(text.contains("RadioService.ACTION_STOP"))
    assertTrue(text.contains("RendererService.ACTION_STOP"))
    assertTrue(text.contains("waitForOwnerRelease"))
    assertTrue(text.contains("SpeakerTarget.resolve("))
    assertTrue(text.contains("SpeakerRemote.setSleepTimer"))
    assertTrue(text.contains("STANDBY_TIMER_SECONDS = 1"))
}
```

Also pin that `standbyNow` never starts either playback service with `ACTION_PLAY`/`ACTION_START`.

- [ ] **Step 2: Run RED**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests '*SleepTimerControlsContractTest'
```

Expected: FAIL because `standbyNow` is absent.

- [ ] **Step 3: Implement the controlled sequence**

`standbyNow` runs off the UI thread and performs:

```text
1. If RadioService.active, send ACTION_STOP.
2. If RendererService.busy, send ACTION_STOP.
3. Wait up to 4 seconds for both RadioService.active == false
   and RendererService.busy == false.
4. If either is still active, fail without arming a timer.
5. Under SpeakerControlGate.serial, re-check both are still inactive.
6. Resolve the current M5 through SpeakerTarget.resolve(context).
7. SpeakerRemote.setSleepTimer(context, ip, 1).
8. Publish the returned confirmed timer state.
9. Do not send ACTION_PLAY, ACTION_START, or any recovery action.
```

Use:

```kotlin
private const val STANDBY_TIMER_SECONDS = 1
private const val OWNER_RELEASE_TIMEOUT_MS = 4_000L
```

The one-second timer is an implementation based on the measured seconds semantics but is **not** described as hardware-proven until the physical checklist passes.

- [ ] **Step 4: Run GREEN and suite**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests '*SleepTimerControlsContractTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/SleepTimerControls.kt \
        mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SleepTimerControlsContractTest.kt
git commit -m "feat(android): add controlled standby action"
```

---

### Task 5: Wire Home and Settings UI to the shared timer state

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SleepTimerSurfaceContractTest.kt`

**Interfaces:**
- Consumes: `SleepTimerControls.set`, `refresh`, `standbyNow`
- Consumes: `SpeakerSnapshot.sleepTimer`

- [ ] **Step 1: Write failing UI contract tests**

Create:

```kotlin
package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerSurfaceContractTest {
    private val main = File(
        "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
    ).readText()

    @Test
    fun homeQuickActionsExposeSleepChooser() {
        val start = main.indexOf("private fun buildHomePane")
        val end = main.indexOf("private fun buildRadioPane", start)
        val home = main.substring(start, end)
        assertTrue(home.contains("\"Sleep\""))
        assertTrue(main.contains("\"15 min\""))
        assertTrue(main.contains("\"30 min\""))
        assertTrue(main.contains("\"45 min\""))
        assertTrue(main.contains("\"60 min\""))
        assertTrue(main.contains("\"Off\""))
        assertTrue(main.contains("\"Standby now\""))
    }

    @Test
    fun uiUsesSharedTimerControlsAndNeverRunsLocalCountdown() {
        assertTrue(main.contains("SleepTimerControls.set("))
        assertTrue(main.contains("SleepTimerControls.refresh("))
        assertTrue(main.contains("SleepTimerControls.standbyNow("))
        assertTrue(main.contains("snapshot.sleepTimer"))
        assertFalse(main.contains("CountDownTimer"))
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests '*SleepTimerSurfaceContractTest'
```

Expected: FAIL because Home has no Sleep action/chooser.

- [ ] **Step 3: Add Home Sleep chooser**

Add **Sleep** to Home quick actions. Clicking it opens one `AlertDialog` with:

```text
15 min
30 min
45 min
60 min
Off
Standby now
```

For presets use `sleepTimerSeconds(15|30|45|60)`; do not inline minute-to-second multiplication in the Activity.

Dispatch `SleepTimerControls.set` / `standbyNow` through an existing or dedicated single-thread executor so network/service-wait work never blocks the UI thread.

- [ ] **Step 4: Show human timer state in Settings**

In Settings Playback & system, add a compact status line derived only from `SpeakerSnapshot.sleepTimer`:

```text
UNKNOWN   -> Sleep timer · unknown
REQUESTED -> Sleep timer · request sent
OFF       -> Sleep timer · off
ARMED     -> Sleep timer · M5 reports <seconds>s
```

Do not decrement the value locally. On Settings resume/open, call `SleepTimerControls.refresh` once. If an owner is active, the owner service performs the read on its existing channel.

- [ ] **Step 5: Run GREEN and navigation/state suite**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests '*SleepTimerSurfaceContractTest' \
  --tests '*HomeSurfaceContractTest' \
  --tests '*SettingsSurfaceContractTest' \
  --tests '*SpeakerStateStoreTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt \
        mobile/app/src/test/java/io/github/trvny/wambridge/mobile/SleepTimerSurfaceContractTest.kt
git commit -m "feat(android): expose sleep timer controls"
```

---

### Task 6: Documentation, final verification and PR

**Files:**
- Modify: `mobile/README.md`
- Modify: `docs/ANDROID_VNEXT.md`
- Modify: `docs/DEVELOPMENT_STATUS.md`

- [ ] **Step 1: Update docs without upgrading hardware claims**

Document:
- 15/30/45/60/Off UI;
- shared owner-routed timer control;
- speaker-confirmed state;
- Standby now as a one-second measured-protocol implementation;
- explicitly state that **Standby now remains hardware-unverified** until the physical M5 pass.

Mark roadmap item 3 as “implemented in software; physical standby validation pending” rather than fully hardware-shipped.

- [ ] **Step 2: Run final verification**

Run exactly:

```bash
cd mobile
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Review the final diff against the spec and review focus**

Confirm:
- no second state store;
- no new dependency;
- no `pwron` in timer commands;
- active owner routing;
- stale channel replies rejected;
- no local countdown;
- no service restart after Standby now.

- [ ] **Step 4: Commit docs**

```bash
git add mobile/README.md docs/ANDROID_VNEXT.md docs/DEVELOPMENT_STATUS.md
git commit -m "docs(android): document sleep timer controls"
```

- [ ] **Step 5: Open one PR**

Branch:

```text
feat/android-sleep-timer
```

PR title:

```text
feat(android): add M5 sleep timer controls
```

PR body should mention:
- owner-routed timer commands;
- 15/30/45/60/Off;
- controlled Standby now;
- final Mobile verification command;
- hardware validation still required for one-second Standby now and active-owner behavior on the physical M5.

## Hardware Validation After CI

Use the physical M5 at safe volume:

1. Arm 15 minutes, then read `GetSleepTimer`; confirm `sleepoption=start` and a positive `sleeptime`.
2. Cancel; confirm `sleepoption=off`, `sleeptime=0`.
3. Start native/direct radio, arm 15 minutes from Android, confirm playback continues and the timer is armed.
4. Start renderer/DLNA playback, arm 15 minutes, confirm playback continues and the timer is armed.
5. Trigger **Standby now**; confirm WAM Bridge releases playback, the speaker reaches dark state, and neither RadioService nor RendererService restarts/reconnects.
6. Record the measured result in `docs/WAM_PROTOCOL.md` / `docs/DEVELOPMENT_STATUS.md`. Only then upgrade “Standby now” from software-implemented to hardware-validated.
