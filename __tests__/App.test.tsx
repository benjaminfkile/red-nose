/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import { NativeModules } from 'react-native';
import App from '../App';

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
        playServices: true, camera: true, phoneState: true, systemApp: true,
        rootAvailable: true, launcher: true, serviceRunning: false,
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
  };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (NativeModules as any).RedNose = stub;
});

test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});
