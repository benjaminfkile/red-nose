package com.wmsfo.rednose.bridge

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.wmsfo.rednose.ipc.IBeaconListener

// Minimal R1 binding (red-nose.md task R1): exposes getState and saveEnrollment
// so JS can drive the service.  The full 4.2 surface is R2.
class RedNoseModule(private val reactContext: ReactApplicationContext)
    : ReactContextBaseJavaModule(reactContext), ServiceBinder.OnConnected {

    private val binder = ServiceBinder(reactContext)
    private var listener: IBeaconListener? = null

    override fun getName(): String = NAME

    override fun initialize() {
        super.initialize()
        binder.bind(this)
    }

    override fun invalidate() {
        binder.unbind()
        listener = null
        super.invalidate()
    }

    override fun onConnected(service: com.wmsfo.rednose.ipc.IBeaconService) {
        val l = object : IBeaconListener.Stub() {
            override fun onState(stateJson: String) {
                emit("rednose.state", stateJson)
            }
            override fun onLog(line: String) {
                emit("rednose.log", line)
            }
            override fun onLogUploadResult(json: String) {
                emit("rednose.logUpload", json)
            }
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

    @ReactMethod
    fun getState(promise: Promise) {
        val s = binder.get()
        if (s == null) { promise.reject("not_bound", "service not bound"); return }
        try {
            promise.resolve(s.getStateJson())
        } catch (t: Throwable) {
            promise.reject("service_error", t.message ?: t.javaClass.simpleName)
        }
    }

    @ReactMethod
    fun saveEnrollment(enrollmentJson: String, promise: Promise) {
        val s = binder.get()
        if (s == null) { promise.reject("not_bound", "service not bound"); return }
        try {
            s.saveEnrollment(enrollmentJson)
            promise.resolve(null)
        } catch (t: Throwable) {
            promise.reject("service_error", t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        const val NAME = "RedNose"
    }
}
