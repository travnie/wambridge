package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSurfaceContractTest {
    private val main = File(
        "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
    ).readText()

    @Test
    fun homeUsesSharedRuntimeStateAndExistingControlRouter() {
        assertTrue(main.contains("SpeakerStateStore.subscribe"))
        assertTrue(main.contains("PhysicalPresetStore.subscribe"))
        assertTrue(main.contains("SpeakerControls.Action.PLAY_PAUSE"))
        assertTrue(main.contains("SpeakerControls.Action.MUTE"))
        assertTrue(main.contains("SpeakerControls.Action.VOLUME_DOWN"))
        assertTrue(main.contains("SpeakerControls.Action.VOLUME_UP"))
        assertFalse(main.contains("SamsungTuneIn.request("))
    }

    @Test
    fun homeCreatesExactlyThreePhysicalPresetSlotsFromOneSharedStore() {
        assertTrue(main.contains("PHYSICAL_PRESET_SLOTS"))
        assertTrue(main.contains("homePresetButtons"))
        assertTrue(main.contains("PhysicalPresetController.play"))
        assertTrue(main.contains("PhysicalPresetController.refresh"))
    }

    @Test
    fun homeRendersNowPlayingFromSpeakerSnapshot() {
        assertTrue(main.contains("snapshot.stationAlias"))
        assertTrue(main.contains("snapshot.metadata"))
        assertTrue(main.contains("snapshot.owner"))
        assertTrue(main.contains("snapshot.playback"))
        assertTrue(main.contains("snapshot.discovery"))
    }

    @Test
    fun homeKeepsSetupDetailsInSettings() {
        val homeStart = main.indexOf("private fun buildHomePane")
        val radioStart = main.indexOf("private fun buildRadioPane")
        val settingsStart = main.indexOf("private fun buildSettingsPane")

        assertTrue(homeStart >= 0)
        assertTrue(radioStart > homeStart)
        assertTrue(settingsStart > radioStart)

        val home = main.substring(homeStart, radioStart)
        val settings = main.substring(settingsStart)

        assertFalse(home.contains("IPv4 address"))
        assertFalse(home.contains("Save + test"))
        assertTrue(settings.contains("IPv4 address"))
        assertTrue(settings.contains("Save + test"))
    }
    @Test
    fun radioUsesTheSamePhysicalPresetStoreAsHome() {
        val radioStart = main.indexOf("private fun buildRadioPane")
        val settingsStart = main.indexOf("private fun buildSettingsPane")
        assertTrue(radioStart >= 0)
        assertTrue(settingsStart > radioStart)

        val radio = main.substring(radioStart, settingsStart)
        assertTrue(radio.contains("radioPresetButtons"))
        assertTrue(radio.contains("PHYSICAL_PRESET_SLOTS"))
        assertTrue(main.contains("renderPhysicalPresets"))
        assertTrue(main.contains("PhysicalPresetStore.current()"))
    }

    @Test
    fun radioKeepsSavedStationsAndTuneInExploreEntrypoints() {
        val radioStart = main.indexOf("private fun buildRadioPane")
        val settingsStart = main.indexOf("private fun buildSettingsPane")
        val radio = main.substring(radioStart, settingsStart)

        assertTrue(radio.contains("RadioStationsActivity::class.java"))
        assertTrue(radio.contains("CatalogueActivity::class.java"))
    }

    @Test
    fun thisReleaseDoesNotAddPresetWriteCommandsToMobileProductionCode() {
        val productionRoot = File("src/main/java/io/github/trvny/wambridge/mobile")
        val source = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(source.contains("SetSavePreset"))
        assertFalse(source.contains("SetMovePreset"))
        assertFalse(source.contains("SetRemovePreset"))
    }
}
