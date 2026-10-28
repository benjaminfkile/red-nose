// The environment frame (red-nose.md 15): a pointer-transparent 3 dp border in
// the flavour colour around every screen, with a DEV or PROD tag on its top
// edge. The colours are the admin panel's own and match each flavour's
// @color/env_frame, the launcher icon's ring.

import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

export const ENV_COLOURS: Record<string, string> = {
  dev: '#66bb6a',
  prod: '#f44336',
};

// Prod red for the prod flavour only; anything else reads as dev.
export function envColour(flavour: string): string {
  return flavour === 'prod' ? ENV_COLOURS.prod : ENV_COLOURS.dev;
}

export function EnvFrame({ flavour }: { flavour: string }) {
  const colour = envColour(flavour);
  return (
    <View
      pointerEvents="none"
      testID="env-frame"
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={[styles.frame, { borderColor: colour }]}
    >
      <View style={[styles.tag, { backgroundColor: colour }]}>
        <Text testID="env-tag" style={styles.tagText}>
          {flavour.toUpperCase()}
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  frame: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    borderWidth: 3,
    borderRadius: 10,
    zIndex: 1000,
    elevation: 1000,
  },
  tag: {
    position: 'absolute',
    top: -1,
    right: 14,
    paddingHorizontal: 7,
    paddingVertical: 1,
    borderBottomLeftRadius: 6,
    borderBottomRightRadius: 6,
  },
  tagText: { color: 'white', fontSize: 10, fontWeight: '700', letterSpacing: 1 },
});
