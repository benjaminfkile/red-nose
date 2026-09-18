#!/system/bin/sh
# Red-Nose survival watchdog (red-nose.md 5.3).
# Magisk runs a module's service.sh at late_start service; this file must keep that name.
#
# Runs as root at late_start_service.  Every 15 s: if pidof shows the :beacon
# process gone, force it up with `am start-foreground-service` against
# BeaconService.  A no-op when the service is already running.  It never
# touches the launcher or any other setting: the phone stays an ordinary
# phone and the device guard (5.5) restores everything the beacon needs
# from inside the service.

BEACON_PROC=com.wmsfo.rednose:beacon
SERVICE_COMP=com.wmsfo.rednose/.service.BeaconService

# Wait for the framework before probing; without it every command below
# fails until boot completes.
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
  sleep 2
done

# One initial start so we do not wait a whole loop tick before the service
# comes up on boot.
am start-foreground-service -n "$SERVICE_COMP" >/dev/null 2>&1

while true; do
  if ! pidof "$BEACON_PROC" >/dev/null 2>&1; then
    am start-foreground-service -n "$SERVICE_COMP" >/dev/null 2>&1
    log -t rednose "service.sh: :beacon absent, start-foreground-service issued"
  fi

  sleep 15
done
