package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    @Test
    fun idleWithoutWifiUsesStableHumanPlaceholders() {
        val report = diagnosticsReport(
            snapshot = SpeakerSnapshot(),
            wifiEndpoint = null,
            appVersion = "1.2.3",
            rendererPhase = "STOPPED",
            rendererStatus = "Stopped",
            radioStatus = "Idle",
        )

        assertEquals("Not connected", report.rows["Speaker IP"])
        assertEquals("Unavailable", report.rows["Wi-Fi"])
        assertEquals("IDLE", report.rows["Owner"])
        assertEquals("STOPPED", report.rows["Playback"])
        assertEquals("1.2.3", report.rows["App version"])
        assertTrue(report.toPlainText().startsWith("WAM Bridge diagnostics\n"))
    }

    @Test
    fun activeRadioIncludesSourceFallbackAndLastErrorInStableOrder() {
        val report = diagnosticsReport(
            snapshot = SpeakerSnapshot(
                owner = SpeakerOwner.RADIO,
                playback = SpeakerPlaybackState.PLAYING,
                speakerIp = "10.0.0.44",
                deviceId = "ABC123",
                source = "https://example.invalid/live.mp3",
                metadata = "Artist - Track",
                fallback = "2/3",
                lastError = "Previous timeout",
            ),
            wifiEndpoint = WifiLan.Endpoint(42L, "10.0.0.117"),
            appVersion = "9.9.9",
            rendererPhase = "STOPPED",
            rendererStatus = "Stopped",
            radioStatus = "BBC Radio 1 · playing",
        )

        val text = report.toPlainText()
        assertTrue(text.contains("Speaker IP: 10.0.0.44"))
        assertTrue(text.contains("Device ID: ABC123"))
        assertTrue(text.contains("Wi-Fi: 10.0.0.117 (network 42)"))
        assertTrue(text.contains("Source: https://example.invalid/live.mp3"))
        assertTrue(text.contains("Now playing: Artist - Track"))
        assertTrue(text.contains("Fallback: 2/3"))
        assertTrue(text.contains("Last error: Previous timeout"))

        assertTrue(text.indexOf("Speaker IP:") < text.indexOf("Owner:"))
        assertTrue(text.indexOf("Owner:") < text.indexOf("Renderer:"))
        assertTrue(text.indexOf("Renderer:") < text.indexOf("Last error:"))
    }
}
