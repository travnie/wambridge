package io.github.trvny.wambridge.mobile

import android.os.SystemClock

internal class RelayTimeline(private val clock: () -> Long, private val capacity: Int = 128) {
    init { require(capacity > 0) }

    private val entries = ArrayDeque<String>()
    @Volatile var enabled = true
        private set
    private var origin = 0L
    private var nextConnection = 0L

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        entries.clear()
        origin = clock()
        enabled = value
    }

    @Synchronized
    fun connection(): Long = ++nextConnection

    @Synchronized
    fun record(message: String) {
        if (!enabled) return
        val now = clock()
        if (entries.isEmpty()) origin = now
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast("+${now - origin}ms ${message.take(300)}")
    }

    @Synchronized
    fun report(): String = when {
        !enabled -> "Off"
        entries.isEmpty() -> "On · no events yet"
        else -> entries.joinToString("\n")
    }
}

internal object ExtendedRelayDiagnostics {
    const val PREF = "extended_relay_diagnostics"
    val timeline = RelayTimeline(SystemClock::elapsedRealtime)
}

// Inspect only bytes already read by the relay. Never read ahead or hold audio.
internal class RelayFormatProbe(private val format: String) {
    private val header = ByteArray(4096)
    private var size = 0
    private var done = false

    fun observe(buffer: ByteArray, count: Int, enabled: Boolean = true): String? {
        if (done || count <= 0) return null
        if (!enabled) {
            done = true
            return null
        }
        val copied = minOf(count, header.size - size)
        buffer.copyInto(header, size, 0, copied)
        size += copied
        val result = when (format) {
            "audio/wav", "audio/x-wav" -> wav()
            "audio/flac", "audio/x-flac" -> flac()
            else -> "parameters unavailable"
        }
        if (result != null || size == header.size) {
            done = true
            return result ?: "header parameters unavailable"
        }
        return null
    }

    private fun tag(offset: Int, value: String): Boolean =
        offset >= 0 && offset <= size - value.length &&
            value.indices.all { header[offset + it].toInt() == value[it].code }

    private fun u(i: Int) = header[i].toInt() and 255
    private fun le16(i: Int) = u(i) or (u(i + 1) shl 8)
    private fun le32(i: Int): Long = le16(i).toLong() or (le16(i + 2).toLong() shl 16)

    private fun wav(): String? {
        if (size < 12) return null
        if (!tag(0, "RIFF") || !tag(8, "WAVE")) return "unrecognized WAV header"
        var offset = 12
        while (offset <= size - 8) {
            val length = le32(offset + 4)
            val start = offset + 8
            if (tag(offset, "fmt ")) {
                if (length < 16) return "invalid WAV fmt chunk"
                if (size - start < 16) return null
                return "WAV codec=${le16(start)} rate=${le32(start + 4)}Hz channels=${le16(start + 2)} bits=${le16(start + 14)}"
            }
            val next = start.toLong() + length + (length and 1L)
            if (next > size || next > header.size) return null
            offset = next.toInt()
        }
        return null
    }

    private fun flac(): String? {
        if (size < 8) return null
        if (!tag(0, "fLaC") || (u(4) and 127) != 0 || u(5) != 0 || u(6) != 0 || u(7) != 34) {
            return "unrecognized FLAC STREAMINFO"
        }
        if (size < 26) return null
        val rate = (u(18) shl 12) or (u(19) shl 4) or (u(20) shr 4)
        val channels = ((u(20) shr 1) and 7) + 1
        val bits = (((u(20) and 1) shl 4) or (u(21) shr 4)) + 1
        return "FLAC rate=${rate}Hz channels=$channels bits=$bits"
    }
}
