#!/system/bin/sh
# Red-Nose Magisk module installer hook (red-nose.md 14.3).
# Magisk sources this script inside its own busybox shell; $MODPATH is the
# unpacked module tree it will overlay onto /system.

# shellcheck shell=sh disable=SC2154
ui_print "- Red-Nose module: $(cat "$MODPATH/module.prop" | grep '^version=' | cut -d= -f2-)"

APK="$MODPATH/system/app/RedNose/RedNose.apk"
if [ ! -s "$APK" ]; then
  ui_print "! RedNose.apk is missing or empty at system/app/RedNose/RedNose.apk"
  ui_print "! CI must drop the signed APK into the module zip before flashing."
  abort "! aborting: no APK to install"
fi

# The overlay tree (system/app/RedNose/RedNose.apk, service.sh)
# is placed by Magisk itself.  Just fix ownership and modes.
set_perm_recursive "$MODPATH/system/app/RedNose" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755

ui_print "- Installed RedNose.apk to /system/app/RedNose/"
ui_print "- Installed service.sh (root watchdog + launcher re-assert)"
ui_print "- Reboot, then run provisioning/provision.sh once."
