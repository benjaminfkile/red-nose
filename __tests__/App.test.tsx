/**
 * @format
 */

import * as fs from 'fs';
import * as path from 'path';
import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import { NativeModules } from 'react-native';
import App from '../App';
import { EnrollScreen } from '../src/screens/EnrollScreen';

// The real SafeAreaProvider renders nothing until native insets arrive; the
// library's own mock renders its children at once.
jest.mock('react-native-safe-area-context', () =>
  require('react-native-safe-area-context/jest/mock').default,
);

beforeAll(() => {
  // R2's App.tsx reads native state on mount; provide a stub that resolves to a
  // null enrollment so the Enroll shell renders in tests.
  const stub = {
    getState: jest.fn(async () => JSON.stringify({
      serviceRunning: false,
      serviceStartedAt: null,
      enrollment: null,
      socketState: 'disconnected',
      revoked: false,
      liveEventId: null,
      isActive: null,
      lastHeartbeatAcceptedAt: null,
      lastHeartbeatError: null,
      clockSkewMs: null,
      latestFix: null,
      lastDeliveredSeqLocal: null,
      lastReceiptLatencyMs: null,
      lastSendError: null,
      telemetry: { sentAt: '2026-12-22T01:31:07.412Z' },
      replay: null,
      replayAllowed: false,
      checklist: {
        fineLocation: true, backgroundLocation: true, preciseLocation: true,
        notifications: true, batteryOptimizationExempt: true, locationServicesOn: true,
        playServices: true, phoneState: true, airplaneModeOff: true, mobileDataOn: true,
        batterySaverOff: true, serviceRunning: false,
      },
      appVersion: '0.1.0',
    })),
    saveEnrollment: jest.fn(async () => undefined),
    clearEnrollment: jest.fn(async () => undefined),
    setGpsOnlyFallback: jest.fn(async () => undefined),
    startReplay: jest.fn(async () => undefined),
    stopReplay: jest.fn(async () => undefined),
    uploadLog: jest.fn(async () => undefined),
    getRecentLog: jest.fn(async () => '[]'),
    pickRouteFile: jest.fn(async () => null),
    getInitialEnrollUrl: jest.fn(async () => null),
    scanQrCode: jest.fn(async () => null),
    // getConstants entries (red-nose.md 4.2).
    flavour: 'dev',
    applicationId: 'com.wmsfo.rednose.dev',
    prodApiBaseUrl: 'https://api.example.org',
    defaultApiBaseUrl: 'https://api-dev.example.org',
  };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (NativeModules as any).RedNose = stub;
});

test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});

// red-nose.md 15: every screen renders inside the environment frame.
test('the dev flavour shows the Enroll screen inside the DEV frame', async () => {
  let tree!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    tree = ReactTestRenderer.create(<App />);
  });
  expect(tree.root.findAllByType(EnrollScreen)).toHaveLength(1);
  const frames = tree.root.findAll(n => n.props.testID === 'env-frame' && typeof n.type !== 'string');
  expect(frames.length).toBeGreaterThan(0);
  const frame = frames[0];
  expect(frame.findAllByType(EnrollScreen)).toHaveLength(1);
  expect(frame.findByProps({ testID: 'env-tag' }).props.children).toBe('DEV');
});

// red-nose.md 5.3: Red-Nose is a normal app; android:persistent is honoured
// for system apps only, so the manifest does not carry it.
test('the manifest does not ask to be persistent', () => {
  const manifest = fs.readFileSync(
    path.resolve(__dirname, '..', 'android', 'app', 'src', 'main', 'AndroidManifest.xml'),
    'utf8',
  );
  expect(manifest).toContain('<application');
  expect(manifest).not.toContain('android:persistent');
});
