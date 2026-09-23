package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionsTest {
    private val quick = listOf(
        MobileRadioStation("bbc1", listOf("https://one.example/live")),
        MobileRadioStation("trojka", listOf("https://two.example/live")),
        MobileRadioStation("czworka", listOf("https://three.example/live")),
    )

    @Test
    fun idleRadioTilePrefersLastPlayed() {
        val last = MobileRadioStation("radioparadise", listOf("https://rp.example/live"))
        val default = quick[1]

        assertEquals(
            last,
            nextRadioTileStation(
                quickStations = quick,
                lastPlayed = last,
                defaultStation = default,
                activeAlias = null,
            ),
        )
    }

    @Test
    fun idleRadioTileFallsBackToDefaultThenTop3() {
        assertEquals(
            quick[1],
            nextRadioTileStation(quick, null, quick[1], null),
        )
        assertEquals(
            quick[0],
            nextRadioTileStation(quick, null, null, null),
        )
        assertNull(nextRadioTileStation(emptyList(), null, null, null))
    }

    @Test
    fun activeRadioTileCyclesTop3AndWraps() {
        assertEquals(
            "trojka",
            nextRadioTileStation(quick, null, null, "BBC1")?.alias,
        )
        assertEquals(
            "bbc1",
            nextRadioTileStation(quick, null, null, "czworka")?.alias,
        )
    }

    @Test
    fun activeStationOutsideTop3StartsCycleAtFirstQuickStation() {
        assertEquals(
            "bbc1",
            nextRadioTileStation(quick, null, null, "radioparadise")?.alias,
        )
    }

    @Test
    fun launcherActionsParseOnlyExplicitKnownCommands() {
        assertEquals(
            AppQuickAction.PlayStation("trojka"),
            appQuickAction(LauncherQuickActions.ACTION_PLAY_STATION, " trojka "),
        )
        assertEquals(
            AppQuickAction.Stop,
            appQuickAction(LauncherQuickActions.ACTION_STOP, null),
        )
        assertEquals(
            AppQuickAction.Standby,
            appQuickAction(LauncherQuickActions.ACTION_STANDBY, null),
        )
        assertNull(appQuickAction(LauncherQuickActions.ACTION_PLAY_STATION, " "))
        assertNull(appQuickAction("other", "trojka"))
    }

    @Test
    fun top3AliasesSelectCurrentStationsInPackOrder() {
        val available = listOf(quick[2], quick[0], quick[1])
        val selected = stationsForAliases(
            available,
            listOf("bbc1", "missing", "TROJKA", "czworka"),
        )

        assertEquals(listOf("bbc1", "trojka", "czworka"), selected.map { it.alias })
        assertTrue(selected.none { it.alias == "missing" })
    }
}
