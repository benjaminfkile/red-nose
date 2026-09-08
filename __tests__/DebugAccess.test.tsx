// Acceptance test (R3, ac 764): the debug entry point must be absent for
// the `beacon` role and present for the `admin` role.  The StatusScreen only
// renders the debug button when `enrollment.role === 'admin'` and its parent
// hands it an `onDebug` callback.

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type { ReactTestInstance } from 'react-test-renderer';
import { Text } from 'react-native';
import { StatusScreen } from '../src/screens/StatusScreen';
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

describe('debug access gate (R3 ac 764)', () => {
  test('role=beacon: no debug button', () => {
    const state = makeServiceState({
      enrollment: { ...makeServiceState().enrollment!, role: 'beacon' },
    });
    let tree!: ReactTestRenderer.ReactTestRenderer;
    ReactTestRenderer.act(() => {
      tree = ReactTestRenderer.create(
        <StatusScreen state={state} onDebug={() => { /* not passed in real app */ }} />,
      );
    });
    expect(textAt(tree.root)).not.toContain('debug');
  });

  test('role=beacon: no debug button even without onDebug callback', () => {
    const state = makeServiceState({
      enrollment: { ...makeServiceState().enrollment!, role: 'beacon' },
    });
    let tree!: ReactTestRenderer.ReactTestRenderer;
    ReactTestRenderer.act(() => {
      tree = ReactTestRenderer.create(<StatusScreen state={state} />);
    });
    expect(textAt(tree.root)).not.toContain('debug');
  });

  test('role=admin: debug button appears when onDebug is provided', () => {
    const state = makeServiceState({
      enrollment: { ...makeServiceState().enrollment!, role: 'admin' },
    });
    let tree!: ReactTestRenderer.ReactTestRenderer;
    ReactTestRenderer.act(() => {
      tree = ReactTestRenderer.create(
        <StatusScreen state={state} onDebug={() => { /* opens debug */ }} />,
      );
    });
    expect(textAt(tree.root)).toContain('debug');
  });
});
