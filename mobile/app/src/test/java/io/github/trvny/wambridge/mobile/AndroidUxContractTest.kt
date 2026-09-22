package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUxContractTest {
    @Test
    fun manifestDeclaresNotificationPermissionAndBothWidgets() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(manifest.contains(".WamBridgeWidget"))
        assertTrue(manifest.contains(".WamBridgeControlsWidget"))
        assertTrue(manifest.contains("@xml/wam_bridge_controls_widget_info"))
    }

    @Test
    fun remoteWidgetStartsWithTheControlsLayout() {
        val info = File("src/main/res/xml/wam_bridge_controls_widget_info.xml").readText()

        assertTrue(info.contains("@layout/widget_wam_bridge_controls"))
    }

    @Test
    fun widgetAndTileStartOnlyTheRendererAndDoNotOwnDiscovery() {
        val widget = File(
            "src/main/java/io/github/trvny/wambridge/mobile/WamBridgeWidget.kt",
        ).readText()
        val tile = File(
            "src/main/java/io/github/trvny/wambridge/mobile/WamBridgeTileService.kt",
        ).readText()

        for (source in listOf(widget, tile)) {
            assertTrue(source.contains("RendererService.ACTION_START"))
            assertTrue(source.contains("SpeakerStateStore.current()"))
            assertTrue(!source.contains("WamDiscovery.discover("))
            assertTrue(!source.contains("SpeakerTarget.resolve"))
        }
    }

    @Test
    fun widgetSettingsRouteUsesTheSettingsDestination() {
        val widget = File(
            "src/main/java/io/github/trvny/wambridge/mobile/WamBridgeWidget.kt",
        ).readText()

        assertTrue(
            widget.contains(
                "MainNavigation.intent(context, MainDestination.SETTINGS)",
            ),
        )
    }
}
