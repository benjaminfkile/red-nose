package com.wmsfo.rednose.location

import kotlinx.coroutines.flow.Flow

// One of three implementations chosen at runtime (red-nose.md 6.1).
interface FixSource {
    /** `fused`, `gps`, or `replay`; carried on `gps.provider` in the heartbeat (8). */
    val provider: String
    fun start()
    fun stop()
    val fixes: Flow<LatestFix>
}
