package io.github.trvny.wambridge.mobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcyMetadataTest {
    @Test
    fun parsesStreamTitleWithoutTreatingApostrophesAsTerminators() {
        val metadata = "StreamTitle='Don't Stop Me Now';StreamUrl='';"
            .toByteArray(StandardCharsets.ISO_8859_1)

        assertEquals("Don't Stop Me Now", parseIcyStreamTitle(metadata))
    }

    @Test
    fun parsesImageStreamUrlAsArtwork() {
        val metadata =
            "StreamTitle='Artist - Track';StreamUrl='https://radio.example/covers/42.webp?size=512';"
                .toByteArray(StandardCharsets.UTF_8)

        assertEquals(
            "https://radio.example/covers/42.webp?size=512",
            parseIcyArtworkUrl(metadata),
        )
    }

    @Test
    fun stationHomepageStreamUrlIsNotArtwork() {
        val metadata = "StreamUrl='https://radio.example/';"
            .toByteArray(StandardCharsets.UTF_8)

        assertNull(parseIcyArtworkUrl(metadata))
    }

    @Test
    fun missingTitleDoesNotInventMetadata() {
        assertNull(
            parseIcyStreamTitle(
                "StreamUrl='https://example.test';".toByteArray(StandardCharsets.UTF_8),
            ),
        )
    }

    @Test
    fun relayStripsMetadataAndKeepsAudioContinuous() {
        val metadata = paddedMetadata("StreamTitle='Artist - Track';")
        val input = ByteArrayOutputStream().apply {
            write("ABCD".toByteArray())
            write(metadata.size / 16)
            write(metadata)
            write("EFGH".toByteArray())
            write(0)
            write("IJKL".toByteArray())
        }.toByteArray()

        val output = ByteArrayOutputStream()
        val updates = mutableListOf<RadioNowPlaying>()
        relayIcyAudio(
            input = ByteArrayInputStream(input),
            output = output,
            metadataInterval = 4,
            onMetadata = updates::add,
        )

        assertEquals("ABCDEFGHIJKL", output.toString(StandardCharsets.US_ASCII.name()))
        assertEquals(listOf("Artist - Track"), updates.map { it.title })
    }

    @Test
    fun repeatedMetadataDoesNotSpamStateUpdates() {
        val metadata = paddedMetadata(
            "StreamTitle='Same';StreamUrl='https://radio.example/same.jpg';",
        )
        val input = ByteArrayOutputStream().apply {
            write("ABCD".toByteArray())
            write(metadata.size / 16)
            write(metadata)
            write("EFGH".toByteArray())
            write(metadata.size / 16)
            write(metadata)
            write("IJKL".toByteArray())
        }.toByteArray()

        val updates = mutableListOf<RadioNowPlaying>()
        relayIcyAudio(
            input = ByteArrayInputStream(input),
            output = ByteArrayOutputStream(),
            metadataInterval = 4,
            onMetadata = updates::add,
        )

        assertEquals(1, updates.size)
        assertEquals("Same", updates.single().title)
        assertEquals("https://radio.example/same.jpg", updates.single().artworkUrl)
    }

    @Test
    fun newTitleWithoutNewArtworkClearsOldTrackCover() {
        val first = paddedMetadata(
            "StreamTitle='First';StreamUrl='https://radio.example/first.jpg';",
        )
        val second = paddedMetadata("StreamTitle='Second';")
        val input = ByteArrayOutputStream().apply {
            write("ABCD".toByteArray())
            write(first.size / 16)
            write(first)
            write("EFGH".toByteArray())
            write(second.size / 16)
            write(second)
            write("IJKL".toByteArray())
        }.toByteArray()

        val updates = mutableListOf<RadioNowPlaying>()
        relayIcyAudio(
            input = ByteArrayInputStream(input),
            output = ByteArrayOutputStream(),
            metadataInterval = 4,
            onMetadata = updates::add,
        )

        assertEquals(2, updates.size)
        assertEquals("https://radio.example/first.jpg", updates.first().artworkUrl)
        assertEquals("Second", updates.last().title)
        assertNull(updates.last().artworkUrl)
    }

    @Test
    fun emptyStreamTitleClearsExistingMetadata() {
        val metadata = paddedMetadata("StreamTitle='';")
        val input = ByteArrayOutputStream().apply {
            write("ABCD".toByteArray())
            write(metadata.size / 16)
            write(metadata)
        }.toByteArray()

        val updates = mutableListOf<RadioNowPlaying>()
        relayIcyAudio(
            input = ByteArrayInputStream(input),
            output = ByteArrayOutputStream(),
            metadataInterval = 4,
            onMetadata = updates::add,
        )

        assertEquals(listOf<String?>(null), updates.map { it.title })
    }

    @Test
    fun utf8TitlesSurviveWhenTheStationUsesUtf8() {
        val title = "Łona - Żadnych złudzeń"
        val metadata = paddedMetadata("StreamTitle='$title';", StandardCharsets.UTF_8)

        assertEquals(title, parseIcyStreamTitle(metadata))
    }

    private fun paddedMetadata(
        value: String,
        charset: java.nio.charset.Charset = StandardCharsets.ISO_8859_1,
    ): ByteArray {
        val data = value.toByteArray(charset)
        val size = ((data.size + 15) / 16) * 16
        return ByteArray(size).also { target ->
            data.copyInto(target)
        }
    }
}
