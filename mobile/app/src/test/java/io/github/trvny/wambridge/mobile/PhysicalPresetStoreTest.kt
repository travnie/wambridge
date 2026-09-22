package io.github.trvny.wambridge.mobile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalPresetStoreTest {
    private fun preset(
        contentId: String,
        title: String,
        kind: String = "speaker",
    ) = SamsungTuneIn.Preset(
        contentId = contentId,
        title = title,
        kind = kind,
    )

    @After
    fun reset() {
        PhysicalPresetStore.resetForTests()
    }

    @Test
    fun physicalSlotsIgnoreMyPresetsAndSortSpeakerSlotsNumerically() {
        val slots = physicalPresetSlots(
            listOf(
                preset("7", "Account favourite", "my"),
                preset("2", "BBC Radio 1"),
                preset("0", "Trójka"),
                preset("1", "Czwórka"),
                preset("9", "Fourth speaker preset"),
            ),
        )

        assertEquals(3, slots.size)
        assertEquals("Trójka", slots[0]?.title)
        assertEquals("Czwórka", slots[1]?.title)
        assertEquals("BBC Radio 1", slots[2]?.title)
    }

    @Test
    fun physicalSlotsAlwaysContainExactlyThreeEntries() {
        val slots = physicalPresetSlots(
            listOf(
                preset("0", "One"),
                preset("1", "Two"),
            ),
        )

        assertEquals(3, slots.size)
        assertEquals("One", slots[0]?.title)
        assertEquals("Two", slots[1]?.title)
        assertNull(slots[2])
    }

    @Test
    fun subscriptionReceivesCurrentSnapshotAndLaterChanges() {
        val seen = mutableListOf<PhysicalPresetSnapshot>()
        val subscription = PhysicalPresetStore.subscribe(seen::add)

        PhysicalPresetStore.publishPresets(listOf(preset("0", "One")))

        subscription.close()

        assertEquals(2, seen.size)
        assertEquals(3, seen[0].slots.size)
        assertEquals("One", seen[1].slots[0]?.title)
        assertEquals(false, seen[1].loading)
    }

    @Test
    fun failedRefreshKeepsPreviouslyLoadedSlots() {
        PhysicalPresetStore.publishPresets(
            listOf(
                preset("0", "One"),
                preset("1", "Two"),
                preset("2", "Three"),
            ),
        )

        PhysicalPresetStore.publishError("Speaker is busy")

        val snapshot = PhysicalPresetStore.current()
        assertEquals(listOf("One", "Two", "Three"), snapshot.slots.map { it?.title })
        assertEquals("Speaker is busy", snapshot.error)
        assertEquals(false, snapshot.loading)
    }
}
