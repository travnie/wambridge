package io.github.trvny.wambridge.mobile

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

internal object SleepTimerOwnerRequests {
    const val EXTRA_REQUEST_ID = "sleep_timer_request_id"

    data class Ticket internal constructor(
        val id: Long,
        internal val result: CompletableFuture<Boolean>,
    )

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<Boolean>>()

    fun create(): Ticket {
        val id = nextId.getAndIncrement()
        val result = CompletableFuture<Boolean>()
        pending[id] = result
        return Ticket(id, result)
    }

    fun complete(id: Long, accepted: Boolean) {
        if (id <= 0) return
        pending.remove(id)?.complete(accepted)
    }

    fun cancel(ticket: Ticket) {
        pending.remove(ticket.id)?.cancel(false)
    }

    fun await(ticket: Ticket, timeoutMs: Long = ACK_TIMEOUT_MS): Boolean? = try {
        ticket.result.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } finally {
        pending.remove(ticket.id)
    }

    private const val ACK_TIMEOUT_MS = 2_000L
}
