package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravnyRadioMetadataTest {
    @Test
    fun parsesSongArtistAndRelativeCover() {
        val poll = parseTravnyRadioNowPlaying(
            """
            {
              "live": true,
              "song": {
                "artist": "Trikk",
                "title": "Marazul",
                "album": "Test",
                "cover": "/artwork/0123456789abcdefabcd.jpg"
              }
            }
            """.trimIndent(),
        )

        assertEquals("Trikk - Marazul", poll.nowPlaying.title)
        assertEquals(
            "https://travny-radio.taild44c6c.ts.net/artwork/0123456789abcdefabcd.jpg",
            poll.nowPlaying.artworkUrl,
        )
        assertEquals(15_000L, poll.refreshAfterMs)
    }

    @Test
    fun ignoresUnsafeCoverAndOfflineState() {
        val unsafe = parseTravnyRadioNowPlaying(
            """{"live":true,"song":{"title":"Song","cover":"https://evil.example/logo.png"}}""",
        )
        assertEquals("Song", unsafe.nowPlaying.title)
        assertNull(unsafe.nowPlaying.artworkUrl)

        val offline = parseTravnyRadioNowPlaying(
            """{"live":false,"song":{"title":"Stale","cover":"/artwork/0123456789abcdefabcd.jpg"}}""",
        )
        assertNull(offline.nowPlaying.title)
        assertNull(offline.nowPlaying.artworkUrl)
    }

    @Test
    fun detectsOnlyKnownSecureStreamHosts() {
        assertTrue(
            hasTravnyRadioMetadata(
                MobileRadioStation(
                    "travny",
                    listOf("https://travny-radio.taild44c6c.ts.net/radio.mp3"),
                ),
            ),
        )
        assertTrue(
            hasTravnyRadioMetadata(
                MobileRadioStation("travny", listOf("https://radio.bartek.lol/radio.mp3")),
            ),
        )
        assertFalse(
            hasTravnyRadioMetadata(
                MobileRadioStation("untrusted", listOf("https://radio.bartek.lol.evil.test/radio.mp3")),
            ),
        )
        assertFalse(
            hasTravnyRadioMetadata(
                MobileRadioStation("untrusted", listOf("http://radio.bartek.lol/radio.mp3")),
            ),
        )
        val mixed = MobileRadioStation(
            "mixed",
            listOf(
                "https://example.org/other.mp3",
                "https://travny-radio.taild44c6c.ts.net/radio.mp3",
            ),
        )
        assertTrue(hasTravnyRadioMetadata(mixed))
        assertFalse(isTravnyRadioStream(mixed.urls.first()))
        assertTrue(isTravnyRadioStream(mixed.urls.last()))
    }
}
