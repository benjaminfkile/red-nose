package com.wmsfo.rednose.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

// Channel `beacon`, importance low, ongoing, not dismissible (red-nose.md 5.1).
// Text is the socket state and the age of the last delivered fix; updated at most
// once per 5 s (the service caller enforces the 5 s cadence).
object BeaconNotification {
    const val CHANNEL_ID = "beacon"
    const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Beacon", NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(ch)
        }
    }

    fun build(context: Context, socketState: String, fixAgeSeconds: Int?): Notification {
        val age = fixAgeSeconds?.let { " · fix ${it}s" } ?: " · no fix"
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Red-Nose")
            .setContentText("$socketState$age")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
