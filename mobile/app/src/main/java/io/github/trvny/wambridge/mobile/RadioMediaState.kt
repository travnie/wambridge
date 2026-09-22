package io.github.trvny.wambridge.mobile

internal enum class RadioMediaPlayback {
    STOPPED,
    BUFFERING,
    PLAYING,
    PAUSED,
}

internal enum class RadioMediaAction {
    PLAY,
    PAUSE,
    STOP,
}

internal data class RadioMediaState(
    val playback: RadioMediaPlayback,
    val actions: Set<RadioMediaAction>,
)

internal fun radioMediaState(
    starting: Boolean,
    running: Boolean,
    recovering: Boolean,
    paused: Boolean,
): RadioMediaState {
    val playback = when {
        starting || recovering -> RadioMediaPlayback.BUFFERING
        running && paused -> RadioMediaPlayback.PAUSED
        running -> RadioMediaPlayback.PLAYING
        else -> RadioMediaPlayback.STOPPED
    }
    val actions = when (playback) {
        RadioMediaPlayback.PLAYING ->
            setOf(RadioMediaAction.PAUSE, RadioMediaAction.STOP)
        RadioMediaPlayback.PAUSED ->
            setOf(RadioMediaAction.PLAY, RadioMediaAction.STOP)
        RadioMediaPlayback.BUFFERING ->
            setOf(RadioMediaAction.STOP)
        RadioMediaPlayback.STOPPED ->
            setOf(RadioMediaAction.PLAY)
    }
    return RadioMediaState(playback, actions)
}
