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
        assertFalse(source.contains("WamDiscovery.discover("))
    }
}
