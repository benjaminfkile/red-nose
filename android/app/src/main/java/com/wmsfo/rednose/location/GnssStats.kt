package com.wmsfo.rednose.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler

// A running snapshot of satellite counts fed by GnssStatus.Callback.
// Telemetry-only (red-nose.md 6.2).
class GnssStats(private val context: Context, private val handler: Handler) {

    @Volatile var satellitesUsed: Int? = null
        private set
    @Volatile var satellitesInView: Int? = null
        private set

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val callback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val count = status.satelliteCount
            for (i in 0 until count) if (status.usedInFix(i)) used++
            satellitesUsed = used
            satellitesInView = count
        }

        override fun onStopped() {
            satellitesUsed = null
            satellitesInView = null
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        manager?.registerGnssStatusCallback(callback, handler)
    }

    fun stop() {
        manager?.unregisterGnssStatusCallback(callback)
    }
}
