package com.servora.android.data.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Retry pacing: the queue is never hammered, and the wait is bounded (`§6`). */
class ReplayBackoffTest {

    @Test
    fun `does not delay the first attempt`() {
        assertEquals(0L, ReplayBackoff.delayMillis(attemptCount = 0))
    }

    @Test
    fun `doubles the wait after each failed attempt`() {
        assertEquals(30_000L, ReplayBackoff.delayMillis(1))
        assertEquals(60_000L, ReplayBackoff.delayMillis(2))
        assertEquals(120_000L, ReplayBackoff.delayMillis(3))
    }

    @Test
    fun `caps the wait`() {
        val capped = 60 * 60 * 1000L

        assertEquals(capped, ReplayBackoff.delayMillis(8))
        assertEquals(capped, ReplayBackoff.delayMillis(10_000))
    }
}
