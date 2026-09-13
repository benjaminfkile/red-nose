// ScanScreen tests (red-nose.md 9.1): the Play services code scanner path via
// NativeRedNose.scanQrCode.  Verifies enrollment exchange on success, idle on
// cancel (null), the fallback message on scanner_unavailable, and that a
// second tap while a scan is open does not start another.

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type { ReactTestInstance } from 'react-test-renderer';
import { NativeModules, Text, TouchableOpacity } from 'react-native';
import { ScanScreen } from '../src/screens/ScanScreen';

const TOKEN = 'wet_' + 'A'.repeat(43);
const ENROLL_URL = `rednose://enroll?api=${encodeURIComponent('https://api.example.org')}&token=${TOKEN}`;

const stub = {
  getState: jest.fn(async () => '{}'),
  saveEnrollment: jest.fn(async (_json: string) => undefined),
  clearEnrollment: jest.fn(async () => undefined),
  setGpsOnlyFallback: jest.fn(async () => undefined),
  startReplay: jest.fn(async () => undefined),
  stopReplay: jest.fn(async () => undefined),
  uploadLog: jest.fn(async () => undefined),
  getRecentLog: jest.fn(async () => '[]'),
  pickRouteFile: jest.fn(async () => null as string | null),
  getInitialEnrollUrl: jest.fn(async () => null as string | null),
  scanQrCode: jest.fn(async () => null as string | null),
};

beforeAll(() => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (NativeModules as any).RedNose = stub;
});

beforeEach(() => {
  stub.getInitialEnrollUrl.mockReset().mockResolvedValue(null);
  stub.scanQrCode.mockReset().mockResolvedValue(null);
  stub.saveEnrollment.mockClear();
});

function collectText(root: ReactTestInstance): string {
  const buf: string[] = [];
  const walk = (n: ReactTestInstance | string) => {
    if (typeof n === 'string') { buf.push(n); return; }
    if (n.type === Text && typeof n.props?.children === 'string') buf.push(n.props.children);
    for (const c of n.children ?? []) walk(c as ReactTestInstance | string);
  };
  walk(root);
  return buf.join(' | ');
}

function tap(instance: ReactTestInstance) {
  const onPress = instance.props?.onPress;
  if (typeof onPress === 'function') onPress();
}

describe('ScanScreen (red-nose.md 9.1)', () => {
  test('a resolved enrollment URL runs the exchange and saveEnrollment', async () => {
    stub.scanQrCode.mockResolvedValueOnce(ENROLL_URL);
    const fetchImpl = jest.fn(async () => new Response(
      JSON.stringify({
        apiBaseUrl: 'https://api.example.org',
        hubUrl: 'https://hub.example.org',
        ingestChannel: 'ingest',
        beaconId: 42,
        name: 'beacon',
        key: 'wbk_the_key_' + 'x'.repeat(24),
      }),
      { status: 200, headers: { 'content-type': 'application/json' } },
    ));
    // Route the module's fetch through our stub.
    const origFetch = globalThis.fetch;
    globalThis.fetch = fetchImpl as unknown as typeof fetch;
    const onEnrolled = jest.fn();
    try {
      await ReactTestRenderer.act(async () => {
        ReactTestRenderer.create(
          <ScanScreen onEnrolled={onEnrolled} onManual={() => {}} />,
        );
      });
      // let the microtasks (scanQrCode -> exchange -> saveEnrollment) settle
      await ReactTestRenderer.act(async () => { await Promise.resolve(); });
      await ReactTestRenderer.act(async () => { await Promise.resolve(); });
    } finally {
      globalThis.fetch = origFetch;
    }
    expect(fetchImpl).toHaveBeenCalledWith(
      'https://api.example.org/beacons/enroll',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(stub.saveEnrollment).toHaveBeenCalledTimes(1);
    const savedArg = JSON.parse(stub.saveEnrollment.mock.calls[0][0] as string);
    expect(savedArg.beaconId).toBe(42);
    expect(onEnrolled).toHaveBeenCalled();
  });

  test('a cancelled scan (null) leaves the screen idle with no message', async () => {
    stub.scanQrCode.mockResolvedValueOnce(null);
    let tree!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      tree = ReactTestRenderer.create(
        <ScanScreen onEnrolled={() => {}} onManual={() => {}} />,
      );
    });
    await ReactTestRenderer.act(async () => { await Promise.resolve(); });
    const text = collectText(tree.root);
    expect(text).not.toContain('scanner unavailable');
    expect(text).not.toContain('not an enrollment code');
    expect(stub.saveEnrollment).not.toHaveBeenCalled();
  });

  test('a scanner_unavailable rejection shows the fallback message', async () => {
    stub.scanQrCode.mockRejectedValueOnce({ code: 'scanner_unavailable', message: 'module missing' });
    let tree!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      tree = ReactTestRenderer.create(
        <ScanScreen onEnrolled={() => {}} onManual={() => {}} />,
      );
    });
    await ReactTestRenderer.act(async () => { await Promise.resolve(); });
    expect(collectText(tree.root)).toContain('scanner unavailable (Play services): enter the code manually');
    // The manual button remains visible.
    const manualButton = tree.root
      .findAllByType(TouchableOpacity)
      .find(b => collectText(b).includes('enter manually'));
    expect(manualButton).toBeDefined();
  });

  test('a second tap while a scan is open does not start another', async () => {
    // The first scan resolves only when we tell it to; the second tap must not
    // start another native call.
    let releaseFirst: ((v: string | null) => void) | null = null;
    stub.scanQrCode
      .mockImplementationOnce(() => new Promise<string | null>(res => { releaseFirst = res; }))
      .mockImplementation(async () => null);
    let tree!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      tree = ReactTestRenderer.create(
        <ScanScreen onEnrolled={() => {}} onManual={() => {}} />,
      );
    });
    // The auto-open on mount is the first call.
    await ReactTestRenderer.act(async () => { await Promise.resolve(); });
    expect(stub.scanQrCode).toHaveBeenCalledTimes(1);

    // Tap "scan a code" while the first scan is still open.
    const scanButton = tree.root
      .findAllByType(TouchableOpacity)
      .find(b => collectText(b).includes('scan a code'));
    expect(scanButton).toBeDefined();
    await ReactTestRenderer.act(async () => { tap(scanButton!); });
    // No second call: the first is still pending.
    expect(stub.scanQrCode).toHaveBeenCalledTimes(1);

    // Release the first scan; that lets the ref clear.
    await ReactTestRenderer.act(async () => {
      releaseFirst!(null);
      await Promise.resolve();
    });

    // Now a fresh tap opens a new scan.
    await ReactTestRenderer.act(async () => { tap(scanButton!); });
    expect(stub.scanQrCode).toHaveBeenCalledTimes(2);
  });
});
