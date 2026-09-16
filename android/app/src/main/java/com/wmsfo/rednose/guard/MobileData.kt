package com.wmsfo.rednose.guard

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

// The user's mobile data switch (red-nose.md 5.5).  Phones keep it per SIM
// subscription: the switch writes Settings.Global `mobile_data<subId>` and the
// plain `mobile_data` key can stay 1 while data is off.  TelephonyManager
// reads the default data subscription's real state (READ_PHONE_STATE); the
// per-subscription setting is the fallback when that read is refused.
object MobileData {
    fun isOn(context: Context): Boolean {
        try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            if (tm != null) return tm.isDataEnabled
        } catch (_: Throwable) {
            // SecurityException while READ_PHONE_STATE is revoked; fall through.
        }
        val cr = context.contentResolver
        val plain = try { Settings.Global.getInt(cr, "mobile_data", 1) } catch (_: Throwable) { 1 }
        val key = subscriptionKey() ?: return plain == 1
        return try { Settings.Global.getInt(cr, key, plain) == 1 } catch (_: Throwable) { plain == 1 }
    }

    // Every settings key the switch may write; the guard observes all of them.
    fun observedUris(): List<Uri> {
        val keys = listOfNotNull("mobile_data", subscriptionKey())
        return keys.map { Settings.Global.getUriFor(it) }
    }

    private fun subscriptionKey(): String? {
        val sub = try { SubscriptionManager.getDefaultDataSubscriptionId() } catch (_: Throwable) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        return if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) null else "mobile_data$sub"
    }
}
