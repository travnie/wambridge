package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationDefaultsTest {
    private val bundled = listOf(
        MobileRadioStation("bbc1", listOf("http://builtin/bbc1"), "s24939"),
        MobileRadioStation("trojka", listOf("http://builtin/trojka"), "s15984"),
        MobileRadioStation("czworka", listOf("http://builtin/czworka"), "s118200"),
    )

    @Test
    fun bundledStationsMakeAnEmptyStoreUseful() {
        assertEquals(bundled, mergeRadioStations(emptyList(), bundled))
    }

    @Test
    fun userStationOverridesBundledAlias() {
        val custom = MobileRadioStation("trojka", listOf("http://custom/trojka"))
        val merged = mergeRadioStations(listOf(custom), bundled)

        assertEquals(custom, merged.first { it.alias == "trojka" })
        assertEquals(3, merged.size)
    }

    @Test
    fun hiddenBundledStationStaysDeleted() {
        val merged = mergeRadioStations(
            saved = emptyList(),
            bundled = bundled,
            hiddenBundledAliases = setOf("TROJKA"),
        )

        assertFalse(merged.any { it.alias.equals("trojka", ignoreCase = true) })
        assertEquals(listOf("bbc1", "czworka"), merged.map { it.alias })
    }

    @Test
    fun userOverrideWinsEvenIfAnOldHideMarkerExists() {
        val custom = MobileRadioStation("trojka", listOf("http://custom/trojka"))
        val merged = mergeRadioStations(
            saved = listOf(custom),
            bundled = bundled,
            hiddenBundledAliases = setOf("trojka"),
        )
        assertEquals(custom, merged.first { it.alias == "trojka" })
    }

    @Test
    fun customStationsRemainAlongsideBundledOnes() {
        val custom = MobileRadioStation("my-radio", listOf("http://custom/radio"))
        val merged = mergeRadioStations(listOf(custom), bundled)

        assertEquals(listOf("bbc1", "trojka", "czworka", "my-radio"), merged.map { it.alias })
    }

    @Test
    fun userOrderWinsAndNewBundledStationsAreAppended() {
        val ordered = orderRadioStations(
            bundled,
            listOf("czworka", "BBC1"),
        )

        assertEquals(listOf("czworka", "bbc1", "trojka"), ordered.map { it.alias })
    }

    @Test
    fun staleAndDuplicateOrderEntriesAreIgnored() {
        val ordered = orderRadioStations(
            bundled,
            listOf("nothing", "trojka", "TROJKA"),
        )

        assertEquals(listOf("trojka", "bbc1", "czworka"), ordered.map { it.alias })
    }

    @Test
    fun aSavedAliasIsPlayedFromWhatWasSaved() {
        assertEquals(bundled.first { it.alias == "trojka" }, radioStationToPlay("Trojka", null, bundled))
    }

    @Test
    fun anUnknownAliasHasNothingToPlay() {
        assertNull(radioStationToPlay("nothing-here", null, bundled))
        assertNull(radioStationToPlay("  ", null, bundled))
    }

    @Test
    fun aCatalogueStationIsPlayedFromItsTuneInIdAlone() {
        val station = radioStationToPlay("PR3 Trójka", "s15984", bundled)

        assertEquals(MobileRadioStation("PR3 Trójka", emptyList(), "s15984"), station)
    }

    @Test
    fun aCatalogueStationIsNotConfusedWithASavedOneOfTheSameName() {
        val station = radioStationToPlay("trojka", "s99999", bundled)

        assertEquals("s99999", station?.tuneInId)
        assertEquals(emptyList<String>(), station?.urls)
    }

    @Test
    fun jsonExportRoundTripsFallbacksAndTuneIn() {
        val station = MobileRadioStation(
            "test",
            listOf("https://radio.example/one.mp3", "https://radio.example/two.aac"),
            "s123",
        )

        val imported = importRadioStations("stations.json", exportRadioStationsJson(listOf(station)))

        assertEquals(listOf(station), imported)
    }

    @Test
    fun m3uImportUsesExtinfTitles() {
        val imported = importRadioStations(
            "stations.m3u",
            """
            #EXTM3U
            #EXTINF:-1,One
            https://one.example/live.mp3
            #EXTINF:-1,Two
            http://two.example/live.aac
            """.trimIndent(),
        )

        assertEquals(listOf("One", "Two"), imported.map { it.alias })
        assertEquals(listOf("https://one.example/live.mp3"), imported.first().urls)
    }

    @Test
    fun plsImportPairsFileAndTitleEntries() {
        val imported = importRadioStations(
            "stations.pls",
            """
            [playlist]
            File1=https://one.example/live.mp3
            Title1=One
            File2=http://two.example/live.aac
            Title2=Two
            NumberOfEntries=2
            Version=2
            """.trimIndent(),
        )

        assertEquals(listOf("One", "Two"), imported.map { it.alias })
    }

    @Test
    fun m3uAndPlsExportsKeepEveryDirectlyAddressableStation() {
        val stations = listOf(
            MobileRadioStation("one", listOf("https://one.example/live.mp3")),
            MobileRadioStation("two", listOf("http://two.example/live.aac")),
            MobileRadioStation("tune-only", emptyList(), "s123"),
        )

        val m3u = exportRadioStationsM3u(stations)
        val pls = exportRadioStationsPls(stations)

        assertTrue(m3u.contains("#EXTINF:-1,one"))
        assertTrue(m3u.contains("#EXTINF:-1,two"))
        assertFalse(m3u.contains("tune-only"))
        assertTrue(pls.contains("NumberOfEntries=2"))
    }
}
