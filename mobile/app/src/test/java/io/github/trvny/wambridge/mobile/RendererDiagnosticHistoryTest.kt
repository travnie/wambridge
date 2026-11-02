package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererDiagnosticHistoryTest {
    private companion object {
        const val SPEAKER_A = "10.0.0.102"
        const val SPEAKER_B = "10.0.0.103"
        const val TIMEOUT_FAILURE = "Relay failed (SocketTimeoutException)"
        const val SPEAKER_ERROR = "M5 error MEDIA_BUFFER_ERROR"
        const val RELAY_ACTIVE = "Relay active"
        const val IO_FAILURE = "Relay failed (IOException)"
    }

    @Test
    fun diagnosticsScreenReadsRetainedServiceHistory() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/DiagnosticsActivity.kt",
        ).readText()

        assertTrue(source.contains("RendererService.diagnosticSnapshot"))
        assertTrue(source.contains("rendererDiagnostics.failure"))
        assertTrue(source.contains("rendererDiagnostics.speakerError"))
    }

    @Test
    fun retryDoesNotErasePreviousFailureOrSpeakerError() {
        val history = RendererDiagnosticHistory()
        history.begin(SPEAKER_A)
        history.recordRelay(SPEAKER_A, TIMEOUT_FAILURE)
        history.recordSpeakerError(SPEAKER_A, SPEAKER_ERROR)

        history.begin(SPEAKER_B)
        history.recordRelay(SPEAKER_B, RELAY_ACTIVE)

        assertEquals(RELAY_ACTIVE, history.relayStatus)
        assertEquals(SPEAKER_B, history.relaySpeakerIp)
        assertEquals(TIMEOUT_FAILURE, history.lastFailure)
        assertEquals(SPEAKER_A, history.failureSpeakerIp)
        assertEquals(SPEAKER_ERROR, history.lastSpeakerError)
        assertEquals(SPEAKER_A, history.errorSpeakerIp)
    }

    @Test
    fun snapshotKeepsSpeakerAndMessageTogether() {
        val history = RendererDiagnosticHistory()
        history.recordRelay(SPEAKER_A, TIMEOUT_FAILURE)
        history.recordSpeakerError(SPEAKER_B, SPEAKER_ERROR)
        val snapshot = history.snapshot()
        assertEquals(SPEAKER_A, snapshot.failure.speakerIp)
        assertEquals(TIMEOUT_FAILURE, snapshot.failure.message)
        assertEquals(SPEAKER_B, snapshot.speakerError.speakerIp)
        assertEquals(SPEAKER_ERROR, snapshot.speakerError.message)
    }

    @Test
    fun newerFailureReplacesPreviousFailure() {
        val history = RendererDiagnosticHistory()
        history.recordRelay(SPEAKER_A, "Relay watchdog timeout")
        history.recordRelay(SPEAKER_B, IO_FAILURE)

        assertEquals(IO_FAILURE, history.lastFailure)
        assertEquals(SPEAKER_B, history.failureSpeakerIp)
    }
}
