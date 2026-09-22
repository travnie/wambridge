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
    @Test
    fun requestedTimerIsNotRenderedAsConfirmedSuccess() {
        assertTrue(main.contains("outcome.state.phase"))
        val compact = main.replace(Regex("\\s+"), " ")
        assertTrue(
            compact.contains(
                "SleepTimerPhase.REQUESTED, SleepTimerPhase.UNKNOWN -> MobileUi.StatusKind.INFO",
            ),
        )
        assertTrue(
            compact.contains(
                "SleepTimerPhase.ARMED, SleepTimerPhase.OFF -> MobileUi.StatusKind.SUCCESS",
            ),
        )
    }
}
