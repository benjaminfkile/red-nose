// Parse the provisioning shell scripts and check the Magisk module tree
// (red-nose.md 5.3, 14.2, 14.3).  Nothing here runs the scripts; we only
// confirm they are syntactically well-formed and that every knob 14.2 lists
// is spelt in provision.sh.

import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

const ROOT = path.resolve(__dirname, '..');
const MODULE_DIR = path.join(ROOT, 'provisioning', 'magisk-module');

function parses(scriptPath: string): void {
  // `bash -n` and `sh -n` do syntax-only parsing (no execution).
  execFileSync('bash', ['-n', scriptPath], { stdio: 'pipe' });
}

describe('provisioning/magisk-module tree', () => {
  test('module.prop names the module and carries a version', () => {
    const body = fs.readFileSync(path.join(MODULE_DIR, 'module.prop'), 'utf8');
    expect(body).toMatch(/^id=rednose\b/m);
    expect(body).toMatch(/^name=/m);
    expect(body).toMatch(/^version=\d+\.\d+\.\d+/m);
    expect(body).toMatch(/^versionCode=\d+/m);
  });

  test('customize.sh parses and refers to the APK drop path', () => {
    const script = path.join(MODULE_DIR, 'customize.sh');
    parses(script);
    const body = fs.readFileSync(script, 'utf8');
    expect(body).toMatch(/system\/app\/RedNose\/RedNose\.apk/);
  });

  test('service.sh parses and implements the 15 s watchdog without touching the launcher', () => {
    const script = path.join(MODULE_DIR, 'service.sh');
    parses(script);
    const body = fs.readFileSync(script, 'utf8');
    // Section 5.3: pidof + am start-foreground-service on a 15 s loop.  The
    // launcher re-assert is gone (red-nose.md 5.5, 2026-09-16: no kiosk).
    expect(body).toMatch(/pidof/);
    expect(body).toMatch(/am start-foreground-service/);
    expect(body).toMatch(/sleep\s+["']?15/);
    expect(body).toMatch(/com\.wmsfo\.rednose:beacon/);
    expect(body).not.toMatch(/set-home-activity/);
  });

  test('system/app/RedNose/ tree exists as the APK drop point', () => {
    const dir = path.join(MODULE_DIR, 'system', 'app', 'RedNose');
    expect(fs.statSync(dir).isDirectory()).toBe(true);
  });
});

describe('provisioning/provision.sh', () => {
  const script = path.join(ROOT, 'provisioning', 'provision.sh');
  const body = fs.readFileSync(script, 'utf8');

  test('parses as bash', () => {
    parses(script);
  });

  test('routes every device command through `su`', () => {
    // Every device-touching call goes through the adb_su / adb_su_out helpers,
    // which shell out to `adb ... shell su -c ...`.
    expect(body).toMatch(/adb .*shell su -c/);
    // And there are no bare `adb shell` commands that skip su.
    const bareAdbShell = body.match(/^\s*adb\b[^\n]*\bshell\b(?!\s+su\b)/gm);
    expect(bareAdbShell).toBeNull();
  });

  test('covers every row of section 14.2', () => {
    // Runtime permissions (14.2 row 1).
    for (const perm of [
      'ACCESS_FINE_LOCATION',
      'ACCESS_COARSE_LOCATION',
      'ACCESS_BACKGROUND_LOCATION',
      'POST_NOTIFICATIONS',
      'READ_PHONE_STATE',
    ]) {
      expect(body).toContain(perm);
    }
    // CAMERA is not in the list (red-nose.md 9.1, 14.2): the app does not
    // declare it and pm grant would fail.
    expect(body).not.toContain('CAMERA');
    expect(body).toMatch(/pm grant/);
    // Location appops (14.2 row 2).
    expect(body).toMatch(/appops set/);
    expect(body).toContain('FINE_LOCATION');
    expect(body).toContain('COARSE_LOCATION');
    // Battery / Doze (14.2 row 3).
    expect(body).toMatch(/deviceidle whitelist \+/);
    // Location mode (14.2 row 4).
    expect(body).toMatch(/settings put secure location_mode 3/);
    // Stay awake while charging (14.2 row 5).
    expect(body).toMatch(/settings put global stay_on_while_plugged_in 7/);
    // Permission auto-revoke off (14.2, added 2026-09-16 with no kiosk).
    expect(body).toContain('AUTO_REVOKE_PERMISSIONS_IF_UNUSED');
    // Safe boot disallowed (14.2 row 9).
    expect(body).toMatch(/settings put global safe_boot_disallowed 1/);
    // No launcher / keyguard / immersive rows (red-nose.md 5.5, 14.2).
    expect(body).not.toMatch(/set-home-activity/);
    expect(body).not.toMatch(/locksettings/);
    expect(body).not.toMatch(/immersive_mode_confirmations/);
  });

  test('reads each value back and prints a final summary', () => {
    // Each row uses expect / expect_contains / expect_nonempty; the summary
    // reports passed / failed counts.
    expect(body).toMatch(/passed:/);
    expect(body).toMatch(/failed:/);
    expect(body).toMatch(/settings get secure location_mode/);
    expect(body).toMatch(/dumpsys package/);
    expect(body).toMatch(/appops get .*AUTO_REVOKE_PERMISSIONS_IF_UNUSED/);
  });
});

describe('AndroidManifest.xml (red-nose.md 5.5: no kiosk)', () => {
  const manifest = fs.readFileSync(
    path.join(ROOT, 'android', 'app', 'src', 'main', 'AndroidManifest.xml'),
    'utf8',
  );

  test('MAIN intent-filter carries LAUNCHER, no HOME category', () => {
    expect(manifest).toContain('android.intent.category.LAUNCHER');
    expect(manifest).not.toContain('android.intent.category.HOME');
  });
});
