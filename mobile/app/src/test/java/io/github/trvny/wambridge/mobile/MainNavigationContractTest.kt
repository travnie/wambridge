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
    fun mainActivityBuildsThreePersistentRootPanes() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
        ).readText()

        assertTrue(source.contains("buildHomePane()"))
        assertTrue(source.contains("buildRadioPane()"))
        assertTrue(source.contains("buildSettingsPane()"))
        assertTrue(source.contains("showDestination("))
        assertTrue(source.contains("\"Home\""))
        assertTrue(source.contains("\"Radio\""))
        assertTrue(source.contains("\"Settings\""))
    }
}
