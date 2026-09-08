// Shared ServiceState fixture builder for React component tests.

import type { ServiceState } from '../native/NativeRedNose';

export function makeServiceState(o: Partial<ServiceState> = {}): ServiceState {
  const base: ServiceState = {
    serviceRunning: true,
    serviceStartedAt: '2026-12-22T01:31:07.412Z',
    enrollment: {
      beaconId: 1,
      name: 'beacon',
      role: 'beacon',
      keyPrefix: 'wbk_prefix12',
      apiBaseUrl: 'https://api.example.org',
      hubUrl: 'https://hub.example.org',
      ingestChannel: 'ingest',
      gpsOnlyFallback: false,
    },
    socketState: 'connected',
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
    telemetry: { sentAt: '2026-12-22T01:31:07.412Z' } as ServiceState['telemetry'],
    replay: null,
    replayAllowed: true,
    checklist: {
      fineLocation: true, backgroundLocation: true, preciseLocation: true,
      notifications: true, batteryOptimizationExempt: true, locationServicesOn: true,
      playServices: true, camera: true, phoneState: true, systemApp: true,
      rootAvailable: true, launcher: true, serviceRunning: true,
    },
    appVersion: '0.1.0',
  };
  return { ...base, ...o };
}
