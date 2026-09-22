package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerServiceStateTest {
    @Test
    fun rendererStartingOwnsTheControlPlane() {
        val snapshot = speakerSnapshotForRenderer(
            phase = RendererService.Phase.STARTING,
            ownsPlayback = false,
            transportState = null,
            status = "Finding WAM speaker on Wi-Fi…",
            speakerIp = null,
        )

        assertEquals(SpeakerOwner.RENDERER, snapshot.owner)
        assertEquals(SpeakerPlaybackState.STARTING, snapshot.playback)
    }

    @Test
    fun rendererReadyWithoutPlaybackLeavesSpeakerIdle() {
        val snapshot = speakerSnapshotForRenderer(
            phase = RendererService.Phase.RUNNING,
            ownsPlayback = false,
            transportState = "STOPPED",
            status = "Ready · speaker released",
            speakerIp = "10.0.0.44",
        )

        assertEquals(SpeakerOwner.IDLE, snapshot.owner)
        assertEquals(SpeakerPlaybackState.STOPPED, snapshot.playback)
    }

    @Test
    fun rendererStreamingOwnsSpeakerAndShowsPlaying() {
        val snapshot = speakerSnapshotForRenderer(
            phase = RendererService.Phase.RUNNING,
            ownsPlayback = true,
            transportState = "PLAYING",
            status = "Streaming player → M5",
            speakerIp = "10.0.0.44",
        )

        assertEquals(SpeakerOwner.RENDERER, snapshot.owner)
        assertEquals(SpeakerPlaybackState.PLAYING, snapshot.playback)
    }

    @Test
    fun rendererPausedPlaybackReleasesSpeakerButKeepsPausedState() {
        val snapshot = speakerSnapshotForRenderer(
            phase = RendererService.Phase.RUNNING,
            ownsPlayback = false,
            transportState = "PAUSED_PLAYBACK",
            status = "Paused · speaker released",
            speakerIp = "10.0.0.44",
        )

        assertEquals(SpeakerOwner.IDLE, snapshot.owner)
        assertEquals(SpeakerPlaybackState.PAUSED, snapshot.playback)
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
            ownsPlayback = false,
            transportState = "STOPPED",
            status = "Stopped",
            speakerIp = null,
            current = current,
        )

        assertEquals(SpeakerOwner.RADIO, snapshot.owner)
        assertEquals("trojka", snapshot.stationAlias)
    }

    @Test
    fun startingRadioIsNotReportedAsPlaying() {
        val snapshot = speakerSnapshotForRadio(
            starting = true,
            running = false,
            recovering = false,
            paused = false,
            muted = false,
            volume = 3,
            stationAlias = null,
            status = "Starting radio…",
        )

        assertEquals(SpeakerOwner.RADIO, snapshot.owner)
        assertEquals(SpeakerPlaybackState.STARTING, snapshot.playback)
    }

    @Test
    fun pausedRadioPublishesRadioOwnerAndPausedPlayback() {
        val snapshot = speakerSnapshotForRadio(
            starting = false,
            running = true,
            recovering = false,
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

    @Test
    fun stoppedRadioLeavesSpeakerIdle() {
        val snapshot = speakerSnapshotForRadio(
            starting = false,
            running = false,
            recovering = false,
            paused = false,
            muted = false,
            volume = 3,
            stationAlias = null,
            status = "Stopped",
        )

        assertEquals(SpeakerOwner.IDLE, snapshot.owner)
        assertEquals(SpeakerPlaybackState.STOPPED, snapshot.playback)
    }

    @Test
    fun nativePresetPlaybackPublishesRadioPlayingState() {
        val preset = SamsungTuneIn.Preset(
            contentId = "2",
            title = "BBC Radio 1",
            kind = "speaker",
        )

        val snapshot = nativePresetPlayingSnapshot(
            speakerIp = "10.0.0.44",
            preset = preset,
        )

        assertEquals(SpeakerOwner.RADIO, snapshot.owner)
        assertEquals(SpeakerPlaybackState.PLAYING, snapshot.playback)
        assertEquals("BBC Radio 1", snapshot.stationAlias)
        assertEquals("TuneIn preset", snapshot.source)
        assertEquals("10.0.0.44", snapshot.speakerIp)
    }
}
