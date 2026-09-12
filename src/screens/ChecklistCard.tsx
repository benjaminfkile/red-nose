// Renders any red rows from the ServiceState.checklist (red-nose.md 13).
// Nothing is inferred; the values come from the service's system-API probes.
// Any red row means re-run provision.sh (14.2).

import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { Checklist } from '../native/NativeRedNose';

export type ChecklistCardProps = {
  checklist: Checklist;
};

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

export function ChecklistCard(props: ChecklistCardProps) {
  const red = ROWS.filter(r => props.checklist[r.key] === false);
  if (red.length === 0) return null;
  return (
    <View style={styles.card}>
      <Text style={styles.heading}>Checklist</Text>
      {red.map(r => (
        <Text key={r.key} style={styles.row}>
          <Text style={styles.dot}>{'● '}</Text>
          {r.label}
        </Text>
      ))}
      <Text style={styles.hint}>re-run provision.sh</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: '#2a0f10',
    padding: 12,
    borderRadius: 8,
    marginTop: 12,
    borderColor: '#c02',
    borderWidth: 1,
  },
  heading: { color: '#f88', fontWeight: '700', marginBottom: 8 },
  row: { color: '#f88', marginVertical: 2 },
  dot: { color: '#f22' },
  hint: { color: '#f88', marginTop: 8, fontStyle: 'italic' },
});
