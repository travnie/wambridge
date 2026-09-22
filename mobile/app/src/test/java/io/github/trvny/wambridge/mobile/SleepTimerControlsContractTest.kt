package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertFalse
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

        val start = text.indexOf("fun standbyNow(")
        val end = text.indexOf("private fun", start)
        val standby = text.substring(start, end)
        assertFalse(standby.contains("RadioService.ACTION_PLAY"))
        assertFalse(standby.contains("RendererService.ACTION_START"))
    }
}
