package com.wmsfo.rednose.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.wmsfo.rednose.ipc.IBeaconService
import com.wmsfo.rednose.service.BeaconService

// Binds the UI process to :beacon over AIDL (red-nose.md 4.2).  Bound only while
// an Activity is resumed; unbound in onPause.  The R1 module exposes only
// getState and saveEnrollment so the service can be driven.
class ServiceBinder(private val context: Context) {

    interface OnConnected { fun onConnected(service: IBeaconService); fun onDisconnected() }

    @Volatile private var service: IBeaconService? = null
    @Volatile private var callback: OnConnected? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = IBeaconService.Stub.asInterface(binder) ?: return
            service = s
            callback?.onConnected(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            callback?.onDisconnected()
        }
    }

    fun bind(cb: OnConnected) {
        callback = cb
        val intent = Intent(context, BeaconService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        callback = null
        try { context.unbindService(connection) } catch (_: Throwable) {}
        service = null
    }

    fun get(): IBeaconService? = service
}
