package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioFallbackPolicyTest {
    private val candidates = listOf(
        "https://radio.example/primary",
        "https://radio.example/fallback-2",
        "https://radio.example/fallback-3",
    )

    @Test
    fun untouchedCandidatesKeepDeclaredOrder() {
        assertEquals(
            candidates,
            prioritizeRadioCandidates(
                candidates = candidates,
                memory = RadioFallbackMemory(),
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun lastWorkingEndpointMovesToTheFront() {
        assertEquals(
            listOf(candidates[2], candidates[0], candidates[1]),
            prioritizeRadioCandidates(
                candidates = candidates,
                memory = RadioFallbackMemory(lastWorking = candidates[2]),
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun recentFailureMovesBehindHealthyCandidates() {
        assertEquals(
            listOf(candidates[0], candidates[2], candidates[1]),
            prioritizeRadioCandidates(
                candidates = candidates,
                memory = RadioFallbackMemory(
                    failedAt = mapOf(candidates[1] to 900L),
                ),
                nowMs = 1_000L,
                cooldownMs = 500L,
            ),
        )
    }

    @Test
    fun failedLastWorkingEndpointDoesNotStayPreferred() {
        assertEquals(
            listOf(candidates[0], candidates[1], candidates[2]),
            prioritizeRadioCandidates(
                candidates = candidates,
                memory = RadioFallbackMemory(
                    lastWorking = candidates[2],
                    failedAt = mapOf(candidates[2] to 900L),
                ),
                nowMs = 1_000L,
                cooldownMs = 500L,
            ),
        )
    }

    @Test
    fun expiredFailureReturnsToNormalDeterministicOrder() {
        assertEquals(
            candidates,
            prioritizeRadioCandidates(
                candidates = candidates,
                memory = RadioFallbackMemory(
                    failedAt = mapOf(candidates[0] to 100L),
                ),
                nowMs = 1_000L,
                cooldownMs = 500L,
            ),
        )
    }

    @Test
    fun duplicateCandidatesAreRemovedWithoutReordering() {
        assertEquals(
            candidates,
            prioritizeRadioCandidates(
                candidates = candidates + candidates[1],
                memory = RadioFallbackMemory(),
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun fallbackPositionUsesCanonicalOrder() {
        assertEquals(2 to 3, radioFallbackPosition(candidates[1], candidates))
        assertEquals(1 to 3, radioFallbackPosition(candidates[0], candidates))
        assertNull(radioFallbackPosition("https://other.example/live", candidates))
    }
}
