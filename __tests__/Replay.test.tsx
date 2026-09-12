// Acceptance test (R3, ac 765): when `replayAllowed === false` (the enrolled
// apiBaseUrl equals REDNOSE_PROD_API_BASE_URL), the ReplayScreen refuses to
// start a replay: it shows the disabled banner and never calls startReplay
// through the native module, even if the user has typed a URL or picked a
// file.  Also verifies the URL / rate / file paths and the 1..10 rate gate.

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type { ReactTestInstance } from 'react-test-renderer';
import { NativeModules, Text, TouchableOpacity } from 'react-native';
import { ReplayScreen, clampRate, REPLAY_MIN_RATE, REPLAY_MAX_RATE } from '../src/screens/debug/ReplayScreen';
import { makeServiceState } from '../src/__testutils__/fixtures';

const stub = {
  getState: jest.fn(async () => JSON.stringify(makeServiceState())),
  saveEnrollment: jest.fn(async () => undefined),
  clearEnrollment: jest.fn(async () => undefined),
  setGpsOnlyFallback: jest.fn(async () => undefined),
  startReplay: jest.fn(async () => undefined),
  stopReplay: jest.fn(async () => undefined),
  uploadLog: jest.fn(async () => undefined),
  getRecentLog: jest.fn(async () => '[]'),
  pickRouteFile: jest.fn(async () => null as string | null),
  getInitialEnrollUrl: jest.fn(async () => null),
  scanQrCode: jest.fn(async () => null as string | null),
};

beforeAll(() => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (NativeModules as any).RedNose = stub;
});

beforeEach(() => {
  stub.startReplay.mockClear();
  stub.stopReplay.mockClear();
  stub.pickRouteFile.mockClear();
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

describe('replay gate (R3 ac 765)', () => {
  test('replayAllowed=false: refuses; startReplay is never called', async () => {
    const state = makeServiceState({ replayAllowed: false });
    let tree!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      tree = ReactTestRenderer.create(<ReplayScreen state={state} />);
    });
    // The disabled banner must be visible.
    expect(collectText(tree.root)).toContain('replay is disabled');
    // No start button is rendered at all; the block short-circuits the form.
    const buttons = tree.root.findAllByType(TouchableOpacity);
    expect(buttons.length).toBe(0);
    // And no attempt was made to call the native module.
    expect(stub.startReplay).not.toHaveBeenCalled();
  });

  test('replayAllowed=true: pick-file path calls startReplay with the picked URI', async () => {
    stub.pickRouteFile.mockResolvedValueOnce('content://com.example/routes/2025.json');
    const state = makeServiceState({ replayAllowed: true });
    let tree!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      tree = ReactTestRenderer.create(<ReplayScreen state={state} />);
    });
    const pickButton = tree.root
      .findAllByType(TouchableOpacity)
      .find(b => collectText(b).includes('pick file'));
    expect(pickButton).toBeDefined();
    await ReactTestRenderer.act(async () => { tap(pickButton!); });
    expect(stub.pickRouteFile).toHaveBeenCalled();
    expect(stub.startReplay).toHaveBeenCalledWith(
      'content://com.example/routes/2025.json',
      1,
    );
  });

  test('replayAllowed=true: URL path calls startReplay with the typed URL', async () => {
    const state = makeServiceState({ replayAllowed: true });
    let tree!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      tree = ReactTestRenderer.create(<ReplayScreen state={state} />);
    });
    // TextInput renders as a wrapper + inner instance; filter to the top-level
    // one that owns the callback we passed in (identified by keyboardType).
    const urlInput = tree.root.find(n =>
      typeof n.props?.onChangeText === 'function'
      && n.props?.keyboardType === 'url',
    );
    const rateInput = tree.root.find(n =>
      typeof n.props?.onChangeText === 'function'
      && n.props?.keyboardType === 'number-pad',
    );
    await ReactTestRenderer.act(async () => {
      urlInput.props.onChangeText('https://example.org/routes/2025.json');
      rateInput.props.onChangeText('5');
    });
    const startButton = tree.root
      .findAllByType(TouchableOpacity)
      .find(b => collectText(b).includes('start from URL'));
    expect(startButton).toBeDefined();
    await ReactTestRenderer.act(async () => { tap(startButton!); });
    expect(stub.startReplay).toHaveBeenCalledWith(
      'https://example.org/routes/2025.json',
      5,
    );
  });
});

describe('replay rate bounds', () => {
  test('accepts integers 1..10', () => {
    for (let i = REPLAY_MIN_RATE; i <= REPLAY_MAX_RATE; i++) {
      expect(clampRate(String(i))).toBe(i);
    }
  });
  test('rejects out-of-range and non-integer values', () => {
    for (const bad of ['0', '11', '-1', '1.5', '', 'x', ' ']) {
      expect(clampRate(bad)).toBeNull();
    }
  });
});
