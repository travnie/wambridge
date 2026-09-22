package io.github.trvny.wambridge.mobile

import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService

internal enum class MainDestination { HOME, RADIO, SETTINGS }

internal fun mainDestination(
    action: String?,
    requested: String?,
): MainDestination {
    val explicit = requested
        ?.let { value -> MainDestination.entries.firstOrNull { it.name == value } }
    if (explicit != null) return explicit
    if (action == TileService.ACTION_QS_TILE_PREFERENCES) return MainDestination.SETTINGS
    return MainDestination.HOME
}

internal object MainNavigation {
    const val EXTRA_DESTINATION = "main_destination"

    fun intent(context: Context, destination: MainDestination): Intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_DESTINATION, destination.name)
        }
}
