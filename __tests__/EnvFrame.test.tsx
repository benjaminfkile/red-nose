// The environment frame (red-nose.md 15): the flavour's colour and its DEV or
// PROD tag over every screen.

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import { StyleSheet } from 'react-native';
import { ENV_COLOURS, EnvFrame, envColour } from '../src/components/EnvFrame';

function render(flavour: string) {
  let tree!: ReactTestRenderer.ReactTestRenderer;
  ReactTestRenderer.act(() => {
    tree = ReactTestRenderer.create(<EnvFrame flavour={flavour} />);
  });
  const frame = tree.root.findByProps({ testID: 'env-frame' });
  const tag = tree.root.findByProps({ testID: 'env-tag' });
  return { frame, tag, style: StyleSheet.flatten(frame.props.style) };
}

describe('EnvFrame (red-nose.md 15)', () => {
  test('dev: a green frame tagged DEV', () => {
    const { frame, tag, style } = render('dev');
    expect(tag.props.children).toBe('DEV');
    expect(style.borderColor).toBe('#66bb6a');
    expect(style.borderWidth).toBe(3);
    expect(style.position).toBe('absolute');
    expect(frame.props.pointerEvents).toBe('none');
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
