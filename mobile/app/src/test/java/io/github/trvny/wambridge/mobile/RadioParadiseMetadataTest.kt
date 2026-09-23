package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioParadiseMetadataTest {
    @Test
    fun parsesTrackArtworkAndApiRefreshHint() {
        val poll = parseRadioParadiseNowPlaying(
            """
            {
              "time": 163,
              "artist": "Shook Twins",
              "title": "Awhile",
              "album": "What We Do",
              "cover": "https://img.radioparadise.com/covers/l/10669.jpg",
              "cover_med": "https://img.radioparadise.com/covers/m/10669.jpg"
            }
            """.trimIndent(),
        )

        assertEquals("Shook Twins - Awhile", poll.nowPlaying.title)
        assertEquals(
            "https://img.radioparadise.com/covers/m/10669.jpg",
            poll.nowPlaying.artworkUrl,
        )
        assertEquals(164_500L, poll.refreshAfterMs)
    }

    @Test
    fun detectsRadioParadiseByMaintainedStreamHost() {
        assertTrue(
            hasRadioParadiseMetadata(
                MobileRadioStation(
                    "radioparadise",
                    listOf("http://stream.radioparadise.com/ogg-192m"),
                ),
            ),
        )
        assertFalse(
            hasRadioParadiseMetadata(
                MobileRadioStation(
                    "other",
                    listOf("https://radio.example/live.mp3"),
                ),
            ),
        )
    }
}
