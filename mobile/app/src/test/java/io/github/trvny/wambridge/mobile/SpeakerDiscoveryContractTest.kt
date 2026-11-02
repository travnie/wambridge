package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerDiscoveryContractTest {
    @Test
    fun speakerTargetOwnsTheFullSsdpAndLanFallbackPipeline() {
        val target = File(
            "src/main/java/io/github/trvny/wambridge/mobile/SpeakerTarget.kt",
        ).readText()
        val discovery = File(
            "src/main/java/io/github/trvny/wambridge/mobile/WamDiscovery.kt",
        ).readText()

        assertTrue(target.contains("resolveDetailed"))
        assertTrue(target.contains("WamDiscovery.discover"))
        assertTrue(discovery.contains("SpeakerDiscoveryStage.SSDP"))
        assertTrue(discovery.contains("SpeakerDiscoveryStage.LAN_SCAN"))
    }

    @Test
    fun mainActivityDoesNotCallLowLevelDiscoveryDirectly() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
        ).readText()

        assertFalse(source.contains("WamDiscovery.discover("))
    }

    @Test
    fun autoAndManualActivityDiscoveryUseTheSameDetailedResolver() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
        ).readText()

        assertTrue(source.contains("runDiscovery(manual = false)"))
        assertTrue(source.contains("runDiscovery(manual = true)"))
        assertTrue(source.contains("SpeakerTarget.resolveDetailed("))
        assertTrue(source.contains("forceDiscovery = manual"))
        assertFalse(source.contains("WamDiscovery.discover("))
    }

    @Test
    fun startupDiscoveryRetriesAndObservesWifiChanges() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
        ).readText()

        val watcher = source.substringAfter("WifiLan.watch(applicationContext) {")
            .substringBefore("}.onFailure")
        assertTrue(watcher.contains("if (endpoints != wifiDiscoveryEndpoints) {"))
        assertTrue(watcher.contains("scheduleAutoDiscoveryRetry()"))
        assertTrue(source.contains("wifiDiscoveryInitialized && currentEndpoints != wifiDiscoveryEndpoints"))
        assertTrue(source.contains("WifiLan.endpoints(applicationContext) != networkAtStart"))
        assertTrue(source.contains("autoDiscoveryRetriesRemaining = 1"))
        assertTrue(source.contains("!manualDiscoveryActive"))
        assertTrue(source.contains("wifiDiscoveryWatcher?.close()"))
        assertTrue(source.contains("autoDiscoveryRetriesRemaining--"))
        assertTrue(source.contains("scheduleAutoDiscoveryRetry(5_000L)"))
        assertFalse(source.contains("postDelayed(autoDiscoveryRetry, 1_000L)"))
        assertTrue(source.contains("activityStarted = false"))
        assertTrue(source.contains("autoDiscoveryRetryPending"))
    }

    @Test
    fun cancelledDiscoveryCannotLeaveSettingsSearching() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
        ).readText()

        assertTrue(source.contains("discoveryOwnsStatus = false"))
        val cancellationBlock = source
            .substringAfter(
                "if (result.getOrNull() is SpeakerTarget.ResolveOutcome.Cancelled) {",
                missingDelimiterValue = "",
            )
            .substringBefore("}")
        assertTrue(cancellationBlock.contains("clearActiveDiscoveryStage()"))
        assertTrue(source.contains("discoveryOwnsStatus = result.getOrNull() is SpeakerTarget.ResolveOutcome.Ambiguous"))
        assertTrue(source.contains("snapshot.discovery == SpeakerDiscoveryStage.READY"))
        assertTrue(source.contains("if (visibility.settings) {"))
        assertTrue(source.contains("SpeakerDiscoveryStage.AMBIGUOUS"))
        assertTrue(source.contains("SpeakerDiscoveryStage.NEEDS_VERIFICATION"))
    }

    @Test
    fun manuallyChosenSpeakerIdentityIsResolvedOffTheUiThread() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
        ).readText()
        val selectionBody = source
            .substringAfter("private fun useDiscoveredSpeaker")
            .substringBefore("private fun testSpeaker")

        assertTrue(selectionBody.contains("discoveryExecutor.execute"))
        assertTrue(selectionBody.contains("SpeakerTarget.acceptDiscovered"))
        assertTrue(selectionBody.indexOf("discoveryExecutor.execute") < selectionBody.indexOf("SpeakerTarget.acceptDiscovered"))
    }
}
