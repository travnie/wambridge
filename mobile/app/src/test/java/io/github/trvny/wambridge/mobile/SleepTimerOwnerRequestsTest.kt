package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerOwnerRequestsTest {
    @Test
    fun acceptedOwnerRequestIsAcknowledged() {
        val ticket = SleepTimerOwnerRequests.create()
        SleepTimerOwnerRequests.complete(ticket.id, true)
        assertEquals(true, SleepTimerOwnerRequests.await(ticket))
    }

    @Test
    fun rejectedOwnerRequestIsAcknowledged() {
        val ticket = SleepTimerOwnerRequests.create()
        SleepTimerOwnerRequests.complete(ticket.id, false)
        assertEquals(false, SleepTimerOwnerRequests.await(ticket))
    }
}
