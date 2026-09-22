package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSurfaceContractTest {
    private val diagnostics = File(
        "src/main/java/io/github/trvny/wambridge/mobile/DiagnosticsActivity.kt",
    )
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun diagnosticsActivityIsDeclaredAndUsesSharedRuntimeState() {
        assertTrue(manifest.contains(".DiagnosticsActivity"))
        val source = diagnostics.readText()
        assertTrue(source.contains("SpeakerStateStore.current()"))
        assertTrue(source.contains("WifiLan.preferredTarget"))
        assertTrue(source.contains("diagnosticsReport("))
        assertTrue(source.contains("RendererService.lastStatus"))
        assertTrue(source.contains("RadioService.lastStatus"))
    }

    @Test
    fun copyDiagnosticsUsesAndroidClipboard() {
        val source = diagnostics.readText()
        assertTrue(source.contains("ClipboardManager"))
        assertTrue(source.contains("ClipData.newPlainText"))
        assertTrue(source.contains("toPlainText()"))
    }

    @Test
    fun fixConnectionStopsOwnersThenForcesSharedDiscoveryOffMainThread() {
        val source = diagnostics.readText()
        assertTrue(source.contains("Executors.newSingleThreadExecutor"))
        assertTrue(source.contains("RendererService.ACTION_STOP"))
        assertTrue(source.contains("RadioService.ACTION_STOP"))
        assertTrue(source.contains("SpeakerTarget.resolveDetailed("))
        assertTrue(source.contains("forceDiscovery = true"))
        assertTrue(source.contains("runOnUiThread"))
    }
}
