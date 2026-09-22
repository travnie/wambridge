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
