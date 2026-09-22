package io.github.trvny.wambridge.mobile

import android.service.quicksettings.TileService
import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationTest {
    @Test
    fun launcherDefaultsToHome() {
        assertEquals(MainDestination.HOME, mainDestination(null, null))
    }

    @Test
    fun explicitRadioDestinationWins() {
        assertEquals(
            MainDestination.RADIO,
            mainDestination(null, MainDestination.RADIO.name),
        )
    }

    @Test
    fun explicitSettingsDestinationWins() {
        assertEquals(
            MainDestination.SETTINGS,
            mainDestination(null, MainDestination.SETTINGS.name),
        )
    }

    @Test
    fun quickSettingsPreferencesOpenSettings() {
        assertEquals(
            MainDestination.SETTINGS,
            mainDestination(
                TileService.ACTION_QS_TILE_PREFERENCES,
                null,
            ),
        )
    }

    @Test
    fun unknownDestinationFallsBackToHome() {
        assertEquals(MainDestination.HOME, mainDestination(null, "NOPE"))
    }

    @Test
    fun paneVisibilityMatchesEachDestination() {
        assertEquals(
            MainPaneVisibility(home = true, radio = false, settings = false),
            mainPaneVisibility(MainDestination.HOME),
        )
        assertEquals(
            MainPaneVisibility(home = false, radio = true, settings = false),
            mainPaneVisibility(MainDestination.RADIO),
        )
        assertEquals(
            MainPaneVisibility(home = false, radio = false, settings = true),
            mainPaneVisibility(MainDestination.SETTINGS),
        )
    }
}
