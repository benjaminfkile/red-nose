# Red-Nose technical design

Red-Nose is the WMSFO v2 beacon app: a React Native user interface over a native Kotlin foreground service, Android only. It runs as an ordinary app on a phone that carries a clean Android build (LineageOS on the phone we own, section 14) with Google Play services and nothing else: no manufacturer updater, no manufacturer task killer, no root. Everything the platform would otherwise make hard is done once from `adb` at provisioning (every permission granted, every setting set, no user taps), and the platform's own mechanisms keep the service alive: a foreground service, the Doze allowlist, `START_STICKY`, and a boot receiver. The app's data lives in `/data` like any app's, so no boot mode can make the platform see it as uninstalled. The phone stays an ordinary phone: the launcher, the status and navigation bars, and every other app work as shipped, and Red-Nose is one app among them. The app never asks the user for anything. The service owns GPS, the send loops, and telemetry; the JavaScript side owns the screens, enrollment, and debug mode. Every name, shape, path, and rule below is the one in the shared contracts (`contracts.md`); where this document restates a contract it does so for the engineer's convenience and the contract wins on any difference.

---

## 1. Scope and shape

| Item | Value |
|---|---|
| Platform | Android 10 (API 29) or newer. `minSdk 29`; `compileSdk` and `targetSdk` as the React Native template sets them (37 and 36 today). Google Play services present (fused location, code scanner). Not rooted; the phone we own runs LineageOS (section 14). |
| Install | A normal app, installed with `adb install -r -g`. Its data lives in `/data`, so no boot mode can make the platform see it as uninstalled. Red-Nose requests only normal and runtime permissions, never a signature or privileged one, so it needs neither `/system/priv-app` nor a privileged-permission allowlist and runs on any Android build. |
| UI | React Native, bare CLI template (no Expo), TypeScript, Hermes. |
| Native | Kotlin. One foreground service in its own process, one React Native native module in the app process, one boot receiver. Process survival is the platform's, not a userspace watchdog's and not root's (section 5.3). |
| Application id | `com.wmsfo.rednose` in both flavours (section 15). |
| Processes | `com.wmsfo.rednose` (React Native UI) and `com.wmsfo.rednose:beacon` (the service). The service process never loads React Native. |
| Distribution | The signed APK that CI builds (section 16), installed over `adb`; one signing key per flavour, no store, no self-update. |
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
      guard/DeviceGuard.kt             # watches the settings the beacon needs and reports them when wrong (section 5.5)
      guard/DeviceState.kt  MobileData.kt  # the read side of 5.5; the per-SIM mobile data switch
    app/src/main/res/xml/network_security_config.xml       # prod: cleartext off
    app/src/dev/res/xml/network_security_config.xml        # dev: cleartext allowed
    app/src/test/resources/conformance/                    # shared socket loop conformance scenarios (section 7.4), copied from beacon-library at CONFORMANCE_SHA
  provisioning/
    DEVICE.md                          # the runbook for the phone we own (section 14.4); the provisioning script itself lives in the operator kit (section 14.2)
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
| Process survival | Platform | A foreground service of type `location` on the Doze allowlist, `START_STICKY`, and the `:beacon` boot receiver (section 5.3) |
| Permissions, Doze allowlist, location mode, safe boot, updates | `adb` provisioning | Granted and set from the shell at provisioning (section 14). The app requests nothing at runtime and opens no settings screen; the checklist only reports |
| Watching what the beacon needs | Service (`DeviceGuard`) | Airplane mode off, location on, mobile data on, battery saver off, the runtime permission grants, the Doze allowlist: read on every change and every sweep, logged and reported in the heartbeat when wrong; nothing on the phone can switch them back without root (section 5.5) |

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

There is no permission request and no settings-screen intent in the module: provisioning grants every permission over `adb` (section 14) and the checklist reports the result. A red checklist row means re-run the provisioning rows, not tap through a dialog.

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

`BeaconService` runs in process `:beacon`. Every start reaches it through `startForegroundService` (`BootReceiver`, `saveEnrollment`, the platform's `START_STICKY` recreate), so `onStartCommand` always calls `startForeground` with type `location`, enrolled or not; without that the platform kills the process a few seconds after each start. Before enrollment the fix source and the loops stay idle and the notification shows the socket state. The UI binds to it in either state.

| Event | Action |
|---|---|
| `saveEnrollment` | Store fields, `startForegroundService`, `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_LOCATION)`, start the fix source and the three loops |
| `BOOT_COMPLETED` | `BootReceiver` (in `:beacon`) reads the store; enrollment present: `startForegroundService`. Background location is granted at provisioning, so the location foreground service is allowed to start from boot; the reboot drill (section 17) proves it on the chosen phone, and the checklist (section 13) shows the permission state so a missing grant is visible, never guessed around |
| `onTaskRemoved` | Ignored; the service is not tied to the task |
| `onDestroy` | Only after `clearEnrollment` or a system kill. `START_STICKY` asks the system to recreate it (5.3) |
| `onLowMemory`, `onTrimMemory` | Logged with the level; nothing is released (the service holds one fix and one telemetry value) |

Notification: channel `beacon`, importance low, ongoing, not dismissible, text is the socket state and the age of the last delivered fix, updated at most once per 5 s. It is the only visible surface on a locked device.

### 5.2 Wake locks and power

- The service holds a `PARTIAL_WAKE_LOCK` for its whole life. Location updates every 250 ms keep the radio awake anyway; the wake lock keeps the send loop scheduled during the gaps.
- Doze: provisioning puts the package on the device idle allowlist from `adb` (`dumpsys deviceidle whitelist +com.wmsfo.rednose`), so Doze deferral never applies; the checklist shows the state.
- Screen: the service never touches the screen. Provisioning (section 14) sets stay-awake-while-charging on the device so the notification stays visible.

### 5.3 Process survival

Survival is the platform's job, not a userspace watchdog's and not root's. Three layers, each a no-op when the service is already running:

| Layer | Mechanism |
|---|---|
| Foreground service on the Doze allowlist | a foreground service of type `location` with an ongoing notification is user-visible work: the platform does not kill it to save memory or battery short of the device running out of memory, and the Doze allowlist plus the battery-optimization exemption (set at provisioning, section 14.2) keep every background restriction away from it. A clean Android build has no manufacturer task killer on top (section 14) |
| `START_STICKY` | the foreground service asks the system to recreate it after a kill, and the recreate reaches `onStartCommand` like every other start (5.1) |
| `BootReceiver` (`:beacon`) | at boot the receiver reads the store and starts the foreground service (section 5.1); the reboot drill (section 17) proves the time to the first heartbeat |

There is no `WorkManager` worker, no exact alarm, no root script, and no `android:persistent` (the platform honours it for system apps only; on a normal app it is noise). What the platform will not undo on its own, the design accepts as a human act on a mounted phone nobody touches: a force stop from Settings or `adb` keeps the service down until the next boot or the next launch of the app, and a disabled app does not run. Android safe mode would disable every non-system app, so provisioning disallows it (14.2); the app's data is untouched by any boot mode either way. `BeaconService.isRunning` (a static flag set in `onCreate`, cleared in `onDestroy`) is what the checklist reads.

### 5.4 Restart counters

`BootCounters` keys two counters by the system `BOOT_COUNT` setting: `serviceRestartCount` (incremented in `onCreate` after the first start since boot) and `sendsFailedSinceBoot`. A new boot value resets both. Stored in plain `SharedPreferences` in the `:beacon` process.

### 5.5 Device guard

The phone is an ordinary phone that anyone may use for other apps. Whatever the beacon needs from the device, the service watches: when someone (or something) switches one of the items below off, `DeviceGuard` sees it within a sweep, logs it, counts it, and reports it in every heartbeat until it is back. It cannot switch anything back: without root an app holds none of the permissions those switches need (`MODIFY_PHONE_STATE`, `WRITE_SECURE_SETTINGS`, `GRANT_RUNTIME_PERMISSIONS`), and root is not on the phone by design (section 14). The fix for a wrong item is a human with `adb` (the provisioning rows of 14.2, re-run) or the phone's own quick settings. There is no pause, no override, and no setting that disables the guard; it runs whenever the `:beacon` process runs, enrolled or not.

| Item | Needed state, read from | Put right by |
|---|---|---|
| Airplane mode | off: `Settings.Global.AIRPLANE_MODE_ON` is 0 | quick settings, or `adb shell cmd connectivity airplane-mode disable` |
| Location | on: `LocationManager.isLocationEnabled` | quick settings, or `adb shell settings put secure location_mode 3` |
| Mobile data | on: `TelephonyManager.isDataEnabled` for the default data subscription (the switch is kept per SIM in `Settings.Global` `mobile_data<subId>`, which is the fallback read; the plain `mobile_data` key can stay 1 while data is off) | quick settings, or `adb shell svc data enable` |
| Battery saver | off: `PowerManager.isPowerSaveMode` is false (battery saver can switch GPS off while the screen is off) | quick settings, or `adb shell cmd power set-mode 0` |
| Runtime permissions | granted: `checkSelfPermission` for `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `READ_PHONE_STATE` | the permission rows of 14.2 (`pm grant` and `appops set` over `adb`) |
| Doze allowlist | exempt: `PowerManager.isIgnoringBatteryOptimizations` | `adb shell dumpsys deviceidle whitelist +com.wmsfo.rednose` |

**When it checks.** Immediately on every change the platform announces: a `ContentObserver` on `AIRPLANE_MODE_ON`, `mobile_data`, and `mobile_data<subId>` of the default data subscription, and a receiver for `LocationManager.MODE_CHANGED_ACTION` and `PowerManager.ACTION_POWER_SAVE_MODE_CHANGED`, all registered in `:beacon`. As a backstop for changes that announce nothing (a revoked permission, the Doze allowlist), a full sweep of every item runs once when the service is created and then every `REDNOSE_GUARD_SWEEP_MS` (5000). A check reads the system API only; the guard runs no shell command and holds no shell. Sweeps never overlap; a request that arrives during a sweep causes exactly one more sweep after it.

**What it records.** An item found wrong when it was right at the previous sweep writes one ring-log line at WARN, `guard wrong <item>`, and counts one for that item; an item found right when it was wrong writes one line at INFO, `guard ok <item> after=<ms>` (ms since it was first seen wrong). Nothing is repeated while the state holds. `debug.guard` in the heartbeat (section 8) carries `wrong` (the names of every item wrong right now, in sweep order, empty when all is well), `wrongSince` (when the oldest of them was first seen wrong, null when none), `lastWrongItem` and `lastWrongAt` (the latest transition into wrong), and `seenWrong`, a count per item of the times it was found wrong since the service started. The admin panel shows the group verbatim; a non-empty `wrong` on a mounted beacon is the operator's signal to reach for `adb`.

**Permissions.** Revoking a runtime permission kills the app's processes; `START_STICKY` and the boot receiver bring `:beacon` back (5.3) and the creation sweep finds the permissions item wrong. `startFixSource` runs only after the creation sweep, and a fix source whose start threw a `SecurityException` stays the pending one: when a later sweep finds the permissions item right again (someone re-ran the permission rows over `adb`), the service restarts `GnssStats` and the fix source.

**What it does not do.** It does not keep Red-Nose in front, hide the bars, disable the lock screen, or touch Wi-Fi, volume, the screen, or any app. It does not stop anyone from force-stopping the app or disabling it in Settings (5.3). A screen lock with a PIN, pattern, or password must never be set on the phone: before the first unlock after a boot the credential-encrypted store holding the enrollment is unreadable, so the service could not start until someone unlocks (provisioning disables the keyguard, 14.2).

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
| `process.systemApp` | `ApplicationInfo.flags` has `FLAG_SYSTEM`; false on the phone we own (section 14) and kept as a fact about the install, not a requirement |
| `process.rootAvailable` | `su -c id` answers `uid=0` (probed once per heartbeat, 2 s timeout); false on the phone we own and kept for the same reason |
| `identity.deviceModel` | `Build.MANUFACTURER + " " + Build.MODEL` |
| `identity.androidVersion` | `Build.VERSION.RELEASE` |
| `identity.appVersion` | `BuildConfig.VERSION_NAME` |
| `identity.clockSkewMs` | from the last HTTP answer (contracts 9.2) |
| `guard.wrong`, `guard.wrongSince` | `DeviceGuard` (section 5.5): the names of the items wrong right now, in sweep order (an empty array when all is well), and the rfc3339 time the oldest of them was first seen wrong (null when none) |
| `guard.lastWrongItem`, `guard.lastWrongAt` | `DeviceGuard`: the latest transition into wrong; the item names are `airplaneMode`, `location`, `mobileData`, `batterySaver`, `permissions`, `dozeAllowlist`; null until one happens |
| `guard.seenWrong.<item>` | `DeviceGuard`: one integer per item name, the times it was found wrong since the service started |
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

`Checklist` verifies every item from system APIs on every state tick; the Status screen shows red for anything not satisfied. Provisioning (section 14) sets every item from `adb`, so on a provisioned phone every row is green before enrollment. A red row stays red until a human fixes it: the fix path for every row is the same, re-run the provisioning rows of 14.2 (the operator kit's script), and the device guard (5.5) is what tells the operator remotely that a row went red. Nothing is inferred; a green row means the API said so. Whether the app is a system app or root is available is not on the list: neither is a requirement (section 14), and the heartbeat's `process` group still reports both as facts (section 8).

| Item | Check | Set by |
|---|---|---|
| Fine location | `ACCESS_FINE_LOCATION` granted | `pm grant` |
| Background location | `ACCESS_BACKGROUND_LOCATION` granted | `pm grant` plus `appops set` |
| Precise location | fine, not coarse | `pm grant` |
| Notifications | `POST_NOTIFICATIONS` granted (33+) and channel not blocked | `pm grant` |
| Battery optimization exempt | `isIgnoringBatteryOptimizations` | `dumpsys deviceidle whitelist +pkg` |
| Location services on | `LocationManager.isLocationEnabled` | `settings put secure location_mode 3` |
| Google Play services | availability `SUCCESS` | the Google apps package flashed with the ROM (section 14.1) |
| Phone state (signal telemetry) | `READ_PHONE_STATE` granted; optional, telemetry only | `pm grant` |
| Airplane mode off | `Settings.Global.AIRPLANE_MODE_ON` is 0 | quick settings or `adb` (5.5) |
| Mobile data on | `TelephonyManager.isDataEnabled` (fallback `mobile_data<subId>`, 5.5) | `svc data enable` plus `settings put global mobile_data 1` (14.2) |
| Battery saver off | `PowerManager.isPowerSaveMode` is false | quick settings or `adb` (5.5) |
| Service running | `BeaconService.isRunning` | automatic |

---

## 14. Device provisioning

The phone runs a clean Android build with nothing on it that updates itself or kills apps, and Red-Nose is a normal app on it. There is no root, no device owner, and no DevicePolicyManager; `adb` does the job from the shell at provisioning, once, with the phone on a cable. Every step is a row in the operator kit's provisioning script, which is idempotent, runs every command over plain `adb shell`, and reads each value back so a failed row is visible, never assumed. The script lives in the operator kit with the ROM images, the APK, and the runbook of the specific phone, never in this repository (the kit carries the phone's network details, and this repository is public); this section is the design of its rows, and `provisioning/DEVICE.md` is the runbook. The Provisioning debug screen (section 10) shows the same state read-only.

Why no root, decided 2026-10-25. The first phone ran stock Motorola firmware rooted with Magisk: root hid a non-disableable updater, enforced a debloat, and delivered Red-Nose as a `/system/app` through a Magisk module overlay. One boot in Magisk's safe mode skipped the overlays, the platform saw the package as uninstalled and wiped its data, and the enrollment was gone until someone came with a laptop. A custom ROM removes every reason root existed: it has no manufacturer updater and no bloat, its own updater is one disabled package, and a normal app in `/data` cannot vanish from any boot. What root also bought, the device guard switching settings back (5.5), the design gives up: on a mounted phone nobody touches, a wrong setting is a human act, and the guard reports it instead.

### 14.1 The ROM and the install (once per phone)

1. Unlock the bootloader and flash LineageOS with its official recovery and the Google apps package (MindTheGapps) per the LineageOS wiki for the device: the additional partitions, the recovery, format data, sideload the ROM, sideload the Google apps. The device-specific gotchas are in 14.4 and `provisioning/DEVICE.md`.
2. First boot needs hands: skip the whole setup wizard (no Google account is ever added, Wi-Fi can be skipped), enable developer options, USB debugging, and rooted debugging (it gives `adb root` to the operator on a cable, never root to an app, and the provisioning script needs it for one row), accept the USB debugging dialog from the provisioning host.
3. Run the operator kit's provisioning script (14.2) with the CI APK next to it. It installs Red-Nose with `adb install -r -g` and asserts the package is not a system app.
4. Confirm the install: `adb shell dumpsys package com.wmsfo.rednose` shows `flags=[ HAS_CODE ... ]` with neither `SYSTEM` nor `PERSISTENT`. `provisioning/DEVICE.md` section 8 is the full check.
5. Enroll (section 9).

### 14.2 Provisioning rows (the operator kit's script, plain `adb shell`)

| Concern | Command |
|---|---|
| Setup wizard | `settings put secure user_setup_complete 1`, `settings put global device_provisioned 1`, `pm disable-user --user 0` on the ROM's and Google's setup wizard packages, so a reboot never lands in a wizard |
| No self-updates | `pm disable-user --user 0` on the ROM's updater package; `settings put global package_verifier_user_consent -1` so a sideload never waits on a Play Protect prompt |
| Google Play services present | `pm path com.google.android.gms` is non-empty (the fused provider and the code scanner, section 9.1) |
| Install | `adb install -r -g <apk>`; then `dumpsys package` must show no `SYSTEM` flag (a system app would be subject to the overlay failure above) |
| Runtime permissions | `pm grant com.wmsfo.rednose <perm>` for `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `READ_PHONE_STATE` (no CAMERA: the QR path uses the Play services code scanner, section 9.1) |
| Location appops | `appops set com.wmsfo.rednose FINE_LOCATION allow` and `appops set com.wmsfo.rednose COARSE_LOCATION allow`, so the grant is not downgraded to coarse or foreground-only |
| Battery / Doze | `dumpsys deviceidle whitelist +com.wmsfo.rednose` |
| Location mode | `settings put secure location_mode 3` |
| Stay awake while charging | `settings put global stay_on_while_plugged_in 7` |
| Permission auto-revoke | `appops set com.wmsfo.rednose AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore`, so the platform never removes the grants of an app nobody opens |
| Keyguard | `locksettings set-disabled true`: no lock screen at all, so the credential-encrypted store is readable from the first boot (5.5) and nothing needs a swipe |
| Mobile data | `svc data enable` and `settings put global mobile_data 1`. `svc` writes the per-subscription key only; the plain key is what the read falls back to when `READ_PHONE_STATE` is not yet granted, and the checklist stays red until both are set |
| No safe boot | `settings put global safe_boot_disallowed 1`: Android safe mode disables every non-system app (5.3) |
| Launcher | none; the ROM's launcher stays the home screen and Red-Nose is an ordinary app in the app drawer |
| Wi-Fi | `cmd wifi connect-network <ssid> wpa2 <psk>` with the credentials from a file next to the script that is never committed anywhere; the field transport is mobile data, Wi-Fi is for the bench |
| Wireless adb | `adb root`, then `setprop persist.adb.tcp.port 5555` (survives reboots), then `adb unroot`; the only row that needs rooted debugging |
| No uninstall | nothing stops `adb uninstall` or Settings on an unlocked phone; accepted (5.3) |

What the design accepts: the bootloader stays unlocked (a custom ROM needs it, and the warning on every boot is cosmetic), so a fastboot wipe is one cable away; Settings stays reachable, so a factory reset, disabling the app, or setting a PIN stays possible. None of them happens by accident on a phone mounted in the aircraft. Whatever a casual change can break (airplane mode, location, mobile data, battery saver, the grants, the Doze allowlist) the guard reports (5.5) and a human puts back. There is no kiosk, no screen pinning, and no lock task.

### 14.3 Updating Red-Nose

An update is `adb install -r -g <new apk>` from any machine with `adb` (the cable, or wireless adb on the bench network), then `adb reboot`, then the provisioning script again with its install step skipped so every row is read back. The reboot is required: a package update kills the app's processes and the platform starts nothing afterwards (opening the app only binds the service, which the platform freezes and drops again), so the boot receiver (5.3) is what brings the beacon back. The enrollment and every setting survive: the app is a normal package, and `-r` replaces the code in place. Never `pm clear` the package (it drops the enrollment, which is Keystore-bound and cannot be restored from a copy, 9.3) and never `adb uninstall` it for an update.

Only CI builds go on the phone. The installed package is signed with the flavour's release key (section 15); `adb install -r` refuses an APK with a different signature, and uninstalling to switch signatures loses the enrollment. A locally built APK (debug keystore) therefore never replaces a CI build; it goes on an emulator or a spare phone.

### 14.4 The phone we own

The beacon phone is a Moto G 5G (2024), Motorola codename `fogo`, model XT2417-1, on official LineageOS 24.0 (Android 17) with MindTheGapps for Google Play services, no Magisk, Red-Nose installed as a normal app. `provisioning/DEVICE.md` is the runbook; the parts that cost time to learn:

- Run `fastboot` from a Linux machine. Windows `fastboot.exe` on an Intel xHCI-only host never sees the device over USB 3, while `adb` works. Use a real USB data cable; a charge-only cable enumerates nothing.
- The LineageOS recovery's `adb` trusts only hosts the previous system had authorized, so "Format data" and "Apply from ADB" are taps on the phone; `adb sideload` itself needs no authorization. The ROM sideload may never print its final transfer line on the host; the phone's screen is the truth. The Google apps sideload reports a signature verification failure and asks to install anyway: yes (they are signed by their own project; their checksum was checked on download).
- Every new `adb` host, wired or wireless, gets the "Allow USB debugging" dialog once; nobody is there to tap it on a mounted phone, so authorize every host on the bench.
- The ROM has no manufacturer updater; its own updater package is disabled at provisioning, and nothing else on the phone installs anything. Google Play services' "theft protection" notification is harmless.
- Mobile data: `svc data enable` alone leaves the checklist red on this ROM; the plain `mobile_data` key must be set too (14.2).
- GPS fixes arrive at 1 Hz whatever rate the app asks for (Qualcomm GNSS HAL, 6.1); the first fix after a wipe is a cold start and takes minutes.
- Never write to the kernel's adaptive charge control (`qpnp_adaptive_charge`); it once latched USB input off.
- Stock restore kits (stock `boot`, `super`, `dtbo`, `vbmeta`, plus a stock `vendor_boot` to undo the ROM's) live in the operator kit and were proven end to end on the stock firmware; LineageOS put `dtbo` and `vendor_boot` in place, so a return to stock flashes those too.

---

## 15. Build, flavours, versioning

- Flavours `dev` and `prod` differ only in `BuildConfig` fields (contracts 8.5: `REDNOSE_DEFAULT_API_BASE_URL`, `REDNOSE_PROD_API_BASE_URL`, the intervals, backoff, ring size) and the network security config (dev allows cleartext). Same application id, so a phone holds one flavour at a time.
- The manifest requests only normal and runtime permissions, never a signature or privileged one, and does not set `android:persistent` (section 5.3), so the package is a plain install on any Android build.
- `VERSION` holds the semantic version; the Gradle build reads it into `versionName` and derives `versionCode` from it (`major * 10000 + minor * 100 + patch`).
- Release builds are signed by CI with a release key per flavour, one for dev and one for prod, that never lives in the repository. The master copy of each keystore (PKCS12, with its alias and passwords) is a secret in the operator's secrets manager; a copy sits in the GitHub environment of its flavour (`dev` admits the `dev` branch, `prod` admits `main`) as `REDNOSE_KEYSTORE_B64`, `REDNOSE_KEYSTORE_PASSWORD`, `REDNOSE_KEY_ALIAS`, and `REDNOSE_KEY_PASSWORD`. The workflow writes the keystore to the runner's temporary directory, hands Gradle its path in `REDNOSE_KEYSTORE_FILE`, and removes it when the job ends. A local release build has none of those variables and falls back to the checked-in debug keystore, so a locally built APK cannot replace a CI build on the phone without uninstalling first, which loses the enrollment (14.3); the same holds between the dev and prod flavours' keys. Losing a key costs one uninstall, reinstall, and enrollment, nothing more: there is no store listing tied to it.
- Local builds: README.md has the exact commands. Build the phone's ABI only (`-PreactNativeArchitectures=arm64-v8a`); on Windows enable long paths or clone to a short path, or the native build of a dependency loops forever on a 260-character path.
- `CONTRACTS_SHA` names the API commit whose `contracts/` is vendored; the JS tests validate the fix and heartbeat bodies against those schemas.

---

## 16. Branch flow and CI

`dev` and `main`. `.github/workflows/android.yml` on every push to `dev` or `main` (not `grunt`, whose content reaches `dev` anyway), on the self-hosted runner `nimbus` (the operator's Linux build box, registered to this repository as a systemd service; persistent Gradle and npm caches). The repository is public, so the runner is guarded three ways: the workflow has no `pull_request` trigger (pushes to `dev` and `main` and a manual run only, which need write access; a fork's own pushes run in the fork, which has no runner), the repository setting that holds workflows from outside contributors for approval is at its strictest, and the signing keys sit in environments that admit their own branch only. The workflow still runs unchanged on a GitHub-hosted runner if `runs-on` is switched back: JS unit tests, `./gradlew :app:testDevDebugUnitTest`, then `assembleDevRelease` on `dev` and `assembleProdRelease` on `main`, signed, arm64-v8a only (the beacon phone's ABI, section 14.4; other ABIs build locally only). CI uploads one artifact, `red-nose-<flavour>-<version>-<sha>.apk`. No store, no auto-update; the phone is updated with `adb install -r -g` and a reboot (14.3).

---

## 17. Soak test

Runs on the real device against the dev API for at least 30 days before December. `tools/soak-observer` polls `GET /admin/beacons` every minute with an admin token and records heartbeat age, socket state, restart count, and battery.

| Drill | Pass criterion |
|---|---|
| Unattended, charging | heartbeat gap never exceeds 60 s over the whole soak; `serviceRestartCount` stays 0 between reboots |
| Reboot | first heartbeat within 120 s of boot without touching the phone |
| Airplane mode switched on from quick settings, switched off again after a minute | `guard wrong airplaneMode` in the log within 5 s and `debug.guard.wrong` carrying `airplaneMode` in the first heartbeat after the network returns; `guard ok airplaneMode` once it is off; first delivered fix within 15 s of the network returning |
| Kill from recents | the UI process dies, the `:beacon` process does not; heartbeats continue |
| `adb shell am force-stop com.wmsfo.rednose`, then reboot | the service stays down until the reboot (accepted, 5.3) and is back within 120 s of boot |
| `kill -9` of the `:beacon` pid from `adb root` (rooted debugging on the bench) | service back within 60 s through `START_STICKY`; `serviceRestartCount` up by one |
| Location switched off; battery saver switched on; mobile data switched off (each from quick settings, one at a time, switched back after a minute) | each reported wrong within 5 s with its WARN line, its `seenWrong` count up by one, and `debug.guard.wrong` naming it in the next heartbeat; the INFO line and an empty `wrong` once it is back |
| Location permission set to "Don't allow" in Settings, app info, then `pm grant` over `adb` | the processes restart within 60 s, the creation sweep reports `permissions` wrong, fixes are delivered again within 5 s of the grant without a reboot |
| Use the phone for other apps (browser, maps, camera) for 10 min, then lock it | Red-Nose never takes the screen; heartbeats and fixes continue throughout |
| Cellular only, driving 1 h at highway speed | fixes delivered at the provider's rate (up to 4 Hz) with gaps only where the carrier has none; socket reconnects logged, HTTP fallback covering them at most once per second |
| Battery to 10 percent unplugged, then charged | no change in behaviour; battery telemetry correct |
| Dev event set live with replay of the 2025 route | the dev site shows the tracker moving along the route |

Every drill is logged in the repository under `docs/soak/<date>.md` with the observer's output.

---

## 18. Decisions made here

- The service runs in its own process and communicates with the UI over AIDL only; the UI never holds the key after enrollment.
- No root and no system app (2026-10-25): the phone runs a clean Android build (LineageOS) with Google Play services, and Red-Nose is a normal app installed over `adb` whose data lives in `/data`. The rooted, Magisk-module, persistent-system-app design that preceded it lost the enrollment to one safe-mode boot (section 14); the custom ROM removes every reason root existed. It requests no privileged permission, so there is no `/system/priv-app` install and no allowlist, and `android:persistent` is gone from the manifest.
- Device owner mode and DevicePolicyManager are not used; permissions, settings, safe boot, and the updater are set from the shell over `adb` at provisioning (section 14), and the service reports what the beacon needs at runtime instead of restoring it (5.5).
- Process survival is the platform's: a foreground service on the Doze allowlist, `START_STICKY`, and the boot receiver; there is no WorkManager worker, no exact alarm, and no root script.
- The app never requests a permission and never opens a settings screen; provisioning grants everything and the checklist only reports.
- No kiosk (2026-09-16): the phone stays usable for other apps; Red-Nose is not the launcher and does not hide the bars. The service's device guard watches whatever the beacon needs and reports it in the log and the heartbeat when someone switches it off (5.5), with no pause and no override. The bootloader stays unlocked and a factory reset from Settings stays possible; both are accepted.
- Updates to the OS are prevented by disabling the ROM's updater package at provisioning; there is no manufacturer updater to fight.
- Fused provider is the default source (`REDNOSE_FIX_INTERVAL_MS = 250`, so the location request asks for a fix every 250 ms; the delivered rate is whatever the chip gives, up to 4 Hz); GPS-only is a toggle, never automatic.
- A partial wake lock for the life of the service.
- `READ_PHONE_STATE` is requested for signal telemetry and is optional.
- Replay stops at the end of the route (no loop).
- Heartbeats are HTTP only; the socket carries locations only.
- Enrollment happens after provisioning (section 14.1 order); the in-app scanner is the enrollment path, and the system camera can open a `rednose://enroll` link as well.
- The heartbeat telemetry `process` group carries `systemApp` and `rootAvailable` in place of `deviceOwnerMode` (section 8, contracts 4.2); both are facts about the install and read false on the phone we own. They are not checklist rows.
- The API knows Red-Nose only as a key: debug mode is always available on the phone; the heartbeat's typed `health` core is filled from the phone's own probes and everything else rides in `debug` for the panel to show verbatim (section 8).
- Hub invocations use the Java client's `Completable` overload and pass the payload as an object (7.4).
- QR scanning is the Google Play services code scanner (`play-services-code-scanner`, a full-screen system activity behind `NativeRedNose.scanQrCode`, section 9.1): the phone keeps GMS, the app never opens the camera, no CAMERA permission is declared or granted, and `MainActivity.onCreate` warms the scanner module through `ModuleInstallClient`. `react-native-vision-camera` was removed with `react-native-nitro-image` and `react-native-nitro-modules`; vision-camera 5 has no Android code scanner, and vision-camera 4 or an ML Kit frame-processor would each mean a camera pipeline the app does not need.

## 19. Needs a decision

- Remote management of the mounted phone: installing a build and reading logs from anywhere, without a person on site. Nothing in the app depends on the answer; the candidate is a mesh VPN on the phone with `adb` over it, decided outside this document.
- An update without a reboot: a receiver for `ACTION_MY_PACKAGE_REPLACED` in `:beacon` that starts the foreground service when an enrollment exists, the way `BootReceiver` does (the broadcast is exempt from the background start restrictions, and the app is on the battery-optimization allowlist). Until it exists, 14.3 reboots after every install.
