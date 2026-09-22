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
}
