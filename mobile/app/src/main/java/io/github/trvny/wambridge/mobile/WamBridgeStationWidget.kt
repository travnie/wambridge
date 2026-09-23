package io.github.trvny.wambridge.mobile

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.RemoteViews

internal fun stationWidgetLabel(
    alias: String,
    activeAlias: String?,
    radioRunning: Boolean,
): String {
    val trimmed = alias.trim()
    val label = if (trimmed.startsWith("bbc", ignoreCase = true)) {
        trimmed.uppercase()
    } else {
        trimmed.replaceFirstChar { first ->
            if (first.isLowerCase()) first.titlecase() else first.toString()
        }
    }
    return if (radioRunning && alias.equals(activeAlias, ignoreCase = true)) {
        "▶ $label"
    } else {
        label
    }
}

class WamBridgeStationWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = preferences(context).edit()
        appWidgetIds.forEach { editor.remove(key(it)) }
        editor.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_PLAY) return

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val alias = selectedAlias(context, appWidgetId) ?: return
        val station = RadioStationStore(context).all().firstOrNull {
            it.alias.equals(alias, ignoreCase = true)
        } ?: return

        context.applicationContext.startForegroundService(
            Intent(context, RadioService::class.java).apply {
                action = RadioService.ACTION_PLAY
                putExtra(RadioService.EXTRA_ALIAS, station.alias)
            },
        )
    }

    companion object {
        private const val ACTION_PLAY = "trvny.wambridge.mobile.STATION_WIDGET_PLAY"
        private const val KEY_PREFIX = "station_widget_"

        fun saveSelection(context: Context, appWidgetId: Int, alias: String) {
            preferences(context).edit().putString(key(appWidgetId), alias).apply()
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WamBridgeStationWidget::class.java),
            )
            ids.forEach { update(context, manager, it) }
        }

        fun update(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val alias = selectedAlias(context, appWidgetId)
            val station = alias?.let { selected ->
                RadioStationStore(context).all().firstOrNull {
                    it.alias.equals(selected, ignoreCase = true)
                }
            }
            val views = RemoteViews(context.packageName, R.layout.widget_wam_bridge_station)
            if (station == null) {
                views.setTextViewText(R.id.widget_station_label, "Choose")
                views.setContentDescription(R.id.widget_station_root, "Choose station")
                views.setOnClickPendingIntent(
                    R.id.widget_station_root,
                    PendingIntent.getActivity(
                        context,
                        appWidgetId,
                        Intent(context, StationWidgetConfigActivity::class.java).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                val snapshot = SpeakerStateStore.current()
                views.setTextViewText(
                    R.id.widget_station_label,
                    stationWidgetLabel(station.alias, snapshot.stationAlias, RadioService.running),
                )
                views.setContentDescription(
                    R.id.widget_station_root,
                    "Play ${station.alias} on Samsung M5",
                )
                views.setOnClickPendingIntent(
                    R.id.widget_station_root,
                    PendingIntent.getBroadcast(
                        context,
                        appWidgetId,
                        Intent(context, WamBridgeStationWidget::class.java).apply {
                            action = ACTION_PLAY
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun selectedAlias(context: Context, appWidgetId: Int): String? =
            preferences(context).getString(key(appWidgetId), null)
                ?.trim()
                ?.takeIf(String::isNotEmpty)

        private fun preferences(context: Context) =
            context.applicationContext.getSharedPreferences(
                RendererService.PREFS,
                Context.MODE_PRIVATE,
            )

        private fun key(appWidgetId: Int) = "$KEY_PREFIX$appWidgetId"
    }
}

class StationWidgetConfigActivity : Activity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileUi.applyWindow(this)
        setResult(RESULT_CANCELED)

        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val manager = AppWidgetManager.getInstance(this)
        val expectedProvider = ComponentName(this, WamBridgeStationWidget::class.java)
        if (
            appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            manager.getAppWidgetInfo(appWidgetId)?.provider != expectedProvider
        ) {
            finish()
            return
        }

        val content = MobileUi.page(this)
        content.addView(
            MobileUi.header(
                this,
                "Station widget",
                "Choose a one-tap station from the shared top3 pack.",
            ),
        )

        val stations = RadioStationStore(this).quickStations()
        if (stations.isEmpty()) {
            content.addView(MobileUi.status(this, "No quick stations are available."))
        } else {
            val card = MobileUi.card(this)
            card.addView(MobileUi.label(this, "One tap plays"))
            stations.forEachIndexed { index, station ->
                card.addView(
                    MobileUi.button(
                        this,
                        stationWidgetLabel(station.alias, null, false),
                        if (index == 0) {
                            MobileUi.ButtonKind.PRIMARY
                        } else {
                            MobileUi.ButtonKind.SECONDARY
                        },
                    ) {
                        choose(station.alias)
                    },
                )
            }
            content.addView(card)
        }

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun choose(alias: String) {
        WamBridgeStationWidget.saveSelection(this, appWidgetId, alias)
        val manager = AppWidgetManager.getInstance(this)
        WamBridgeStationWidget.update(this, manager, appWidgetId)
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        finish()
    }
}
