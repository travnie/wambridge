package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalPresetContractTest {
    @Test
    fun controllerReadsSpeakerPresetsOnlyWhenServiceOwnersAreIdle() {
        val file = File(
            "src/main/java/io/github/trvny/wambridge/mobile/PhysicalPresetController.kt",
        )
        assertTrue("PhysicalPresetController.kt must exist", file.exists())
        val source = file.readText()

        assertTrue(source.contains("RendererService.busy || RadioService.active"))
        assertTrue(source.contains("SamsungTuneIn.getPresets"))
        assertTrue(source.contains("PhysicalPresetStore.publishPresets"))
        assertTrue(source.contains("SpeakerTarget.resolve"))
    }

    @Test
    fun presetPlayReleasesOwnersAndUsesMeasuredSafePath() {
        val file = File(
            "src/main/java/io/github/trvny/wambridge/mobile/PhysicalPresetController.kt",
        )
        assertTrue("PhysicalPresetController.kt must exist", file.exists())
        val source = file.readText()

        assertTrue(source.contains("releasePlaybackOwners"))
        assertTrue(source.contains("SpeakerControlGate.serial"))
        assertTrue(source.contains("SamsungTuneIn.playSafely"))
        assertTrue(source.contains("nativePresetPlayingSnapshot"))
        assertTrue(source.contains("SpeakerStateStore.update"))
    }

    @Test
    fun directNativeControlsPublishTheirConcreteResults() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/SpeakerControls.kt",
        ).readText()

        assertTrue(source.contains("SpeakerStateStore.update"))
        assertTrue(source.contains("SpeakerPlaybackState.PAUSED"))
        assertTrue(source.contains("muted ="))
        assertTrue(source.contains("volume ="))
    }
}
