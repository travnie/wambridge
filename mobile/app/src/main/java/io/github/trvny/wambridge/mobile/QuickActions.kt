package io.github.trvny.wambridge.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

internal sealed interface AppQuickAction {
    data class PlayStation(val alias: String) : AppQuickAction
    data object Stop : AppQuickAction
    data object Standby : AppQuickAction
}

internal fun appQuickAction(
    action: String?,
    alias: String?,
): AppQuickAction? = when (action) {
    LauncherQuickActions.ACTION_PLAY_STATION ->
        alias?.trim()?.takeIf(String::isNotEmpty)?.let(AppQuickAction::PlayStation)
    LauncherQuickActions.ACTION_STOP -> AppQuickAction.Stop
    LauncherQuickActions.ACTION_STANDBY -> AppQuickAction.Standby
    else -> null
}

internal fun nextRadioTileStation(
    quickStations: List<MobileRadioStation>,
    lastPlayed: MobileRadioStation?,
    defaultStation: MobileRadioStation?,
    activeAlias: String?,
): MobileRadioStation? {
    if (!activeAlias.isNullOrBlank() && quickStations.isNotEmpty()) {
        val current = quickStations.indexOfFirst {
            it.alias.equals(activeAlias, ignoreCase = true)
        }
        return if (current >= 0) {
            quickStations[(current + 1) % quickStations.size]
        } else {
            quickStations.first()
        }
    }
    return lastPlayed ?: defaultStation ?: quickStations.firstOrNull()
}

internal object LauncherQuickActions {
    const val ACTION_PLAY_STATION = "trvny.wambridge.mobile.QUICK_PLAY_STATION"
    const val ACTION_STOP = "trvny.wambridge.mobile.QUICK_STOP"
    const val ACTION_STANDBY = "trvny.wambridge.mobile.QUICK_STANDBY"
    const val EXTRA_ALIAS = "quick_station_alias"

    fun sync(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val max = manager.maxShortcutCountPerActivity
        if (max <= 0) return

        val candidates = buildList {
            RadioStationStore(context).quickStations().forEach { station ->
                add(
                    shortcut(
                        context = context,
                        id = "radio:${station.alias.lowercase()}",
                        label = shortcutLabel(station.alias),
                        longLabel = "Play ${shortcutLabel(station.alias)}",
                        action = ACTION_PLAY_STATION,
                        alias = station.alias,
                    ),
                )
            }
            add(
                shortcut(
                    context = context,
                    id = "stop",
                    label = "Stop",
                    longLabel = "Stop playback",
                    action = ACTION_STOP,
                ),
            )
            add(
                shortcut(
                    context = context,
                    id = "standby",
                    label = "Standby",
                    longLabel = "Put M5 in standby",
                    action = ACTION_STANDBY,
                ),
            )
        }

        val desired = candidates.take(max).mapIndexed { index, item ->
            ShortcutInfo.Builder(context, item.id)
                .setShortLabel(item.shortLabel)
                .setLongLabel(item.longLabel)
                .setIcon(item.icon)
                .setIntent(item.intent)
                .setRank(index)
                .build()
        }
        val currentIds = manager.dynamicShortcuts.map { it.id }
        val desiredIds = desired.map { it.id }
        if (currentIds == desiredIds) return

        runCatching { manager.dynamicShortcuts = desired }
    }

    private fun shortcut(
        context: Context,
        id: String,
        label: String,
        longLabel: String,
        action: String,
        alias: String? = null,
    ): ShortcutInfo =
        ShortcutInfo.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(longLabel)
            .setIcon(Icon.createWithResource(context, R.drawable.ic_qs_tile))
            .setIntent(
                Intent(context, MainActivity::class.java).apply {
                    this.action = action
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    alias?.let { putExtra(EXTRA_ALIAS, it) }
                },
            )
            .build()

    private fun shortcutLabel(alias: String): String {
        val trimmed = alias.trim()
        if (trimmed.startsWith("bbc", ignoreCase = true)) return trimmed.uppercase()
        return trimmed.replaceFirstChar { first ->
            if (first.isLowerCase()) first.titlecase() else first.toString()
        }
    }
}
