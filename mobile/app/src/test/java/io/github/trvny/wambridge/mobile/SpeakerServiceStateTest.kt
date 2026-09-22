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
