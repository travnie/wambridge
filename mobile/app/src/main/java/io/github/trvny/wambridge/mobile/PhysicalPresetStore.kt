package io.github.trvny.wambridge.mobile

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

internal const val PHYSICAL_PRESET_SLOTS = 3

internal fun physicalPresetSlots(
    presets: List<SamsungTuneIn.Preset>,
): List<SamsungTuneIn.Preset?> {
    val speakerPresets = presets
        .filter { it.kind.equals("speaker", ignoreCase = true) }
        .mapNotNull { preset ->
            preset.contentId.toIntOrNull()?.let { index -> index to preset }
        }
        .sortedBy { it.first }
        .take(PHYSICAL_PRESET_SLOTS)
        .map { it.second }

    return List(PHYSICAL_PRESET_SLOTS) { index -> speakerPresets.getOrNull(index) }
}

internal data class PhysicalPresetSnapshot(
    val slots: List<SamsungTuneIn.Preset?> = List(PHYSICAL_PRESET_SLOTS) { null },
    val loading: Boolean = false,
    val error: String? = null,
)

internal object PhysicalPresetStore {
    private val state = AtomicReference(PhysicalPresetSnapshot())
    private val listeners = CopyOnWriteArraySet<(PhysicalPresetSnapshot) -> Unit>()

    fun current(): PhysicalPresetSnapshot = state.get()

    fun publishLoading() {
        update { it.copy(loading = true, error = null) }
    }

    fun publishPresets(presets: List<SamsungTuneIn.Preset>) {
        update {
            PhysicalPresetSnapshot(
                slots = physicalPresetSlots(presets),
                loading = false,
                error = null,
            )
        }
    }

    fun publishError(message: String) {
        update { it.copy(loading = false, error = message) }
    }

    fun subscribe(listener: (PhysicalPresetSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        listener(state.get())
        return AutoCloseable { listeners -= listener }
    }

    private fun update(
        transform: (PhysicalPresetSnapshot) -> PhysicalPresetSnapshot,
    ): PhysicalPresetSnapshot {
        while (true) {
            val previous = state.get()
            val next = transform(previous)
            if (next == previous) return previous
            if (state.compareAndSet(previous, next)) {
                listeners.forEach { it(next) }
                return next
            }
        }
    }

    internal fun resetForTests() {
        listeners.clear()
        state.set(PhysicalPresetSnapshot())
    }
}
