package io.github.trvny.wambridge.mobile

import android.content.Context
import android.content.Intent
import android.os.SystemClock

internal object SleepTimerControls {
    data class Outcome(
        val message: String,
        val state: SleepTimerState,
    )

    fun set(context: Context, seconds: Int): Outcome {
        val command = sleepTimerCommand(seconds)
        val previous = SpeakerStateStore.current().sleepTimer
        val requested = SleepTimerState(
            phase = SleepTimerPhase.REQUESTED,
            seconds = command.seconds,
        )
        SpeakerStateStore.update { it.copy(sleepTimer = requested) }

        return try {
            routeSet(context.applicationContext, command.seconds)
        } catch (error: Exception) {
            restoreRequestedState(requested, previous)
            throw error
        }
    }

    fun standbyNow(context: Context): Outcome {
        val appContext = context.applicationContext
        val previous = SpeakerStateStore.current().sleepTimer
        val requested = SleepTimerState(
            phase = SleepTimerPhase.REQUESTED,
            seconds = STANDBY_TIMER_SECONDS,
        )
        SpeakerStateStore.update { it.copy(sleepTimer = requested) }

        return try {
            if (RadioService.active) {
                appContext.startService(
                    Intent(appContext, RadioService::class.java).apply {
                        action = RadioService.ACTION_STOP
                    },
                )
            }
            if (RendererService.busy) {
                appContext.startService(
                    Intent(appContext, RendererService::class.java).apply {
                        action = RendererService.ACTION_STOP
                    },
                )
            }

            waitForOwnerRelease()

            SpeakerControlGate.serial {
                check(!RadioService.active && !RendererService.busy) {
                    "M5 control owner did not release"
                }
                val target = SpeakerTarget.resolve(appContext)
                    ?: error("No WAM speaker found")
                val confirmed = SpeakerRemote.setSleepTimer(
                    appContext,
                    target,
                    STANDBY_TIMER_SECONDS,
                )
                SpeakerStateStore.update { it.copy(sleepTimer = confirmed) }
                Outcome("Standby requested from M5.", confirmed)
            }
        } catch (error: Exception) {
            restoreRequestedState(requested, previous)
            throw error
        }
    }

    fun refresh(context: Context): Outcome =
        routeRefresh(context.applicationContext)

    private fun routeSet(context: Context, seconds: Int): Outcome {
        val deadline = SystemClock.elapsedRealtime() + OWNER_ROUTE_TIMEOUT_MS
        while (true) {
            dispatchSetToOwner(context, seconds)?.let { return it }

            var direct: Outcome? = null
            SpeakerControlGate.serial {
                if (!RadioService.active && !RendererService.busy) {
                    val target = SpeakerTarget.resolve(context)
                        ?: error("No WAM speaker found")
                    val confirmed = SpeakerRemote.setSleepTimer(context, target, seconds)
                    SpeakerStateStore.update { it.copy(sleepTimer = confirmed) }
                    direct = Outcome(timerMessage(confirmed), confirmed)
                }
            }
            direct?.let { return it }

            if (SystemClock.elapsedRealtime() >= deadline) {
                error("M5 control owner did not settle for sleep timer")
            }
            SystemClock.sleep(OWNER_ROUTE_POLL_MS)
        }
    }

    private fun routeRefresh(context: Context): Outcome {
        val deadline = SystemClock.elapsedRealtime() + OWNER_ROUTE_TIMEOUT_MS
        while (true) {
            dispatchRefreshToOwner(context)?.let { return it }

            var direct: Outcome? = null
            SpeakerControlGate.serial {
                if (!RadioService.active && !RendererService.busy) {
                    val target = SpeakerTarget.resolve(context)
                        ?: error("No WAM speaker found")
                    val confirmed = SpeakerRemote.readSleepTimer(context, target)
                    SpeakerStateStore.update { it.copy(sleepTimer = confirmed) }
                    direct = Outcome(timerMessage(confirmed), confirmed)
                }
            }
            direct?.let { return it }

            if (SystemClock.elapsedRealtime() >= deadline) {
                error("M5 control owner did not settle for sleep timer refresh")
            }
            SystemClock.sleep(OWNER_ROUTE_POLL_MS)
        }
    }

    private fun dispatchSetToOwner(context: Context, seconds: Int): Outcome? {
        val requested = SleepTimerState(SleepTimerPhase.REQUESTED, seconds)
        return when {
            RadioService.running -> {
                val accepted = sendOwnerRequest(
                    context,
                    Intent(context, RadioService::class.java).apply {
                        action = RadioService.ACTION_SET_SLEEP_TIMER
                        putExtra(RadioService.EXTRA_SLEEP_TIMER_SECONDS, seconds)
                    },
                    "radio",
                )
                if (accepted) Outcome("Sleep timer request sent to radio.", requested) else null
            }

            RendererService.phase == RendererService.Phase.RUNNING -> {
                val accepted = sendOwnerRequest(
                    context,
                    Intent(context, RendererService::class.java).apply {
                        action = RendererService.ACTION_SET_SLEEP_TIMER
                        putExtra(RendererService.EXTRA_SLEEP_TIMER_SECONDS, seconds)
                    },
                    "renderer",
                )
                if (accepted) Outcome("Sleep timer request sent to renderer.", requested) else null
            }

            else -> null
        }
    }

    private fun dispatchRefreshToOwner(context: Context): Outcome? {
        val current = SpeakerStateStore.current().sleepTimer
        return when {
            RadioService.running -> {
                val accepted = sendOwnerRequest(
                    context,
                    Intent(context, RadioService::class.java).apply {
                        action = RadioService.ACTION_GET_SLEEP_TIMER
                    },
                    "radio",
                )
                if (accepted) Outcome("Sleep timer refresh requested from radio.", current) else null
            }

            RendererService.phase == RendererService.Phase.RUNNING -> {
                val accepted = sendOwnerRequest(
                    context,
                    Intent(context, RendererService::class.java).apply {
                        action = RendererService.ACTION_GET_SLEEP_TIMER
                    },
                    "renderer",
                )
                if (accepted) Outcome("Sleep timer refresh requested from renderer.", current) else null
            }

            else -> null
        }
    }

    private fun sendOwnerRequest(
        context: Context,
        intent: Intent,
        owner: String,
    ): Boolean {
        val ticket = SleepTimerOwnerRequests.create()
        intent.putExtra(SleepTimerOwnerRequests.EXTRA_REQUEST_ID, ticket.id)
        context.startService(intent)
        return when (val accepted = SleepTimerOwnerRequests.await(ticket)) {
            true -> true
            false -> false
            null -> error("M5 $owner did not acknowledge sleep timer request")
        }
    }

    private fun restoreRequestedState(
        requested: SleepTimerState,
        previous: SleepTimerState,
    ) {
        SpeakerStateStore.update { current ->
            if (current.sleepTimer == requested) {
                current.copy(sleepTimer = previous)
            } else {
                current
            }
        }
    }

    private fun waitForOwnerRelease() {
        val deadline = SystemClock.elapsedRealtime() + OWNER_RELEASE_TIMEOUT_MS
        while (
            (RadioService.active || RendererService.busy) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(50)
        }
        check(!RadioService.active && !RendererService.busy) {
            "M5 control owner did not release"
        }
    }

    private fun timerMessage(state: SleepTimerState): String = when (state.phase) {
        SleepTimerPhase.OFF -> "Sleep timer off."
        SleepTimerPhase.ARMED -> "M5 sleep timer · ${state.seconds ?: "?"}s."
        SleepTimerPhase.REQUESTED -> "Sleep timer request sent."
        SleepTimerPhase.UNKNOWN -> "Sleep timer state unknown."
    }

    private const val STANDBY_TIMER_SECONDS = 1
    private const val OWNER_RELEASE_TIMEOUT_MS = 4_000L
    private const val OWNER_ROUTE_TIMEOUT_MS = 4_000L
    private const val OWNER_ROUTE_POLL_MS = 50L
}
