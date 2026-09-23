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
        val titles = mutableListOf<String?>()
        relayIcyAudio(
            input = ByteArrayInputStream(input),
            output = output,
            metadataInterval = 4,
            onStreamTitle = titles::add,
        )

        assertEquals("ABCDEFGHIJKL", output.toString(StandardCharsets.US_ASCII.name()))
        assertEquals(listOf("Artist - Track"), titles)
    }

    @Test
    fun repeatedTitlesDoNotSpamStateUpdates() {
        val metadata = paddedMetadata("StreamTitle='Same';")
        val input = ByteArrayOutputStream().apply {
            write("ABCD".toByteArray())
            write(metadata.size / 16)
            write(metadata)
            write("EFGH".toByteArray())
            write(metadata.size / 16)
            write(metadata)
            write("IJKL".toByteArray())
        }.toByteArray()

        val titles = mutableListOf<String?>()
        relayIcyAudio(
            input = ByteArrayInputStream(input),
            output = ByteArrayOutputStream(),
            metadataInterval = 4,
            onStreamTitle = titles::add,
        )

        assertEquals(listOf("Same"), titles)
    }

    @Test
    fun emptyStreamTitleClearsExistingMetadata() {
        val metadata = paddedMetadata("StreamTitle='';")
        val input = ByteArrayOutputStream().apply {
            write("ABCD".toByteArray())
            write(metadata.size / 16)
            write(metadata)
        }.toByteArray()

        val titles = mutableListOf<String?>()
        relayIcyAudio(
            input = ByteArrayInputStream(input),
            output = ByteArrayOutputStream(),
            metadataInterval = 4,
            onStreamTitle = titles::add,
        )

        assertEquals(listOf<String?>(null), titles)
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
