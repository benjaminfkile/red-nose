// Debug shell (red-nose.md 10): the seven debug screens, gated at the caller
// on `enrollment.role === "admin"`.  Reads ServiceState from the parent (which
// already subscribes at 1 Hz) and the log stream through `onLog`.

import React, { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import type { ServiceState } from '../../native/NativeRedNose';
import { onLog } from '../../native/NativeRedNose';
import { TelemetryScreen } from './TelemetryScreen';
import { FixLogScreen } from './FixLogScreen';
import { SocketLogScreen } from './SocketLogScreen';
import { FailureLogScreen } from './FailureLogScreen';
import { LogFileScreen } from './LogFileScreen';
import { ReplayScreen } from './ReplayScreen';
import { ProvisioningScreen } from './ProvisioningScreen';

export type DebugTab =
  | 'telemetry' | 'fix' | 'socket' | 'failure' | 'log' | 'replay' | 'provisioning';

const TABS: { key: DebugTab; label: string }[] = [
  { key: 'telemetry', label: 'Telemetry' },
  { key: 'fix', label: 'Fix log' },
  { key: 'socket', label: 'Socket log' },
  { key: 'failure', label: 'Failure log' },
  { key: 'log', label: 'Log file' },
  { key: 'replay', label: 'Replay' },
  { key: 'provisioning', label: 'Provisioning' },
];

const LOG_RING = 500;

export function DebugScreen(props: { state: ServiceState; onBack: () => void }) {
  const [tab, setTab] = useState<DebugTab>('telemetry');
  const [lines, setLines] = useState<string[]>([]);

  useEffect(() => {
    const sub = onLog(line => {
      setLines(prev => {
        const next = prev.length >= LOG_RING ? prev.slice(prev.length - LOG_RING + 1) : prev.slice();
        next.push(line);
        return next;
      });
    });
    return () => sub.remove();
  }, []);

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <TouchableOpacity onPress={props.onBack} style={styles.backButton}>
          <Text style={styles.backText}>{'‹ back'}</Text>
        </TouchableOpacity>
        <Text style={styles.headerTitle}>debug</Text>
      </View>
      <ScrollView
        horizontal
        style={styles.tabs}
        contentContainerStyle={styles.tabsContent}
        showsHorizontalScrollIndicator={false}
      >
        {TABS.map(t => (
          <TouchableOpacity
            key={t.key}
            onPress={() => setTab(t.key)}
            style={[styles.tab, tab === t.key && styles.tabActive]}
          >
            <Text style={[styles.tabText, tab === t.key && styles.tabTextActive]}>{t.label}</Text>
          </TouchableOpacity>
        ))}
      </ScrollView>
      <View style={styles.body}>
        {tab === 'telemetry' && <TelemetryScreen state={props.state} />}
        {tab === 'fix' && <FixLogScreen lines={lines} />}
        {tab === 'socket' && <SocketLogScreen lines={lines} />}
        {tab === 'failure' && <FailureLogScreen lines={lines} />}
        {tab === 'log' && <LogFileScreen />}
        {tab === 'replay' && <ReplayScreen state={props.state} />}
        {tab === 'provisioning' && <ProvisioningScreen state={props.state} />}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#111' },
  header: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 12, paddingVertical: 10, backgroundColor: '#000',
  },
  backButton: { padding: 4, marginRight: 8 },
  backText: { color: '#8cf', fontWeight: '600' },
  headerTitle: { color: '#fff', fontWeight: '700', fontSize: 16 },
  tabs: { flexGrow: 0, backgroundColor: '#000' },
  tabsContent: { paddingHorizontal: 8, paddingBottom: 8 },
  tab: {
    paddingHorizontal: 12, paddingVertical: 8, marginRight: 6,
    borderRadius: 6, backgroundColor: '#222',
  },
  tabActive: { backgroundColor: '#c02' },
  tabText: { color: '#ccc' },
  tabTextActive: { color: '#fff', fontWeight: '700' },
  body: { flex: 1 },
});
