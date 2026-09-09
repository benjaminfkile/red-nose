# Soak drills

Red-Nose runs on the real device against the dev API for at least 30 days
before December (red-nose.md 17). Every drill in the table below is exercised
against a provisioned phone and its outcome is logged as a dated file in this
directory, one file per drill run.

## Observer

`tools/soak-observer` polls `GET /admin/beacons` every minute with an admin
`X-Beacon-Key` and appends one CSV row per beacon per poll. Columns:

```
observedAt,beaconId,name,heartbeatAgeS,socketState,serviceRestartCount,batteryPercent,charging
```

Run it from a laptop that can reach the dev API:

```sh
SOAK_API_BASE_URL=https://api-dev.example.org \
SOAK_ADMIN_KEY=wbk_... \
SOAK_CSV_PATH=./soak.csv \
node --loader tsx tools/soak-observer/index.ts
```

`SOAK_INTERVAL_MS` overrides the one-minute default. The tool writes the
header once when the CSV is empty and appends thereafter; kill it with
`SIGINT` or `SIGTERM`.

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
| Airplane mode 10 min, then off | first delivered fix within 15 s of the network returning | | | | |
| Kill from recents, `adb shell am force-stop` | service back within 60 s (persistent-app restart, or the `service.d` root script) | | | | |
| `kill -9` of the `:beacon` pid only, as root | service back within 15 s; the log records whether the platform or the `service.d` script restarted it (section 5.3) | | | | |
| Press HOME, open recents, swipe the app away | Red-Nose is back in front within 15 s (launcher re-assert) | | | | |
| Cellular only, driving 1 h at highway speed | fixes delivered at 1 Hz with gaps only where the carrier has none; socket reconnects logged, HTTP fallback covering them | | | | |
| Battery to 10 percent unplugged, then charged | no change in behaviour; battery telemetry correct | | | | |
| Dev event set live with replay of the 2025 route | the dev site shows the tracker moving along the route | | | | |
```

The drill list above is the one in red-nose.md 17. If a drill is repeated
across multiple sessions the file lists the latest attempt; earlier attempts
stay in their own dated files. A drill that fails records what was tried, what
happened, and what changed on the phone or in the code before the next run.
