package io.github.trvny.wambridge.mobile

internal data class DiagnosticsReport(
    val rows: Map<String, String>,
) {
    fun toPlainText(): String = buildString {
        appendLine("WAM Bridge diagnostics")
        for ((label, value) in rows) {
            append(label)
            append(": ")
            appendLine(value)
        }
    }.trimEnd()
}

internal fun diagnosticsReport(
    snapshot: SpeakerSnapshot,
    wifiEndpoint: WifiLan.Endpoint?,
    appVersion: String,
    rendererPhase: String,
    rendererStatus: String,
    radioStatus: String,
): DiagnosticsReport {
    val rows = linkedMapOf<String, String>()
    rows["Speaker IP"] = snapshot.speakerIp ?: "Not connected"
    rows["Device ID"] = snapshot.deviceId ?: "Unknown"
    rows["Wi-Fi"] = wifiEndpoint?.let { endpoint ->
        endpoint.address + " (network " + endpoint.networkHandle + ")"
    } ?: "Unavailable"
    rows["Owner"] = snapshot.owner.name
    rows["Playback"] = snapshot.playback.name
    rows["Discovery"] = snapshot.discovery.name
    rows["Source"] = snapshot.source ?: "None"
    rows["Now playing"] = snapshot.metadata ?: "None"
    rows["Fallback"] = snapshot.fallback ?: "None"
    rows["Renderer"] = rendererPhase + " · " + rendererStatus
    rows["Radio"] = radioStatus
    rows["Last error"] = snapshot.lastError ?: "None"
    rows["App version"] = appVersion
    return DiagnosticsReport(rows)
}
