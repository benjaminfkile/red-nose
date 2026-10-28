# Device runbook: Moto G 5G (2024), `fogo` / XT2417-1

The beacon phone is a Motorola Moto G 5G (2024), codename `fogo`, model
XT2417-1, bootloader unlocked, running official LineageOS 24.0 (Android 17)
with MindTheGapps for Google Play services. No Magisk, no root for apps.
Red-Nose is a normal app installed over `adb`; its data lives in `/data`.
This file is the runbook: the parts that cost real time to learn on this
specific phone, and where it stands.

Source: `docs/red-nose.md` 14. Where they differ, that section wins.

Everything needed to put this phone back together lives in the operator
kit, off the phone and outside this repository: the ROM, recovery, and
Google apps images with their publisher checksums, the CI APK, the flash
script, the provisioning script (`docs/red-nose.md` 14.2 lists its rows),
the enrollment helper, the network credentials, and `INSTALL.md`, the
step-by-step of the install that was run. Nothing in this file names a
network, a serial, or a credential; the kit does.

## 1. Why LineageOS, and why no root

The phone first ran stock Motorola firmware rooted with Magisk. Root did
three jobs: hide the Motorola updater (not disableable), enforce a debloat,
and deliver Red-Nose as a `/system/app` through a Magisk module overlay.
One boot in Magisk's safe mode skipped the overlays, Android saw Red-Nose
as uninstalled and wiped its data, and the enrollment was gone until
someone came with a laptop. Not acceptable for a mounted, unattended
beacon.

LineageOS has no Motorola updater and no bloat, its own updater is one
disabled package, and a normal app in `/data` cannot vanish from any boot.
Every reason for root went away with the stock firmware, so root did not
come back. "Rooted debugging" in Developer options gives `adb root` to the
operator on a cable; it never gives an app root, and the app's own probe
correctly reports `rootAvailable` false.

## 2. Fastboot goes through a Linux machine, on a data cable

Windows `fastboot.exe` on an Intel xHCI-only host never sees the device
over USB 3, though `adb` on the same host works fine. Use a Linux box for
every `fastboot` command. Use a real USB **data** cable; a charge-only
cable enumerates nothing.

## 3. Flashing LineageOS (recovery quirks)

The kit's flash script follows the LineageOS wiki for `fogo`: flash the
additional partitions (`dtbo`, `vendor_boot`), flash the Lineage recovery
to `boot`, boot into it, format data, sideload the ROM, sideload the
Google apps. Things the wiki does not say:

- The recovery's `adb` trusts only hosts the previous system had
  authorized, so "Factory reset > Format data" and "Apply update > Apply
  from ADB" are taps on the phone. `adb sideload` itself needs no
  authorization.
- `adb sideload` of the ROM may never print its final "Total xfer" line on
  the host. The phone's screen is the truth; wait for it.
- After the ROM, answer yes to "Reboot to recovery to install add-ons",
  then sideload the Google apps the same way. The recovery reports
  "Signature verification failed, install anyway?": yes. They are signed
  by their own project, not by LineageOS; their SHA-256 was checked
  against the publisher's on download.
- Back arrow, then "Reboot system now". First boot takes a few minutes.

## 4. First boot needs hands

Skip the whole setup wizard (no Google account, Wi-Fi can be skipped).
Settings > About phone > tap Build number seven times. Developer options:
USB debugging on, Rooted debugging on. Plug in the provisioning host and
accept "Allow USB debugging" with "Always allow".

Every new `adb` host, wired or wireless, gets that dialog once. Nobody is
there to tap it on a mounted phone, so authorize every host that will ever
need the phone while it is on the bench.

## 5. Provisioning

Run the kit's provisioning script with the CI APK next to it. It needs no
root: every row is plain `adb shell`, every value is read back, and it is
safe to re-run at any time. `docs/red-nose.md` 14.2 is the design of the
rows; the commands, so the bench never needs the script to read them:

| Row | Command (`adb shell ...`) |
|---|---|
| Setup wizard | `settings put secure user_setup_complete 1`; `settings put global device_provisioned 1`; `pm disable-user --user 0 org.lineageos.setupwizard`; same for `com.google.android.setupwizard` |
| No self-updates | `pm disable-user --user 0 org.lineageos.updater`; `settings put global package_verifier_user_consent -1` |
| Play services | `pm path com.google.android.gms` non-empty |
| Install | `adb install -r -g <apk>`, once per flavour (the prod and the dev build are two apps, `com.wmsfo.rednose` and `com.wmsfo.rednose.dev`); then `dumpsys package <package>` shows no `SYSTEM` flag |
| Permissions | `pm grant <package> android.permission.<perm>` for `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `READ_PHONE_STATE` |
| Location appops | `appops set <package> FINE_LOCATION allow`; same for `COARSE_LOCATION` |
| Doze | `dumpsys deviceidle whitelist +<package>` |
| Location | `settings put secure location_mode 3` |
| Stay awake | `settings put global stay_on_while_plugged_in 7` |
| Auto-revoke | `appops set <package> AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore` |
| Keyguard | `locksettings set-disabled true` |
| Mobile data | `svc data enable`; `settings put global mobile_data 1` |
| Safe mode | `settings put global safe_boot_disallowed 1` |
| Wi-Fi (bench) | `cmd wifi connect-network <ssid> wpa2 <psk>` from the kit's credential file |
| Wireless adb | `adb root`; `setprop persist.adb.tcp.port 5555`; `adb unroot` |

All rows must print `ok`. `<package>` is `com.wmsfo.rednose` (the prod
build) or `com.wmsfo.rednose.dev` (the dev build); the per-package rows run
once for each flavour on the phone, and the kit's script loops over every
Red-Nose package it finds installed.

Rows that bit on this ROM:

- Mobile data: `svc data enable` writes the per-subscription key only; the
  checklist's read falls back to the plain `mobile_data` key until
  `READ_PHONE_STATE` is granted, so the script sets both and the row stays
  red if either is missing.
- Wireless adb: `adb root`, `setprop persist.adb.tcp.port 5555`, `adb
  unroot`. The port survives reboots. After a reboot `adb disconnect`, then
  connect again to the phone's address on the bench network.
- The safe-mode row matters more than it did: Android safe mode would
  disable a non-system app (data untouched), so `safe_boot_disallowed`
  stays 1.

The checklist in the app (`docs/red-nose.md` 13) shows the same rows
read-only. Any red row: re-run the script. The app cannot fix a row itself.

## 6. Enrollment from adb

Rotate the beacon's key on the API (`POST /admin/beacons/{id}/rotate`; the
response carries `enrollment.url`, valid 15 minutes). Then, from any `adb`
host, cold start only:

```
adb shell am force-stop <package>
adb shell "am start -a android.intent.action.VIEW -d 'rednose://enroll?api=...&token=wet_...' -p <package>"
```

The inner quotes matter: without them the phone's shell splits the URL at
`&` and the app says "not an enrollment code". `-p <package>` matters when
both flavours are installed: both declare the `rednose://enroll` link, and
without it Android asks on the screen which app gets it. The kit's
enrollment helper does the quoting and takes the package. The wrong app
refuses the code anyway (`docs/red-nose.md` 9.1): the prod build takes a
code for the prod API only, the dev build never does. The QR from the
admin panel and "enter manually" (API base URL plus the `wbk_` key) work
the same; manual entry is also how a build enrolls with a key that was
minted elsewhere, and its two fields take `input text` over adb after a
tap on each.

The enrollment lives in `EncryptedSharedPreferences` bound to the Android
Keystore. It cannot be copied between installs or phones, only re-done.
Never `pm clear` the package.

Never `POST /admin/beacons/{id}/activate` on dev: it steals the active role
from the simulator beacon.

## 7. Updating Red-Nose

```
adb install -r -g red-nose-<flavour>-<version>-<sha>.apk
```

then the provisioning script again with its install step skipped, so every
row is read back. Each flavour updates its own package; the other is
untouched. No reboot: the install kills the app, and the app's boot
receiver also answers the package-replaced broadcast and starts the service
again within a minute (`docs/red-nose.md` 5.1, 14.3). The enrollment and
every setting survive.

Only CI builds go on the phone: `adb install -r` refuses an APK signed with
a different key, and uninstalling to switch keys loses the enrollment. A
local build (debug keystore) goes on an emulator or a spare phone.

## 8. Verifying an install

Plug into any machine with `adb` (no fastboot needed), or connect over
wireless adb, then:

```
adb shell dumpsys package <package> | grep -m1 'flags=\['   # HAS_CODE ..., no SYSTEM, no PERSISTENT; once per flavour
adb shell dumpsys package <package> | grep -m1 versionName
adb shell dumpsys activity services <package> | grep isForeground   # isForeground=true once enrolled
adb shell logcat -d -s AndroidRuntime:E ReactNativeJS:E rednose:*    # empty apart from the log tag's own lines
adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -n1
                                                                 # the ROM's launcher, never a Red-Nose package
adb exec-out screencap -p > shot.png                             # what the screen shows
```

The phone boots to the ROM's launcher; the beacon service starts on its
own without anyone opening the app (proven: foreground 46 s after an
unattended reboot). Opening Red-Nose on an enrolled phone shows the Status
screen (beacon name, socket state, live event, delivered seq, receipt
latency, heartbeat age, clock skew). Its "debug" button opens the
Telemetry, Fix log, Socket log and Failure log tabs; "settings" holds the
enrollment. On an unenrolled phone the app opens the code scanner; the X
returns to the scan screen, which also offers manual entry.

The device guard is checked by switching something off and reading the
log and the heartbeat (the soak drills in `docs/red-nose.md` 17), for
example:

```
adb shell cmd connectivity airplane-mode enable; sleep 5
adb shell run-as <package> sh -c 'grep "guard wrong" files/rednose-a.log | tail -n 3'   # debug builds only
adb shell cmd connectivity airplane-mode disable
```

On a release build the ring log is read from the app's Failure log tab or
the API's log upload, and `debug.guard.wrong` in the beacon's last
heartbeat names the item.

The proof that the whole pipeline works is the CDN: `live/location.json`
carries the phone's position within a second of the fix, with `seq`
climbing. If the Status screen shows fixes delivered but the CDN does not
move, the problem is behind the API, not on the phone.

Useful shell habits on this phone:

- Any argument that starts with `/` gets rewritten by Git Bash on Windows
  unless `MSYS_NO_PATHCONV=1` is set; the kit's scripts set it themselves.
- Wake the screen with `input keyevent KEYCODE_WAKEUP` before driving the
  UI; the JS side sleeps in Doze.
- GPS fixes arrive at 1 Hz whatever the app asks for (Qualcomm GNSS HAL);
  the first fix after a wipe is a cold start and takes minutes.
- Never write to `qpnp_adaptive_charge` under the kernel's power supply
  tree; it once latched USB input off.

## 9. Accepted trade-offs

- The bootloader stays unlocked (a custom ROM needs it; the warning on
  every boot is cosmetic). A fastboot wipe is one cable away, accepted
  because the phone is mounted, plugged in, and touched by nobody.
- The phone is an ordinary phone: the launcher, the bars, Settings, and
  every other app work as shipped. A factory reset, disabling the app,
  force-stopping it, or setting a PIN stays possible and is accepted. A
  force stop keeps the service down until the next boot.
- Nothing on the phone can switch a setting back. Whatever the beacon needs
  (airplane mode off, location on, mobile data on, battery saver off, the
  runtime grants, the Doze allowlist) the app watches and reports in its
  log and heartbeat (`docs/red-nose.md` 5.5); a human puts it back, over
  `adb` or from quick settings.
- The keyguard is disabled by provisioning. Never set a PIN, pattern, or
  password: the enrollment lives in credential-encrypted storage, so after
  a reboot the service could not start until someone unlocks.

## 10. The way back to stock

The kit keeps the stock restore images (stock `boot`, `super`, `dtbo`,
`vbmeta`, the debloat list, `restore.sh`), proven end to end on the stock
firmware. Coming from LineageOS, flash a stock `vendor_boot` as well; the
Lineage install replaced it. The stock `super` is not downloadable
anywhere (it was dumped through root while the phone was rooted) and
`super` flashes only through fastbootd (`fastboot reboot fastboot`, then
`fastboot flash super`), not from the bootloader.

## 11. Remote access

The phone keeps a WireGuard tunnel up as an always-on VPN to the operator's
own hub (`docs/red-nose.md` 14.5); the workstation and the build box are peers
of the same hub. From either, anywhere:

```
adb connect <phone-vpn-address>:5555
```

then everything in sections 6 to 8 works as on the bench. The hub, the peer
addresses, the keys, and the phone's tunnel config live in the operator kit
(its REMOTE.md), never here. Rules: the WireGuard app stays on the Doze
allowlist, always-on VPN stays set to it, and the lockdown switch ("block
connections without VPN") is never turned on. A new adb host gets its key
appended to the phone's authorized keys from the bench first.

## 12. Where the phone stands

Updated 2026-10-25.

- LineageOS 24.0 (Android 17), official `fogo` nightly 20261022, flashed
  per section 3; MindTheGapps 17.0.0 arm64. No Google account on the
  device. The home screen is Trebuchet, the ROM's launcher; Red-Nose is an
  icon in the drawer and a foreground service the boot receiver starts.
- Setup wizard, the ROM's updater, and Play Protect's sideload prompt are
  disabled. The keyguard is disabled. Wireless adb is on port 5555 and
  survives reboots.
- Red-Nose dev flavour 0.1.0 (CI build) installed as a normal app;
  `flags=[ HAS_CODE ... ]`, no `SYSTEM`, no `PERSISTENT`. Every
  provisioning row ok.
- Enrolled against the dev API as beacon `red-nose-DEV` (key rotated at
  the install). Proven: unattended reboot, service foreground in 46 s,
  enrollment intact, heartbeats and GPS fixes (about 15 satellites used)
  on dev. Telemetry: `identity.androidVersion` 17, `process.systemApp`
  false, `process.rootAvailable` false.
- Remote access over the WireGuard tunnel proven 2026-10-27: Wi-Fi off, reboot
  on cellular, tunnel back within two minutes, adb over it, heartbeats on LTE
  throughout. Red-Nose runs the CI build of 22fb967 (R14 to R16).
