// Typed binding over the native RedNose module (red-nose.md 4.2).
// The module lives in the UI process; every call runs over AIDL to :beacon.
// Events on DeviceEventEmitter: `rednose.state`, `rednose.log`, `rednose.logUpload`.

import { NativeModules, DeviceEventEmitter } from 'react-native';
import type { EmitterSubscription } from 'react-native';

export type Enrollment = {
  apiBaseUrl: string;
  hubUrl: string;
  ingestChannel: string;
  beaconId: number;
  name: string;
  key: string;
};

export type LatestFix = {
  lat: number;
  lng: number;
  recordedAt: string;
  speedMps: number | null;
  altitudeM: number | null;
  headingDeg: number | null;
  accuracyM: number | null;
  seqLocal: number;
};

export type EnrollmentPublic = {
  beaconId: number;
  name: string;
  keyPrefix: string;
  apiBaseUrl: string;
  hubUrl: string;
  ingestChannel: string;
  gpsOnlyFallback: boolean;
};

export type Heartbeat = {
  sentAt: string;
  health: {
    batteryPercent: number | null;
    lastFixAgeS: number | null;
    socketState: string | null;
  } | null;
  debug: {
    power: {
      charging: boolean | null;
      batteryTempC: number | null;
      thermalStatus: string | null;
    } | null;
    radio: {
      networkType: string | null;
      signalDbm: number | null;
      signalLevel: number | null;
      airplaneMode: boolean | null;
      connected: boolean | null;
    } | null;
    gps: {
      provider: string | null;
      satellitesUsed: number | null;
      satellitesInView: number | null;
      lastFixAccuracyM: number | null;
      fixesLastMinute: number | null;
      permission: {
        foreground: boolean | null;
        background: boolean | null;
        precise: boolean | null;
      } | null;
    } | null;
    transport: {
      reconnectCount: number | null;
      rejoinCount: number | null;
      httpFallbackSeconds: number | null;
      lastReceiptLatencyMs: number | null;
      sendsFailedSinceBoot: number | null;
    } | null;
    process: {
      deviceUptimeS: number | null;
      serviceUptimeS: number | null;
      serviceRestartCount: number | null;
      memoryPressure: string | null;
      batteryOptimizationExempt: boolean | null;
      notificationPermission: boolean | null;
      systemApp: boolean | null;
      rootAvailable: boolean | null;
    } | null;
    identity: {
      deviceModel: string | null;
      androidVersion: string | null;
      appVersion: string | null;
      clockSkewMs: number | null;
    } | null;
    guard: {
      airplaneModeRestores: number;
      locationRestores: number;
      mobileDataRestores: number;
      batterySaverRestores: number;
      permissionRestores: number;
      dozeAllowlistRestores: number;
      lastRestoredItem: string | null;
      lastRestoredAt: string | null;
      lastError: string | null;
    } | null;
  } | null;
};

export type Checklist = {
  fineLocation: boolean;
  backgroundLocation: boolean;
  preciseLocation: boolean;
  notifications: boolean;
  batteryOptimizationExempt: boolean;
  locationServicesOn: boolean;
  playServices: boolean;
  phoneState: boolean;
  systemApp: boolean;
  rootAvailable: boolean;
  airplaneModeOff: boolean;
  mobileDataOn: boolean;
  batterySaverOff: boolean;
  serviceRunning: boolean;
};

export type ServiceState = {
  serviceRunning: boolean;
  serviceStartedAt: string | null;
  enrollment: EnrollmentPublic | null;
  socketState: 'connected' | 'connecting' | 'reconnecting' | 'disconnected';
  revoked: boolean;
  liveEventId: number | null;
  isActive: boolean | null;
  lastHeartbeatAcceptedAt: string | null;
  lastHeartbeatError: string | null;
  clockSkewMs: number | null;
  latestFix: LatestFix | null;
  lastDeliveredSeqLocal: number | null;
  lastReceiptLatencyMs: number | null;
  lastSendError: string | null;
  telemetry: Heartbeat;
  replay: {
    running: boolean;
    source: string;
    index: number;
    total: number;
    ratePerSecond: number;
  } | null;
  replayAllowed: boolean;
  checklist: Checklist;
  appVersion: string;
};

export type LogUploadResult =
  | { ok: true; id: string; sizeBytes: number; receivedAt: string }
  | { ok: false; code: string; message: string };

type RawNativeRedNose = {
  getState(): Promise<string>;
  saveEnrollment(enrollmentJson: string): Promise<void>;
  clearEnrollment(): Promise<void>;
  setGpsOnlyFallback(enabled: boolean): Promise<void>;
  startReplay(source: string, ratePerSecond: number): Promise<void>;
  stopReplay(): Promise<void>;
  uploadLog(): Promise<void>;
  getRecentLog(maxLines: number): Promise<string>;
  pickRouteFile(): Promise<string | null>;
  getInitialEnrollUrl(): Promise<string | null>;
  scanQrCode(): Promise<string | null>;
};

// Resolve NativeModules.RedNose on each call so tests can install a stub in
// beforeAll (module-load-time capture would miss it).
function raw(): RawNativeRedNose {
  return NativeModules.RedNose as RawNativeRedNose;
}

export const NativeRedNose = {
  async getState(): Promise<ServiceState> {
    const s = await raw().getState();
    return JSON.parse(s) as ServiceState;
  },
  saveEnrollment(e: Enrollment): Promise<void> {
    return raw().saveEnrollment(JSON.stringify(e));
  },
  clearEnrollment(): Promise<void> {
    return raw().clearEnrollment();
  },
  setGpsOnlyFallback(enabled: boolean): Promise<void> {
    return raw().setGpsOnlyFallback(enabled);
  },
  startReplay(source: string, ratePerSecond: number): Promise<void> {
    return raw().startReplay(source, ratePerSecond);
  },
  stopReplay(): Promise<void> {
    return raw().stopReplay();
  },
  uploadLog(): Promise<void> {
    return raw().uploadLog();
  },
  async getRecentLog(maxLines: number): Promise<string[]> {
    const s = await raw().getRecentLog(maxLines);
    return JSON.parse(s) as string[];
  },
  pickRouteFile(): Promise<string | null> {
    return raw().pickRouteFile();
  },
  getInitialEnrollUrl(): Promise<string | null> {
    return raw().getInitialEnrollUrl();
  },
  scanQrCode(): Promise<string | null> {
    return raw().scanQrCode();
  },
};

export type StateListener = (state: ServiceState) => void;
export type LogListener = (line: string) => void;
export type LogUploadListener = (result: LogUploadResult) => void;

export function onServiceState(cb: StateListener): EmitterSubscription {
  return DeviceEventEmitter.addListener('rednose.state', (payload: string) => {
    try {
      cb(JSON.parse(payload) as ServiceState);
    } catch {
      // ignore malformed emissions; the next tick brings a fresh one
    }
  });
}

export function onLog(cb: LogListener): EmitterSubscription {
  return DeviceEventEmitter.addListener('rednose.log', cb);
}

export function onLogUpload(cb: LogUploadListener): EmitterSubscription {
  return DeviceEventEmitter.addListener('rednose.logUpload', (payload: string) => {
    try {
      cb(JSON.parse(payload) as LogUploadResult);
    } catch {
      // ignore
    }
  });
}
