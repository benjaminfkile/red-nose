# Red-Nose technical design

Red-Nose is the WMSFO v2 beacon app: a React Native user interface over a native Kotlin foreground service, Android only. It runs as a persistent system app on a rooted (Magisk) phone; root is the tool for everything the platform would otherwise make hard: it keeps the service alive across kills and low memory, grants every permission, configures the device from the shell with no user taps, and lets the service switch back on anything the beacon needs that someone switches off. The phone stays an ordinary phone: the stock launcher, the status and navigation bars, and every other app work as shipped, and Red-Nose is one app among them. The app never asks the user for anything. The service owns GPS, the send loops, and telemetry; the JavaScript side owns the screens, enrollment, and debug mode. Every name, shape, path, and rule below is the one in the shared contracts (`contracts.md`); where this document restates a contract it does so for the engineer's convenience and the contract wins on any difference.

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
| Roles | None. The API knows Red-Nose only as a key; debug mode is always available to whoever holds the phone. |
| Storage | Enrollment fields in Keystore-backed `EncryptedSharedPreferences`, a ring-buffer log file, and two per-boot counters. No fixes are ever stored. |

---

## 2. Repository and project structure

Repository `red-nose`, branch flow `dev` and `main` (section 16).

```
red-nose/
  package.json  tsconfig.json  app.json  index.js  babel.config.js  metro.config.js  jest.config.js
  App.tsx                              # navigation root: Enroll | Status | Debug
  VERSION                              # semantic version, one line (section 15)
  CONTRACTS_SHA                        # API commit the vendored contracts came from
  CONFORMANCE_SHA                      # beacon-library commit the socket loop conformance scenarios came from (section 7.4)
  contracts/                           # vendored copy of the API's contracts/ (schemas, fixtures)
  scripts/check-contracts.mjs          # diffs contracts/ against wmsfo-api at CONTRACTS_SHA
  src/
    native/NativeRedNose.ts            # typed binding over the native module (section 4)
    state/useServiceState.ts           # subscribes to service state events, exposes ServiceState
    enroll/parseEnrollUrl.ts           # rednose://enroll?api=&token= parsing
    enroll/enrollApi.ts                # POST /beacons/enroll, GET /beacons/me (fetch)
    screens/EnrollScreen.tsx  ScanScreen.tsx  ManualEnrollScreen.tsx
    screens/StatusScreen.tsx  ChecklistCard.tsx
    screens/debug/DebugScreen.tsx      # the debug shell over the seven screens (section 10)
    screens/debug/TelemetryScreen.tsx  FixLogScreen.tsx  SocketLogScreen.tsx  LogStreamScreen.tsx (the shared ring-log renderer)
    screens/debug/FailureLogScreen.tsx LogFileScreen.tsx  ReplayScreen.tsx  ProvisioningScreen.tsx
  __tests__/                           # Jest: URL parsing, state reducer, schema fixtures
  tools/soak-observer/                 # Node script polling GET /admin/beacons during the soak (section 17)
  android/
    build.gradle  settings.gradle  gradle.properties
    app/build.gradle                   # flavours dev, prod; BuildConfig fields (section 15)
    app/src/main/AndroidManifest.xml
    app/src/main/aidl/com/wmsfo/rednose/ipc/IBeaconService.aidl
    app/src/main/aidl/com/wmsfo/rednose/ipc/IBeaconListener.aidl
    app/src/main/java/com/wmsfo/rednose/
      MainApplication.kt               # RN host in the UI process
      MainActivity.kt                  # single-task, launcher icon, rednose://enroll intent filter
      bridge/RedNoseModule.kt          # the RN native module (UI process)
      bridge/RedNosePackage.kt
      bridge/ServiceBinder.kt          # bind/unbind to BeaconService over AIDL
      bridge/MlKitInit.kt              # initialises ML Kit by hand when its provider has not run, for the code scanner
      service/BeaconService.kt         # the foreground service (:beacon), START_STICKY
      service/BeaconNotification.kt
      service/BootReceiver.kt          # BOOT_COMPLETED, runs in :beacon, reads the store, starts foreground
      service/BootCounters.kt          # serviceRestartCount, sendsFailedSinceBoot keyed by BOOT_COUNT
      service/ServiceState.kt          # the JSON shape shipped to JS every second (section 4.3)
      location/FixSource.kt            # interface: start(), stop(), fixes: Flow<LatestFix>
      location/FusedFixSource.kt       # FusedLocationProviderClient, 250 ms, high accuracy
      location/GpsFixSource.kt         # LocationManager GPS_PROVIDER, 250 ms
      location/GnssStats.kt            # GnssStatus callback: satellites used and in view
      location/LatestFix.kt  FixTime.kt  # FixTime: the one RFC 3339 formatter (section 7.9)
      transport/HubClient.kt           # SignalR Java client wrapper
      transport/HubTransport.kt        # the surface the socket loop needs from a hub connection
      transport/SocketEnvelopeRouter.kt  # routes ChannelEvent envelopes into the socket loop
      transport/RestClient.kt          # OkHttp: /locations, /beacons/heartbeat, /beacons/logs
      transport/SocketLoop.kt
      transport/SendLoop.kt
      transport/HeartbeatLoop.kt
      transport/Backoff.kt
      transport/Connectivity.kt        # default network callback, retry-now signal
      transport/TransportStats.kt
      telemetry/TelemetryCollector.kt  # builds the heartbeat body
      telemetry/Heartbeat.kt           # the heartbeat body's types (section 8)
      telemetry/PowerProbe.kt  RadioProbe.kt  ProcessProbe.kt  PermissionProbe.kt
      store/SecureStore.kt             # EncryptedSharedPreferences, :beacon only
      store/Enrollment.kt
      log/RingLog.kt                   # two-file ring, REDNOSE_LOG_RING_BYTES total
      log/LogUploader.kt               # POST /beacons/logs
      log/BeaconJson.kt                # the one kotlinx.serialization instance every body uses (section 7.9)
      replay/RouteLoader.kt            # URL or content URI to a validated route object
      replay/Route.kt  RouteSchema.kt  # the route object and its check against the vendored schema
      replay/ReplayFixSource.kt        # FixSource that plays a route
      checklist/Checklist.kt           # every first-run item, verified from system APIs
      guard/DeviceGuard.kt             # watches the settings the beacon needs and switches them back on (section 5.5)
      guard/DeviceState.kt  MobileData.kt  # the read side of 5.5; the per-SIM mobile data switch
      guard/RootShell.kt               # su -c on a background executor with a timeout
    app/src/main/res/xml/network_security_config.xml       # prod: cleartext off
    app/src/dev/res/xml/network_security_config.xml        # dev: cleartext allowed
    app/src/test/resources/conformance/                    # shared socket loop conformance scenarios (section 7.4), copied from beacon-library at CONFORMANCE_SHA
  provisioning/
    magisk-module/                     # module.prop, customize.sh, service.sh (root watchdog), system/app/RedNose/ tree
    provision.sh                       # adb+root: pm grant, appops, deviceidle whitelist, settings, root policy, safe boot off
    DEVICE.md                          # the runbook for the phone we own (section 14.4)
  .github/workflows/android.yml
```

Gradle dependencies that matter, all pinned to exact versions in `app/build.gradle`:

| Artifact | Use |
|---|---|
| `com.microsoft.signalr:signalr` | Hub client (brings OkHttp 4 and RxJava 3) |
| `com.squareup.okhttp3:okhttp` | REST client, same major as the SignalR client's |
| `com.google.android.gms:play-services-location` | Fused provider |
| `com.google.android.gms:play-services-code-scanner` (16.x) | QR scan for enrollment (section 9.1) |
| `com.google.android.gms:play-services-base` | `ModuleInstallClient` for warming the code-scanner module |
| `androidx.security:security-crypto` (1.1.0-alpha06) | `EncryptedSharedPreferences` |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | Every JSON body the service writes or parses |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android`, `kotlinx-coroutines-rx3` | Loops; awaiting the SignalR client's RxJava types without blocking |
| `androidx.core:core-ktx`, `androidx.appcompat:appcompat` | Notifications, permissions |

JavaScript dependencies: `react-native`, `@react-navigation/native` with the native stack, `react-native-safe-area-context`, `react-native-screens`. QR scanning is the Google Play services code scanner reached through `NativeRedNose.scanQrCode`; the JS side does not link a camera library. Nothing else touches native code; every other native capability is reached through `NativeRedNose`.

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
| Permissions, Doze allowlist, location mode, safe boot, OTA | Root provisioning | Granted and set from the shell at provisioning (section 14). The app requests nothing at runtime and opens no settings screen; the checklist only reports |
| Keeping what the beacon needs switched on | Service (`DeviceGuard`) | Airplane mode off, location on, mobile data on, battery saver off, the runtime permission grants, the Doze allowlist: restored with root whenever someone changes them (section 5.5) |

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
  scanQrCode(): Promise<string | null>;           // Play services code scanner; null on cancel, `scanner_unavailable` on failure
};
```

There is no permission request and no settings-screen intent in the module: provisioning grants every permission with root (section 14) and the checklist reports the result. A red checklist row means re-run `provision.sh`, not tap through a dialog.

Events on `DeviceEventEmitter`: `rednose.state` (a `ServiceState`), `rednose.log` (a string), `rednose.logUpload` (the upload result object). The module registers its `IBeaconListener` on bind and forwards each callback as one event.

Binding: `bindService(Intent(context, BeaconService::class.java), conn, BIND_AUTO_CREATE)` in the Activity's `onResume`, `unbindService` in `onPause`. `BIND_AUTO_CREATE` brings the `:beacon` process up when it is not running; a service with no enrollment stays a bound-only service and exits when the UI unbinds. A service with an enrollment is also a started foreground service and outlives the binding.

### 4.3 Shapes

```ts
export type Enrollment = {
  apiBaseUrl: string; hubUrl: string; ingestChannel: string;
  beaconId: number; name: string; key: string;
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
    beaconId: number; name: string; keyPrefix: string;   // first 12 characters of the key
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
  telemetry: Heartbeat;                    // the heartbeat body as it would be sent now (section 8): sentAt, health, debug
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

`BeaconService` runs in process `:beacon`. Every start reaches it through `startForegroundService` (`BootReceiver`, `saveEnrollment`, the `service.sh` watchdog), so `onStartCommand` always calls `startForeground` with type `location`, enrolled or not; without that the platform kills the process a few seconds after each start. Before enrollment the fix source and the loops stay idle and the notification shows the socket state. The UI binds to it in either state.

| Event | Action |
|---|---|
| `saveEnrollment` | Store fields, `startForegroundService`, `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_LOCATION)`, start the fix source and the three loops |
| `BOOT_COMPLETED` | `BootReceiver` (in `:beacon`) reads the store; enrollment present: `startForegroundService`. Background location is granted at provisioning, so the location foreground service is allowed to start from boot; the reboot drill (section 17) proves it on the chosen phone, and the checklist (section 13) shows the permission state so a missing grant is visible, never guessed around |
| `onTaskRemoved` | Ignored; the service is not tied to the task |
| `onDestroy` | Only after `clearEnrollment` or a system kill. `START_STICKY` asks the system to recreate it; as a persistent system app the platform also restarts the process, and the `service.sh` root script (5.3) covers anything the platform does not |
| `onLowMemory`, `onTrimMemory` | Logged with the level; nothing is released (the service holds one fix and one telemetry value) |

Notification: channel `beacon`, importance low, ongoing, not dismissible, text is the socket state and the age of the last delivered fix, updated at most once per 5 s. It is the only visible surface on a locked device.

### 5.2 Wake locks and power

- The service holds a `PARTIAL_WAKE_LOCK` for its whole life. Location updates every 250 ms keep the radio awake anyway; the wake lock keeps the send loop scheduled during the gaps.
- Doze: provisioning puts the package on the device idle allowlist with root (`dumpsys deviceidle whitelist +com.wmsfo.rednose`), so Doze deferral never applies; the checklist shows the state.
- Screen: the service never touches the screen. Provisioning (section 14) sets stay-awake-while-charging on the device so the notification stays visible.

### 5.3 Process survival

Survival is the platform's job and root's, not a userspace watchdog's. Four layers, each a no-op when the service is already running:

| Layer | Mechanism |
|---|---|
| Persistent system app | `android:persistent="true"` on a `/system/app` package: the app's process is exempt from low-memory kills and the platform restarts it immediately if it dies |
| `START_STICKY` | the foreground service asks the system to recreate it after a kill |
| `BootReceiver` (`:beacon`) | at boot the receiver reads the store and starts the foreground service (section 5.1) |
| Magisk `service.sh` | a root boot script that starts the service and, every 15 s, force-starts it (`am start-foreground-service`) if `pidof` shows the `:beacon` process gone; running as root outside the app, it is the guarantee the other three layers are measured against. It never touches the launcher or any other setting |

There is no `WorkManager` worker and no exact alarm; the persistent process plus the root script replace both. Whether the platform's persistent treatment extends to the secondary `:beacon` process or only to the main process is verified in the soak (section 17, the `kill -9` drill); either answer is acceptable because the root script restarts the process within 15 s regardless. `BeaconService.isRunning` (a static flag set in `onCreate`, cleared in `onDestroy`) is what the checklist reads.

### 5.4 Restart counters

`BootCounters` keys two counters by the system `BOOT_COUNT` setting: `serviceRestartCount` (incremented in `onCreate` after the first start since boot) and `sendsFailedSinceBoot`. A new boot value resets both. Stored in plain `SharedPreferences` in the `:beacon` process.

### 5.5 Device guard

The phone is an ordinary phone that anyone may use for other apps. Whatever the beacon needs from the device, the service keeps switched on: when someone (or something) switches one of the items below off, `DeviceGuard` switches it back on with root, logs it, and counts it. There is no pause, no override, and no setting that disables the guard; it runs whenever the `:beacon` process runs, enrolled or not.

| Item | Needed state, read from | Restored with (`su -c`) |
|---|---|---|
| Airplane mode | off: `Settings.Global.AIRPLANE_MODE_ON` is 0 | `cmd connectivity airplane-mode disable` |
| Location | on: `LocationManager.isLocationEnabled` | `cmd location set-location-enabled true` |
| Mobile data | on: `TelephonyManager.isDataEnabled` for the default data subscription (the switch is kept per SIM in `Settings.Global` `mobile_data<subId>`, which is the fallback read; the plain `mobile_data` key can stay 1 while data is off) | `svc data enable` |
| Battery saver | off: `PowerManager.isPowerSaveMode` is false (battery saver can switch GPS off while the screen is off) | `cmd power set-mode 0` |
| Runtime permissions | granted: `checkSelfPermission` for `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `READ_PHONE_STATE` | `pm grant com.wmsfo.rednose <permission>` for each missing one, then `appops set com.wmsfo.rednose FINE_LOCATION allow` and `COARSE_LOCATION allow` |
| Doze allowlist | exempt: `PowerManager.isIgnoringBatteryOptimizations` | `dumpsys deviceidle whitelist +com.wmsfo.rednose` |

**When it checks.** Immediately on every change the platform announces: a `ContentObserver` on `AIRPLANE_MODE_ON`, `mobile_data`, and `mobile_data<subId>` of the default data subscription, and a receiver for `LocationManager.MODE_CHANGED_ACTION` and `PowerManager.ACTION_POWER_SAVE_MODE_CHANGED`, all registered in `:beacon`. As a backstop for changes that announce nothing (a revoked permission, the Doze allowlist), a full sweep of every item runs once when the service is created and then every `REDNOSE_GUARD_SWEEP_MS` (5000). A check reads the system API only; a root command runs only when an item is in the wrong state.

**How it restores.** Root commands run on `RootShell`, a single background thread with a 5 s timeout per command, so the beacon dispatcher never blocks (7.1). After a command the guard reads the item again, every 100 ms for up to 2 s because some switches apply asynchronously (`svc data enable`): back in the needed state within that window is a restore; still wrong is a failure, retried on the next sweep, forever. Nothing gives up and nothing backs off beyond the sweep interval. Revoking a runtime permission kills the app's processes; the `service.sh` watchdog brings `:beacon` back (5.3), the guard's creation sweep regrants, and the fix source starts once the location grants are back: `startFixSource` runs only after the creation sweep, and a fix source whose start threw a `SecurityException` is started again after the next sweep that regrants.

**What it records.** Every restore writes one ring-log line at INFO, `guard restored <item> took=<ms>`, and every failure one line at WARN, `guard restore failed <item> err=<text>`, at most once per minute per item. `debug.guard` in the heartbeat (section 8) carries a restore counter per item since the service started, `lastRestoredItem`, `lastRestoredAt`, and `lastError`.

**What it does not do.** It does not keep Red-Nose in front, hide the bars, disable the lock screen, or touch Wi-Fi, volume, the screen, or any app. It does not stop anyone from force-stopping the app (the watchdog restarts it within 15 s) or disabling it in Settings (a disabled system app does not run; that is accepted). A screen lock with a PIN, pattern, or password must never be set on the phone: before the first unlock after a boot the credential-encrypted store holding the enrollment is unreadable, so the service could not start until someone unlocks (a swipe-only lock screen is fine).

---

## 6. Location

### 6.1 Sources

`FixSource` is an interface with three implementations chosen at runtime:

| Source | When | Request |
|---|---|---|
| `FusedFixSource` | default | `FusedLocationProviderClient.requestLocationUpdates(LocationRequest.Builder(PRIORITY_HIGH_ACCURACY, 250).setMinUpdateIntervalMillis(250).setWaitForAccurateLocation(false).build(), callback, looper)` |
| `GpsFixSource` | `gpsOnlyFallback = true` | `LocationManager.requestLocationUpdates(GPS_PROVIDER, 250L, 0f, listener, looper)` |
| `ReplayFixSource` | replay running (section 11) | plays a route object at the chosen rate |

The provider may deliver slower than the requested `REDNOSE_FIX_INTERVAL_MS` (many GNSS chips fix once a second); the service sends whatever rate the provider gives, and the true rate is visible in `gps.fixesLastMinute` (section 8).

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

`SocketLoop` implements contracts 9.2 with the SignalR Java client. Two rules of the Java client that the loop depends on: a void hub method (`JoinPrivateChannel`, `SendToChannel`) is invoked through the `Completable invoke(String, Object...)` overload, because the `Single<T>` overload cannot complete on a null result and would time out every call; and the location payload is passed as an object (`LocationPayload`), which the client serializes into the invocation arguments, so the gateway forwards `data` as the location body itself rather than as a JSON string.

```kotlin
while (isActive) {
    socketState = if (attempt == 0) CONNECTING else RECONNECTING
    val conn = HubClient.build(enrollment.hubUrl)          // WEBSOCKETS, shouldSkipNegotiate(true), keepAlive 15 s, timeout 30 s
    try {
        conn.on("ChannelEvent", router::onEnvelope, com.google.gson.JsonElement::class.java)  // Gson-typed, see below
        val closed = Channel<Throwable?>(CONFLATED); closedSignal = closed   // one close channel per connection
        conn.onClosed { cause -> closed.trySend(cause) }
        val closedEarly = raceAgainst(closed) {             // whichever comes first: the handshake settling or this connection closing
            withTimeout(10_000) { conn.start().await() }                               // kotlinx-coroutines-rx3
            withTimeout(10_000) { conn.invoke(Void::class.java, "JoinPrivateChannel", enrollment.ingestChannel, enrollment.key).await() }
        }
        if (!closedEarly) {
            attempt = 0; reconnectCount++; socketState = CONNECTED; hub = conn   // reconnectCount: times the socket has reached connected
            sendLoop.kick()                                 // send the current fix now
            closed.receive()                                // suspend until this connection closes
        }
    } catch (t: Throwable) {
        log.socket("join or start failed", t)
        joinDenied = t.isJoinDenied()
    }
    hub = null; conn.stop(); socketState = RECONNECTING
    if (joinDenied) delay(10_000)                           // a denied join: the first retry waits 10 s in place of the backoff step (contracts 2.3 step 8)
    else withTimeoutOrNull(Backoff.delayMs(attempt)) { connectivity.retryNow.receive() }   // sleep the backoff, or less if the network came back
    attempt++
}
```

The close channel is created per connection: the loop's own `conn.stop()` of a finished connection fires `onClosed` as well, and a channel shared across connections would hand that stale close to the next connection the moment it joined, closing it again in a loop.

A close received while `start()` or the join is still pending ends the wait at once and takes the same close branch as a close received after the join; the 10 s ceilings remain for a handshake that neither settles nor closes.

`onEnvelope` routes on `channel` and `event`: `joined` for the ingest channel confirms `CONNECTED`; `channelEvicted` with `auth_expired` re-invokes `JoinPrivateChannel` immediately and kicks the send loop; `service_removed` retries the join every 5 s; anything else is ignored. A join that throws after an eviction closes the connection and takes the failure branch. At most one `HubConnection` exists.

The handler is registered with `com.google.gson.JsonElement::class.java` because the SignalR Java client deserializes handler arguments with Gson: a `kotlinx.serialization.json.JsonElement` is a sealed type Gson cannot construct, and the client drops an argument it cannot build before the handler runs. The router walks the Gson tree (or an `Object`/map alternative) and reads `event`, `reason`, and `data.reason`; both eviction shapes on the wire are accepted. Every eviction, every re-join success, and every re-join failure is written to the ring log (`socket: evicted <reason>`, `socket: rejoined`, `socket: rejoin failed <error>`, red-nose.md 7.6), and `rejoinCount` on `TransportStats` (section 8) counts every `JoinPrivateChannel` re-invocation the loop issues on a still-open connection.

The loop is held to the shared socket loop conformance scenarios in `android/app/src/test/resources/conformance/`, copied byte-identically from `beacon-library` at the commit in `CONFORMANCE_SHA` and never edited here.

### 7.5 Send loop

`SendLoop` implements contracts 9.2 exactly. It wakes on a new fix (`kick`), on `REDNOSE_FIX_INTERVAL_MS`, and on `retryNow`. One attempt at a time:

```kotlin
suspend fun attempt() {
    val first = latestFix ?: return
    if (first.seqLocal == lastDeliveredSeqLocal || inFlight) return
    if (socketState != CONNECTED && lastHttpSendStartedAt != null) {
        val remaining = REDNOSE_HTTP_FALLBACK_INTERVAL_MS - (elapsed - lastHttpSendStartedAt)
        if (remaining > 0) delay(remaining)                 // a kick during the wait only replaces latestFix
    }
    val fix = latestFix ?: return                           // re-read; a fresh fix may have arrived
    if (fix.seqLocal == lastDeliveredSeqLocal) return
    inFlight = true
    val overHub = socketState == CONNECTED
    val t0 = SystemClock.elapsedRealtime()
    if (!overHub) lastHttpSendStartedAt = t0
    val ok = if (overHub)
        runCatching { withTimeout(10_000) { hub!!.invoke(Void::class.java, "SendToChannel", channel, "location", fix.toPayload()).await() } }.isSuccess
    else
        rest.postLocation(fix)                              // 2xx => true; 10 s timeout
    inFlight = false
    if (ok) { lastDeliveredSeqLocal = fix.seqLocal; lastReceiptLatencyMs = elapsed(t0); attempt = 0 }
    else { onSendFailed(fix); scheduleRetry(Backoff.delayMs(attempt++)) }
}
```

`onSendFailed` logs the outcome (hub: the generic text and `seqLocal`; HTTP: `code` and `requestId`) and increments `sendsFailedSinceBoot` only while `lastHeartbeat.liveEventId != null`. A rejected hub invoke never falls back to HTTP while the socket is up. A fix that arrives during an in-flight send replaces `latestFix` and goes out when the attempt resolves. `httpFallbackSeconds` accrues while a fix is delivered over HTTP.

Over the socket every fix goes out as soon as the previous send resolved, so the delivered rate is the provider's rate, up to four per second. Over HTTP a send starts no sooner than `REDNOSE_HTTP_FALLBACK_INTERVAL_MS` (1000 ms, section 15) after the previous HTTP send started; a fix that arrives inside the window only replaces `latestFix`, the wait itself is not shortened, and the newest fix goes out when the window ends. A socket that connects during the wait takes the fix over the hub with no window: after the delay the door is re-decided from the top. `lastHttpSendStartedAt` is stamped when the HTTP post starts (not when it resolves), so a slow REST call cannot compress the next window. The window is the only difference between the two doors; every other rule (the hub branch, the in-flight guard, the backoff after a failure, the coalescing of a fix that arrives mid-send, the three-rejections re-join, `sendsFailedSinceBoot`, `httpFallbackSeconds`, and every log line) is unchanged.

Three consecutive `hub_rejected` outcomes while `socketState == CONNECTED` and `lastHeartbeat.liveEventId != null` ask the socket loop to re-join the ingest channel once (without a live event every hub send is rejected by design and nothing counts) (the same path as `channelEvicted(auth_expired)`, contracts 9.2), for the case where membership was lost without an envelope reaching the client. The counter resets on any delivered send. If the re-join throws, the socket loop takes its close-or-failure branch (`socketState = RECONNECTING`, backoff) and the next attempts fall back to HTTP until the socket is `CONNECTED` again.

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

`TelemetryCollector` builds the contracts 4.2 body on every heartbeat tick from four probes:

```json
{
  "sentAt": "...",
  "health": { "batteryPercent": 87, "lastFixAgeS": 1, "socketState": "connected" },
  "debug": { "power": {...}, "radio": {...}, "gps": {...}, "transport": {...}, "process": {...}, "identity": {...}, "guard": {...} }
}
```

`health` is the API's typed core: `batteryPercent` from `BatteryManager.BATTERY_PROPERTY_CAPACITY`, `lastFixAgeS` from `latestFix` (age from `elapsedRealtime` since the fix), and `socketState` from `TransportStats.socketState`. `debug` is everything else the phone knows, the seven groups below verbatim (those three fields are not duplicated in the debug groups); the admin panel shows it as a JSON tree and never reads it, so a new leaf here is a one-line change in this file and nowhere else. Every value is nullable and a probe that fails leaves its group's fields null and logs once per minute.

| Group and field (inside `debug`) | Android source |
|---|---|
| `power.charging` | `BatteryManager.isCharging` |
| `power.batteryTempC` | `ACTION_BATTERY_CHANGED` extra `EXTRA_TEMPERATURE` / 10 |
| `power.thermalStatus` | `PowerManager.currentThermalStatus` mapped to the contract names |
| `radio.networkType` | `TelephonyManager.dataNetworkType` name (`READ_PHONE_STATE`), else `ConnectivityManager` transport name (`WIFI`, `CELLULAR`, `NONE`) |
| `radio.signalDbm`, `radio.signalLevel` | `TelephonyManager.signalStrength?.cellSignalStrengths?.firstOrNull()` `dbm` and `level` |
| `radio.airplaneMode` | `Settings.Global.AIRPLANE_MODE_ON` |
| `radio.connected` | default network has `NET_CAPABILITY_VALIDATED` |
| `gps.provider` | `fused`, `gps`, or `replay` |
| `gps.satellitesUsed`, `gps.satellitesInView` | `GnssStats` |
| `gps.lastFixAccuracyM` | from `latestFix` |
| `gps.fixesLastMinute` | ring of fix timestamps over the last 60 s |
| `gps.permission.foreground`, `.background`, `.precise` | `checkSelfPermission` for `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`; precise is fine granted (as opposed to coarse only) |
| `transport.reconnectCount`, `transport.rejoinCount`, `transport.httpFallbackSeconds`, `transport.lastReceiptLatencyMs`, `transport.sendsFailedSinceBoot` | `TransportStats` |
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
| `guard.airplaneModeRestores`, `guard.locationRestores`, `guard.mobileDataRestores`, `guard.batterySaverRestores`, `guard.permissionRestores`, `guard.dozeAllowlistRestores` | `DeviceGuard` counters since the service started (section 5.5) |
| `guard.lastRestoredItem`, `guard.lastRestoredAt`, `guard.lastError` | `DeviceGuard`: the item names are `airplaneMode`, `location`, `mobileData`, `batterySaver`, `permissions`, `dozeAllowlist`; `lastError` is the text of the last failure line, null until one happens |
| `sentAt` | wall clock at build |

`transport.reconnectCount` is the number of times the socket has reached connected since the service started.

`ServiceState.telemetry` is the same body as it would be sent now, refreshed every second for the UI.

---

## 9. Enrollment

### 9.1 QR path

1. `ScanScreen` uses the Google Play services code scanner through `NativeRedNose.scanQrCode`: a full-screen system activity behind one native call (`GmsBarcodeScanning`, QR format only, auto-zoom on). The app never touches the camera and needs no CAMERA permission. The screen opens the scanner automatically once on mount and offers a "scan a code" button to open it again; a resolved string is fed to `parseEnrollUrl`, a cancel resolves to `null` and returns to the screen, and a `scanner_unavailable` failure (the scanner module not yet installed by Play services) shows "scanner unavailable (Play services): enter the code manually" and keeps the manual-entry button visible. `MainActivity.onCreate` warms the module through `ModuleInstallClient` so the first scan does not wait. The `rednose://enroll` intent-filter path through `getInitialEnrollUrl` is unchanged: an incoming URL is parsed directly, skipping the scanner.
2. `parseEnrollUrl` accepts exactly `rednose://enroll?api=<encoded https url>&token=<wet_ token>`; the token must match `^wet_[A-Za-z0-9_-]{43}$`; the API URL must be `https` (or `http` in the dev flavour). Anything else shows "not an enrollment code".
3. `enrollApi.exchange(api, token)`: `POST {api}/beacons/enroll { token }`. `404 enrollment_token_invalid` shows "this code was already used or expired; ask for a new one". Other failures show the `code` and `requestId` and offer retry.
4. On `200`, the JS side calls `NativeRedNose.saveEnrollment(response fields)` and navigates to Status. The key exists in JS memory only between steps 3 and 4.

### 9.2 Manual path

`ManualEnrollScreen`: API base URL (prefilled with `REDNOSE_DEFAULT_API_BASE_URL`) and key fields. `GET {api}/beacons/me` with the key; on `200` `saveEnrollment` with the response fields plus the typed key. A `401` shows "key not accepted".

### 9.3 Storage

`SecureStore` wraps `EncryptedSharedPreferences` (`MasterKey` with `AES256_GCM`, keyset in the Android Keystore) in the `:beacon` process only. Keys: `apiBaseUrl`, `hubUrl`, `ingestChannel`, `beaconId`, `name`, `key`, `gpsOnlyFallback`. `clearEnrollment` wipes them and stops the service. A device with a compromised Keystore is a device we no longer own; there is no second line of defence on the phone, revocation is the answer.

---

## 10. Debug mode

Always available from the status screen once enrolled; nothing on the API side gates it. Every screen reads `ServiceState` at 1 Hz and the log stream.

| Screen | Content |
|---|---|
| Telemetry | the full heartbeat body as a table, the last heartbeat outcome, skew, `liveEventId`, `isActive`, revoked |
| Fix log | last 200 fixes: `seqLocal`, time, accuracy, provider, delivered or not, receipt latency |
| Socket log | connection attempts, join results, evictions, closes with cause, backoff waits, `retryNow` signals |
| Failure log | every failed send (with its door) and every failed heartbeat, with `code`, `requestId` or the generic hub text |
| Log file | the ring log tail, an "upload" button (`POST /beacons/logs`), the upload result |
| Replay | section 11 |
| Provisioning | section 14: read-only verification of the system-app, root, and settings state, plus the `debug.guard` counters; provisioning itself is done from the shell, not from buttons |

Nothing in debug mode changes what the service sends except replay and the GPS-only toggle, both of which are also available on the status screen's settings sheet.

---

## 11. Replay

Offered only when `replayAllowed` (the enrolled `apiBaseUrl` differs from `REDNOSE_PROD_API_BASE_URL`). `RouteLoader` fetches a URL or opens a `content://` URI, validates the route object against the vendored `route.schema.json`, and hands the points to `ReplayFixSource`, which emits one fix per `1000 / ratePerSecond` ms with `recordedAt = now()` and the route's `lat`, `lng`, null optional fields, then stops at the end (no loop). While replaying, `gps.provider` reports `replay`. Stopping replay returns to the configured source. Replay against dev with a dev event set live is the end-to-end test of the pipeline.

---

## 12. Logging

`RingLog` writes one line per event to two files of `REDNOSE_LOG_RING_BYTES / 2` each, rotating between them; the older file is truncated when the newer fills. Format: `2026-12-22T01:31:07.412Z INFO socket join ok channel=... attempt=0`. Levels: `DEBUG` (fix-by-fix), `INFO`, `WARN`, `ERROR`. Every line also goes to `IBeaconListener.onLog` for the live screens. The key, the enrollment token, and the `Authorization`-style headers never appear in a line. `LogUploader` reads both files in order and POSTs them as `text/plain` with `X-App-Version`; the result reaches JS through `onLogUploadResult`.

---

## 13. First-run checklist

`Checklist` verifies every item from system APIs on every state tick; the Status screen shows red for anything not satisfied. Provisioning (section 14) sets every item from the shell with root, so on a provisioned phone every row is green before enrollment. The rows the device guard owns (the grants, the Doze allowlist, location, airplane mode, mobile data, battery saver; 5.5) turn green again on their own within a sweep; a row that stays red means the guard's root command is failing (its WARN line in the log says why), and the fix path for that and for every other red row is the same: re-run `provision.sh`. Nothing is inferred; a green row means the API said so.

| Item | Check | Set by |
|---|---|---|
| Fine location | `ACCESS_FINE_LOCATION` granted | `pm grant` |
| Background location | `ACCESS_BACKGROUND_LOCATION` granted | `pm grant` plus `appops set` |
| Precise location | fine, not coarse | `pm grant` |
| Notifications | `POST_NOTIFICATIONS` granted (33+) and channel not blocked | `pm grant` |
| Battery optimization exempt | `isIgnoringBatteryOptimizations` | `dumpsys deviceidle whitelist +pkg` |
| Location services on | `LocationManager.isLocationEnabled` | `settings put secure location_mode 3` |
| Google Play services | availability `SUCCESS` | the stock image keeps it (section 14.4) |
| Phone state (signal telemetry) | `READ_PHONE_STATE` granted; optional, telemetry only | `pm grant` |
| System app | `FLAG_SYSTEM` set | the Magisk module |
| Root available | `su -c id` answers `uid=0` | Magisk |
| Airplane mode off | `Settings.Global.AIRPLANE_MODE_ON` is 0 | the guard (5.5) |
| Mobile data on | `TelephonyManager.isDataEnabled` (fallback `mobile_data<subId>`, 5.5) | the guard (5.5) |
| Battery saver off | `PowerManager.isPowerSaveMode` is false | the guard (5.5) |
| Service running | `BeaconService.isRunning` | automatic |

---

## 14. Device provisioning

The phone is rooted and Red-Nose is a persistent system app. There is no device owner and no DevicePolicyManager; root does the job from the shell at provisioning. Every step is a line in `provisioning/provision.sh`, which is idempotent, runs every command through `su`, and reads each value back so a failed line is visible, never assumed. The Provisioning debug screen (section 10) shows the same state read-only.

### 14.1 Root and install (once per phone)

1. Factory reset; skip account setup; enable developer options and USB debugging. No Google account is ever added.
2. Root with Magisk: unlock the bootloader, patch the boot image, flash it, confirm `adb shell su -c id` answers `uid=0(root)`. The device-specific steps are in 14.4 and `provisioning/DEVICE.md`.
3. Flash the Red-Nose Magisk module (`provisioning/magisk-module/`, built in CI, section 16). It places the signed APK at `/system/app/RedNose/RedNose.apk` and `service.sh` (section 5.3). Reboot.
4. Confirm the install: `adb shell dumpsys package com.wmsfo.rednose` shows `SYSTEM` and `PERSISTENT` in `flags=[ ... ]` (`persistent=true` on Android 14 and earlier). `provisioning/DEVICE.md` section 12 is the full check.
5. Run `provision.sh` (14.2), then enroll (section 9).

### 14.2 Provisioning from the shell (`provision.sh`, root)

| Concern | Command (as root) |
|---|---|
| Runtime permissions | `pm grant com.wmsfo.rednose <perm>` for `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `READ_PHONE_STATE` (no CAMERA: the QR path uses the Play services code scanner, section 9.1) |
| Location appops | `appops set com.wmsfo.rednose FINE_LOCATION allow` and `appops set com.wmsfo.rednose COARSE_LOCATION allow`, so the grant is not downgraded to coarse or foreground-only |
| Battery / Doze | `dumpsys deviceidle whitelist +com.wmsfo.rednose` |
| Location mode | `settings put secure location_mode 3` |
| Stay awake while charging | `settings put global stay_on_while_plugged_in 7` |
| Launcher | none; the stock launcher stays the home screen and Red-Nose is an ordinary app in the app drawer |
| Screen lock | none set by provisioning; never a PIN, pattern, or password (5.5: the enrollment is in credential-encrypted storage, unreadable before the first unlock after a boot) |
| Root for the app | `magisk --sqlite "REPLACE INTO policies ..."` with the app uid and policy 2 (allow), so the guard's root commands (5.5) and the checklist's `su -c id` probe never show a Magisk prompt |
| Permission auto-revoke | `appops set com.wmsfo.rednose AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore`, so the platform never removes the grants of an app nobody opens |
| No safe boot | `settings put global safe_boot_disallowed 1` |
| No uninstall | inherent to a `/system/app` package; the user can only disable it in Settings, which is accepted (5.5) |
| OTA | blocked by the patched boot image: an OTA fails verification against the modified boot partition and the system stays as flashed. Updater packages are left alone (some are non-disableable on this phone) |

What root does not buy and the design accepts: the bootloader stays unlocked (a Magisk-patched boot image cannot pass verified boot with the bootloader locked, so relocking bricks or boot-loops), which means a fastboot wipe is one cable away; Settings stays reachable, so a factory reset, disabling the app, or setting a PIN stays possible. None of them happens by accident on a phone mounted in the aircraft, and everything a casual change can break (airplane mode, location, mobile data, battery saver, the grants, the Doze allowlist) the guard switches back (5.5). There is no kiosk, no screen pinning, and no lock task.

### 14.3 The Magisk module

`provisioning/magisk-module/` is a standard Magisk module: `module.prop`, `customize.sh`, a `system/app/RedNose/` tree carrying the APK, and `service.sh`. Red-Nose requests only normal and runtime permissions, so it does not need `/system/priv-app` and carries no privileged-permission allowlist; `android:persistent="true"` is honoured for any system app. Updating Red-Nose is a module reflash and reboot (section 16), never a `pm install`.

Every build carries the same `versionCode` (section 15), and the package manager caches its parse of a system APK, so a reflash on its own keeps the previous manifest and only swaps the code: new activities, services and content providers do not exist until the cache is dropped. `customize.sh` therefore clears `/data/system/package_cache` at install; the next boot re-parses the APK.

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
- Release builds are signed with the checked-in `android/app/debug.keystore`, in CI and locally alike, so any build can replace any other on the phone. A real release key stored outside the repository is a prod concern for later (section 19).
- Local builds: README.md has the exact commands. Build the phone's ABI only (`-PreactNativeArchitectures=arm64-v8a`); on Windows enable long paths or clone to a short path, or the native build of a dependency loops forever on a 260-character path.
- `CONTRACTS_SHA` names the API commit whose `contracts/` is vendored; the JS tests validate the fix and heartbeat bodies against those schemas.

---

## 16. Branch flow and CI

`dev` and `main`. `.github/workflows/android.yml` on every push to `dev` or `main` (not `grunt`, whose content reaches `dev` anyway), on the self-hosted runner `nimbus` (the operator's Linux build box, registered to this repository as a systemd service; free minutes, persistent Gradle and npm caches). The workflow still runs unchanged on a GitHub-hosted runner if `runs-on` is switched back: JS unit tests, `./gradlew :app:testDevDebugUnitTest`, then `assembleDevRelease` on `dev` and `assembleProdRelease` on `main`, signed, arm64-v8a only (the beacon phone's ABI, section 14.4; other ABIs build locally only). CI then packages the signed APK into the Magisk module (`provisioning/magisk-module/` plus the APK) and uploads two artifacts, `red-nose-<flavour>-<version>-<sha>.apk` and `red-nose-<flavour>-<version>-<sha>.magisk.zip`. No store, no auto-update; the phone is updated by flashing the new module zip and rebooting.

---

## 17. Soak test

Runs on the real device against the dev API for at least 30 days before December. `tools/soak-observer` polls `GET /admin/beacons` every minute with an admin token and records heartbeat age, socket state, restart count, and battery.

| Drill | Pass criterion |
|---|---|
| Unattended, charging | heartbeat gap never exceeds 60 s over the whole soak; `serviceRestartCount` stays 0 between reboots |
| Reboot | first heartbeat within 120 s of boot without touching the phone |
| Airplane mode switched on from quick settings | off again within 5 s, `guard restored airplaneMode` in the log, `debug.guard.airplaneModeRestores` up by one, first delivered fix within 15 s of the network returning |
| Kill from recents, `adb shell am force-stop` | service back within 60 s (persistent-app restart, or the `service.sh` root script) |
| `kill -9` of the `:beacon` pid only, as root | service back within 15 s; the log records whether the platform or the `service.sh` script restarted it (section 5.3) |
| Location switched off; battery saver switched on; mobile data switched off (each from quick settings, one at a time) | each back in its needed state within 5 s, with its log line and counter |
| Location permission set to "Don't allow" in Settings, app info | the processes restart (watchdog, within 15 s), the grant is back within 5 s of the restart, fixes are delivered again, `debug.guard.permissionRestores` up by one |
| Use the phone for other apps (browser, maps, camera) for 10 min, then lock it | Red-Nose never takes the screen; heartbeats and fixes continue throughout |
| Cellular only, driving 1 h at highway speed | fixes delivered at the provider's rate (up to 4 Hz) with gaps only where the carrier has none; socket reconnects logged, HTTP fallback covering them at most once per second |
| Battery to 10 percent unplugged, then charged | no change in behaviour; battery telemetry correct |
| Dev event set live with replay of the 2025 route | the dev site shows the tracker moving along the route |

Every drill is logged in the repository under `docs/soak/<date>.md` with the observer's output.

---

## 18. Decisions made here

- The service runs in its own process and communicates with the UI over AIDL only; the UI never holds the key after enrollment.
- The phone is rooted (Magisk) and Red-Nose is a persistent system app under `/system/app`; that is the baseline for process survival, permission granting, and restoring what the beacon needs, not a post-soak fallback. It requests no privileged permission, so there is no `/system/priv-app` install and no allowlist.
- Device owner mode and DevicePolicyManager are not used; permissions, settings, safe boot, and OTA blocking are done from the shell with root at provisioning (section 14), and the service restores what the beacon needs with root at runtime (5.5).
- Process survival is the platform's plus root's: the persistent system app and `START_STICKY`, with the Magisk `service.sh` root script as the guarantee; there is no WorkManager worker and no exact alarm.
- The app never requests a permission and never opens a settings screen; provisioning grants everything and the checklist only reports.
- No kiosk (2026-09-16): the phone stays usable for other apps; Red-Nose is not the launcher, does not hide the bars, and provisioning leaves the lock screen alone. The service's device guard switches back on whatever the beacon needs when someone switches it off (5.5), with no pause and no override. The bootloader stays unlocked and a factory reset from Settings stays possible; both are accepted.
- OTA is blocked by the patched boot image, not by disabling updater packages (some cannot be disabled).
- Fused provider is the default source (`REDNOSE_FIX_INTERVAL_MS = 250`, so the location request asks for a fix every 250 ms; the delivered rate is whatever the chip gives, up to 4 Hz); GPS-only is a toggle, never automatic.
- A partial wake lock for the life of the service.
- `READ_PHONE_STATE` is requested for signal telemetry and is optional.
- Replay stops at the end of the route (no loop).
- Heartbeats are HTTP only; the socket carries locations only.
- Enrollment happens after provisioning (section 14.1 order); the in-app scanner is the enrollment path, and the system camera can open a `rednose://enroll` link as well.
- The heartbeat telemetry `process` group carries `systemApp` and `rootAvailable` in place of `deviceOwnerMode` (section 8, contracts 4.2).
- The API knows Red-Nose only as a key: debug mode is always available on the phone; the heartbeat's typed `health` core is filled from the phone's own probes and everything else rides in `debug` for the panel to show verbatim (section 8).
- Hub invocations use the Java client's `Completable` overload and pass the payload as an object (7.4).
- QR scanning is the Google Play services code scanner (`play-services-code-scanner`, a full-screen system activity behind `NativeRedNose.scanQrCode`, section 9.1): the phone keeps GMS, the app never opens the camera, no CAMERA permission is declared or granted, and `MainActivity.onCreate` warms the scanner module through `ModuleInstallClient`. `react-native-vision-camera` was removed with `react-native-nitro-image` and `react-native-nitro-modules`; vision-camera 5 has no Android code scanner, and vision-camera 4 or an ML Kit frame-processor would each mean a camera pipeline the app does not need.

## 19. Needs a decision

- Release signing. Every build, including prod, is signed with the checked-in debug keystore (section 15). Before the phone flies with a prod build, decide whether a dedicated release key (kept outside the repository, supplied to CI as a secret) is worth the one-time re-provisioning it forces, since a signature change means uninstalling the module and re-enrolling.
