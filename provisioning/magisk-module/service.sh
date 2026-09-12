#!/system/bin/sh
# Red-Nose survival + launcher re-assert (red-nose.md 5.3, 14.3).
# Magisk runs a module's service.sh at late_start service; this file must keep that name.
#
# Runs as root at late_start_service.  Every 15 s:
#   1. If pidof shows the :beacon process gone, force it up with
#      `am start-foreground-service` against BeaconService.
#   2. If the resolved HOME activity is not MainActivity, re-assert Red-Nose
#      as the launcher.  The provisioning step (14.2) sets it once; this
#      keeps it set if the platform or a launcher app moves it back.
#
# Each layer is a no-op when the service (or the launcher) is already right.

BEACON_PROC=com.wmsfo.rednose:beacon
SERVICE_COMP=com.wmsfo.rednose/.service.BeaconService
HOME_COMP=com.wmsfo.rednose/.MainActivity

# Wait for the framework before probing; without it every command below
# fails until boot completes.
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
  sleep 2
done

# One initial start so we do not wait a whole loop tick before the service
# comes up on boot.
am start-foreground-service -n "$SERVICE_COMP" >/dev/null 2>&1
cmd package set-home-activity "$HOME_COMP" >/dev/null 2>&1

while true; do
  if ! pidof "$BEACON_PROC" >/dev/null 2>&1; then
    am start-foreground-service -n "$SERVICE_COMP" >/dev/null 2>&1
    log -t rednose "service.sh: :beacon absent, start-foreground-service issued"
  fi

  CURRENT_HOME="$(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null | tail -n1)"
  case "$CURRENT_HOME" in
    "$HOME_COMP") : ;;
    *)
      cmd package set-home-activity "$HOME_COMP" >/dev/null 2>&1
      log -t rednose "service.sh: launcher re-asserted (was '$CURRENT_HOME')"
      ;;
  esac

  sleep 15
done
