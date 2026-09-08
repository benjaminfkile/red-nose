package com.wmsfo.rednose.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

// red-nose.md 7.2: 1 s, 2 s, 3 s, then 5 s forever; no maximum attempt; no
// stopped state.  `attempt` is expected to be reset to 0 by the caller on success.
class BackoffTest {

    @Test fun table_matches_1_2_3_5() {
        assertArrayEquals(intArrayOf(1_000, 2_000, 3_000, 5_000), Backoff.delaysMs)
    }

    @Test fun first_three_attempts_grow() {
        assertEquals(1_000, Backoff.delayMs(0))
        assertEquals(2_000, Backoff.delayMs(1))
        assertEquals(3_000, Backoff.delayMs(2))
    }

    @Test fun clamps_to_5s_forever() {
        assertEquals(5_000, Backoff.delayMs(3))
        assertEquals(5_000, Backoff.delayMs(4))
        assertEquals(5_000, Backoff.delayMs(100))
        assertEquals(5_000, Backoff.delayMs(1_000_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_negative_attempt() {
        Backoff.delayMs(-1)
    }
}
