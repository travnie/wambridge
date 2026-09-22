package io.github.trvny.wambridge.mobile

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.IOException

/**
 * Blocking physical-preset operations.
 *
 * Callers are responsible for using a background executor. The M5 is the source
 * of truth; this class never persists a second preset list.
 */
internal object PhysicalPresetController {
    fun refresh(context: Context): List<SamsungTuneIn.Preset?> {
        val appContext = context.applicationContext
        PhysicalPresetStore.publishLoading()
        return try {
            val presets = SpeakerControlGate.serial {
                check(!(RendererService.busy || RadioService.active)) {
                    "Speaker is busy"
                }
                val target = SpeakerTarget.resolve(appContext)
                    ?: throw IOException("No WAM speaker found on Wi-Fi")
                SamsungTuneIn.getPresets(appContext, target)
            }
            PhysicalPresetStore.publishPresets(presets)
            PhysicalPresetStore.current().slots
        } catch (error: Exception) {
            PhysicalPresetStore.publishError(error.message ?: error.javaClass.simpleName)
            throw error
        }
    }

    fun play(context: Context, preset: SamsungTuneIn.Preset) {
        val appContext = context.applicationContext
        releasePlaybackOwners(appContext)
        val target = SpeakerControlGate.serial {
            check(!(RendererService.busy || RadioService.active)) {
                "Speaker is busy"
            }
            val resolved = SpeakerTarget.resolve(appContext)
                ?: throw IOException("No WAM speaker found on Wi-Fi")
            SamsungTuneIn.playSafely(appContext, resolved, preset)
            resolved
        }
        SpeakerStateStore.update {
            nativePresetPlayingSnapshot(
                speakerIp = target,
                preset = preset,
                current = it,
            )
        }
    }

    private fun releasePlaybackOwners(context: Context) {
        releaseOwner(
            name = "Renderer",
            active = { RendererService.busy },
        ) {
            context.startService(
                Intent(context, RendererService::class.java).apply {
                    action = RendererService.ACTION_STOP
                },
            )
        }
        releaseOwner(
            name = "Radio",
            active = { RadioService.active },
        ) {
            context.startService(
                Intent(context, RadioService::class.java).apply {
                    action = RadioService.ACTION_STOP
                },
            )
        }
    }

    private fun releaseOwner(
        name: String,
        active: () -> Boolean,
        stop: () -> Unit,
    ) {
        if (!active()) return
        stop()
        val deadline = SystemClock.elapsedRealtime() + OWNER_STOP_TIMEOUT_MS
        while (active() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        check(!active()) { "$name did not release the WAM control channel" }
    }

    private const val OWNER_STOP_TIMEOUT_MS = 2_500L
}
