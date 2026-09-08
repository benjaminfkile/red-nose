package com.wmsfo.rednose.replay

import com.wmsfo.rednose.location.FixSource
import com.wmsfo.rednose.location.FixTime
import com.wmsfo.rednose.location.LatestFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

// FixSource that plays a route object (red-nose.md 11).  One fix per
// 1000/ratePerSecond ms, recordedAt = now(), all optional fields null,
// stops at the end (no loop).
class ReplayFixSource(
    private val route: Route,
    private val ratePerSecond: Int,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : FixSource {
    override val provider: String = "replay"
    val source: String get() = route.name
    val total: Int get() = route.points.size
    @Volatile var index: Int = 0
        private set

    private val seq = AtomicLong(0L)
    private val _fixes = MutableSharedFlow<LatestFix>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: SharedFlow<LatestFix> = _fixes
    private var job: Job? = null

    override fun start() {
        require(ratePerSecond in 1..10) { "ratePerSecond must be 1..10" }
        if (job?.isActive == true) return
        val periodMs = (1000L / ratePerSecond).coerceAtLeast(1L)
        job = scope.launch {
            while (index < route.points.size) {
                val p = route.points[index++]
                val fix = LatestFix(
                    lat = p.lat, lng = p.lng,
                    recordedAt = FixTime.rfc3339(clockMs()),
                    speedMps = null, altitudeM = null, headingDeg = null, accuracyM = null,
                    seqLocal = seq.incrementAndGet(),
                )
                _fixes.tryEmit(fix)
                delay(periodMs)
            }
        }
    }

    override fun stop() {
        job?.cancel(); job = null
    }
}
