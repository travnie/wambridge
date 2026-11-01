package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpLifecycleTest {
    private val udn = "11111111-2222-3333-4444-555555555555"
    private val renderer = "urn:schemas-upnp-org:device:MediaRenderer:1"

    @Test
    fun advertisedTargetsIncludeTheDeviceUuid() {
        assertEquals(
            listOf("uuid:$udn", "upnp:rootdevice", renderer),
            SsdpLifecycle.advertisedTargets(udn, listOf("upnp:rootdevice", renderer)),
        )
    }

    @Test
    fun aliveAnnouncementCarriesLocationAndIdentity() {
        val message = String(
            SsdpLifecycle.alive(
                "239.255.255.250:1900",
                "http://10.0.0.5:49152/description.xml",
                "Android/1.0 UPnP/1.1 WAMBridge/0.1",
                udn,
                renderer,
            ),
        )
        assertTrue("NTS: ssdp:alive\r\n" in message)
        assertTrue("CACHE-CONTROL: max-age=${SsdpLifecycle.MAX_AGE_SECONDS}\r\n" in message)
        assertTrue(SsdpLifecycle.REFRESH_INTERVAL_SECONDS * 2 < SsdpLifecycle.MAX_AGE_SECONDS)
        assertTrue("LOCATION: http://10.0.0.5:49152/description.xml\r\n" in message)
        assertTrue("NT: $renderer\r\n" in message)
        assertTrue("USN: uuid:$udn::$renderer\r\n" in message)
    }

    @Test
    fun refreshScheduleMatchesTheAdvertisedLease() {
        assertEquals(1800L, SsdpLifecycle.MAX_AGE_SECONDS)
        assertEquals(600L, SsdpLifecycle.REFRESH_INTERVAL_SECONDS)
        val rendererSource = File(
            "src/main/java/io/github/trvny/wambridge/mobile/UpnpRenderer.kt",
        ).readText()
        assertTrue(rendererSource.contains("streamWatchdog.scheduleWithFixedDelay("))
        assertTrue(
            rendererSource.contains(
                "SsdpLifecycle.REFRESH_INTERVAL_SECONDS,\n" +
                    "            SsdpLifecycle.REFRESH_INTERVAL_SECONDS,",
            ),
        )
    }

    @Test
    fun byebyeAnnouncementHasNoStaleLocation() {
        val message = String(
            SsdpLifecycle.byebye("239.255.255.250:1900", udn, "uuid:$udn"),
        )
        assertTrue("NTS: ssdp:byebye\r\n" in message)
        assertTrue("USN: uuid:$udn\r\n" in message)
        assertFalse("LOCATION:" in message)
        assertFalse("CACHE-CONTROL:" in message)
    }
}