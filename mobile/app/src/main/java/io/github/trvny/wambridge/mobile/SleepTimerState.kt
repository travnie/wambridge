package io.github.trvny.wambridge.mobile

internal const val MAX_SLEEP_TIMER_SECONDS = 86_400
private val SLEEP_TIMER_PRESET_MINUTES = setOf(15, 30, 45, 60)

internal data class SleepTimerCommand(
    val option: String,
    val seconds: Int,
)

internal enum class SleepTimerPhase {
    UNKNOWN,
    REQUESTED,
    OFF,
    ARMED,
}

internal data class SleepTimerState(
    val phase: SleepTimerPhase = SleepTimerPhase.UNKNOWN,
    val seconds: Int? = null,
)

internal fun sleepTimerCommand(seconds: Int): SleepTimerCommand {
    require(seconds in 0..MAX_SLEEP_TIMER_SECONDS) {
        "Sleep timer seconds must be 0..$MAX_SLEEP_TIMER_SECONDS"
    }
    return SleepTimerCommand(
        option = if (seconds == 0) "off" else "start",
        seconds = seconds,
    )
}

internal fun sleepTimerSeconds(minutes: Int): Int {
    require(minutes in SLEEP_TIMER_PRESET_MINUTES) {
        "Sleep timer preset must be 15, 30, 45 or 60 minutes"
    }
    return minutes * 60
}

internal fun sleepTimerState(values: Map<String, String>): SleepTimerState {
    val option = values["sleepoption"]?.trim()?.lowercase() ?: return SleepTimerState()
    val seconds = values["sleeptime"]?.trim()?.toIntOrNull() ?: return SleepTimerState()
    if (seconds !in 0..MAX_SLEEP_TIMER_SECONDS) return SleepTimerState()
    return when {
        option == "off" && seconds == 0 ->
            SleepTimerState(SleepTimerPhase.OFF, 0)
        option == "start" && seconds > 0 ->
            SleepTimerState(SleepTimerPhase.ARMED, seconds)
        else -> SleepTimerState()
    }
}
