package io.github.trvny.wambridge.mobile

import android.content.Context
import android.content.Intent

internal object SleepTimerControls {
    data class Outcome(
        val message: String,
        val state: SleepTimerState,
    )

    fun set(context: Context, seconds: Int): Outcome {
        val command = sleepTimerCommand(seconds)
        val requested = SleepTimerState(
            phase = SleepTimerPhase.REQUESTED,
            seconds = command.seconds,
        )
        SpeakerStateStore.update { it.copy(sleepTimer = requested) }

        val appContext = context.applicationContext
        dispatchSetToOwner(appContext, command.seconds)?.let { return it }

        return SpeakerControlGate.serial {
            dispatchSetToOwner(appContext, command.seconds)?.let { return@serial it }
            val target = SpeakerTarget.resolve(appContext)
                ?: error("No WAM speaker found")
            val confirmed = SpeakerRemote.setSleepTimer(appContext, target, command.seconds)
            SpeakerStateStore.update { it.copy(sleepTimer = confirmed) }
            Outcome(timerMessage(confirmed), confirmed)
        }
    }

    fun refresh(context: Context): Outcome {
        val appContext = context.applicationContext
        dispatchRefreshToOwner(appContext)?.let { return it }

        return SpeakerControlGate.serial {
            dispatchRefreshToOwner(appContext)?.let { return@serial it }
            val target = SpeakerTarget.resolve(appContext)
                ?: error("No WAM speaker found")
            val confirmed = SpeakerRemote.readSleepTimer(appContext, target)
            SpeakerStateStore.update { it.copy(sleepTimer = confirmed) }
            Outcome(timerMessage(confirmed), confirmed)
        }
    }

    private fun dispatchSetToOwner(context: Context, seconds: Int): Outcome? {
        val requested = SleepTimerState(SleepTimerPhase.REQUESTED, seconds)
        return when {
            RadioService.active -> {
                context.startService(
                    Intent(context, RadioService::class.java).apply {
                        action = RadioService.ACTION_SET_SLEEP_TIMER
                        putExtra(RadioService.EXTRA_SLEEP_TIMER_SECONDS, seconds)
                    },
                )
                Outcome("Sleep timer request sent to radio.", requested)
            }

            RendererService.busy -> {
                context.startService(
                    Intent(context, RendererService::class.java).apply {
                        action = RendererService.ACTION_SET_SLEEP_TIMER
                        putExtra(RendererService.EXTRA_SLEEP_TIMER_SECONDS, seconds)
                    },
                )
                Outcome("Sleep timer request sent to renderer.", requested)
            }

            else -> null
        }
    }

    private fun dispatchRefreshToOwner(context: Context): Outcome? {
        val current = SpeakerStateStore.current().sleepTimer
        return when {
            RadioService.active -> {
                context.startService(
                    Intent(context, RadioService::class.java).apply {
                        action = RadioService.ACTION_GET_SLEEP_TIMER
                    },
                )
                Outcome("Sleep timer refresh requested from radio.", current)
            }

            RendererService.busy -> {
                context.startService(
                    Intent(context, RendererService::class.java).apply {
                        action = RendererService.ACTION_GET_SLEEP_TIMER
                    },
                )
                Outcome("Sleep timer refresh requested from renderer.", current)
            }

            else -> null
        }
    }

    private fun timerMessage(state: SleepTimerState): String = when (state.phase) {
        SleepTimerPhase.OFF -> "Sleep timer off."
        SleepTimerPhase.ARMED -> "M5 sleep timer · ${state.seconds ?: "?"}s."
        SleepTimerPhase.REQUESTED -> "Sleep timer request sent."
        SleepTimerPhase.UNKNOWN -> "Sleep timer state unknown."
    }
}
