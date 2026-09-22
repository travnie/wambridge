package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationContractTest {
    @Test
    fun mobileUiExposesBottomNavigationPrimitives() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/MobileUi.kt",
        ).readText()

        assertTrue(source.contains("fun bottomNavigation("))
        assertTrue(source.contains("fun navigationButton("))
        assertTrue(source.contains("fun setNavigationSelected("))
    }

    @Test
    fun homeSpeakerStateSubscriptionIsLifecycleBoundAndMainThreadRendered() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
        ).readText()

        assertTrue(source.contains("override fun onStart()"))
        assertTrue(source.contains("SpeakerStateStore.subscribe"))
        assertTrue(source.contains("runOnUiThread"))
        assertTrue(source.contains("override fun onStop()"))
        assertTrue(source.contains("speakerStateSubscription?.close()"))
        assertTrue(source.contains("renderHomeState("))
    }
}
