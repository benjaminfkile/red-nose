// Telemetry debug screen (red-nose.md 10).  Renders the heartbeat body as a table
// alongside the last heartbeat outcome, skew, live event, active, and revoked.

import React from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import type { Heartbeat, ServiceState } from '../../native/NativeRedNose';

export function TelemetryScreen(props: { state: ServiceState }) {
  const s = props.state;
  return (
    <ScrollView contentContainerStyle={styles.root}>
      <Section title="last heartbeat">
        <Row label="accepted at" value={s.lastHeartbeatAcceptedAt ?? '—'} />
        <Row label="error" value={s.lastHeartbeatError ?? '—'} />
        <Row label="clock skew" value={s.clockSkewMs == null ? '—' : `${s.clockSkewMs} ms`} />
        <Row label="live event" value={s.liveEventId == null ? '—' : `#${s.liveEventId}`} />
        <Row label="active" value={s.isActive == null ? '—' : String(s.isActive)} />
        <Row label="revoked" value={String(s.revoked)} />
      </Section>
      <Section title="telemetry (heartbeat body)">
        <Body body={s.telemetry} />
      </Section>
    </ScrollView>
  );
}

function Body({ body }: { body: Heartbeat }) {
  const groups: [string, Record<string, unknown> | null | undefined][] = [
    ['sentAt', { sentAt: body.sentAt }],
    ['power', body.power ?? null],
    ['radio', body.radio ?? null],
    ['gps', body.gps ?? null],
    ['transport', body.transport ?? null],
    ['process', body.process ?? null],
    ['identity', body.identity ?? null],
  ];
  return (
    <View>
      {groups.map(([name, group]) => (
        <View key={name} style={styles.group}>
          <Text style={styles.groupLabel}>{name}</Text>
          {group == null ? (
            <Row label="(null)" value="—" />
          ) : (
            Object.entries(group).map(([k, v]) => (
              <Row key={k} label={k} value={formatValue(v)} />
            ))
          )}
        </View>
      ))}
    </View>
  );
}

function formatValue(v: unknown): string {
  if (v == null) return '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { padding: 16, backgroundColor: '#111', minHeight: '100%' },
  section: { backgroundColor: '#1b1b1b', padding: 12, borderRadius: 8, marginBottom: 12 },
  sectionTitle: { color: '#f88', fontWeight: '700', marginBottom: 8 },
  group: { marginBottom: 8 },
  groupLabel: { color: '#8cf', marginBottom: 4 },
  row: { flexDirection: 'row', justifyContent: 'space-between', marginVertical: 2 },
  rowLabel: { color: '#aaa', flexShrink: 0, marginRight: 12 },
  rowValue: { color: '#fff', flexShrink: 1, textAlign: 'right', fontVariant: ['tabular-nums'] },
});
