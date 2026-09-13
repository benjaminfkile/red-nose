// Provisioning debug screen (red-nose.md 10, 14): read-only verification of the
// system-app, root, launcher, and settings state.  Provisioning itself is done
// from the shell (`provision.sh`), not from buttons here.

import React from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import type { Checklist, ServiceState } from '../../native/NativeRedNose';

type Row = { key: keyof Checklist; label: string };

const ROWS: Row[] = [
  { key: 'fineLocation', label: 'Fine location' },
  { key: 'backgroundLocation', label: 'Background location' },
  { key: 'preciseLocation', label: 'Precise location' },
  { key: 'notifications', label: 'Notifications' },
  { key: 'batteryOptimizationExempt', label: 'Battery optimization exempt' },
  { key: 'locationServicesOn', label: 'Location services on' },
  { key: 'playServices', label: 'Google Play services' },
  { key: 'phoneState', label: 'Phone state' },
  { key: 'systemApp', label: 'System app' },
  { key: 'rootAvailable', label: 'Root available' },
  { key: 'launcher', label: 'Launcher' },
  { key: 'serviceRunning', label: 'Service running' },
];

export function ProvisioningScreen(props: { state: ServiceState }) {
  const c = props.state.checklist;
  const t = props.state.telemetry;
  return (
    <ScrollView contentContainerStyle={styles.root}>
      <Text style={styles.title}>provisioning (read-only)</Text>
      <Text style={styles.hint}>Re-run provision.sh from the shell to fix any red row.</Text>

      <View style={styles.card}>
        {ROWS.map(r => {
          const ok = c[r.key] === true;
          return (
            <View key={r.key} style={styles.row}>
              <Text style={[styles.dot, ok ? styles.green : styles.red]}>{ok ? '●' : '●'}</Text>
              <Text style={styles.rowLabel}>{r.label}</Text>
              <Text style={[styles.rowValue, ok ? styles.green : styles.red]}>{ok ? 'ok' : 'red'}</Text>
            </View>
          );
        })}
      </View>

      <View style={styles.card}>
        <Text style={styles.subtitle}>identity</Text>
        <KV label="deviceModel" value={t.debug?.identity?.deviceModel} />
        <KV label="androidVersion" value={t.debug?.identity?.androidVersion} />
        <KV label="appVersion" value={t.debug?.identity?.appVersion ?? props.state.appVersion} />
      </View>

      <View style={styles.card}>
        <Text style={styles.subtitle}>process</Text>
        <KV label="systemApp" value={t.debug?.process?.systemApp} />
        <KV label="rootAvailable" value={t.debug?.process?.rootAvailable} />
        <KV label="batteryOptimizationExempt" value={t.debug?.process?.batteryOptimizationExempt} />
        <KV label="notificationPermission" value={t.debug?.process?.notificationPermission} />
        <KV label="serviceRestartCount" value={t.debug?.process?.serviceRestartCount} />
        <KV label="serviceUptimeS" value={t.debug?.process?.serviceUptimeS} />
        <KV label="deviceUptimeS" value={t.debug?.process?.deviceUptimeS} />
      </View>
    </ScrollView>
  );
}

function KV({ label, value }: { label: string; value: unknown }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValue}>{value == null ? '-' : String(value)}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { padding: 16, backgroundColor: '#111', minHeight: '100%' },
  title: { color: '#f88', fontWeight: '700', marginBottom: 4 },
  hint: { color: '#aaa', fontStyle: 'italic', marginBottom: 12 },
  card: { backgroundColor: '#1b1b1b', padding: 12, borderRadius: 8, marginBottom: 12 },
  subtitle: { color: '#8cf', fontWeight: '700', marginBottom: 8 },
  row: { flexDirection: 'row', alignItems: 'center', marginVertical: 3 },
  dot: { marginRight: 8 },
  rowLabel: { color: '#ccc', flex: 1 },
  rowValue: { color: '#fff', textAlign: 'right' },
  green: { color: '#4c4' },
  red: { color: '#f44' },
});
