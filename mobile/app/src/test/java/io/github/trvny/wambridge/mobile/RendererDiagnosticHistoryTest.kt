package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererDiagnosticHistoryTest {
    @Test
    fun retryDoesNotErasePreviousFailureOrSpeakerError() {
        val history = RendererDiagnosticHistory()
        history.begin("10.0.0.102")
        history.recordRelay("10.0.0.102", "Relay failed (SocketTimeoutException)")
        history.recordSpeakerError("10.0.0.102", "M5 error MEDIA_BUFFER_ERROR")

        history.begin("10.0.0.103")
        history.recordRelay("10.0.0.103", "Relay active")

        assertEquals("Relay active", history.relayStatus)
        assertEquals("10.0.0.103", history.relaySpeakerIp)
        assertEquals("Relay failed (SocketTimeoutException)", history.lastFailure)
        assertEquals("10.0.0.102", history.failureSpeakerIp)
        assertEquals("M5 error MEDIA_BUFFER_ERROR", history.lastSpeakerError)
        assertEquals("10.0.0.102", history.errorSpeakerIp)
    }

    @Test
    fun newerFailureReplacesPreviousFailure() {
        val history = RendererDiagnosticHistory()
        history.recordRelay("10.0.0.102", "Relay watchdog timeout")
        history.recordRelay("10.0.0.103", "Relay failed (IOException)")

        assertEquals("Relay failed (IOException)", history.lastFailure)
        assertEquals("10.0.0.103", history.failureSpeakerIp)
    }
}
