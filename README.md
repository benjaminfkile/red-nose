# red-nose

Red-Nose is the WMSFO v2 beacon app: a React Native user interface over a native Kotlin foreground service, Android only. It runs as a persistent system app on a rooted phone in the helicopter and posts Santa's location to the API. The pilot never touches it.

Read `docs/` before touching anything:

- `docs/red-nose.md`: this repository's technical design, including provisioning and the runbook for the phone we own.
- `docs/DESIGN.md`: the design overview for all of v2 (a copy; the original is in `wmsfo-api/docs`).
- `docs/contracts.md`: the shared contracts every component codes against (a copy; wins on any conflict). Section 9 is the beacon contract.
- `provisioning/DEVICE.md`: the phone runbook, including how to flash a build and check it, and where the phone currently stands.

## Layout

`App.tsx` and `src/` hold the React Native user interface, `android/` the Kotlin foreground service and the native bridge, `provisioning/` the Magisk module, `provision.sh`, and the phone runbook, `tools/soak-observer/` the soak script; `docs/red-nose.md` section 2 lists every file. There is no iOS target.

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

`assembleProdRelease` is the same with the prod flavour. Release builds are signed with `android/app/debug.keystore`, so a local build and a CI build carry the same signature and either can replace the other on the phone.

`npx react-native run-android` (a debug build on a USB device or emulator) is only for UI work: it installs into `/data/app`, which is not how the phone runs the app (see `docs/red-nose.md` section 14).

## Package and flash

The phone runs the app as a Magisk module, never through `pm install`. CI (`.github/workflows/android.yml`, section 16 of the design) produces `red-nose-<flavour>-<version>-<sha>.magisk.zip` and the matching APK as artifacts on every push to `dev` or `main`. To build the same zip locally:

```
stage=$(mktemp -d)
cp -R provisioning/magisk-module/. "$stage"/
rm -f "$stage/system/app/RedNose/.gitkeep"
cp android/app/build/outputs/apk/dev/release/app-dev-release.apk "$stage/system/app/RedNose/RedNose.apk"
(cd "$stage" && zip -r ../red-nose-dev.magisk.zip .)
# Windows without zip: (cd "$stage" && tar -a -cf ..\red-nose-dev.magisk.zip module.prop customize.sh service.sh system)
```

Flash it from any machine with adb (fastboot is not involved):

```
adb push red-nose-dev.magisk.zip /data/local/tmp/rednose.magisk.zip
adb shell su -c "magisk --install-module /data/local/tmp/rednose.magisk.zip"
adb reboot
```

The installer clears the package manager's parse cache so a same-version reflash is picked up. After the reboot, verify as in `provisioning/DEVICE.md` section 12. The enrollment lives in userdata and survives a reflash.
