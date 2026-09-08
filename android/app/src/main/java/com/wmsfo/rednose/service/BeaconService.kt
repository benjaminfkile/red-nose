package com.wmsfo.rednose.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.RemoteCallbackList
import android.os.SystemClock
import com.wmsfo.rednose.BuildConfig
import com.wmsfo.rednose.ipc.IBeaconListener
import com.wmsfo.rednose.ipc.IBeaconService
import com.wmsfo.rednose.location.FixSource
import com.wmsfo.rednose.location.FixTime
import com.wmsfo.rednose.location.FusedFixSource
import com.wmsfo.rednose.location.GnssStats
import com.wmsfo.rednose.location.GpsFixSource
import com.wmsfo.rednose.location.LatestFix
import com.wmsfo.rednose.log.BeaconJson
import com.wmsfo.rednose.log.LogUploader
import com.wmsfo.rednose.log.RingLog
import com.wmsfo.rednose.replay.ReplayFixSource
import com.wmsfo.rednose.replay.RouteLoader
import com.wmsfo.rednose.store.Enrollment
import com.wmsfo.rednose.store.SecureStore
import com.wmsfo.rednose.telemetry.Heartbeat
import com.wmsfo.rednose.telemetry.TelemetryCollector
import com.wmsfo.rednose.transport.Connectivity
import com.wmsfo.rednose.transport.HeartbeatLoop
import com.wmsfo.rednose.transport.RestClient
import com.wmsfo.rednose.transport.SendLoop
import com.wmsfo.rednose.transport.SocketLoop
import com.wmsfo.rednose.transport.TransportStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.serialization.encodeToString

// The foreground service (red-nose.md 5).  Runs in :beacon, START_STICKY, of type
// location as soon as an enrollment exists.  All transport state lives on the
// single-threaded "beacon" dispatcher (7.1); nothing here queues fixes.
class BeaconService : Service(), SendLoop.State, HeartbeatLoop.State {

    companion object {
        const val ACTION_BOOT_START = "com.wmsfo.rednose.action.BOOT_START"
        @Volatile var isRunning: Boolean = false
            private set
    }

    // Single-threaded dispatcher for every transport touch (7.1).
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private val dispatcher = newSingleThreadContext("beacon")
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    private lateinit var ring: RingLog
    private lateinit var store: SecureStore
    private lateinit var counters: BootCounters
    private lateinit var stats: TransportStats
    private lateinit var rest: RestClient
    private lateinit var gnss: GnssStats
    private lateinit var telemetry: TelemetryCollector
    private lateinit var connectivity: Connectivity
    private lateinit var listeners: RemoteCallbackList<IBeaconListener>
    private lateinit var locationHandlerThread: HandlerThread
    private lateinit var locationHandler: Handler

    private var wakeLock: PowerManager.WakeLock? = null
    private var enrollment: Enrollment? = null
    private var fixSource: FixSource? = null
    private var socketLoop: SocketLoop? = null
    private var sendLoop: SendLoop? = null
    private var heartbeatLoop: HeartbeatLoop? = null
    private var replay: ReplayFixSource? = null
    private var stateEmitter: Job? = null

    // ---- SendLoop.State ----
    @Volatile override var latestFix: LatestFix? = null
    @Volatile override var lastDeliveredSeqLocal: Long? = null
    @Volatile override var lastReceiptLatencyMs: Long? = null
    @Volatile override var lastSendError: String? = null
    @Volatile override var attempt: Int = 0
    @Volatile override var inFlight: Boolean = false
    override var httpFallbackSeconds: Int
        get() = stats.httpFallbackSeconds
        set(value) { stats.httpFallbackSeconds = value }
    override var socketState: String
        get() = stats.socketState
        set(value) { stats.socketState = value }
    override var ingestChannel: String = ""

    // ---- HeartbeatLoop.State ----
    @Volatile override var liveEventId: Long? = null
    @Volatile override var isActive: Boolean? = null
    @Volatile override var clockSkewMs: Long? = null
    @Volatile override var revoked: Boolean = false
    @Volatile override var lastHeartbeatAcceptedAt: String? = null
    @Volatile override var lastHeartbeatError: String? = null

    private var serviceStartedElapsedRealtime: Long = 0L
    private var serviceStartedAt: String? = null
    private var startedForeground: Boolean = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        serviceStartedElapsedRealtime = SystemClock.elapsedRealtime()
        serviceStartedAt = FixTime.rfc3339(System.currentTimeMillis())
        ring = RingLog(filesDir, BuildConfig.REDNOSE_LOG_RING_BYTES)
        store = SecureStore(this)
        counters = BootCounters(this).also { it.onServiceCreated() }
        stats = TransportStats().also { it.sendsFailedSinceBoot = counters.sendsFailedSinceBoot }
        rest = RestClient()
        locationHandlerThread = HandlerThread("beacon-loc").also { it.start() }
        locationHandler = Handler(locationHandlerThread.looper)
        gnss = GnssStats(this, locationHandler).also { it.start() }
        connectivity = Connectivity(this).also { it.start() }
        listeners = RemoteCallbackList()
        telemetry = TelemetryCollector(
            context = this,
            log = ring,
            stats = stats,
            gnss = gnss,
            counters = counters,
            serviceStartedElapsedRealtime = serviceStartedElapsedRealtime,
            appVersion = BuildConfig.VERSION_NAME,
        )
        BeaconNotification.ensureChannel(this)
        acquireWakeLock()
        ring.addListener { line ->
            broadcast { it.onLog(line) }
        }
        // If an enrollment is already stored, come up as a foreground service.
        store.load()?.let { onEnrollmentAvailable(it) }
        startStateEmitter()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rednose:beacon").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start in the foreground if we already have an enrollment; otherwise stay bound-only.
        if (enrollment != null && !startedForeground) startForegroundNow()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Ignored on purpose (red-nose.md 5.1).
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ring.warn("onLowMemory")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        telemetry.onTrim(level)
        ring.warn("onTrimMemory level=$level")
    }

    override fun onDestroy() {
        isRunning = false
        stateEmitter?.cancel()
        stopAllLoops()
        connectivity.stop()
        gnss.stop()
        locationHandlerThread.quitSafely()
        try { wakeLock?.release() } catch (_: Throwable) {}
        listeners.kill()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Loop plumbing ----

    private fun onEnrollmentAvailable(e: Enrollment) {
        enrollment = e
        ingestChannel = e.ingestChannel
        rest.configure(object : RestClient.RestApi {
            override val apiBaseUrl: String = e.apiBaseUrl
            override val beaconKey: String = e.key
        })
        startFixSource(e)
        startSocketAndSend(e)
        startHeartbeat()
        if (!startedForeground) startForegroundNow()
    }

    private fun startForegroundNow() {
        val n = BeaconNotification.build(this, stats.socketState, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(BeaconNotification.NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(BeaconNotification.NOTIFICATION_ID, n)
        }
        startedForeground = true
    }

    private fun startFixSource(e: Enrollment) {
        val src: FixSource = if (e.gpsOnlyFallback) GpsFixSource(this, locationHandler.looper)
            else FusedFixSource(this, locationHandler.looper)
        stopFixSource()
        fixSource = src
        src.start()
        scope.launch {
            src.fixes.collect { fix ->
                latestFix = fix
                telemetry.onFix(fix, src.provider)
                sendLoop?.kick()
            }
        }
    }

    private fun stopFixSource() {
        try { fixSource?.stop() } catch (_: Throwable) {}
        fixSource = null
    }

    private fun startSocketAndSend(e: Enrollment) {
        val send = SendLoop(this, stats, hub = object : SendLoop.HubSender {
            override suspend fun sendToChannel(channel: String, fix: LatestFix): Boolean =
                socketLoop?.sendToChannel(channel, fix) ?: false
        }, rest = rest, log = ring, fixIntervalMs = BuildConfig.REDNOSE_FIX_INTERVAL_MS.toLong())
        sendLoop = send
        send.start(scope)
        val loop = SocketLoop(e, stats, send, connectivity, ring)
        socketLoop = loop
        loop.start(scope)
    }

    private fun startHeartbeat() {
        val loop = HeartbeatLoop(
            intervalMs = BuildConfig.REDNOSE_HEARTBEAT_INTERVAL_MS.toLong(),
            build = { telemetry.build().also { telemetry.clockSkewMs = clockSkewMs } },
            rest = rest,
            state = this,
            log = ring,
        )
        heartbeatLoop = loop
        loop.start(scope)
    }

    private fun stopAllLoops() {
        try { heartbeatLoop?.stop() } catch (_: Throwable) {}
        try { socketLoop?.stop() } catch (_: Throwable) {}
        try { sendLoop?.stop() } catch (_: Throwable) {}
        heartbeatLoop = null; socketLoop = null; sendLoop = null
        stopFixSource()
    }

    private fun startStateEmitter() {
        stateEmitter?.cancel()
        stateEmitter = scope.launch {
            var lastNotifiedAtMs = 0L
            while (isActive) {
                val json = buildStateJson()
                broadcast { it.onState(json) }
                val nowMs = SystemClock.elapsedRealtime()
                if (startedForeground && nowMs - lastNotifiedAtMs >= 5_000L) {
                    val n = BeaconNotification.build(this@BeaconService,
                        stats.socketState, ageSecondsOf(latestFix))
                    val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    try { nm.notify(BeaconNotification.NOTIFICATION_ID, n) } catch (_: Throwable) {}
                    lastNotifiedAtMs = nowMs
                }
                delay(1_000L)
            }
        }
    }

    private fun ageSecondsOf(fix: LatestFix?): Int? {
        val f = fix ?: return null
        val parsed = try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(f.recordedAt)?.time ?: return null
        } catch (_: Throwable) { return null }
        return ((System.currentTimeMillis() - parsed) / 1000L).toInt().coerceAtLeast(0)
    }

    private fun buildStateJson(): String {
        val e = enrollment
        val prodBase = BuildConfig.REDNOSE_PROD_API_BASE_URL
        val telemetryBody: Heartbeat = telemetry.build()
        val state = ServiceState(
            serviceRunning = startedForeground,
            serviceStartedAt = if (startedForeground) serviceStartedAt else null,
            enrollment = e?.redacted(),
            socketState = stats.socketState,
            revoked = revoked,
            liveEventId = liveEventId,
            isActive = isActive,
            lastHeartbeatAcceptedAt = lastHeartbeatAcceptedAt,
            lastHeartbeatError = lastHeartbeatError,
            clockSkewMs = clockSkewMs,
            latestFix = latestFix,
            lastDeliveredSeqLocal = lastDeliveredSeqLocal,
            lastReceiptLatencyMs = lastReceiptLatencyMs,
            lastSendError = lastSendError,
            telemetry = telemetryBody,
            replay = replay?.let {
                ReplayStatus(
                    running = true,
                    source = it.source,
                    index = it.index,
                    total = it.total,
                    ratePerSecond = 0, // filled in R2 when replay is fully driven
                )
            },
            replayAllowed = e != null && e.apiBaseUrl != prodBase,
            checklist = Checklist(serviceRunning = startedForeground),
            appVersion = BuildConfig.VERSION_NAME,
        )
        return BeaconJson.encodeToString(state)
    }

    private fun broadcast(action: (IBeaconListener) -> Unit) {
        val n = listeners.beginBroadcast()
        try {
            for (i in 0 until n) {
                try { action(listeners.getBroadcastItem(i)) } catch (_: Throwable) {}
            }
        } finally { listeners.finishBroadcast() }
    }

    // ---- AIDL ----
    private val binder = object : IBeaconService.Stub() {
        override fun getStateJson(): String = buildStateJson()

        override fun saveEnrollment(enrollmentJson: String) {
            val e = BeaconJson.decodeFromString(Enrollment.serializer(), enrollmentJson)
            scope.launch {
                store.save(e)
                onEnrollmentAvailable(e)
                // Promote to a started foreground service so the process outlives the binding.
                val self = Intent(this@BeaconService, BeaconService::class.java)
                androidx.core.content.ContextCompat.startForegroundService(this@BeaconService, self)
            }
        }

        override fun clearEnrollment() {
            scope.launch {
                stopAllLoops()
                store.clear()
                enrollment = null
                stats.socketState = "disconnected"
                stopForeground(STOP_FOREGROUND_REMOVE)
                startedForeground = false
                stopSelf()
            }
        }

        override fun setGpsOnlyFallback(enabled: Boolean) {
            scope.launch {
                store.setGpsOnlyFallback(enabled)
                val e = enrollment ?: return@launch
                enrollment = e.copy(gpsOnlyFallback = enabled)
                startFixSource(enrollment!!)
            }
        }

        override fun startReplay(source: String, ratePerSecond: Int) {
            scope.launch {
                try {
                    val route = RouteLoader(this@BeaconService).load(source)
                    stopFixSource()
                    val r = ReplayFixSource(route, ratePerSecond)
                    replay = r
                    fixSource = r
                    r.start()
                    scope.launch {
                        r.fixes.collect { fix ->
                            latestFix = fix
                            telemetry.onFix(fix, r.provider)
                            sendLoop?.kick()
                        }
                    }
                } catch (t: Throwable) {
                    ring.error("replay start failed: ${t.javaClass.simpleName}:${t.message}")
                }
            }
        }

        override fun stopReplay() {
            scope.launch {
                replay?.stop()
                replay = null
                enrollment?.let { startFixSource(it) }
            }
        }

        override fun uploadLog() {
            scope.launch {
                val e = enrollment
                if (e == null) {
                    broadcast { it.onLogUploadResult(BeaconJson.encodeToString(
                        LogUploader.Result(false, code = "not_enrolled", message = "no enrollment")
                    )) }
                    return@launch
                }
                val uploader = LogUploader(ring, rest, BuildConfig.VERSION_NAME)
                val result = uploader.upload()
                broadcast { it.onLogUploadResult(BeaconJson.encodeToString(result)) }
            }
        }

        override fun getRecentLog(maxLines: Int): String =
            BeaconJson.encodeToString(ring.recent(maxLines))

        override fun registerListener(listener: IBeaconListener?) {
            if (listener != null) listeners.register(listener)
        }

        override fun unregisterListener(listener: IBeaconListener?) {
            if (listener != null) listeners.unregister(listener)
        }
    }
}
