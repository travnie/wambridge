package io.github.trvny.wambridge.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTranscoderPolicyTest {
    @Test
    fun hlsPathUsesPhoneTranscoder() {
        assertTrue(radioNeedsPhoneTranscode("https://example.test/live/index.m3u8"))
    }

    @Test
    fun oggAndOpusPathsUsePhoneTranscoder() {
        assertTrue(radioNeedsPhoneTranscode("https://example.test/live.ogg"))
        assertTrue(radioNeedsPhoneTranscode("https://example.test/live.opus?token=abc"))
    }

    @Test
    fun contentTypeCanSelectTranscoderWhenUrlHasNoExtension() {
        assertTrue(
            radioNeedsPhoneTranscode(
                "https://example.test/live",
                "application/vnd.apple.mpegurl; charset=utf-8",
            ),
        )
        assertTrue(
            radioNeedsPhoneTranscode(
                "https://example.test/stream",
                "audio/ogg",
            ),
        )
    }

    @Test
    fun hlsContentTypeForcesHlsMediaSourceWithoutM3u8Suffix() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            radioTranscodeMediaMime(
                "https://example.test/live",
                "application/vnd.apple.mpegurl; charset=utf-8",
            ),
        )
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            radioTranscodeMediaMime("https://example.test/live/index.m3u8"),
        )
        assertEquals(
            null,
            radioTranscodeMediaMime("https://example.test/live.ogg", "audio/ogg"),
        )
    }

    @Test
    fun media3MetadataMapsTrackAndArtworkIntoSharedNowPlaying() {
        val metadata = media3RadioNowPlaying(
            title = "Track",
            artist = "Artist",
            artworkUri = "https://radio.example/art/current",
        )

        assertEquals("Artist - Track", metadata.title)
        assertEquals("https://radio.example/art/current", metadata.artworkUrl)
    }

    @Test
    fun media3MetadataRejectsNonWebArtworkUris() {
        val metadata = media3RadioNowPlaying(
            title = "Track",
            artist = null,
            artworkUri = "content://radio.example/art/42",
        )

        assertEquals("Track", metadata.title)
        assertEquals(null, metadata.artworkUrl)
    }

    @Test
    fun ordinaryDirectRadioStaysOnLightweightRelay() {
        assertFalse(
            radioNeedsPhoneTranscode(
                "https://example.test/live.mp3",
                "audio/mpeg",
            ),
        )
        assertFalse(
            radioNeedsPhoneTranscode(
                "https://example.test/live.aac",
                "audio/aac",
            ),
        )
    }

    @Test
    fun endlessWavHeaderIsPcm16Stereo44100WithUnknownLengths() {
        val header = endlessWavHeader()
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(header.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("fmt ", String(header.copyOfRange(12, 16), Charsets.US_ASCII))
        assertEquals("data", String(header.copyOfRange(36, 40), Charsets.US_ASCII))

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-1, buffer.getInt(4))
        assertEquals(1, buffer.getShort(20).toInt())
        assertEquals(TRANSCODE_CHANNEL_COUNT, buffer.getShort(22).toInt())
        assertEquals(TRANSCODE_SAMPLE_RATE_HZ, buffer.getInt(24))
        assertEquals(TRANSCODE_SAMPLE_RATE_HZ * TRANSCODE_CHANNEL_COUNT * 2, buffer.getInt(28))
        assertEquals(TRANSCODE_CHANNEL_COUNT * 2, buffer.getShort(32).toInt())
        assertEquals(16, buffer.getShort(34).toInt())
        assertEquals(-1, buffer.getInt(40))
    }
}
