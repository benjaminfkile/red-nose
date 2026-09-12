# Red-Nose technical design

Red-Nose is the WMSFO v2 beacon app: a React Native user interface over a native Kotlin foreground service, Android only. It runs as a persistent system app on a rooted (Magisk) phone; root is the tool for everything the platform would otherwise make hard: it keeps the service alive across kills and low memory, grants every permission, makes Red-Nose the launcher, and configures the device from the shell with no user taps. The app never asks the user for anything. The service owns GPS, the send loops, and telemetry; the JavaScript side owns the screens, enrollment, and debug mode. Every name, shape, path, and rule below is the one in the shared contracts (`contracts.md`); where this document restates a contract it does so for the engineer's convenience and the contract wins on any difference.

---

## 1. Scope and shape

| Item | Value |
|---|---|
| Platform | Android 10 (API 29) or newer. `minSdk 29`; `compileSdk` and `targetSdk` as the React Native template sets them (37 and 36 today). Google Play services present (fused location). Rooted with Magisk. |
| Install | System app under `/system/app/RedNose/` with `android:persistent="true"`. Not a normal user-installed package. Red-Nose requests no signature or privileged permission, so it needs neither `/system/priv-app` nor a privileged-permission allowlist. |
| UI | React Native, bare CLI template (no Expo), TypeScript, Hermes. |
| Native | Kotlin. One foreground service in its own process, one React Native native module in the app process, one boot receiver. Process survival is the platform's, not a userspace watchdog's (section 5.3). |
| Application id | `com.wmsfo.rednose` in both flavours (section 15). |
| Processes | `com.wmsfo.rednose` (React Native UI) and `com.wmsfo.rednose:beacon` (the service). The service process never loads React Native. |
| Distribution | Packaged as a Magisk module (the signed APK plus a `service.sh` boot script); flashed on a rooted phone, one signing key, no store. |
| Roles | `beacon` and `admin`, from the enrolled key. `admin` unlocks debug mode. |
| Storage | Enrollment fields in Keystore-backed `EncryptedSharedPreferences`, a ring-buffer log file, and two per-boot counters. No fixes are ever stored. |

---

## 2. Repository and project structure

Repository `red-nose`, branch flow `dev` and `main` (section 16).

```
red-nose/
  package.json  tsconfig.json  app.json  index.js  babel.config.js  metro.config.js
  VERSION                              # semantic version, one line (section 15)
  CONTRACTS_SHA                        # API commit the vendored contracts came from
  contracts/                           # vendored copy of the API's contracts/ (schemas, fixtures)
  src/
    App.tsx                            # navigation root: Enroll | Status | Debug
    native/NativeRedNose.ts            # typed binding over the native module (section 4)
    state/useServiceState.ts           # subscribes to service state events, exposes ServiceState
    enroll/parseEnrollUrl.ts           # rednose://enroll?api=&token= parsing
    enroll/enrollApi.ts                # POST /beacons/enroll, GET /beacons/me (fetch)
    screens/EnrollScreen.tsx  ScanScreen.tsx  ManualEnrollScreen.tsx
    screens/StatusScreen.tsx  ChecklistCard.tsx
    screens/debug/TelemetryScreen.tsx  FixLogScreen.tsx  SocketLogScreen.tsx
    screens/debug/FailureLogScreen.tsx LogFileScreen.tsx  ReplayScreen.tsx  ProvisioningScreen.tsx
  __tests__/                           # Jest: URL parsing, state reducer, schema fixtures
  tools/soak-observer/                 # Node script polling GET /admin/beacons during the soak (section 17)
  android/
    build.gradle.kts  settings.gradle.kts  gradle.properties
    app/build.gradle.kts               # flavours dev, prod; BuildConfig fields (section 15)
    app/src/main/AndroidManifest.xml
    app/src/main/aidl/com/wmsfo/rednose/ipc/IBeaconService.aidl
    app/src/main/aidl/com/wmsfo/rednose/ipc/IBeaconListener.aidl
    app/src/main/java/com/wmsfo/rednose/
      MainApplication.kt               # RN host in the UI process
      MainActivity.kt                  # single-task, HOME-capable, rednose://enroll intent filter
      bridge/RedNoseModule.kt          # the RN native module (UI process)
      bridge/RedNosePackage.kt
      bridge/ServiceBinder.kt          # bind/unbind to BeaconService over AIDL
      service/BeaconService.kt         # the foreground service (:beacon), START_STICKY
      service/BeaconNotification.kt
      service/BootReceiver.kt          # BOOT_COMPLETED, runs in :beacon, reads the store, starts foreground
      service/BootCounters.kt          # serviceRestartCount, sendsFailedSinceBoot keyed by BOOT_COUNT
      location/FixSource.kt            # interface: start(), stop(), fixes: Flow<LatestFix>
      location/FusedFixSource.kt       # FusedLocationProviderClient, 1 Hz, high accuracy
      location/GpsFixSource.kt         # LocationManager GPS_PROVIDER, 1 Hz
      location/GnssStats.kt            # GnssStatus callback: satellites used and in view
      location/LatestFix.kt
      transport/HubClient.kt           # SignalR Java client wrapper
      transport/RestClient.kt          # OkHttp: /locations, /beacons/heartbeat, /beacons/logs
      transport/SocketLoop.kt
      transport/SendLoop.kt
      transport/HeartbeatLoop.kt
      transport/Backoff.kt
      transport/Connectivity.kt        # default network callback, retry-now signal
      transport/TransportStats.kt
      telemetry/TelemetryCollector.kt  # builds the heartbeat body
      telemetry/PowerProbe.kt  RadioProbe.kt  ProcessProbe.kt  PermissionProbe.kt
      store/SecureStore.kt             # EncryptedSharedPreferences, :beacon only
      store/Enrollment.kt
      log/RingLog.kt                   # two-file ring, REDNOSE_LOG_RING_BYTES total
      log/LogUploader.kt               # POST /beacons/logs
      replay/RouteLoader.kt            # URL or content URI to a validated route object
      replay/ReplayFixSource.kt        # FixSource that plays a route
      checklist/Checklist.kt           # every first-run item, verified from system APIs
    app/src/main/res/xml/network_security_config.xml       # prod: cleartext off
    app/src/dev/res/xml/network_security_config.xml        # dev: cleartext allowed
  provisioning/
    magisk-module/                     # module.prop, customize.sh, service.sh (root watchdog + launcher re-assert), system/app/RedNose/ tree
    provision.sh                       # adb+root: pm grant, appops, deviceidle whitelist, settings, launcher, safe boot off
    DEVICE.md                          # the runbook for the phone we own (section 14.4)
  .github/workflows/android.yml
```

Gradle dependencies that matter, all pinned to exact versions in `app/build.gradle.kts`:

| Artifact | Use |
|---|---|
| `com.microsoft.signalr:signalr` | Hub client (brings OkHttp 4 and RxJava 3) |
| `com.squareup.okhttp3:okhttp` | REST client, same major as the SignalR client's |
| `com.google.android.gms:play-services-location` | Fused provider |
| `androidx.security:security-crypto` (1.1.0-alpha06) | `EncryptedSharedPreferences` |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | Every JSON body the service writes or parses |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android`, `kotlinx-coroutines-rx3` | Loops; awaiting the SignalR client's RxJava types without blocking |
| `androidx.core:core-ktx`, `androidx.appcompat:appcompat` | Notifications, permissions |

JavaScript dependencies: `react-native`, `@react-navigation/native` with the native stack, `react-native-vision-camera` (QR scanning through its code scanner), `react-native-safe-area-context`, `react-native-screens`. Nothing else touches native code; every other native capability is reached through `NativeRedNose`.

---

## 3. Responsibility split

| Concern | Owner | Notes |
|---|---|---|
| Screens, navigation, checklist presentation | JS | Reads `ServiceState` from the service; never computes telemetry itself |
| QR scan, URL parsing, manual entry form | JS | Section 9 |
| `POST /beacons/enroll`, `GET /beacons/me` during enrollment | JS (`fetch`) | The key exists in JS memory only between the exchange and `saveEnrollment` |
| Storing enrollment fields | Service process | `SecureStore`, written only through `IBeaconService.saveEnrollment` |
| GPS acquisition | Service | Section 6 |
| Socket, send, and heartbeat loops | Service | Section 7 and 8 |
| Telemetry collection | Service | Section 8 |
| Log ring buffer and log upload | Service | JS asks for it; the service holds the key and the file |
| Replay | Service | JS supplies the source and rate; the service plays it |
| Process survival | Platform and root | Persistent system app (low-memory-exempt, restarted by the platform), `START_STICKY`, the `:beacon` boot receiver, and a Magisk `service.sh` root script (section 5.3) |
| Permissions, Doze allowlist, location mode, launcher, safe boot, OTA | Root provisioning | Granted and set from the shell at provisioning (section 14). The app requests nothing at runtime and opens no settings screen; the checklist only reports |
| Hidden bars | The app | `MainActivity` runs immersive-sticky so the status and navigation bars stay hidden while it is in front |

The service never calls into JS. The module binds to the service only while an Activity is resumed and unbinds on pause. A crash, freeze, or kill of the UI process changes nothing in the `:beacon` process; the UI reconnects on next launch and reads the current state.

---

## 4. JS to service interface

### 4.1 AIDL (the only channel between the two processes)

```java
// IBeaconService.aidl
package com.wmsfo.rednose.ipc;
import com.wmsfo.rednose.ipc.IBeaconListener;

interface IBeaconService {
    String getStateJson();                        // ServiceState, section 4.3
    void   saveEnrollment(String enrollmentJson); // Enrollment, section 4.3; starts the foreground service
    void   clearEnrollment();                     // stops loops and the service, wipes the store
    void   setGpsOnlyFallback(boolean enabled);   // persisted; switches FixSource
    void   startReplay(String source, int ratePerSecond);  // source: https URL or content:// URI
    void   stopReplay();
    void   uploadLog();                           // result arrives on IBeaconListener.onLogUploadResult
    String getRecentLog(int maxLines);            // JSON array of log lines, newest last
    void   registerListener(IBeaconListener listener);
    void   unregisterListener(IBeaconListener listener);
}
```

```java
// IBeaconListener.aidl
package com.wmsfo.rednose.ipc;

oneway interface IBeaconListener {
    void onState(String stateJson);        // every 1000 ms while registered, and on every state change
    void onLog(String line);               // every ring-log line as written
    void onLogUploadResult(String json);   // { "ok": true, "id", "sizeBytes", "receivedAt" } or { "ok": false, "code", "message" }
}
```

Every string is JSON produced by the service's `kotlinx.serialization` configuration (section 7.9). The module parses nothing; it forwards the string to JS, which parses it.

### 4.2 The native module as seen from JS

```ts
// src/native/NativeRedNose.ts
export type NativeRedNose = {
  getState(): Promise<ServiceState>;
  saveEnrollment(e: Enrollment): Promise<void>;
  clearEnrollment(): Promise<void>;
  setGpsOnlyFallback(enabled: boolean): Promise<void>;
  startReplay(source: string, ratePerSecond: number): Promise<void>;
  stopReplay(): Promise<void>;
  uploadLog(): Promise<void>;                     // outcome arrives as event "rednose.logUpload"
  getRecentLog(maxLines: number): Promise<string[]>;
  pickRouteFile(): Promise<string | null>;        // ACTION_OPEN_DOCUMENT, application/json; content:// URI or null
  getInitialEnrollUrl(): Promise<string | null>;  // the rednose:// URL the Activity was launched with, once
};
```

There is no permission request and no settings-screen intent in the module: provisioning grants every permission with root (section 14) and the checklist reports the result. A red checklist row means re-run `provision.sh`, not tap through a dialog.

Events on `DeviceEventEmitter`: `rednose.state` (a `ServiceState`), `rednose.log` (a string), `rednose.logUpload` (the upload result object). The module registers its `IBeaconListener` on bind and forwards each callback as one event.

Binding: `bindService(Intent(context, BeaconService::class.java), conn, BIND_AUTO_CREATE)` in the Activity's `onResume`, `unbindService` in `onPause`. `BIND_AUTO_CREATE` brings the `:beacon` process up when it is not running; a service with no enrollment stays a bound-only service and exits when the UI unbinds. A service with an enrollment is also a started foreground service and outlives the binding.

### 4.3 Shapes

```ts
export type Enrollment = {
  apiBaseUrl: string; hubUrl: string; ingestChannel: string;
  beaconId: number; name: string; role: "beacon" | "admin"; key: string;
};

export type LatestFix = {
  lat: number; lng: number; recordedAt: string;
  speedMps: number | null; altitudeM: number | null; headingDeg: number | null; accuracyM: number | null;
  seqLocal: number;
};

export type ServiceState = {
  serviceRunning: boolean;                 // the foreground service is started
  serviceStartedAt: string | null;
  enrollment: {
    beaconId: number; name: string; role: "beacon" | "admin"; keyPrefix: string;   // first 12 characters of the key
    apiBaseUrl: string; hubUrl: string; ingestChannel: string; gpsOnlyFallback: boolean;
  } | null;
  socketState: "connected" | "connecting" | "reconnecting" | "disconnected";
  revoked: boolean;                        // set by an HTTP 401 on a heartbeat
  liveEventId: number | null;              // from the last HTTP heartbeat answer
  isActive: boolean | null;                // from the last HTTP heartbeat answer
  lastHeartbeatAcceptedAt: string | null;  // HTTP
  lastHeartbeatError: string | null;       // "<code> <requestId>" or the hub's generic text
  clockSkewMs: number | null;
  latestFix: LatestFix | null;
  lastDeliveredSeqLocal: number | null;
  lastReceiptLatencyMs: number | null;
  lastSendError: string | null;
  telemetry: Heartbeat;                    // the heartbeat body as it would be sent now (section 8)
  replay: { running: boolean; source: string; index: number; total: number; ratePerSecond: number } | null;
  replayAllowed: boolean;                  // enrollment.apiBaseUrl !== REDNOSE_PROD_API_BASE_URL
  checklist: Checklist;                    // section 13
  appVersion: string;
};
```

`Heartbeat` is the body of `POST /beacons/heartbeat` exactly as in the contracts; `Checklist` is defined in section 13. The key itself never appears in `ServiceState`.

---

## 5. The foreground service

### 5.1 Lifecycle

`BeaconService` runs in process `:beacon`. It is a started foreground service of type `location` from the moment an enrollment exists until `clearEnrollment`, and a bound-only service otherwise.

| Event | Action |
|---|---|
| `saveEnrollment` | Store fields, `startForegroundService`, `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_LOCATION)`, start the fix source and the three loops |
| `BOOT_COMPLETED` | `BootReceiver` (in `:beacon`) reads the store; enrollment present: `startForegroundService`. Background location is granted at provisioning, so the location foreground service is allowed to start from boot; the reboot drill (section 17) proves it on the chosen phone, and the checklist (section 13) shows the permission state so a missing grant is visible, never guessed around |
| `onTaskRemoved` | Ignored; the service is not tied to the task |
| `onDestroy` | Only after `clearEnrollment` or a system kill. `START_STICKY` asks the system to recreate it; as a persistent system app the platform also restarts the process, and the `service.sh` root script (5.3) covers anything the platform does not |
| `onLowMemory`, `onTrimMemory` | Logged with the level; nothing is released (the service holds one fix and one telemetry value) |

Notification: channel `beacon`, importance low, ongoing, not dismissible, text is the socket state and the age of the last delivered fix, updated at most once per 5 s. It is the only visible surface on a locked device.

### 5.2 Wake locks and power

- The service holds a `PARTIAL_WAKE_LOCK` for its whole life. Location updates at 1 Hz keep the radio awake anyway; the wake lock keeps the send loop scheduled during the gaps.
- Doze: provisioning puts the package on the device idle allowlist with root (`dumpsys deviceidle whitelist +com.wmsfo.rednose`), so Doze deferral never applies; the checklist shows the state.
- Screen: the service never touches the screen. Provisioning (section 14) sets stay-awake-while-charging on the device so the notification stays visible.

### 5.3 Process survival

Survival is the platform's job and root's, not a userspace watchdog's. Four layers, each a no-op when the service is already running:

| Layer | Mechanism |
|---|---|
| Persistent system app | `android:persistent="true"` on a `/system/app` package: the app's process is exempt from low-memory kills and the platform restarts it immediately if it dies |
| `START_STICKY` | the foreground service asks the system to recreate it after a kill |
| `BootReceiver` (`:beacon`) | at boot the receiver reads the store and starts the foreground service (section 5.1) |
| Magisk `service.sh` | a root boot script that starts the service and, every 15 s, force-starts it (`am start-foreground-service`) if `pidof` shows the `:beacon` process gone, and re-asserts Red-Nose as the launcher if the HOME activity has changed; running as root outside the app, it is the guarantee the other three layers are measured against |

There is no `WorkManager` worker and no exact alarm; the persistent process plus the root script replace both. Whether the platform's persistent treatment extends to the secondary `:beacon` process or only to the main process is verified in the soak (section 17, the `kill -9` drill); either answer is acceptable because the root script restarts the process within 15 s regardless. `BeaconService.isRunning` (a static flag set in `onCreate`, cleared in `onDestroy`) is what the checklist reads.

### 5.4 Restart counters

`BootCounters` keys two counters by the system `BOOT_COUNT` setting: `serviceRestartCount` (incremented in `onCreate` after the first start since boot) and `sendsFailedSinceBoot`. A new boot value resets both. Stored in plain `SharedPreferences` in the `:beacon` process.

---

## 6. Location

### 6.1 Sources

`FixSource` is an interface with three implementations chosen at runtime:

| Source | When | Request |
|---|---|---|
| `FusedFixSource` | default | `FusedLocationProviderClient.requestLocationUpdates(LocationRequest.Builder(PRIORITY_HIGH_ACCURACY, 1000).setMinUpdateIntervalMillis(1000).setWaitForAccurateLocation(false).build(), callback, looper)` |
| `GpsFixSource` | `gpsOnlyFallback = true` | `LocationManager.requestLocationUpdates(GPS_PROVIDER, 1000L, 0f, listener, looper)` |
| `ReplayFixSource` | replay running (section 11) | plays a route object at the chosen rate |

Switching sources stops the old one before starting the new one; the latest fix is kept across the switch.

### 6.2 What a fix contains

Every `Location` callback produces one `LatestFix`:

| Field | Source |
|---|---|
| `lat`, `lng` | `location.latitude`, `location.longitude` |
| `recordedAt` | `location.time` (epoch ms, the fix time from the provider) formatted rfc3339 with three fractional digits, `Z` |
| `speedMps` | `location.speed` when `hasSpeed()`, else null |
| `altitudeM` | `location.altitude` when `hasAltitude()`, else null |
| `headingDeg` | `location.bearing` when `hasBearing()`, else null |
| `accuracyM` | `location.accuracy` when `hasAccuracy()`, else null |
| `seqLocal` | monotonic counter, incremented per fix |

No filtering, no smoothing, no plausibility check: the API takes what the provider gives. `GnssStats` registers a `GnssStatus.Callback` for satellites used and in view (telemetry only).

---

## 7. Transport

### 7.1 State

The service holds: `socketState` (the four contract values), `attempt` (int, shared by the socket and send loops), `latestFix`, `lastDeliveredSeqLocal`, `inFlight` (bool), `lastHeartbeat` (answer fields), `revoked` (bool), `clockSkewMs`. All access is on one single-threaded coroutine dispatcher (`newSingleThreadContext("beacon")`), so no locking is needed; OkHttp and SignalR callbacks post onto it. Nothing on that dispatcher ever blocks: RxJava completables from the SignalR client are awaited with `kotlinx-coroutines-rx3` (`await()`), never `blockingAwait`, so a slow join cannot stall the send or heartbeat loops.

### 7.2 Backoff

`Backoff.delayMs(attempt) = REDNOSE_BACKOFF_MS[min(attempt, 3)]`, the values `1000, 2000, 3000, 5000`. `attempt` resets to 0 on any success (a completed join, a delivered fix, an accepted HTTP call). There is no maximum attempt and no state that means stopped.

### 7.3 Connectivity

`Connectivity` registers a `ConnectivityManager.NetworkCallback` for the default network. `onAvailable` and `onCapabilitiesChanged` with `NET_CAPABILITY_VALIDATED` signal `retryNow`, which wakes any wait in the socket or send loops immediately. `onLost` is logged and changes nothing: the loops keep probing on their timers because the reported state is unreliable in the air.

### 7.4 Socket loop

`SocketLoop` implements contracts 9.2 with the SignalR Java client:

```kotlin
while (isActive) {
    socketState = if (attempt == 0) CONNECTING else RECONNECTING
    val conn = HubClient.build(enrollment.hubUrl)          // WEBSOCKETS, shouldSkipNegotiate(true), keepAlive 15 s, timeout 30 s
    try {
        conn.on("ChannelEvent", ::onEnvelope, JsonElement::class.java)
        conn.onClosed { cause -> closedSignal.trySend(cause) }
        withTimeout(10_000) { conn.start().await() }                                   // kotlinx-coroutines-rx3
        withTimeout(10_000) { conn.invoke(Void::class.java, "JoinPrivateChannel", enrollment.ingestChannel, enrollment.key).await() }
        attempt = 0; socketState = CONNECTED; hub = conn
        sendLoop.kick()                                     // send the current fix now
        closedSignal.receive()                              // suspend until the connection closes
    } catch (e: Exception) {
        log.socket("join or start failed", e)               // a denied join: first retry waits 10 s (contracts 2.3 step 8)
        if (e.isJoinDenied()) delay(10_000)
    }
    hub = null; conn.stop(); socketState = RECONNECTING
    val wait = Backoff.delayMs(attempt++)
    withTimeoutOrNull(wait) { connectivity.retryNow.receive() }   // sleep the backoff, or less if the network came back
}
```

`onEnvelope` routes on `channel` and `event`: `joined` for the ingest channel confirms `CONNECTED`; `channelEvicted` with `auth_expired` re-invokes `JoinPrivateChannel` immediately and kicks the send loop; `service_removed` retries the join every 5 s; anything else is ignored. A join that throws after an eviction closes the connection and takes the failure branch. At most one `HubConnection` exists.

### 7.5 Send loop

`SendLoop` implements contracts 9.2 exactly. It wakes on a new fix (`kick`), on `REDNOSE_FIX_INTERVAL_MS`, and on `retryNow`. One attempt at a time:

```kotlin
suspend fun attempt() {
    val fix = latestFix ?: return
    if (fix.seqLocal == lastDeliveredSeqLocal || inFlight) return
    inFlight = true
    val t0 = SystemClock.elapsedRealtime()
    val ok = if (socketState == CONNECTED)
        runCatching { withTimeout(10_000) { hub!!.invoke(Void::class.java, "SendToChannel", channel, "location", fix.toPayload()).await() } }.isSuccess
    else
        rest.postLocation(fix)                              // 2xx => true; 10 s timeout
    inFlight = false
    if (ok) { lastDeliveredSeqLocal = fix.seqLocal; lastReceiptLatencyMs = elapsed(t0); attempt = 0 }
    else { onSendFailed(fix); scheduleRetry(Backoff.delayMs(attempt++)) }
}
```

`onSendFailed` logs the outcome (hub: the generic text and `seqLocal`; HTTP: `code` and `requestId`) and increments `sendsFailedSinceBoot` only while `lastHeartbeat.liveEventId != null`. A rejected hub invoke never falls back to HTTP while the socket is up. A fix that arrives during an in-flight send replaces `latestFix` and goes out when the attempt resolves. `httpFallbackSeconds` accrues while a fix is delivered over HTTP.

### 7.6 Heartbeat loop

`HeartbeatLoop` runs every `REDNOSE_HEARTBEAT_INTERVAL_MS`: build the body (section 8), then `POST /beacons/heartbeat` over HTTP whatever the socket state. The answer updates `liveEventId`, `isActive`, `clockSkewMs` per contracts 9.2; a `401` sets `revoked = true` and changes nothing else; any other failure leaves the values and logs. The socket carries locations only.

### 7.7 REST client

`RestClient` is one `OkHttpClient` (connect 5 s, read and write 10 s, no automatic retries, `Connection: keep-alive`) with `X-Beacon-Key` on every call and `X-App-Version` on log uploads. Responses are parsed for `serverTime`, `liveEventId`, `isActive`, and on errors `code` and `requestId`. `REDNOSE_PROD_API_BASE_URL` and the enrolled `apiBaseUrl` are the only hosts it ever talks to.

### 7.8 What the phone never does

No queue, no persistence of fixes, no batching, no local timestamp reordering, no guessing whether an event is live, no giving up. The current fix is the only state and the API's answer is the only truth.

### 7.9 Serialization

One `Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }` instance for every body. Doubles are written by `kotlinx.serialization`'s default (shortest round trip), timestamps by a formatter producing three fractional digits and `Z`. Absent optional fix fields serialize as JSON `null`.

---

## 8. Telemetry and the heartbeat body

`TelemetryCollector` builds the contracts 4.2 body on every heartbeat tick from four probes; every value is nullable and a probe that fails leaves its group's fields null and logs once per minute.

| Group and field | Android source |
|---|---|
| `power.batteryPercent` | `BatteryManager.BATTERY_PROPERTY_CAPACITY` |
| `power.charging` | `BatteryManager.isCharging` |
| `power.batteryTempC` | `ACTION_BATTERY_CHANGED` extra `EXTRA_TEMPERATURE` / 10 |
| `power.thermalStatus` | `PowerManager.currentThermalStatus` mapped to the contract names |
| `radio.networkType` | `TelephonyManager.dataNetworkType` name (`READ_PHONE_STATE`), else `ConnectivityManager` transport name (`WIFI`, `CELLULAR`, `NONE`) |
| `radio.signalDbm`, `radio.signalLevel` | `TelephonyManager.signalStrength?.cellSignalStrengths?.firstOrNull()` `dbm` and `level` |
| `radio.airplaneMode` | `Settings.Global.AIRPLANE_MODE_ON` |
| `radio.connected` | default network has `NET_CAPABILITY_VALIDATED` |
| `gps.provider` | `fused`, `gps`, or `replay` |
| `gps.satellitesUsed`, `gps.satellitesInView` | `GnssStats` |
| `gps.lastFixAccuracyM`, `gps.lastFixAgeS` | from `latestFix`; age from `elapsedRealtime` since the fix |
| `gps.fixesLastMinute` | ring of fix timestamps over the last 60 s |
| `gps.permission.foreground`, `.background`, `.precise` | `checkSelfPermission` for `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`; precise is fine granted (as opposed to coarse only) |
| `transport.*` | `TransportStats`: socket state, reconnect count since service start, `httpFallbackSeconds`, `lastReceiptLatencyMs`, `sendsFailedSinceBoot` |
| `process.deviceUptimeS` | `SystemClock.elapsedRealtime() / 1000` |
| `process.serviceUptimeS` | since `onCreate` |
| `process.serviceRestartCount` | `BootCounters` |
| `process.memoryPressure` | last `onTrimMemory` level mapped to the contract names, `normal` when none |
| `process.batteryOptimizationExempt` | `PowerManager.isIgnoringBatteryOptimizations` |
| `process.notificationPermission` | `NotificationManager.areNotificationsEnabled` |
| `process.systemApp` | `ApplicationInfo.flags` has `FLAG_SYSTEM` |
| `process.rootAvailable` | `su -c id` answers `uid=0` (probed once per heartbeat, 2 s timeout) |
| `identity.deviceModel` | `Build.MANUFACTURER + " " + Build.MODEL` |
| `identity.androidVersion` | `Build.VERSION.RELEASE` |
| `identity.appVersion` | `BuildConfig.VERSION_NAME` |
| `identity.clockSkewMs` | from the last HTTP answer (contracts 9.2) |
| `sentAt` | wall clock at build |

`ServiceState.telemetry` is the same body as it would be sent now, refreshed every second for the UI.

---

## 9. Enrollment

### 9.1 QR path

1. `ScanScreen` uses the `react-native-vision-camera` code scanner for QR only. A scanned value is passed to `parseEnrollUrl`; the system camera app reaches the same code through the `rednose://enroll` intent filter and `getInitialEnrollUrl`.
2. `parseEnrollUrl` accepts exactly `rednose://enroll?api=<encoded https url>&token=<wet_ token>`; the token must match `^wet_[A-Za-z0-9_-]{43}$`; the API URL must be `https` (or `http` in the dev flavour). Anything else shows "not an enrollment code".
3. `enrollApi.exchange(api, token)`: `POST {api}/beacons/enroll { token }`. `404 enrollment_token_invalid` shows "this code was already used or expired; ask for a new one". Other failures show the `code` and `requestId` and offer retry.
4. On `200`, the JS side calls `NativeRedNose.saveEnrollment(response fields)` and navigates to Status. The key exists in JS memory only between steps 3 and 4.

### 9.2 Manual path

`ManualEnrollScreen`: API base URL (prefilled with `REDNOSE_DEFAULT_API_BASE_URL`) and key fields. `GET {api}/beacons/me` with the key; on `200` `saveEnrollment` with the response fields plus the typed key. A `401` shows "key not accepted".

### 9.3 Storage

`SecureStore` wraps `EncryptedSharedPreferences` (`MasterKey` with `AES256_GCM`, keyset in the Android Keystore) in the `:beacon` process only. Keys: `apiBaseUrl`, `hubUrl`, `ingestChannel`, `beaconId`, `name`, `role`, `key`, `gpsOnlyFallback`. `clearEnrollment` wipes them and stops the service. A device with a compromised Keystore is a device we no longer own; there is no second line of defence on the phone, revocation is the answer.

---

## 10. Debug mode (admin role)

Visible only when `enrollment.role === "admin"`. Every screen reads `ServiceState` at 1 Hz and the log stream.

| Screen | Content |
|---|---|
| Telemetry | the full heartbeat body as a table, the last heartbeat outcome, skew, `liveEventId`, `isActive`, revoked |
| Fix log | last 200 fixes: `seqLocal`, time, accuracy, provider, delivered or not, receipt latency |
| Socket log | connection attempts, join results, evictions, closes with cause, backoff waits, `retryNow` signals |
| Failure log | every failed send (with its door) and every failed heartbeat, with `code`, `requestId` or the generic hub text |
| Log file | the ring log tail, an "upload" button (`POST /beacons/logs`), the upload result |
| Replay | section 11 |
| Provisioning | section 14: read-only verification of the system-app, root, launcher, and settings state; provisioning itself is done from the shell, not from buttons |

Nothing in debug mode changes what the service sends except replay and the GPS-only toggle, both of which are also available to the beacon role on the status screen's settings sheet.

---

## 11. Replay

Offered only when `replayAllowed` (the enrolled `apiBaseUrl` differs from `REDNOSE_PROD_API_BASE_URL`). `RouteLoader` fetches a URL or opens a `content://` URI, validates the route object against the vendored `route.schema.json`, and hands the points to `ReplayFixSource`, which emits one fix per `1000 / ratePerSecond` ms with `recordedAt = now()` and the route's `lat`, `lng`, null optional fields, then stops at the end (no loop). While replaying, `gps.provider` reports `replay`. Stopping replay returns to the configured source. Replay against dev with a dev event set live is the end-to-end test of the pipeline.

---

## 12. Logging

`RingLog` writes one line per event to two files of `REDNOSE_LOG_RING_BYTES / 2` each, rotating between them; the older file is truncated when the newer fills. Format: `2026-12-22T01:31:07.412Z INFO socket join ok channel=... attempt=0`. Levels: `DEBUG` (fix-by-fix), `INFO`, `WARN`, `ERROR`. Every line also goes to `IBeaconListener.onLog` for the live screens. The key, the enrollment token, and the `Authorization`-style headers never appear in a line. `LogUploader` reads both files in order and POSTs them as `text/plain` with `X-App-Version`; the result reaches JS through `onLogUploadResult`.

---

## 13. First-run checklist

`Checklist` verifies every item from system APIs on every state tick; the Status screen shows red for anything not satisfied. Provisioning (section 14) sets every item from the shell with root, so on a provisioned phone every row is green before enrollment. The fix path for any red row is the same: re-run `provision.sh`. Nothing is inferred; a green row means the API said so.

| Item | Check | Set by |
|---|---|---|
| Fine location | `ACCESS_FINE_LOCATION` granted | `pm grant` |
| Background location | `ACCESS_BACKGROUND_LOCATION` granted | `pm grant` plus `appops set` |
| Precise location | fine, not coarse | `pm grant` |
| Notifications | `POST_NOTIFICATIONS` granted (33+) and channel not blocked | `pm grant` |
| Battery optimization exempt | `isIgnoringBatteryOptimizations` | `dumpsys deviceidle whitelist +pkg` |
| Location services on | `LocationManager.isLocationEnabled` | `settings put secure location_mode 3` |
| Google Play services | availability `SUCCESS` | the stock image keeps it (section 14.4) |
| Camera (scan only) | `CAMERA` granted | `pm grant` |
| Phone state (signal telemetry) | `READ_PHONE_STATE` granted; optional, telemetry only | `pm grant` |
| System app | `FLAG_SYSTEM` set | the Magisk module |
| Root available | `su -c id` answers `uid=0` | Magisk |
| Launcher | `resolveActivity(HOME)` is `MainActivity` | `cmd package set-home-activity`, re-asserted by `service.sh` |
| Service running | `BeaconService.isRunning` | automatic |

---

## 14. Device provisioning

The phone is rooted and Red-Nose is a persistent system app. There is no device owner and no DevicePolicyManager; root does the job from the shell at provisioning. Every step is a line in `provisioning/provision.sh`, which is idempotent, runs every command through `su`, and reads each value back so a failed line is visible, never assumed. The Provisioning debug screen (section 10) shows the same state read-only.

### 14.1 Root and install (once per phone)

1. Factory reset; skip account setup; enable developer options and USB debugging. No Google account is ever added.
2. Root with Magisk: unlock the bootloader, patch the boot image, flash it, confirm `adb shell su -c id` answers `uid=0(root)`. The device-specific steps are in 14.4 and `provisioning/DEVICE.md`.
3. Flash the Red-Nose Magisk module (`provisioning/magisk-module/`, built in CI, section 16). It places the signed APK at `/system/app/RedNose/RedNose.apk` and `service.sh` (section 5.3). Reboot.
4. Confirm the install: `adb shell dumpsys package com.wmsfo.rednose` shows `FLAG_SYSTEM` and `persistent=true`.
5. Run `provision.sh` (14.2), then enroll (section 9).

### 14.2 Provisioning from the shell (`provision.sh`, root)

| Concern | Command (as root) |
|---|---|
| Runtime permissions | `pm grant com.wmsfo.rednose <perm>` for `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `CAMERA`, `READ_PHONE_STATE` |
| Location appops | `appops set com.wmsfo.rednose FINE_LOCATION allow` and `appops set com.wmsfo.rednose COARSE_LOCATION allow`, so the grant is not downgraded to coarse or foreground-only |
| Battery / Doze | `dumpsys deviceidle whitelist +com.wmsfo.rednose` |
| Location mode | `settings put secure location_mode 3` |
| Stay awake while charging | `settings put global stay_on_while_plugged_in 7` |
| Launcher | `cmd package set-home-activity com.wmsfo.rednose/.MainActivity`; the `service.sh` script re-asserts it every 15 s (section 5.3) |
| Bars | none; `MainActivity` hides the status and navigation bars itself with immersive-sticky mode. (`settings put global policy_control` was removed in Android 11 and does nothing on this phone.) |
| No safe boot | `settings put global safe_boot_disallowed 1` |
| No uninstall | inherent to a `/system/app` package; the user can only disable it, which the launcher lockdown makes unreachable |
| OTA | blocked by the patched boot image: an OTA fails verification against the modified boot partition and the system stays as flashed. Updater packages are left alone (some are non-disableable on this phone) |

What root does not buy and the design accepts: the bootloader stays unlocked (a Magisk-patched boot image cannot pass verified boot with the bootloader locked, so relocking bricks or boot-loops), which means a fastboot wipe is one cable away; a factory reset from Settings stays possible, but Settings is unreachable behind the launcher. Neither matters for a phone that is mounted, plugged in, and touched by nobody. There is no screen pinning and no lock task: without a device owner they show a dialog and exit on a key combination, which is worse than the launcher lockdown.

### 14.3 The Magisk module

`provisioning/magisk-module/` is a standard Magisk module: `module.prop`, `customize.sh`, a `system/app/RedNose/` tree carrying the APK, and `service.sh`. Red-Nose requests only normal and runtime permissions, so it does not need `/system/priv-app` and carries no privileged-permission allowlist; `android:persistent="true"` is honoured for any system app. Updating Red-Nose is a module reflash and reboot (section 16), never a `pm install`.

### 14.4 The phone we own

The beacon phone is a Moto G 5G (2024), Motorola codename `fogo`, model XT2417-1, Android 15, kept on stock firmware (debloated, not a custom ROM) so Google Play services, the fused provider, and the modem stay exactly as shipped. `provisioning/DEVICE.md` is the runbook; the parts that cost time to learn:

- This unit has no `init_boot` partition; the ramdisk is in `boot.img`. Patch and flash `boot`, not `init_boot`.
- Patch the boot image on the phone (push the Magisk binaries and `boot_patch.sh`, run the patch under `su` on the device, pull `new-boot.img`). A headless patch on a Linux box boots but never activates Magisk, because `magiskinit` needs the pre-init device resolved on the phone.
- Mirrors carry firmware for an older build than the phone runs; its `boot.img` boots fine on the newer system with the bootloader unlocked. The exact stock `super.img` is not downloadable anywhere, so it was dumped through root and kept.
- Run `fastboot` from a Linux machine. Windows `fastboot.exe` on an Intel xHCI-only host never sees the device over USB 3, while `adb` works. Use a real USB data cable; a charge-only cable enumerates nothing.
- `super` cannot be flashed from the bootloader ("Unsupported super image format"); it goes through fastbootd (`fastboot reboot fastboot`, then `fastboot flash super`).
- A restore kit (stock and patched `boot.img`, `super.img`, `dtbo`, `vbmeta`, the debloat list, the Magisk APK, `restore.sh`) lives off the phone and was proven end to end: root deliberately broken, full restore run, phone back rooted with the debloat intact. Before any OTA or firmware change, flash `boot_stock.img` first.
- Magisk's `su` lives under `/product/bin`; set Superuser access to "Apps and ADB" and grant the Shell request once so `adb shell su` works for `provision.sh`.
- Debloat is `pm uninstall --user 0` per package from a saved list, reversible with `pm install-existing`. Dialer, telephony, APN, Play services, Play Store, launcher, camera, and Magisk stay.

---

## 15. Build, flavours, versioning

- Flavours `dev` and `prod` differ only in `BuildConfig` fields (contracts 8.5: `REDNOSE_DEFAULT_API_BASE_URL`, `REDNOSE_PROD_API_BASE_URL`, the intervals, backoff, ring size) and the network security config (dev allows cleartext). Same application id, so a phone holds one flavour at a time.
- The manifest sets `android:persistent="true"` and requests only normal and runtime permissions, never a signature or privileged one, so the package stays a plain `/system/app` install with no allowlist (section 14.3).
- `VERSION` holds the semantic version; the Gradle build reads it into `versionName` and derives `versionCode` from it (`major * 10000 + minor * 100 + patch`).
- One release signing key, stored outside the repository; CI signs with it from a secret.
- `CONTRACTS_SHA` names the API commit whose `contracts/` is vendored; the JS tests validate the fix and heartbeat bodies against those schemas.

---

## 16. Branch flow and CI

`dev` and `main`. `.github/workflows/android.yml` on every push: JS unit tests, `./gradlew :app:testDevDebugUnitTest`, then `assembleDevRelease` on `dev` and `assembleProdRelease` on `main`, signed, arm64-v8a only (the beacon phone's ABI, section 14.4; other ABIs build locally only). CI then packages the signed APK into the Magisk module (`provisioning/magisk-module/` plus the APK) and uploads two artifacts, `red-nose-<flavour>-<version>-<sha>.apk` and `red-nose-<flavour>-<version>-<sha>.magisk.zip`. No store, no auto-update; the phone is updated by flashing the new module zip and rebooting.

---

## 17. Soak test

Runs on the real device against the dev API for at least 30 days before December. `tools/soak-observer` polls `GET /admin/beacons` every minute with an admin token and records heartbeat age, socket state, restart count, and battery.

| Drill | Pass criterion |
|---|---|
| Unattended, charging | heartbeat gap never exceeds 60 s over the whole soak; `serviceRestartCount` stays 0 between reboots |
| Reboot | first heartbeat within 120 s of boot without touching the phone |
| Airplane mode 10 min, then off | first delivered fix within 15 s of the network returning |
| Kill from recents, `adb shell am force-stop` | service back within 60 s (persistent-app restart, or the `service.sh` root script) |
| `kill -9` of the `:beacon` pid only, as root | service back within 15 s; the log records whether the platform or the `service.sh` script restarted it (section 5.3) |
| Press HOME, open recents, swipe the app away | Red-Nose is back in front within 15 s (launcher re-assert) |
| Cellular only, driving 1 h at highway speed | fixes delivered at 1 Hz with gaps only where the carrier has none; socket reconnects logged, HTTP fallback covering them |
| Battery to 10 percent unplugged, then charged | no change in behaviour; battery telemetry correct |
| Dev event set live with replay of the 2025 route | the dev site shows the tracker moving along the route |

Every drill is logged in the repository under `docs/soak/<date>.md` with the observer's output.

---

## 18. Decisions made here

- The service runs in its own process and communicates with the UI over AIDL only; the UI never holds the key after enrollment.
- The phone is rooted (Magisk) and Red-Nose is a persistent system app under `/system/app`; that is the baseline for process survival, permission granting, and launcher lockdown, not a post-soak fallback. It requests no privileged permission, so there is no `/system/priv-app` install and no allowlist.
- Device owner mode and DevicePolicyManager are not used; the launcher, permissions, settings, safe boot, and OTA blocking are done from the shell with root at provisioning (section 14).
- Process survival is the platform's plus root's: the persistent system app and `START_STICKY`, with the Magisk `service.sh` root script as the guarantee; there is no WorkManager worker and no exact alarm.
- The app never requests a permission and never opens a settings screen; provisioning grants everything and the checklist only reports.
- Kiosk is "Red-Nose is the launcher, re-asserted by root, hiding its own bars"; screen pinning and lock task are not used. The bootloader stays unlocked and a factory reset from Settings stays possible; both are accepted.
- OTA is blocked by the patched boot image, not by disabling updater packages (some cannot be disabled).
- Fused provider at 1 Hz is the default source; GPS-only is a toggle, never automatic.
- A partial wake lock for the life of the service.
- `READ_PHONE_STATE` is requested for signal telemetry and is optional.
- Replay stops at the end of the route (no loop).
- Heartbeats are HTTP only; the socket carries locations only.
- Enrollment happens after provisioning (section 14.1 order); the in-app scanner works with Red-Nose as the launcher, the system camera is not reachable.
- The heartbeat telemetry `process` group carries `systemApp` and `rootAvailable` in place of `deviceOwnerMode` (section 8, contracts 4.2).

## 19. Needs a decision

Nothing at the moment. Add here as it comes up.
