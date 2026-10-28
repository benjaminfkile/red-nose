// Navigation root (red-nose.md 2, 9.1, 10).  Three screens: Enroll (no stored
// key), Status (enrolled), and Debug (always available once enrolled).  The
// service ownership split lives in :beacon; JS only reads state and drives
// enrollment.

import React, { useMemo, useState } from 'react';
import { StatusBar, StyleSheet, View, ActivityIndicator } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { useServiceState } from './src/state/useServiceState';
import { EnrollScreen } from './src/screens/EnrollScreen';
import { StatusScreen } from './src/screens/StatusScreen';
import { DebugScreen } from './src/screens/debug/DebugScreen';
import { NativeRedNose } from './src/native/NativeRedNose';

function App() {
  const state = useServiceState();
  const [debug, setDebug] = useState(false);
  // Flavour and URLs from the native module's constants (red-nose.md 4.2, 15).
  const build = useMemo(() => NativeRedNose.constants(), []);

  // Android 15 with the app's targetSdk draws edge to edge (no hidden bars;
  // red-nose.md 5.5). SafeAreaView keeps every screen's content clear of the
  // status and navigation bars.
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
        {state == null ? (
          <View style={styles.center}>
            <ActivityIndicator />
          </View>
        ) : state.enrollment == null ? (
          <EnrollScreen
            onEnrolled={() => { /* next tick's ServiceState will carry enrollment */ }}
            defaultApiBaseUrl={build.defaultApiBaseUrl}
            allowHttp={build.flavour === 'dev'}
            flavour={build.flavour}
            prodApiBaseUrl={build.prodApiBaseUrl}
          />
        ) : debug ? (
          <DebugScreen state={state} onBack={() => setDebug(false)} />
        ) : (
          <StatusScreen
            state={state}
            onDebug={() => setDebug(true)}
          />
        )}
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#111' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});

export default App;
