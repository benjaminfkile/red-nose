package com.wmsfo.rednose.guard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

// The read side of red-nose.md 5.5.  Every method calls the API exactly as the
// 5.5 table names it; DeviceGuard walks the items in order and decides whether
// to run the restore command.
interface DeviceState {
    fun airplaneModeOn(): Boolean
    fun locationEnabled(): Boolean
    fun mobileDataOn(): Boolean
    fun powerSaveMode(): Boolean
    fun missingPermissions(): List<String>
    fun ignoringBatteryOptimizations(): Boolean
}

class AndroidDeviceState(private val context: Context) : DeviceState {

    override fun airplaneModeOn(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (_: Throwable) { false }

    override fun locationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return lm.isLocationEnabled
    }

    override fun mobileDataOn(): Boolean = MobileData.isOn(context)

    override fun powerSaveMode(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isPowerSaveMode
    }

    override fun missingPermissions(): List<String> {
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("android.permission.POST_NOTIFICATIONS")
            }
            add(Manifest.permission.READ_PHONE_STATE)
        }
        return required.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    }

    override fun ignoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
