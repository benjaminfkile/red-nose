// TelemetryScreen renders the guard group of the heartbeat body
// (red-nose.md 5.5, 8; R12).

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type { ReactTestInstance } from 'react-test-renderer';
import { Text } from 'react-native';
import { TelemetryScreen } from '../src/screens/debug/TelemetryScreen';
import { makeServiceState } from '../src/__testutils__/fixtures';

function textAt(root: ReactTestInstance): string {
  const buf: string[] = [];
  const walk = (n: ReactTestInstance | string) => {
    if (typeof n === 'string') { buf.push(n); return; }
    if (n.type === Text && typeof n.props?.children === 'string') {
      buf.push(n.props.children);
    }
    for (const c of n.children ?? []) walk(c as ReactTestInstance | string);
  };
  walk(root);
  return buf.join(' | ');
}

test('renders the guard group', () => {
  const state = makeServiceState({
    telemetry: {
      sentAt: '2026-12-22T01:31:07.412Z',
      health: null,
      debug: {
        power: null, radio: null, gps: null, transport: null,
        process: null, identity: null,
        guard: {
          airplaneModeRestores: 2,
          locationRestores: 0,
          mobileDataRestores: 1,
          batterySaverRestores: 0,
          permissionRestores: 3,
          dozeAllowlistRestores: 0,
          lastRestoredItem: 'permissions',
          lastRestoredAt: '2026-12-22T01:31:07.412Z',
          lastError: null,
        },
      },
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any,
  });
  let tree!: ReactTestRenderer.ReactTestRenderer;
  ReactTestRenderer.act(() => {
    tree = ReactTestRenderer.create(<TelemetryScreen state={state} />);
  });
  const text = textAt(tree.root);
  expect(text).toContain('guard');
  expect(text).toContain('airplaneModeRestores');
  expect(text).toContain('permissionRestores');
  expect(text).toContain('lastRestoredItem');
});
