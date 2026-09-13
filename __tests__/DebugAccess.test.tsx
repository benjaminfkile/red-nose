// Debug is always available once enrolled (red-nose.md 1, 10; R9).  The
// StatusScreen renders the debug button whenever its parent hands it an
// `onDebug` callback; nothing gates access anymore.

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

describe('debug access (R9)', () => {
  test('debug button appears when onDebug is provided', () => {
    const state = makeServiceState();
    let tree!: ReactTestRenderer.ReactTestRenderer;
    ReactTestRenderer.act(() => {
      tree = ReactTestRenderer.create(
        <StatusScreen state={state} onDebug={() => { /* opens debug */ }} />,
      );
    });
    expect(textAt(tree.root)).toContain('debug');
  });

  test('no debug button when onDebug is absent', () => {
    const state = makeServiceState();
    let tree!: ReactTestRenderer.ReactTestRenderer;
    ReactTestRenderer.act(() => {
      tree = ReactTestRenderer.create(<StatusScreen state={state} />);
    });
    expect(textAt(tree.root)).not.toContain('debug');
  });
});
