// The provisioning design guard (red-nose.md 13, 14, 16).  The phone has no
// root and Red-Nose is a normal app installed over adb; the provisioning
// script lives in the operator kit, outside this repository, and 14.2 is the
// design of its rows.  This file checks that provisioning/ holds the runbook
// alone, that DEVICE.md names every 14.2 row the app depends on and none of
// the old root commands, that the manifest asks for nothing a normal app
// cannot have, and that CI only builds and ships the APK.
//
// The forbidden strings are built by concatenation so this file never
// contains them literally (as noDashes does with its characters).

import * as fs from 'fs';
import * as path from 'path';

const ROOT = path.resolve(__dirname, '..');
const PROVISIONING_DIR = path.join(ROOT, 'provisioning');

describe('provisioning/ (red-nose.md 14)', () => {
  test('holds DEVICE.md only: no script, no Magisk module', () => {
    const entries = fs.readdirSync(PROVISIONING_DIR);
    expect(entries).toEqual(['DEVICE.md']);
  });
});

describe('provisioning/DEVICE.md (red-nose.md 14.2)', () => {
  const body = fs.readFileSync(path.join(PROVISIONING_DIR, 'DEVICE.md'), 'utf8');

  test('names every 14.2 row the app depends on', () => {
    for (const row of [
      'pm grant',
      'ACCESS_FINE_LOCATION',
      'ACCESS_COARSE_LOCATION',
      'ACCESS_BACKGROUND_LOCATION',
      'POST_NOTIFICATIONS',
      'READ_PHONE_STATE',
      'appops set',
      'deviceidle whitelist',
      'location_mode 3',
      'stay_on_while_plugged_in 7',
      'AUTO_REVOKE_PERMISSIONS_IF_UNUSED',
      'safe_boot_disallowed',
      'locksettings set-disabled true',
      'svc data enable',
      'mobile_data 1',
      'adb install -r -g',
    ]) {
      expect(body).toContain(row);
    }
  });

  test('carries none of the old root commands', () => {
    // Section 1 tells the history and may say the word Magisk; these are the
    // commands that must not come back.
    for (const forbidden of [
      'CAM' + 'ERA',
      's' + 'u -c',
      'set-home' + '-activity',
      'mag' + 'isk --install' + '-module',
    ]) {
      expect(body).not.toContain(forbidden);
    }
  });

  test('names no private network address', () => {
    const PRIVATE_ADDRESS =
      /\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b/;
    expect(body).not.toMatch(PRIVATE_ADDRESS);
  });
});

describe('AndroidManifest.xml (red-nose.md 5.5, 14: a normal app, no kiosk)', () => {
  const manifest = fs.readFileSync(
    path.join(ROOT, 'android', 'app', 'src', 'main', 'AndroidManifest.xml'),
    'utf8',
  );

  test('MAIN intent-filter carries LAUNCHER, no HOME category', () => {
    const filters = manifest.match(/<intent-filter>[\s\S]*?<\/intent-filter>/g) ?? [];
    const main = filters.filter(f => f.includes('android.intent.action.MAIN'));
    expect(main).toHaveLength(1);
    expect(main[0]).toContain('android.intent.category.LAUNCHER');
    expect(manifest).not.toContain('android.intent.category.HOME');
  });

  test('the boot receiver also answers MY_PACKAGE_REPLACED (5.1, 14.3)', () => {
    const receivers = manifest.match(/<receiver[\s>][\s\S]*?<\/receiver>/g) ?? [];
    const boot = receivers.filter(r => r.includes('android.intent.action.BOOT_COMPLETED'));
    expect(boot).toHaveLength(1);
    expect(boot[0]).toContain('.service.BootReceiver');
    expect(boot[0]).toContain('android.intent.action.LOCKED_BOOT_COMPLETED');
    expect(boot[0]).toContain('android.intent.action.MY_PACKAGE_REPLACED');
    expect(manifest).toContain('android.intent.action.MY_PACKAGE_REPLACED');
  });

  test('is not persistent', () => {
    expect(manifest).not.toContain('android:persistent');
  });

  test('requests no signature or privileged permission', () => {
    for (const perm of [
      'INSTALL_PACKAGES',
      'WRITE_SECURE_SETTINGS',
      'MODIFY_PHONE_STATE',
      'GRANT_RUNTIME_PERMISSIONS',
    ]) {
      expect(manifest).not.toContain(`android.permission.${perm}`);
    }
  });
});

describe('.github/workflows/android.yml (red-nose.md 16: CI ships the APK only)', () => {
  const workflow = fs.readFileSync(
    path.join(ROOT, '.github', 'workflows', 'android.yml'),
    'utf8',
  );
  // A comment may say how the artifact goes on the phone; no step may run it.
  const steps = workflow
    .split('\n')
    .filter(line => !line.trimStart().startsWith('#'))
    .join('\n');

  test('installs nothing on a device', () => {
    expect(steps).not.toContain('adb install');
  });

  test('builds no Magisk module', () => {
    expect(workflow.toLowerCase()).not.toContain('magisk');
  });

  test('uploads exactly one artifact', () => {
    const uploads = workflow.match(/uses: actions\/upload-artifact/g) ?? [];
    expect(uploads).toHaveLength(1);
  });
});
