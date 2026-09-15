package com.wmsfo.rednose.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicLong

// LocationManager GPS_PROVIDER at 250 ms, chosen when the enrollment has
// gpsOnlyFallback = true (red-nose.md 6.1).
class GpsFixSource(
    private val context: Context,
    private val looper: Looper,
) : FixSource {

    override val provider: String = "gps"
    private val manager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val seq = AtomicLong(0L)
    private val _fixes = MutableSharedFlow<LatestFix>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: SharedFlow<LatestFix> = _fixes

    private val listener = LocationListener { loc: Location ->
        _fixes.tryEmit(loc.toLatestFix(seq.incrementAndGet()))
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, listener, looper)
    }

    override fun stop() {
        manager.removeUpdates(listener)
    }
}
