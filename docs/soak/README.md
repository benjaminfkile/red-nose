# Soak drills

Red-Nose runs on the real device against the dev API for at least 30 days
before December (red-nose.md 17). Every drill in the table below is exercised
against a provisioned phone and its outcome is logged as a dated file in this
directory, one file per drill run.

## Observer

`tools/soak-observer` polls `GET /admin/beacons` every minute with an admin API
key (`Authorization: Bearer wak_...`, contracts 3.6) and appends one CSV row
per beacon per poll. Columns:

```
observedAt,beaconId,name,heartbeatAgeS,socketState,serviceRestartCount,batteryPercent,charging
```

Run it from any machine that can reach the dev API and stays on for the
whole soak (the operator's build box runs it as a service):

```sh
SOAK_API_BASE_URL=https://api-dev.example.org \
SOAK_ADMIN_KEY=wak_... \
SOAK_CSV_PATH=./soak.csv \
SOAK_BEACON_IDS=3 \
npm run soak:observe
```

`SOAK_INTERVAL_MS` overrides the one-minute default; `SOAK_BEACON_IDS` (comma
separated) keeps only those beacons, every beacon when unset. The tool writes
the header once when the CSV is empty and appends thereafter; kill it with
`SIGINT` or `SIGTERM`. A poll that fails is written to stderr and the next
minute tries again; nothing stops the observer but a signal.

The report reads a CSV and answers the soak question in a few lines:

```sh
npm run soak:report -- ./soak.csv            # every beacon in the file
npm run soak:report -- ./soak.csv 3          # one beacon
```

Per beacon: the window (first and last row), the number of rows, the largest
heartbeat age seen and when, the number of rows past 60 s, the service restart
count at the start and the end, the number of rows whose socket state was not
`connected`, and the lowest battery seen. A soak passes when the largest
heartbeat age stayed under 60 s and the restart count did not move between
reboots.

## Drill log template

Copy this template into `docs/soak/<yyyy-mm-dd>.md` when a drill runs. Fill
every row for every drill exercised that day; leave the observer running for
the whole session so the CSV rows below cover the window.

```markdown
# Soak drill <yyyy-mm-dd>

- Device: <make/model, Android version, build>
- App version: <VERSION>
- API: <dev|prod> at <base URL>
- Observer window: <start rfc3339> to <end rfc3339>
- Observer CSV: <path to the CSV captured during the window>
- Notes: <anything the drill table below does not cover>

| Drill | Pass criterion | Started | Ended | Outcome | Notes |
|---|---|---|---|---|---|
| Unattended, charging | heartbeat gap never exceeds 60 s over the whole soak; `serviceRestartCount` stays 0 between reboots | | | | |
| Reboot | first heartbeat within 120 s of boot without touching the phone | | | | |
| `adb install -r -g` of a build over the running one | service foreground again within 60 s of the install with no reboot and no touch, enrollment intact, heartbeats resume | | | | |
| Airplane mode switched on from quick settings, switched off again after a minute | `guard wrong airplaneMode` in the log within 5 s and `debug.guard.wrong` carrying `airplaneMode` in the first heartbeat after the network returns; `guard ok airplaneMode` once it is off; first delivered fix within 15 s of the network returning | | | | |
| Kill from recents | the UI process dies, the `:beacon` process does not; heartbeats continue | | | | |
| `adb shell am force-stop com.wmsfo.rednose`, then reboot | the service stays down until the reboot (accepted, 5.3) and is back within 120 s of boot | | | | |
| `kill -9` of the `:beacon` pid from `adb root` (rooted debugging on the bench) | service back within 60 s through `START_STICKY`; `serviceRestartCount` up by one | | | | |
| Location switched off; battery saver switched on; mobile data switched off (each from quick settings, one at a time, switched back after a minute) | each reported wrong within 5 s with its WARN line, its `seenWrong` count up by one, and `debug.guard.wrong` naming it in the next heartbeat; the INFO line and an empty `wrong` once it is back | | | | |
| Location permission set to "Don't allow" in Settings, app info, then `pm grant` over `adb` | the processes restart within 60 s, the creation sweep reports `permissions` wrong, fixes are delivered again within 5 s of the grant without a reboot | | | | |
| Use the phone for other apps (browser, maps, camera) for 10 min, then lock it | Red-Nose never takes the screen; heartbeats and fixes continue throughout | | | | |
| Cellular only, driving 1 h at highway speed | fixes delivered at the provider's rate (up to 4 Hz) with gaps only where the carrier has none; socket reconnects logged, HTTP fallback covering them at most once per second | | | | |
| Battery to 10 percent unplugged, then charged | no change in behaviour; battery telemetry correct | | | | |
| Dev event set live with replay of the 2025 route | the dev site shows the tracker moving along the route | | | | |
```

The drill list above is the one in red-nose.md 17. If a drill is repeated
across multiple sessions the file lists the latest attempt; earlier attempts
stay in their own dated files. A drill that fails records what was tried, what
happened, and what changed on the phone or in the code before the next run.
