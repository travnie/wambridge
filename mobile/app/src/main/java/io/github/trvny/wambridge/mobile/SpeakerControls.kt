package io.github.trvny.wambridge.mobile

import android.content.Context
import android.content.Intent

/**
 * One owner for speaker-control routing used by the app and home-screen widget.
 *
 * Radio owns the WAM channel while it is active, the renderer must not be
 * interrupted, and only the idle case may issue direct speaker commands.
 */
internal object SpeakerControls {
    enum class Action { PLAY_PAUSE, MUTE, VOLUME_DOWN, VOLUME_UP }
    enum class Destination { SETTINGS, TUNEIN }

    data class Outcome(
        val message: String? = null,
        val destination: Destination? = null,
    )

    fun perform(context: Context, action: Action): Outcome {
        val appContext = context.applicationContext
        if (RadioService.active) {
            appContext.startService(
                Intent(appContext, RadioService::class.java).apply {
                    this.action = when (action) {
                        Action.PLAY_PAUSE -> RadioService.ACTION_TOGGLE_PAUSE
                        Action.MUTE -> RadioService.ACTION_MUTE
                        Action.VOLUME_DOWN -> RadioService.ACTION_VOLUME_DOWN
                        Action.VOLUME_UP -> RadioService.ACTION_VOLUME_UP
                    }
                },
            )
            return Outcome()
        }

        check(!RendererService.busy) {
            "The local adapter currently owns speaker control"
        }

        val target = SpeakerTarget.resolve(appContext)
            ?: return Outcome(
                message = "No WAM speaker found",
                destination = Destination.SETTINGS,
            )

        return when (action) {
            Action.PLAY_PAUSE -> when (SpeakerRemote.toggleNativePlayback(appContext, target)) {
                SpeakerRemote.PlaybackToggleResult.PAUSED -> Outcome("TuneIn paused")
                SpeakerRemote.PlaybackToggleResult.PLAYING -> Outcome("TuneIn playing")
                SpeakerRemote.PlaybackToggleResult.NO_NATIVE_PLAYBACK ->
                    Outcome(destination = Destination.TUNEIN)
            }

            Action.MUTE ->
                Outcome(if (SpeakerRemote.toggleMute(appContext, target)) "Muted" else "Unmuted")
            Action.VOLUME_DOWN ->
                Outcome("Volume ${SpeakerRemote.changeVolume(appContext, target, -1)}")
            Action.VOLUME_UP ->
                Outcome("Volume ${SpeakerRemote.changeVolume(appContext, target, 1)}")
        }
    }
}
