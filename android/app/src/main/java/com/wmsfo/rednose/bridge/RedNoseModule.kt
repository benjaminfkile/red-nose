package com.wmsfo.rednose.bridge

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.wmsfo.rednose.ipc.IBeaconListener
import com.wmsfo.rednose.ipc.IBeaconService

// The full 4.2 RN native module (red-nose.md 4.2).  Binds to :beacon on resume
// and unbinds on pause; every AIDL call is exposed as a Promise-returning
// @ReactMethod, and IBeaconListener callbacks are forwarded on DeviceEventEmitter
// as `rednose.state`, `rednose.log`, `rednose.logUpload`.
class RedNoseModule(private val reactContext: ReactApplicationContext)
    : ReactContextBaseJavaModule(reactContext),
    ServiceBinder.OnConnected,
    LifecycleEventListener,
    ActivityEventListener {

    private val binder = ServiceBinder(reactContext)
    private var listener: IBeaconListener? = null

    // Set by the Activity via handleEnrollUrl(); consumed once by JS.
    @Volatile private var pendingEnrollUrl: String? = null
    private var pickRoutePromise: Promise? = null

    override fun getName(): String = NAME

    override fun initialize() {
        super.initialize()
        // Pick up any URL the Activity captured before the React context existed.
        pendingInitialUrl?.let { pendingEnrollUrl = it; pendingInitialUrl = null }
        reactContext.addLifecycleEventListener(this)
        reactContext.addActivityEventListener(this)
    }

    override fun invalidate() {
        reactContext.removeLifecycleEventListener(this)
        reactContext.removeActivityEventListener(this)
        unbindNow()
        super.invalidate()
    }

    // Bind on the Activity resume, unbind on pause (red-nose.md 4.2).
    override fun onHostResume() { binder.bind(this) }
    override fun onHostPause() { unbindNow() }
    override fun onHostDestroy() { unbindNow() }

    private fun unbindNow() {
        val s = binder.get()
        val l = listener
        if (s != null && l != null) {
            try { s.unregisterListener(l) } catch (_: Throwable) {}
        }
        listener = null
        binder.unbind()
    }

    override fun onConnected(service: IBeaconService) {
        val l = object : IBeaconListener.Stub() {
            override fun onState(stateJson: String) { emit(EVT_STATE, stateJson) }
            override fun onLog(line: String) { emit(EVT_LOG, line) }
            override fun onLogUploadResult(json: String) { emit(EVT_LOG_UPLOAD, json) }
        }
        listener = l
        try { service.registerListener(l) } catch (_: Throwable) {}
    }

    override fun onDisconnected() { listener = null }

    private fun emit(name: String, payload: String) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(name, payload)
    }

    private inline fun withService(promise: Promise, block: (IBeaconService) -> Unit) {
        val s = binder.get()
        if (s == null) { promise.reject("not_bound", "service not bound"); return }
        try {
            block(s)
        } catch (t: Throwable) {
            promise.reject("service_error", t.message ?: t.javaClass.simpleName)
        }
    }

    @ReactMethod
    fun getState(promise: Promise) {
        withService(promise) { promise.resolve(it.getStateJson()) }
    }

    @ReactMethod
    fun saveEnrollment(enrollmentJson: String, promise: Promise) {
        withService(promise) { it.saveEnrollment(enrollmentJson); promise.resolve(null) }
    }

    @ReactMethod
    fun clearEnrollment(promise: Promise) {
        withService(promise) { it.clearEnrollment(); promise.resolve(null) }
    }

    @ReactMethod
    fun setGpsOnlyFallback(enabled: Boolean, promise: Promise) {
        withService(promise) { it.setGpsOnlyFallback(enabled); promise.resolve(null) }
    }

    @ReactMethod
    fun startReplay(source: String, ratePerSecond: Double, promise: Promise) {
        withService(promise) { it.startReplay(source, ratePerSecond.toInt()); promise.resolve(null) }
    }

    @ReactMethod
    fun stopReplay(promise: Promise) {
        withService(promise) { it.stopReplay(); promise.resolve(null) }
    }

    @ReactMethod
    fun uploadLog(promise: Promise) {
        withService(promise) { it.uploadLog(); promise.resolve(null) }
    }

    @ReactMethod
    fun getRecentLog(maxLines: Double, promise: Promise) {
        withService(promise) { promise.resolve(it.getRecentLog(maxLines.toInt())) }
    }

    @ReactMethod
    fun pickRouteFile(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("no_activity", "no foreground activity")
            return
        }
        if (pickRoutePromise != null) {
            promise.reject("in_progress", "pick already in progress")
            return
        }
        pickRoutePromise = promise
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
            activity.startActivityForResult(intent, REQ_PICK_ROUTE)
        } catch (t: Throwable) {
            pickRoutePromise = null
            promise.reject("pick_failed", t.message ?: t.javaClass.simpleName)
        }
    }

    @ReactMethod
    fun getInitialEnrollUrl(promise: Promise) {
        val url = pendingEnrollUrl
        pendingEnrollUrl = null
        promise.resolve(url)
    }

    // Google Play services code scanner (red-nose.md 9.1).  A system full-screen
    // activity reads the QR; the app never touches the camera.  The typical
    // failure is the scanner module not yet installed by Play services, which
    // is warmed at app start from MainActivity.onCreate.
    @ReactMethod
    fun scanQrCode(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("no_activity", "no foreground activity")
            return
        }
        try {
            MlKit.initialize(reactContext.applicationContext)
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            val client = GmsBarcodeScanning.getClient(activity, options)
            client.startScan()
                .addOnSuccessListener { barcode -> promise.resolve(barcode.rawValue) }
                .addOnCanceledListener { promise.resolve(null) }
                .addOnFailureListener { e ->
                    promise.reject("scanner_unavailable", e.message ?: e.javaClass.simpleName)
                }
        } catch (e: Throwable) {
            promise.reject("scanner_unavailable", e.message ?: e.javaClass.simpleName)
        }
    }

    // The MainActivity forwards the deep link URL through this hook.
    fun handleEnrollUrl(url: String?) { pendingEnrollUrl = url }

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_ROUTE) return
        val p = pickRoutePromise ?: return
        pickRoutePromise = null
        if (resultCode != Activity.RESULT_OK) { p.resolve(null); return }
        val uri = data?.data
        if (uri == null) { p.resolve(null); return }
        p.resolve(uri.toString())
    }

    override fun onNewIntent(intent: Intent) {
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null && data.scheme == "rednose") {
            handleEnrollUrl(data.toString())
        }
    }

    companion object {
        const val NAME = "RedNose"
        private const val REQ_PICK_ROUTE = 8801
        private const val EVT_STATE = "rednose.state"
        private const val EVT_LOG = "rednose.log"
        private const val EVT_LOG_UPLOAD = "rednose.logUpload"

        // Set by MainActivity when a rednose:// intent lands before the React
        // context exists; consumed by initialize().
        @Volatile var pendingInitialUrl: String? = null
    }
}
