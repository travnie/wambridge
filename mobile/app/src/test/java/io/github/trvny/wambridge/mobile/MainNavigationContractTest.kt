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
}
