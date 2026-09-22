package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSurfaceContractTest {
    private val main = File(
        "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
    ).readText()
    private val advanced = File(
        "src/main/java/io/github/trvny/wambridge/mobile/AdvancedSettingsActivity.kt",
    )
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun advancedOwnsManualIpAndLowLevelRendererControls() {
        assertTrue(manifest.contains(".AdvancedSettingsActivity"))
        val source = advanced.readText()
        assertTrue(source.contains("IPv4 address"))
        assertTrue(source.contains("Save + test"))
        assertTrue(source.contains("RendererService.isReasonableIpv4"))
        assertTrue(source.contains("SpeakerTarget.rememberManualIp"))
        assertTrue(source.contains("SamsungWamChannel.probe"))
        assertTrue(source.contains("RendererService.ACTION_START"))
        assertTrue(source.contains("RendererService.ACTION_STOP"))
    }

    @Test
    fun normalSettingsKeepsTechnicalSetupBehindAdvanced() {
        val start = main.indexOf("private fun buildSettingsPane")
        val end = main.indexOf("private fun showDestination", start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        val settings = main.substring(start, end)

        assertFalse(settings.contains("IPv4 address"))
        assertFalse(settings.contains("Save + test"))
        assertFalse(settings.contains("Speaker controls"))

        assertTrue(settings.contains("\"Discover\""))
        assertTrue(settings.contains("\"Test connection\""))
        assertTrue(settings.contains("DiagnosticsActivity::class.java"))
        assertTrue(settings.contains("AdvancedSettingsActivity::class.java"))
        assertTrue(settings.contains("\"Quick Settings\""))
        assertTrue(settings.contains("\"Physical presets\""))
        assertTrue(settings.contains("\"Saved stations\""))
    }
}
