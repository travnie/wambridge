package io.github.trvny.wambridge.mobile

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerControlGateTest {
    @Test
    fun serializesConcurrentSpeakerWork() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)

        val first = thread(start = true) {
            SpeakerControlGate.serial {
                firstEntered.countDown()
                releaseFirst.await(1, TimeUnit.SECONDS)
            }
        }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

        val second = thread(start = true) {
            secondStarted.countDown()
            SpeakerControlGate.serial { secondEntered.set(true) }
        }
        assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
        assertFalse(secondEntered.get())

        releaseFirst.countDown()
        first.join(1_000)
        second.join(1_000)

        assertTrue(secondEntered.get())
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
    }
}
