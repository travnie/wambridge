package io.github.trvny.wambridge.mobile

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes direct and service-owned access to the speaker control plane.
 *
 * Service state flags decide who should own the speaker. This gate closes the
 * small race between checking those flags and issuing the actual WAM request.
 * It is reentrant because service teardown can be called from a guarded start.
 */
internal object SpeakerControlGate {
    private val lock = ReentrantLock(true)

    fun enter() = lock.lock()

    fun exit() = lock.unlock()

    fun <T> serial(action: () -> T): T = lock.withLock(action)
}
