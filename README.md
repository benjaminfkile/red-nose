# red-nose

Red-Nose is the WMSFO v2 beacon app: a React Native user interface over a native Kotlin foreground service, Android only. It runs as a persistent system app on a rooted phone in the helicopter and posts Santa's location to the API. The pilot never touches it.

Read `docs/` before touching anything:

- `docs/red-nose.md`: this repository's technical design, including provisioning and the runbook for the phone we own.
- `docs/DESIGN.md`: the design overview for all of v2 (a copy; the original is in `wmsfo-api/docs`).
- `docs/contracts.md`: the shared contracts every component codes against (a copy; wins on any conflict). Section 9 is the beacon contract.

## Layout

The React Native scaffold is in place (`App.tsx`, `android/`, `index.js`); the structure in `docs/red-nose.md` section 2 is the target. There is no iOS target.

## Build

```
npm install
npx react-native run-android
```

Requires the Android SDK and a JDK. Flavours, versioning, and the Magisk module packaging are in `docs/red-nose.md` sections 15 and 16.
