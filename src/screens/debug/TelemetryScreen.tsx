// Telemetry debug screen (red-nose.md 10).  Renders the heartbeat body as it
// would be sent now: `sentAt`, the typed `health` core, and the free `debug`
// object (six groups).  Also shows the last heartbeat outcome, skew, live event,
// active, and revoked.

import React from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import type { Heartbeat, ServiceState } from '../../native/NativeRedNose';

export function TelemetryScreen(props: { state: ServiceState }) {
  const s = props.state;
  return (
    <ScrollView contentContainerStyle={styles.root}>
      <Section title="last heartbeat">
        <Row label="accepted at" value={s.lastHeartbeatAcceptedAt ?? '-'} />
        <Row label="error" value={s.lastHeartbeatError ?? '-'} />
        <Row label="clock skew" value={s.clockSkewMs == null ? '-' : `${s.clockSkewMs} ms`} />
        <Row label="live event" value={s.liveEventId == null ? '-' : `#${s.liveEventId}`} />
        <Row label="active" value={s.isActive == null ? '-' : String(s.isActive)} />
        <Row label="revoked" value={String(s.revoked)} />
      </Section>
      <Section title="telemetry (heartbeat body)">
        <Body body={s.telemetry} />
      </Section>
    </ScrollView>
  );
}

function Body({ body }: { body: Heartbeat }) {
  const health = body.health;
  const debug = body.debug;
  return (
    <View>
      <View style={styles.group}>
        <Text style={styles.groupLabel}>sentAt</Text>
        <Row label="sentAt" value={body.sentAt} />
      </View>
      <View style={styles.group}>
        <Text style={styles.groupLabel}>health</Text>
        {health == null ? (
          <Row label="(null)" value="-" />
        ) : (
          Object.entries(health).map(([k, v]) => (
            <Row key={k} label={k} value={formatValue(v)} />
          ))
        )}
      </View>
      <View style={styles.group}>
        <Text style={styles.groupLabel}>debug</Text>
        {debug == null ? (
          <Row label="(null)" value="-" />
        ) : (
          (['power', 'radio', 'gps', 'transport', 'process', 'identity'] as const).map(name => (
            <View key={name} style={styles.subGroup}>
              <Text style={styles.subGroupLabel}>{name}</Text>
              {debug[name] == null ? (
                <Row label="(null)" value="-" />
              ) : (
                Object.entries(debug[name] as Record<string, unknown>).map(([k, v]) => (
                  <Row key={k} label={k} value={formatValue(v)} />
                ))
              )}
            </View>
          ))
        )}
      </View>
    </View>
  );
}

function formatValue(v: unknown): string {
  if (v == null) return '-';
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
  subGroup: { marginLeft: 12, marginBottom: 8 },
  subGroupLabel: { color: '#6ac', marginBottom: 2 },
  row: { flexDirection: 'row', justifyContent: 'space-between', marginVertical: 2 },
  rowLabel: { color: '#aaa', flexShrink: 0, marginRight: 12 },
  rowValue: { color: '#fff', flexShrink: 1, textAlign: 'right', fontVariant: ['tabular-nums'] },
});
