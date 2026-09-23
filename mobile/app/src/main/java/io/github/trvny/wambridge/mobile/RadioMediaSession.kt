package io.github.trvny.wambridge.mobile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

internal class RadioMediaSession(
    context: Context,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val session = MediaSession(appContext, "WAM Bridge Radio").apply {
        setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        setSessionActivity(
            PendingIntent.getActivity(
                appContext,
                71,
                Intent(appContext, RadioStationsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() {
                    if (RadioService.active) dispatch(RadioService.ACTION_RESUME)
                }

                override fun onPause() {
                    if (RadioService.active) dispatch(RadioService.ACTION_PAUSE)
                }

                override fun onStop() {
                    if (RadioService.active) dispatch(RadioService.ACTION_STOP)
                }
            },
        )
    }

    private val token: MediaSession.Token = session.sessionToken
    private var closed = false

    val sessionToken: MediaSession.Token
        get() = token

    @Synchronized
    fun update(
        state: RadioMediaState,
        title: String?,
        source: String?,
        artworkUrl: String? = null,
    ) {
        if (closed) return
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(playbackActions(state.actions))
                .setState(
                    playbackState(state.playback),
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (state.playback == RadioMediaPlayback.PLAYING) 1f else 0f,
                )
                .build(),
        )
        val metadata = MediaMetadata.Builder()
            .putString(
                MediaMetadata.METADATA_KEY_TITLE,
                title?.takeIf { it.isNotBlank() } ?: "WAM Bridge Radio",
            )
            .putString(
                MediaMetadata.METADATA_KEY_ARTIST,
                source?.takeIf { it.isNotBlank() } ?: "Samsung M5",
            )
        artworkUrl?.takeIf { it.isNotBlank() }?.let {
            metadata.putString(MediaMetadata.METADATA_KEY_ART_URI, it)
            metadata.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, it)
        }
        session.setMetadata(metadata.build())
        session.isActive = state.playback != RadioMediaPlayback.STOPPED
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session.isActive = false
        session.release()
    }

    @Synchronized
    private fun dispatch(action: String) {
        if (closed) return
        appContext.startService(
            Intent(appContext, RadioService::class.java).apply {
                this.action = action
            },
        )
    }

    private fun playbackState(playback: RadioMediaPlayback): Int = when (playback) {
        RadioMediaPlayback.STOPPED -> PlaybackState.STATE_STOPPED
        RadioMediaPlayback.BUFFERING -> PlaybackState.STATE_BUFFERING
        RadioMediaPlayback.PLAYING -> PlaybackState.STATE_PLAYING
        RadioMediaPlayback.PAUSED -> PlaybackState.STATE_PAUSED
    }

    private fun playbackActions(actions: Set<RadioMediaAction>): Long {
        var result = 0L
        if (RadioMediaAction.PLAY in actions) result = result or PlaybackState.ACTION_PLAY
        if (RadioMediaAction.PAUSE in actions) result = result or PlaybackState.ACTION_PAUSE
        if (RadioMediaAction.STOP in actions) result = result or PlaybackState.ACTION_STOP
        if (RadioMediaAction.PLAY in actions || RadioMediaAction.PAUSE in actions) {
            result = result or PlaybackState.ACTION_PLAY_PAUSE
        }
        return result
    }
}
