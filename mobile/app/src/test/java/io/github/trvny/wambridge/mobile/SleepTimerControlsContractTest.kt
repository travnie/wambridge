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
        assertTrue(text.contains("RadioService.acceptsSleepTimerCommands"))
        assertTrue(text.contains("RadioService.ACTION_SET_SLEEP_TIMER"))
        assertTrue(text.contains("RendererService.phase == RendererService.Phase.RUNNING"))
        assertTrue(text.contains("RendererService.ACTION_SET_SLEEP_TIMER"))
        assertTrue(radio.contains("ACTION_SET_SLEEP_TIMER"))
        assertTrue(renderer.contains("ACTION_SET_SLEEP_TIMER"))
    }

    @Test
    fun idlePathRechecksOwnershipInsideSpeakerGate() {
        val text = controls.readText()
        assertTrue(text.contains("SpeakerControlGate.serial"))
        assertTrue(text.contains("RadioService.acceptsSleepTimerCommands"))
        assertTrue(text.contains("RendererService.phase == RendererService.Phase.RUNNING"))
        assertTrue(text.contains("SpeakerTarget.resolve("))
        assertTrue(text.contains("SpeakerRemote.setSleepTimer"))
    }

    @Test
    fun servicesExplicitlyAcknowledgeStableOwnerRequests() {
        assertTrue(radio.contains("SleepTimerOwnerRequests.complete"))
        assertTrue(renderer.contains("SleepTimerOwnerRequests.complete"))
        assertTrue(radio.contains("if (!acceptsSleepTimerCommands"))
        assertTrue(renderer.contains("phase != Phase.RUNNING"))
        assertFalse(radio.contains("pendingSleepTimerSeconds"))
        assertFalse(renderer.contains("pendingSleepTimerSeconds"))
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
    @Test
    fun rendererClearsTemporaryTimerChannelOwnershipWhenChannelCloses() {
        assertTrue(renderer.contains("releaseTimerChannelAfterReply = false"))
        val closeStart = renderer.indexOf("private fun closeWamChannel()")
        val closeEnd = renderer.indexOf("private fun", closeStart + 12)
        val closeBlock = renderer.substring(closeStart, closeEnd)
        assertTrue(closeBlock.contains("releaseTimerChannelAfterReply = false"))
    }
    @Test
    fun transitionalOwnersAreNotTreatedAsAcceptedTimerCommands() {
        val text = controls.readText()
        val setStart = text.indexOf("private fun dispatchSetToOwner")
        val setEnd = text.indexOf("private fun dispatchRefreshToOwner", setStart)
        val setBlock = text.substring(setStart, setEnd)
        assertTrue(setBlock.contains("RadioService.acceptsSleepTimerCommands"))
        assertTrue(setBlock.contains("RendererService.phase == RendererService.Phase.RUNNING"))
        assertFalse(setBlock.contains("RadioService.active ->"))
        assertFalse(setBlock.contains("RendererService.busy ->"))
    }

    @Test
    fun synchronousFailuresRestoreThePreviousTimerSnapshot() {
        val text = controls.readText()
        assertTrue(text.contains("restoreRequestedState"))
        assertTrue(text.contains("val previous = SpeakerStateStore.current().sleepTimer"))
        assertTrue(text.contains("SleepTimerOwnerRequests.await"))
        assertTrue(text.contains("catch (error: Exception)"))
    }

    @Test
    fun rendererBoundsTemporaryTimerChannelLifetime() {
        assertTrue(renderer.contains("timerReplyRelease"))
        assertTrue(renderer.contains("scheduleTimerChannelRelease"))
        assertTrue(renderer.contains("cancelTimerChannelRelease"))
        assertTrue(renderer.contains("TIMER_REPLY_TIMEOUT_MS"))
        assertTrue(renderer.contains("wamChannel === activeChannel"))
        assertTrue(renderer.contains("!ownsPlayback"))

        val refreshStart = renderer.indexOf("private fun requestSleepTimerFromOwner()")
        val refreshEnd = renderer.indexOf("private fun scheduleTimerChannelRelease", refreshStart)
        val refreshBlock = renderer.substring(refreshStart, refreshEnd)
        assertTrue(refreshBlock.contains("catch (error: Exception)"))
        assertTrue(refreshBlock.contains("closeWamChannel()"))
    }
}
