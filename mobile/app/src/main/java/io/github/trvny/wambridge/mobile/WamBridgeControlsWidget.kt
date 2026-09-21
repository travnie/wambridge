package io.github.trvny.wambridge.mobile

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * Full speaker remote exposed as its own picker entry.
 *
 * The buttons themselves broadcast to [WamBridgeWidget], keeping action routing
 * and speaker ownership in one maintained implementation.
 */
class WamBridgeControlsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach {
            WamBridgeWidget.updateControlsWidget(context, manager, it)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        WamBridgeWidget.updateControlsWidget(context, appWidgetManager, appWidgetId)
    }
}
