package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedRelayDiagnosticsTest {
    @Test
    fun boundedTimelineClearsAndStopsWhenDisabled() {
        var now = 100L
        val timeline = RelayTimeline({ now }, 2)
        timeline.record("first")
        now += 5
        timeline.record("second")
        now += 15
        timeline.record("third")
        assertFalse(timeline.report().contains("first"))
        assertEquals("+5ms second\n+20ms third", timeline.report())
        timeline.setEnabled(false)
        timeline.record("ignored")
        assertEquals("Off", timeline.report())
        timeline.setEnabled(true)
        assertEquals("On · no events yet", timeline.report())
    }

    @Test
    fun fragmentedWavHeaderDoesNotModifyAudio() {
        val header = byteArrayOf(
            82, 73, 70, 70, 0, 0, 0, 0, 87, 65, 86, 69,
            102, 109, 116, 32, 16, 0, 0, 0,
            1, 0, 2, 0, 68, -84, 0, 0, 16, -79, 2, 0,
            4, 0, 16, 0,
        )
        val probe = RelayFormatProbe("audio/wav")
        val first = header.copyOfRange(0, 25)
        val second = header.copyOfRange(25, header.size)
        assertNull(probe.observe(first, first.size))
        assertEquals("WAV codec=1 rate=44100Hz channels=2 bits=16", probe.observe(second, second.size))
        assertTrue(first.contentEquals(header.copyOfRange(0, 25)))
        assertTrue(second.contentEquals(header.copyOfRange(25, header.size)))
        assertNull(probe.observe(second, second.size))
    }

    @Test
    fun skippedBytesInvalidatePartialHeader() {
        val probe = RelayFormatProbe("audio/wav")
        val prefix = byteArrayOf(
            82, 73, 70, 70, 0, 0, 0, 0, 87, 65, 86, 69,
            102, 109, 116, 32, 16, 0, 0, 0,
        )
        assertNull(probe.observe(prefix, prefix.size))
        assertNull(probe.observe(ByteArray(16), 16, enabled = false))
        assertNull(probe.observe(ByteArray(16), 16))
    }

    @Test
    fun flacStreamInfo() {
        val header = ByteArray(42)
        "fLaC".encodeToByteArray().copyInto(header)
        header[7] = 34
        header[18] = 10
        header[19] = -60
        header[20] = 66
        header[21] = -16
        val probe = RelayFormatProbe("audio/flac")
        val first = header.copyOfRange(0, 19)
        val second = header.copyOfRange(19, header.size)
        assertNull(probe.observe(first, first.size))
        assertEquals("FLAC rate=44100Hz channels=2 bits=16", probe.observe(second, second.size))
    }
}
