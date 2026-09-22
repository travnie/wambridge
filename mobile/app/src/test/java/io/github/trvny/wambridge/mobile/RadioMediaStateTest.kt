package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioMediaStateTest {
    @Test
    fun startingAndRecoveryAreBuffering() {
        assertEquals(
            RadioMediaPlayback.BUFFERING,
            radioMediaState(starting = true, running = false, recovering = false, paused = false).playback,
        )
        assertEquals(
            RadioMediaPlayback.BUFFERING,
            radioMediaState(starting = false, running = false, recovering = true, paused = false).playback,
        )
    }

    @Test
    fun runningMapsToPlayingOrPaused() {
        assertEquals(
            RadioMediaPlayback.PLAYING,
            radioMediaState(starting = false, running = true, recovering = false, paused = false).playback,
        )
        assertEquals(
            RadioMediaPlayback.PAUSED,
            radioMediaState(starting = false, running = true, recovering = false, paused = true).playback,
        )
    }

    @Test
    fun inactiveRadioIsStopped() {
        val state = radioMediaState(
            starting = false,
            running = false,
            recovering = false,
            paused = false,
        )

        assertEquals(RadioMediaPlayback.STOPPED, state.playback)
        assertTrue(RadioMediaAction.PLAY in state.actions)
        assertTrue(RadioMediaAction.STOP !in state.actions)
    }

    @Test
    fun activeRadioOffersPauseOrResumeAndStop() {
        val playing = radioMediaState(false, true, false, false)
        assertTrue(RadioMediaAction.PAUSE in playing.actions)
        assertTrue(RadioMediaAction.STOP in playing.actions)

        val paused = radioMediaState(false, true, false, true)
        assertTrue(RadioMediaAction.PLAY in paused.actions)
        assertTrue(RadioMediaAction.STOP in paused.actions)
    }
}
