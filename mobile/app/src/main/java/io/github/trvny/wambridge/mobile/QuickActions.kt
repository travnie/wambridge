package io.github.trvny.wambridge.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import java.util.UUID

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
    private data class ShortcutSpec(
        val id: String,
        val label: String,
        val longLabel: String,
        val action: String,
        val alias: String? = null,
    )

    const val ACTION_PLAY_STATION = "trvny.wambridge.mobile.QUICK_PLAY_STATION"
    const val ACTION_STOP = "trvny.wambridge.mobile.QUICK_STOP"
    const val ACTION_STANDBY = "trvny.wambridge.mobile.QUICK_STANDBY"
    const val EXTRA_ALIAS = "quick_station_alias"
    const val EXTRA_TOKEN = "quick_action_token"

    fun isTrusted(context: Context, intent: Intent?): Boolean {
        val provided = intent?.getStringExtra(EXTRA_TOKEN) ?: return false
        return provided == token(context)
    }

    fun sync(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val max = manager.maxShortcutCountPerActivity
        if (max <= 0) return

        val shortcutToken = token(context)
        val candidates = buildList {
            RadioStationStore(context).quickStations().forEach { station ->
                add(
                    ShortcutSpec(
                        id = "radio:${station.alias.lowercase()}",
                        label = shortcutLabel(station.alias),
                        longLabel = "Play ${shortcutLabel(station.alias)}",
                        action = ACTION_PLAY_STATION,
                        alias = station.alias,
                    ),
                )
            }
            add(ShortcutSpec("stop", "Stop", "Stop playback", ACTION_STOP))
            add(ShortcutSpec("standby", "Standby", "Put M5 in standby", ACTION_STANDBY))
        }

        val desired = candidates.take(max).mapIndexed { index, spec ->
            ShortcutInfo.Builder(context, spec.id)
                .setShortLabel(spec.label)
                .setLongLabel(spec.longLabel)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_qs_tile))
                .setIntent(
                    Intent(context, MainActivity::class.java).apply {
                        action = spec.action
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        spec.alias?.let { putExtra(EXTRA_ALIAS, it) }
                        putExtra(EXTRA_TOKEN, shortcutToken)
                    },
                )
                .setRank(index)
                .build()
        }
        val currentIds = manager.dynamicShortcuts.map { it.id }
        val desiredIds = desired.map { it.id }
        if (currentIds == desiredIds) return

        runCatching { manager.setDynamicShortcuts(desired) }
    }

    private fun token(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(
            RendererService.PREFS,
            Context.MODE_PRIVATE,
        )
        return preferences.getString(KEY_TOKEN, null)
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_TOKEN, it).apply()
            }
    }

    private const val KEY_TOKEN = "launcher_quick_action_token"

    private fun shortcutLabel(alias: String): String {
        val trimmed = alias.trim()
        if (trimmed.startsWith("bbc", ignoreCase = true)) return trimmed.uppercase()
        return trimmed.replaceFirstChar { first ->
            if (first.isLowerCase()) first.titlecase() else first.toString()
        }
    }
}
