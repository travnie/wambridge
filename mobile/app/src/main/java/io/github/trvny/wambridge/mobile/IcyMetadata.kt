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
 * Relay an ICY stream while stripping metadata blocks from the audio bytes.
 *
 * The M5 only receives the original audio payload. Metadata is observed passively
 * from the same upstream connection; no second stream/probe is opened.
 */
internal fun relayIcyAudio(
    input: InputStream,
    output: OutputStream,
    metadataInterval: Int,
    onMetadata: (RadioNowPlaying) -> Unit,
) {
    require(metadataInterval > 0) { "ICY metadata interval must be positive" }

    val audioBuffer = ByteArray(min(metadataInterval, COPY_BUFFER).coerceAtLeast(1))
    var current = RadioNowPlaying()
    var emitted = false

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
        val fields = parseIcyFields(metadata)
        if (fields.isEmpty()) continue

        val titlePresent = fields.containsKey("streamtitle")
        val artworkPresent = fields.containsKey("streamurl")
        var next = current

        if (titlePresent) {
            val title = fields["streamtitle"]?.trim()?.takeIf(String::isNotEmpty)
            next = next.copy(title = title)
            if (title != current.title && !artworkPresent) {
                // A new track without new art should not keep the previous track's cover.
                next = next.copy(artworkUrl = null)
            }
        }
        if (artworkPresent) {
            next = next.copy(artworkUrl = likelyArtworkUrl(fields["streamurl"]))
        }

        if (!emitted || next != current) {
            emitted = true
            current = next
            onMetadata(next)
        }
    }
}

internal fun parseIcyStreamTitle(metadata: ByteArray): String? =
    parseIcyFields(metadata)["streamtitle"]

internal fun parseIcyArtworkUrl(metadata: ByteArray): String? =
    likelyArtworkUrl(parseIcyFields(metadata)["streamurl"])

internal fun parseIcyFields(metadata: ByteArray): Map<String, String> {
    val text = decodeIcyMetadata(metadata)
    return buildMap {
        ICY_FIELD.findAll(text).forEach { match ->
            val key = match.groupValues[1].lowercase()
            put(key, match.groupValues[2])
        }
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

private val ICY_FIELD = Regex("([A-Za-z][A-Za-z0-9_-]*)='(.*?)';")
private const val ICY_LENGTH_BLOCK = 16
private const val COPY_BUFFER = 64 * 1024
