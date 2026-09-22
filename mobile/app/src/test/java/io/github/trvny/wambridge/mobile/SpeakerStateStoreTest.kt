package io.github.trvny.wambridge.mobile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerStateStoreTest {
    @After
    fun reset() {
        SpeakerStateStore.resetForTests()
    }

    @Test
    fun subscriptionImmediatelyReceivesCurrentSnapshotAndThenChanges() {
        val seen = mutableListOf<SpeakerSnapshot>()
        val subscription = SpeakerStateStore.subscribe(seen::add)

        SpeakerStateStore.update {
            it.copy(
                owner = SpeakerOwner.RENDERER,
                discovery = SpeakerDiscoveryStage.CHECKING_SAVED,
            )
        }

        subscription.close()

        assertEquals(2, seen.size)
        assertEquals(SpeakerOwner.IDLE, seen[0].owner)
        assertEquals(SpeakerOwner.RENDERER, seen[1].owner)
        assertEquals(SpeakerDiscoveryStage.CHECKING_SAVED, seen[1].discovery)
    }

    @Test
    fun unchangedSnapshotDoesNotNotifyListenersTwice() {
        var calls = 0
        val subscription = SpeakerStateStore.subscribe { calls++ }

        SpeakerStateStore.update { it }

        subscription.close()
        assertEquals(1, calls)
    }

    @Test
    fun readySpeakerClearsPreviousTerminalError() {
        SpeakerStateStore.update {
            it.copy(
                discovery = SpeakerDiscoveryStage.FAILED,
                lastError = "No WAM speaker found",
            )
        }

        SpeakerStateStore.publishSpeaker("10.0.0.44", "A1B2C3D4E5F6")

        val snapshot = SpeakerStateStore.current()
        assertEquals(SpeakerDiscoveryStage.READY, snapshot.discovery)
        assertEquals("10.0.0.44", snapshot.speakerIp)
        assertEquals("A1B2C3D4E5F6", snapshot.deviceId)
        assertNull(snapshot.lastError)
    }

    @Test
    fun discoveryStagesHaveShortHumanLabels() {
        assertEquals("Waiting for Wi-Fi…", discoveryStatus(SpeakerDiscoveryStage.WAITING_FOR_WIFI))
        assertEquals("Checking M5…", discoveryStatus(SpeakerDiscoveryStage.CHECKING_SAVED))
        assertEquals("Finding M5…", discoveryStatus(SpeakerDiscoveryStage.SSDP))
        assertEquals("Scanning Wi-Fi…", discoveryStatus(SpeakerDiscoveryStage.LAN_SCAN))
        assertEquals("M5 ready", discoveryStatus(SpeakerDiscoveryStage.READY))
        assertEquals("M5 not found", discoveryStatus(SpeakerDiscoveryStage.FAILED))
        assertEquals("Starting…", discoveryStatus(SpeakerDiscoveryStage.IDLE))
    }
}
