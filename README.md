# red-nose

Red-Nose is the WMSFO v2 beacon app: a React Native user interface over a native Kotlin foreground service, Android only. It runs as a normal app on a phone with a clean Android build in the helicopter and posts Santa's location to the API. The pilot never touches it.

Read `docs/` before touching anything:

- `docs/red-nose.md`: this repository's technical design, including provisioning and the phone we own.
- `docs/DESIGN.md`: the design overview for all of v2 (a copy; the original is in `wmsfo-api/docs`).
- `docs/contracts.md`: the shared contracts every component codes against (a copy; wins on any conflict). Section 9 is the beacon contract.
- `provisioning/DEVICE.md`: the phone runbook, including how to install a build and check it, and where the phone currently stands.

## Layout

`App.tsx` and `src/` hold the React Native user interface, `android/` the Kotlin foreground service and the native bridge, `provisioning/` the phone runbook, `tools/soak-observer/` the soak script; `docs/red-nose.md` section 2 lists every file. The provisioning script and the phone's images live in the operator kit, outside this repository (`docs/red-nose.md` 14). There is no iOS target.

## Build

Prerequisites: Node 22 or newer, the Android SDK (platform-tools, build-tools, platforms; the NDK is fetched by Gradle), and a JDK 17 or newer. On Windows, Android Studio's bundled JDK works (`JAVA_HOME` to `Android Studio\jbr`), and Windows long paths must be enabled (`LongPathsEnabled` = 1 in `HKLM\SYSTEM\CurrentControlSet\Control\FileSystem`) or the checkout must live at a short path such as `C:\rn`; otherwise the native build of a dependency loops on a 260-character path and never finishes.

Checks, in the order CI runs them:

```
npm ci
npm run contracts:check      # vendored contracts/ match wmsfo-api at CONTRACTS_SHA
npm test
npx tsc --noEmit
cd android && ./gradlew :app:testDevDebugUnitTest
```

Build the phone's flavour and ABI (the beacon phone is arm64 only; the other ABIs quadruple the native build):

```
cd android && ./gradlew :app:assembleDevRelease -PreactNativeArchitectures=arm64-v8a
# output: android/app/build/outputs/apk/dev/release/app-dev-release.apk
```

`assembleProdRelease` is the same with the prod flavour. A local release build is signed with the checked-in debug keystore; CI signs with the flavour's release key (`docs/red-nose.md` 15). `adb install -r` refuses a different signature and uninstalling loses the enrollment, so a local build goes on an emulator or a spare phone, never over the CI build on the beacon phone.

`npx react-native run-android` (a debug build on a USB device or emulator) is for UI work.

## Install on the phone

CI (`.github/workflows/android.yml`, section 16 of the design) uploads `red-nose-<flavour>-<version>-<sha>.apk` as an artifact on every push to `dev` or `main`. Download it and, from any machine with adb (the cable, or wireless adb on the bench network):

```
adb install -r -g red-nose-dev-<version>-<sha>.apk
adb install -r -g red-nose-prod-<version>-<sha>.apk
```

The two flavours are two apps (`com.wmsfo.rednose.dev` and `com.wmsfo.rednose`, labelled RedNose-DEV and RedNose), so a phone can carry both, each enrolled against its own API; each one admits only its own API's enrollment codes (`docs/red-nose.md` 9.1, 14.3).

No reboot: the install kills the app and its boot receiver, which also answers the package-replaced broadcast, starts the service again within a minute. The enrollment and every setting survive. Then re-run the operator kit's provisioning script with its install step skipped so every row is read back, and verify as in `provisioning/DEVICE.md` section 8. First-time setup of a phone (ROM, provisioning, enrollment) is `docs/red-nose.md` 14 and `provisioning/DEVICE.md`.
