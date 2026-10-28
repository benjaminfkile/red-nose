// The environment frame (red-nose.md 15): the wrapper every screen renders
// inside, in the flavour's colour with its DEV or PROD tag.

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import { StyleSheet, Text } from 'react-native';
import { ENV_COLOURS, EnvFrame, envColour } from '../src/components/EnvFrame';

function render(flavour: string) {
  let tree!: ReactTestRenderer.ReactTestRenderer;
  ReactTestRenderer.act(() => {
    tree = ReactTestRenderer.create(
      <EnvFrame flavour={flavour}>
        <Text testID="screen">screen</Text>
      </EnvFrame>,
    );
  });
  const frame = tree.root.findByProps({ testID: 'env-frame' });
  const tag = tree.root.findByProps({ testID: 'env-tag' });
  const screen = tree.root.findByProps({ testID: 'screen' });
  return { frame, tag, screen, style: StyleSheet.flatten(frame.props.style) };
}

describe('EnvFrame (red-nose.md 15)', () => {
  test('dev: a green frame tagged DEV', () => {
    const { tag, style } = render('dev');
    expect(tag.props.children).toBe('DEV');
    expect(style.borderColor).toBe('#66bb6a');
  });

  test('the frame is a flex-filling inset wrapper', () => {
    const { frame, style } = render('dev');
    expect(style).toMatchObject({
      flex: 1,
      margin: 6,
      borderWidth: 4,
      borderRadius: 16,
      overflow: 'hidden',
    });
    expect(style.position).toBeUndefined();
    expect(frame.props.pointerEvents).toBeUndefined();
  });

  test('children render inside the frame, the tag last on its top edge', () => {
    const { frame, screen, tag } = render('dev');
    expect(frame.findByProps({ testID: 'screen' })).toBe(screen);
    // The host view under the composite View carries the rendered children.
    const host = frame.find(n => typeof n.type === 'string' && n.props.testID === 'env-frame');
    const kids = host.children.filter(c => typeof c !== 'string') as ReactTestRenderer.ReactTestInstance[];
    expect(kids[kids.length - 1].findByProps({ testID: 'env-tag' })).toBe(tag);
    const tagStyle = StyleSheet.flatten(kids[kids.length - 1].props.style);
    expect(tagStyle).toMatchObject({ position: 'absolute', top: 0, right: 14, backgroundColor: '#66bb6a' });
  });

  test('prod: a red frame tagged PROD', () => {
    const { tag, style } = render('prod');
    expect(tag.props.children).toBe('PROD');
    expect(style.borderColor).toBe('#f44336');
  });

  test('an unknown flavour falls back to the dev green', () => {
    const { style } = render('staging');
    expect(style.borderColor).toBe('#66bb6a');
  });
});

describe('envColour', () => {
  test('the table holds the admin panel colours', () => {
    expect(ENV_COLOURS).toEqual({ dev: '#66bb6a', prod: '#f44336' });
  });

  test('prod is red, dev and anything else green', () => {
    expect(envColour('prod')).toBe('#f44336');
    expect(envColour('dev')).toBe('#66bb6a');
    expect(envColour('')).toBe('#66bb6a');
    expect(envColour('PROD')).toBe('#66bb6a');
  });
});
