package com.wmsfo.rednose.transport

import org.junit.Assert.assertEquals
import org.junit.Test

// red-nose.md 7.6 / 9.2: skew = serverTime - (tSend + (tReceive - tSend) / 2).
class HeartbeatSkewTest {

    @Test fun midpoint_matches_no_skew() {
        // Round-trip 100 ms, midpoint at t=50 ms, server at t=50 ms → skew 0.
        assertEquals(0L, HeartbeatLoop.skew(serverEpochMs = 1_000_050L,
            tSendMs = 1_000_000L, tReceiveMs = 1_000_100L))
    }

    @Test fun server_ahead_produces_positive_skew() {
        // Round-trip 200 ms, midpoint at 1_000_100.  Server clock says 1_000_500.
        assertEquals(400L, HeartbeatLoop.skew(serverEpochMs = 1_000_500L,
            tSendMs = 1_000_000L, tReceiveMs = 1_000_200L))
    }

    @Test fun server_behind_produces_negative_skew() {
        // Midpoint at 1_000_050; server at 999_930 → skew -120.
        assertEquals(-120L, HeartbeatLoop.skew(serverEpochMs = 999_930L,
            tSendMs = 1_000_000L, tReceiveMs = 1_000_100L))
    }

    @Test fun asymmetric_round_trip_uses_midpoint() {
        // tSend..tReceive is 300 ms; midpoint at 1_000_150.
        // Server at 1_000_150 → skew 0.
        assertEquals(0L, HeartbeatLoop.skew(serverEpochMs = 1_000_150L,
            tSendMs = 1_000_000L, tReceiveMs = 1_000_300L))
    }
}
