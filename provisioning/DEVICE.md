# Device runbook — Moto G 5G (2024), `fogo` / XT2417-1

The beacon phone is a Motorola Moto G 5G (2024), codename `fogo`, model
XT2417-1, running Android 15 on stock firmware (debloated, not a custom
ROM) so Google Play services, the fused location provider, and the modem
stay exactly as shipped. Red-Nose runs as a persistent system app under
`/system/app/RedNose/`, rooted with Magisk. This file is the runbook — the
parts that cost real time to learn on this specific phone.

Source: `docs/red-nose.md` §14.4. Where they differ, that section wins.

## 1. Boot image lives in `boot.img`, not `init_boot`

This unit has **no `init_boot` partition**; the ramdisk is in `boot.img`.
Patch and flash `boot`, not `init_boot`. Trying to flash a patched
`init_boot.img` fails with `Invalid sparse file format`.

## 2. Patch the boot image on the phone, not on a Linux box

`magiskinit` needs the pre-init device resolved on the phone. A headless
patch on a Linux host boots but never activates Magisk.

Steps:

1. Push the Magisk APK to the phone, install it, then extract its
   `assets/` (contains `boot_patch.sh` and the Magisk binaries) to
   `/data/local/tmp/magisk/`.
2. Push the stock `boot.img` alongside it.
3. `adb shell su -c 'cd /data/local/tmp/magisk && sh boot_patch.sh boot.img'`.
4. Pull `/data/local/tmp/magisk/new-boot.img`.
5. `fastboot flash boot new-boot.img`.

## 3. Firmware sources, and where the stock `super.img` came from

Public firmware mirrors carry an **older build** than the phone shipped
with. That mirror's `boot.img` still boots fine on the newer system with
the bootloader unlocked, so it is usable as a fallback.

The exact stock `super.img` for this build is not downloadable anywhere,
so it was **dumped through root** and kept in the restore kit. Any
Motorola OTA that changes `super` invalidates that dump — reflash stock
`boot.img` before letting an OTA run.

## 4. Fastboot goes through a Linux machine

Windows `fastboot.exe` on an Intel xHCI-only host **never sees the
device** over USB 3, though `adb` on the same host works fine. Use a
Linux box for every `fastboot` command below. Use a real USB **data**
cable; a charge-only cable enumerates nothing.

## 5. `super` flashes via fastbootd, not the bootloader

From the bootloader, `fastboot flash super super.img` returns
`Unsupported super image format`. It has to go through fastbootd:

```
fastboot reboot fastboot     # boots into fastbootd (userspace fastboot)
fastboot flash super super.img
fastboot reboot
```

## 6. Restore kit (kept off the phone, proven end to end)

Kept together, offline, on the operator's laptop:

| File               | Purpose                                              |
|--------------------|------------------------------------------------------|
| `boot_stock.img`   | pristine boot image; flash first before any OTA      |
| `boot_patched.img` | Magisk-patched boot from step §2                     |
| `super.img`        | root-dumped stock super, matches installed build     |
| `dtbo.img`         | stock dtbo                                           |
| `vbmeta.img`       | stock vbmeta                                         |
| `debloat.txt`      | list of packages the runbook removes (§8)            |
| `Magisk.apk`       | Magisk installer of a known-good version             |
| `restore.sh`       | end-to-end script: flash stock, root, debloat again  |

This was proven end to end: root was deliberately broken, `restore.sh`
was run, and the phone came back rooted with the debloat intact. Redo the
drill after every firmware change.

## 7. Magisk `su` on this build

Magisk installs its `su` under `/product/bin/su`. Two settings must be
correct or `provision.sh` cannot run:

- Superuser access: **Apps and ADB** (Magisk → Superuser → Settings).
- The first `adb shell su` prompts on the phone screen: **grant the
  Shell request** (persistent).

Verify: `adb shell su -c id` returns `uid=0(root)`.

## 8. Debloat

`pm uninstall --user 0 <package>` per line of the saved `debloat.txt`.
The removal is per-user and reversible with `pm install-existing
<package>`. **Keep**: dialer, telephony, APN config, Play services, Play
Store, launcher, Camera, Magisk. Everything else on the shipped image is
in the list.

## 9. Accepted trade-offs (from §14.2)

- The bootloader stays unlocked (a Magisk-patched boot image cannot pass
  verified boot with the bootloader locked; relocking bricks or
  boot-loops). A fastboot wipe is one cable away — accepted, because the
  phone is mounted, plugged in, and touched by nobody.
- A factory reset from Settings stays possible, but Settings is
  unreachable behind the launcher lockdown.
- No screen pinning, no lock task: without a device owner they show a
  dialog and exit on a key combo, which is worse than the launcher
  lockdown.
- OTAs fail verification against the modified boot partition and the
  system stays as flashed. Updater packages are left alone (some are
  non-disableable on this phone).

## 10. First-time bring-up order

1. Factory reset. Skip account setup. No Google account is ever added.
2. Enable developer options and USB debugging.
3. Root with Magisk (§1, §2, §5, §7).
4. Flash the Red-Nose Magisk module (built in CI, `red-nose-<flavour>-<version>-<sha>.magisk.zip`).
5. Reboot. Confirm `adb shell dumpsys package com.wmsfo.rednose` shows
   `FLAG_SYSTEM` and `persistent=true`.
6. Run `provisioning/provision.sh`. Every row must print `ok`.
7. Enroll (in-app QR against a `rednose://enroll?...` URL from the API).

## 11. Updating Red-Nose

Flash the newer `red-nose-<flavour>-<version>-<sha>.magisk.zip` and
reboot. Never `pm install`; a system-app update through `pm install`
lands in `/data/app` and leaves the `/system/app` copy stale.
