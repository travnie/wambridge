package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioMediaSessionContractTest {
    private val source = File(
        "src/main/java/io/github/trvny/wambridge/mobile/RadioMediaSession.kt",
    )
    private val renderer = File(
        "src/main/java/io/github/trvny/wambridge/mobile/RendererService.kt",
    ).readText()

    @Test
    fun radioOwnsFrameworkMediaSessionAndExistingServiceActions() {
        val text = source.readText()
        assertTrue(text.contains("MediaSession("))
        assertTrue(text.contains("setCallback"))
        assertTrue(text.contains("RadioService.ACTION_PAUSE"))
        assertTrue(text.contains("RadioService.ACTION_RESUME"))
        assertTrue(text.contains("RadioService.ACTION_STOP"))
        assertTrue(text.contains("PlaybackState.Builder"))
        assertTrue(text.contains("MediaMetadata.Builder"))
    }

    @Test
    fun sessionExposesTokenAndReleasesCleanly() {
        val text = source.readText()
        assertTrue(text.contains("sessionToken"))
        assertTrue(text.contains("isActive"))
        assertTrue(text.contains("release()"))
    }

    @Test
    fun rendererNeverCreatesCompetingMediaSession() {
        assertFalse(renderer.contains("MediaSession"))
    }
    @Test
    fun radioServicePublishesSessionFromItsExistingStateAndNotification() {
        val radio = File(
            "src/main/java/io/github/trvny/wambridge/mobile/RadioService.kt",
        ).readText()

        assertTrue(radio.contains("RadioMediaSession"))
        assertTrue(radio.contains("mediaSession.update("))
        assertTrue(radio.contains("running = running && safeVolumeApplied"))
        assertTrue(radio.contains("setMediaSession(mediaSession.sessionToken)"))
        assertTrue(radio.contains("mediaSession.close()"))
        assertTrue(radio.contains("ACTION_PAUSE"))
        assertTrue(radio.contains("ACTION_RESUME"))
        assertTrue(radio.contains("setPaused(true)"))
        assertTrue(radio.contains("setPaused(false)"))
    }
    @Test
    fun lateRadioWorkerCannotPublishIntoReleasedSession() {
        val text = source.readText()
        assertTrue(text.contains("@Synchronized"))
        assertTrue(text.contains("private var closed = false"))
        assertTrue(text.contains("if (closed) return"))
        assertTrue(text.contains("if (closed) return"))
    }
}
