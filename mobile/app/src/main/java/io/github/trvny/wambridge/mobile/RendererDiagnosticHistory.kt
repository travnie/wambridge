package io.github.trvny.wambridge.mobile

internal data class RendererDiagnosticEntry(val speakerIp: String, val message: String)
internal data class RendererDiagnosticSnapshot(
    val relay: RendererDiagnosticEntry,
    val failure: RendererDiagnosticEntry,
    val speakerError: RendererDiagnosticEntry,
)

internal class RendererDiagnosticHistory {
    @Volatile var relayStatus = "No renderer relay recorded"
        private set
    @Volatile var relaySpeakerIp = ""
        private set
    @Volatile var lastFailure = "None"
        private set
    @Volatile var failureSpeakerIp = ""
        private set
    @Volatile var lastSpeakerError = "None"
        private set
    @Volatile var errorSpeakerIp = ""
        private set

    @Synchronized
    fun begin(speakerIp: String) {
        relaySpeakerIp = speakerIp
        relayStatus = "Waiting for Neutron stream"
    }

    @Synchronized
    fun recordRelay(speakerIp: String, message: String) {
        relaySpeakerIp = speakerIp
        relayStatus = message
        if (message.startsWith("Relay failed") || message.startsWith("Relay watchdog timeout")) {
            failureSpeakerIp = speakerIp
            lastFailure = message
        }
    }

    @Synchronized
    fun recordSpeakerError(speakerIp: String, message: String) {
        errorSpeakerIp = speakerIp
        lastSpeakerError = message
    }
    @Synchronized
    fun snapshot(): RendererDiagnosticSnapshot = RendererDiagnosticSnapshot(
        relay = RendererDiagnosticEntry(relaySpeakerIp, relayStatus),
        failure = RendererDiagnosticEntry(failureSpeakerIp, lastFailure),
        speakerError = RendererDiagnosticEntry(errorSpeakerIp, lastSpeakerError),
    )
}
