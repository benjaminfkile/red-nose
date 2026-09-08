# Runner smoke test

Date: 2026-09-08

Verifies that the beacon app's JavaScript toolchain (Node, npm, Jest, TypeScript)
works inside the runner image. This is a smoke test of the runner environment,
not a feature.

## Environment

| Tool | Version |
|---|---|
| node | v22.23.2 |
| npm | 10.9.8 |
| dotnet | 10.0.400 (present in image, not used by red-nose) |
| gradle | not installed in the runner image |

Runner architecture: `aarch64` (linux/arm64).

## Android build: intentionally skipped

The Android Gradle build (`./gradlew assemble*`) is **not** run here. The runner
image is linux/arm64, and Google ships the Android SDK build-tools and the NDK
for x86_64 only; there is no arm64 build of the Android toolchain to fetch.
The APK is produced by the GitHub Actions workflow instead
(`.github/workflows/android.yml`, described in `docs/red-nose.md` section 16),
which runs on x86_64 hosts where the Android SDK/NDK are available. Skipping the
Gradle build here is deliberate, not a missing tool the smoke test worked around.

## Commands

### 1. `node -v`

- Exit code: `0`
- Output:

```
v22.23.2
```

### 2. `npm ci`

- Exit code: `0`
- Tail of output:

```
added 891 packages, and audited 892 packages in 10s

184 packages are looking for funding
  run `npm fund` for details

15 vulnerabilities (6 moderate, 9 high)

To address all issues possible (including breaking changes), run:
  npm audit fix --force

Some issues need review, and may require choosing
a different dependency.

Run `npm audit` for details.
```

### 3. `npm test`

- Exit code: `0`
- Full output:

```
> com.wmsfo.rednose@0.0.1 test
> jest

PASS __tests__/App.test.tsx
  ✓ renders correctly (80 ms)

Test Suites: 1 passed, 1 total
Tests:       1 passed, 1 total
Snapshots:   0 total
Time:        1.01 s
Ran all test suites.
```

### 4. `npx tsc --noEmit`

- Exit code: `0`
- Output: (empty; no type errors)
