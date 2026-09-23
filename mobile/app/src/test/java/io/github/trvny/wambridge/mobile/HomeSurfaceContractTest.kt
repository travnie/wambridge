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
    fun homeKeepsPhysicalPresetsOutAndExposesDlnaToggle() {
        val homeStart = main.indexOf("private fun buildHomePane")
        val radioStart = main.indexOf("private fun buildRadioPane")
        val home = main.substring(homeStart, radioStart)

        assertFalse(home.contains("Physical presets"))
        assertFalse(home.contains("homePresetButtons"))
        assertTrue(home.contains("DLNA renderer"))
        assertTrue(home.contains("homeDlnaButton"))
        assertTrue(main.contains("toggleRenderer"))
        assertTrue(main.contains("MobileUi.setToggleSelected"))
    }

    @Test
    fun homeRendersNowPlayingFromSpeakerSnapshot() {
        assertTrue(main.contains("snapshot.stationAlias"))
        assertTrue(main.contains("snapshot.metadata"))
        assertTrue(main.contains("snapshot.owner"))
        assertTrue(main.contains("snapshot.playback"))
        assertTrue(main.contains("snapshot.discovery"))
        assertTrue(main.contains("snapshot.speakerIp"))
    }

    @Test
    fun homeKeepsManualSetupOutOfDailySurfaces() {
        val homeStart = main.indexOf("private fun buildHomePane")
        val radioStart = main.indexOf("private fun buildRadioPane")
        val settingsStart = main.indexOf("private fun buildSettingsPane")

        assertTrue(homeStart >= 0)
        assertTrue(radioStart > homeStart)
        assertTrue(settingsStart > radioStart)

        val home = main.substring(homeStart, radioStart)
        val settings = main.substring(settingsStart)
        val advanced = File(
            "src/main/java/io/github/trvny/wambridge/mobile/AdvancedSettingsActivity.kt",
        ).readText()

        assertFalse(home.contains("IPv4 address"))
        assertFalse(home.contains("Save + test"))
        assertFalse(settings.contains("IPv4 address"))
        assertFalse(settings.contains("Save + test"))
        assertTrue(settings.contains("AdvancedSettingsActivity::class.java"))
        assertTrue(advanced.contains("IPv4 address"))
        assertTrue(advanced.contains("Save + test"))
    }

    @Test
    fun radioStartsWithTuneInThenShowsOtherStations() {
        val radioStart = main.indexOf("private fun buildRadioPane")
        val settingsStart = main.indexOf("private fun buildSettingsPane")
        assertTrue(radioStart >= 0)
        assertTrue(settingsStart > radioStart)

        val radio = main.substring(radioStart, settingsStart)
        assertTrue(radio.indexOf("\"TuneIn\"") < radio.indexOf("\"Other stations\""))
        assertTrue(radio.contains("radioTuneInView"))
        assertTrue(radio.contains("Browse TuneIn"))
        assertTrue(radio.contains("Refresh presets"))
        assertTrue(radio.contains("radioStationsView"))
        assertTrue(radio.contains("Manage stations"))
        assertTrue(radio.contains("CatalogueActivity::class.java"))
        assertTrue(radio.contains("RadioStationsActivity::class.java"))
        assertTrue(main.contains("snapshot.allPresets"))
        assertTrue(main.contains("playTuneInPreset"))
        assertTrue(main.contains("playSavedStation"))
    }

    @Test
    fun settingsDoesNotDuplicateRadioNavigation() {
        val settingsStart = main.indexOf("private fun buildSettingsPane")
        val settings = main.substring(settingsStart)

        assertFalse(settings.contains("Physical presets"))
        assertFalse(settings.contains("Saved stations"))
    }

    @Test
    fun presetIoRunsOnDedicatedBackgroundExecutor() {
        assertTrue(main.contains("presetExecutor"))
        assertTrue(main.contains("wam-mobile-physical-presets"))
        assertTrue(main.contains("isFinishing || isDestroyed"))
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
    @Test
    fun homeExposesTheExistingConfirmedStopPath() {
        val homeStart = main.indexOf("private fun buildHomePane")
        val radioStart = main.indexOf("private fun buildRadioPane")
        val home = main.substring(homeStart, radioStart)

        assertTrue(home.contains("\"Stop\""))
        assertTrue(main.contains("TuneInActivity.ACTION_STOP_PLAYBACK"))
        assertTrue(main.contains("stopHomePlayback"))
    }

    @Test
    fun homeControlFailuresReportToTheCallingSurface() {
        val start = main.indexOf("private fun runSpeakerControl")
        val end = main.indexOf("private fun refreshSpeakerControlButtons", start)
        val control = main.substring(start, end)

        assertTrue(control.contains("onFailure = { error ->"))
        assertTrue(control.contains("feedbackView"))
        assertFalse(
            control.contains(
                "onFailure = { error ->\n" +
                    "                        MobileUi.setStatus(\n" +
                    "                            statusView,",
            ),
        )
    }
}
