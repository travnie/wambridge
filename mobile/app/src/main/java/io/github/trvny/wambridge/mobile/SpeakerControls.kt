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

    fun perform(context: Context, action: Action): Outcome = SpeakerControlGate.serial {
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
            return@serial Outcome()
        }

        check(!RendererService.busy) {
            "The local adapter currently owns speaker control"
        }

        val target = SpeakerTarget.resolve(appContext)
            ?: return@serial Outcome(
                message = "No WAM speaker found",
                destination = Destination.SETTINGS,
            )

        when (action) {
            Action.PLAY_PAUSE -> when (SpeakerRemote.toggleNativePlayback(appContext, target)) {
                SpeakerRemote.PlaybackToggleResult.PAUSED -> {
                    SpeakerStateStore.update {
                        it.copy(
                            owner = SpeakerOwner.RADIO,
                            playback = SpeakerPlaybackState.PAUSED,
                            speakerIp = target,
                            source = it.source ?: "TuneIn",
                            status = "TuneIn paused",
                            lastError = null,
                        )
                    }
                    Outcome("TuneIn paused")
                }

                SpeakerRemote.PlaybackToggleResult.PLAYING -> {
                    SpeakerStateStore.update {
                        it.copy(
                            owner = SpeakerOwner.RADIO,
                            playback = SpeakerPlaybackState.PLAYING,
                            speakerIp = target,
                            source = it.source ?: "TuneIn",
                            status = "TuneIn playing",
                            lastError = null,
                        )
                    }
                    Outcome("TuneIn playing")
                }

                SpeakerRemote.PlaybackToggleResult.NO_NATIVE_PLAYBACK ->
                    Outcome(destination = Destination.TUNEIN)
            }

            Action.MUTE -> {
                val muted = SpeakerRemote.toggleMute(appContext, target)
                SpeakerStateStore.update {
                    it.copy(
                        speakerIp = target,
                        muted = muted,
                        status = if (muted) "Muted" else "Unmuted",
                        lastError = null,
                    )
                }
                Outcome(if (muted) "Muted" else "Unmuted")
            }

            Action.VOLUME_DOWN -> {
                val volume = SpeakerRemote.changeVolume(appContext, target, -1)
                SpeakerStateStore.update {
                    it.copy(
                        speakerIp = target,
                        volume = volume,
                        status = "Volume $volume",
                        lastError = null,
                    )
                }
                Outcome("Volume $volume")
            }

            Action.VOLUME_UP -> {
                val volume = SpeakerRemote.changeVolume(appContext, target, 1)
                SpeakerStateStore.update {
                    it.copy(
                        speakerIp = target,
                        volume = volume,
                        status = "Volume $volume",
                        lastError = null,
                    )
                }
                Outcome("Volume $volume")
            }
        }
    }
}
