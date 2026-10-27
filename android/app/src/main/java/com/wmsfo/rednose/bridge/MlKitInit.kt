package com.wmsfo.rednose.bridge

import android.content.Context
import android.util.Log
import com.google.mlkit.common.sdkinternal.MlKitContext

// ML Kit initialises itself through MlKitInitProvider. The init here is explicit
// anyway because it is cheap and the first scan must not wait on anything: it
// does the same thing, once, for every caller. Initialising twice throws, so it
// is guarded.
object MlKitInit {
    @Volatile private var done = false

    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            try {
                MlKitContext.initializeIfNeeded(context.applicationContext)
            } catch (e: Throwable) {
                Log.w("rednose", "MlKitContext.initializeIfNeeded failed", e)
            }
            done = true
        }
    }
}
