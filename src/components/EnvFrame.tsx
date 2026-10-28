// The environment frame (red-nose.md 15): the wrapper every screen renders
// inside, a flex-filling view with a 4 dp border in the flavour colour, a 16 dp
// corner radius and a 6 dp margin, with a DEV or PROD tag on its top edge. It
// sits inside the SafeAreaView, so it stays clear of the status bar, the
// navigation bar and the display's rounded corners. The colours are the admin
// panel's own and match each flavour's @color/env_frame, the launcher icon's
// ring.

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

// The tag renders after the children so it paints over the top border.
export function EnvFrame({ flavour, children }: { flavour: string; children?: React.ReactNode }) {
  const colour = envColour(flavour);
  return (
    <View testID="env-frame" style={[styles.frame, { borderColor: colour }]}>
      {children}
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
    flex: 1,
    margin: 6,
    borderWidth: 4,
    borderRadius: 16,
    overflow: 'hidden',
  },
  tag: {
    position: 'absolute',
    top: 0,
    right: 14,
    paddingHorizontal: 7,
    paddingVertical: 1,
    borderBottomLeftRadius: 6,
    borderBottomRightRadius: 6,
  },
  tagText: { color: 'white', fontSize: 10, fontWeight: '700', letterSpacing: 1 },
});
