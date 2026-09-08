package com.wmsfo.rednose.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

// checkSelfPermission for the fine, background, and coarse grants that decide
// the checklist's location rows and the gps.permission group (red-nose.md 8).
class PermissionProbe(private val context: Context) {

    fun read(): GpsPermissionGroup {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        // Fine granted and not just coarse.  A fine grant implies coarse on Android,
        // so `fine` alone would be correct today, but red-nose.md 8 states "fine
        // granted as opposed to coarse only", so we compare explicitly.
        val precise = fine && !(coarse && !fine)
        return GpsPermissionGroup(
            foreground = fine,
            background = background,
            precise = precise,
        )
    }

    private fun granted(name: String): Boolean =
        context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
}
