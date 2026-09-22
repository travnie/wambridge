package io.github.trvny.wambridge.mobile

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

internal enum class SpeakerOwner { IDLE, RADIO, RENDERER }

internal enum class SpeakerPlaybackState {
    STOPPED,
    STARTING,
    PLAYING,
    PAUSED,
    STOPPING,
    UNKNOWN,
}

internal enum class SpeakerDiscoveryStage {
    IDLE,
    WAITING_FOR_WIFI,
    CHECKING_SAVED,
    SSDP,
    LAN_SCAN,
    READY,
    FAILED,
}

internal data class SpeakerSnapshot(
    val owner: SpeakerOwner = SpeakerOwner.IDLE,
    val playback: SpeakerPlaybackState = SpeakerPlaybackState.STOPPED,
    val speakerIp: String? = null,
    val deviceId: String? = null,
    val volume: Int? = null,
    val muted: Boolean? = null,
    val stationAlias: String? = null,
    val metadata: String? = null,
    val source: String? = null,
    val fallback: String? = null,
    val discovery: SpeakerDiscoveryStage = SpeakerDiscoveryStage.IDLE,
    val status: String = "Idle",
    val lastError: String? = null,
)

internal object SpeakerStateStore {
    private val state = AtomicReference(SpeakerSnapshot())
    private val listeners = CopyOnWriteArraySet<(SpeakerSnapshot) -> Unit>()

    fun current(): SpeakerSnapshot = state.get()

    fun update(transform: (SpeakerSnapshot) -> SpeakerSnapshot): SpeakerSnapshot {
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

    fun subscribe(listener: (SpeakerSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        listener(state.get())
        return AutoCloseable { listeners -= listener }
    }

    fun publishSpeaker(ip: String, deviceId: String?) {
        update {
            it.copy(
                speakerIp = ip,
                deviceId = deviceId ?: it.deviceId,
                discovery = SpeakerDiscoveryStage.READY,
                lastError = null,
            )
        }
    }

    internal fun resetForTests() {
        listeners.clear()
        state.set(SpeakerSnapshot())
    }
}

internal fun speakerSnapshotForRenderer(
    phase: RendererService.Phase,
    status: String,
    speakerIp: String?,
    current: SpeakerSnapshot = SpeakerStateStore.current(),
): SpeakerSnapshot = when (phase) {
    RendererService.Phase.STARTING -> current.copy(
        owner = SpeakerOwner.RENDERER,
        playback = SpeakerPlaybackState.STARTING,
        status = status,
        speakerIp = speakerIp ?: current.speakerIp,
        lastError = null,
    )

    RendererService.Phase.RUNNING -> current.copy(
        owner = SpeakerOwner.RENDERER,
        playback = SpeakerPlaybackState.PLAYING,
        status = status,
        speakerIp = speakerIp ?: current.speakerIp,
        lastError = null,
    )

    RendererService.Phase.STOPPING -> current.copy(
        owner = SpeakerOwner.RENDERER,
        playback = SpeakerPlaybackState.STOPPING,
        status = status,
    )

    RendererService.Phase.STOPPED ->
        if (current.owner == SpeakerOwner.RADIO) {
            current
        } else {
            current.copy(
                owner = SpeakerOwner.IDLE,
                playback = SpeakerPlaybackState.STOPPED,
                status = status,
            )
        }
}

internal fun speakerSnapshotForRadio(
    active: Boolean,
    paused: Boolean,
    muted: Boolean,
    volume: Int,
    stationAlias: String?,
    status: String,
    current: SpeakerSnapshot = SpeakerStateStore.current(),
): SpeakerSnapshot =
    if (active) {
        current.copy(
            owner = SpeakerOwner.RADIO,
            playback = if (paused) SpeakerPlaybackState.PAUSED else SpeakerPlaybackState.PLAYING,
            muted = muted,
            volume = volume,
            stationAlias = stationAlias,
            status = status,
            lastError = null,
        )
    } else if (current.owner == SpeakerOwner.RENDERER) {
        current
    } else {
        current.copy(
            owner = SpeakerOwner.IDLE,
            playback = SpeakerPlaybackState.STOPPED,
            stationAlias = null,
            status = status,
        )
    }
