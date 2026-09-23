package io.github.trvny.wambridge.mobile

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Relay an ICY stream while stripping the metadata blocks from the audio bytes.
 *
 * The M5 only receives the original audio payload. Metadata is observed passively
 * from the same upstream connection; no second stream/probe is opened.
 */
internal fun relayIcyAudio(
    input: InputStream,
    output: OutputStream,
    metadataInterval: Int,
    onStreamTitle: (String?) -> Unit,
) {
    require(metadataInterval > 0) { "ICY metadata interval must be positive" }

    val audioBuffer = ByteArray(min(metadataInterval, COPY_BUFFER).coerceAtLeast(1))
    var titleSeen = false
    var lastTitle: String? = null

    while (true) {
        var remaining = metadataInterval
        while (remaining > 0) {
            val count = input.read(audioBuffer, 0, min(audioBuffer.size, remaining))
            if (count < 0) return
            output.write(audioBuffer, 0, count)
            remaining -= count
        }

        val lengthByte = input.read()
        if (lengthByte < 0) return
        val metadataLength = lengthByte * ICY_LENGTH_BLOCK
        if (metadataLength == 0) continue

        val metadata = ByteArray(metadataLength)
        readIcyBlock(input, metadata)
        val parsed = parseIcyStreamTitle(metadata) ?: continue
        val normalized = parsed.trim().takeIf(String::isNotEmpty)
        if (!titleSeen || normalized != lastTitle) {
            titleSeen = true
            lastTitle = normalized
            onStreamTitle(normalized)
        }
    }
}

internal fun parseIcyStreamTitle(metadata: ByteArray): String? {
    val text = decodeIcyMetadata(metadata)
    val marker = "StreamTitle='"
    val start = text.indexOf(marker, ignoreCase = true)
    if (start < 0) return null

    val valueStart = start + marker.length
    val end = text.indexOf("';", startIndex = valueStart)
    return if (end >= 0) {
        text.substring(valueStart, end)
    } else {
        text.substring(valueStart)
    }
}

private fun readIcyBlock(input: InputStream, target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val count = input.read(target, offset, target.size - offset)
        if (count < 0) throw EOFException("ICY metadata block ended early")
        offset += count
    }
}

private fun decodeIcyMetadata(bytes: ByteArray): String {
    val end = bytes.indexOfFirst { it == 0.toByte() }
        .let { if (it >= 0) it else bytes.size }
    if (end == 0) return ""

    val data = bytes.copyOfRange(0, end)
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
            .toString()
    } catch (_: CharacterCodingException) {
        String(data, StandardCharsets.ISO_8859_1)
    }
}

private const val ICY_LENGTH_BLOCK = 16
private const val COPY_BUFFER = 64 * 1024
