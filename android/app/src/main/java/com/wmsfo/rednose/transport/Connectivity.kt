package com.wmsfo.rednose.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

// Default-network callback (red-nose.md 7.3).  onAvailable and
// onCapabilitiesChanged(VALIDATED) signal retryNow; onLost is logged only.
class Connectivity(private val context: Context) {

    private val manager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // A conflated channel: waiters get one wake per network signal.
    val retryNow: Channel<Unit> = Channel(capacity = Channel.CONFLATED)

    private val _events = MutableSharedFlow<Event>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _events.tryEmit(Event.Available)
            retryNow.trySend(Unit)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                _events.tryEmit(Event.Validated)
                retryNow.trySend(Unit)
            }
        }

        override fun onLost(network: Network) {
            _events.tryEmit(Event.Lost)
        }
    }

    fun start() {
        manager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        try { manager.unregisterNetworkCallback(callback) } catch (_: Throwable) {}
    }

    /** Wake any waiter (e.g. debug-mode retry-now button). */
    fun kick() { retryNow.trySend(Unit) }

    enum class Event { Available, Validated, Lost }
}
