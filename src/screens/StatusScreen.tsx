// Status screen for the enrolled beacon (contracts 9.3 + red-nose.md 9.1).  Reads
// ServiceState off the 1 Hz stream and shows everything 9.3 lists.  The settings
// sheet holds the GPS-only toggle and (behind a long press) clear enrollment.

import React, { useMemo, useState } from 'react';
import {
  Alert, Modal, ScrollView, StyleSheet, Switch, Text, TouchableOpacity, View,
} from 'react-native';
import type { ServiceState } from '../native/NativeRedNose';
import { NativeRedNose } from '../native/NativeRedNose';
import { ChecklistCard } from './ChecklistCard';

export type StatusScreenProps = {
  state: ServiceState;
  onDebug?: () => void;
};

export function StatusScreen(props: StatusScreenProps) {
  const s = props.state;
  const [sheetOpen, setSheetOpen] = useState(false);

  const heartbeatAge = useHeartbeatAge(s.lastHeartbeatAcceptedAt);
  const activeLabel = s.isActive == null ? '-' : s.isActive ? 'active' : 'spare';
  const liveEventLabel = s.liveEventId == null ? 'no live event' : `event #${s.liveEventId}`;
  const skewLabel = s.clockSkewMs == null ? '-' : `${s.clockSkewMs} ms`;

  return (
    <ScrollView contentContainerStyle={styles.root}>
      {s.revoked ? (
        <View style={styles.revokedBanner}>
          <Text style={styles.revokedText}>revoked: rotate and re-enroll</Text>
        </View>
      ) : null}

      <View style={styles.card}>
        <Row label="beacon" value={s.enrollment?.name ?? '-'} />
        <Row label="role" value={s.enrollment?.role ?? '-'} />
        <Row label="socket" value={s.socketState} />
        <Row label="live event" value={liveEventLabel} />
        <Row label="active" value={activeLabel} />
        <Row label="last delivered seq" value={s.lastDeliveredSeqLocal?.toString() ?? '-'} />
        <Row label="receipt latency" value={s.lastReceiptLatencyMs != null ? `${s.lastReceiptLatencyMs} ms` : '-'} />
        <Row label="heartbeat age" value={heartbeatAge} />
        <Row label="clock skew" value={skewLabel} />
      </View>

      <ChecklistCard checklist={s.checklist} />

      <TouchableOpacity style={styles.sheetButton} onPress={() => setSheetOpen(true)}>
        <Text style={styles.sheetText}>settings</Text>
      </TouchableOpacity>

      {s.enrollment?.role === 'admin' && props.onDebug ? (
        <TouchableOpacity style={styles.sheetButton} onPress={props.onDebug}>
          <Text style={styles.sheetText}>debug</Text>
        </TouchableOpacity>
      ) : null}

      <Modal visible={sheetOpen} transparent animationType="slide">
        <SettingsSheet state={s} onClose={() => setSheetOpen(false)} />
      </Modal>
    </ScrollView>
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

function useHeartbeatAge(iso: string | null): string {
  return useMemo(() => {
    if (!iso) return '-';
    const t = Date.parse(iso);
    if (Number.isNaN(t)) return '-';
    const s = Math.max(0, Math.floor((Date.now() - t) / 1000));
    return `${s} s`;
  }, [iso]);
}

function SettingsSheet(props: { state: ServiceState; onClose: () => void }) {
  const [gpsOnly, setGpsOnly] = useState(props.state.enrollment?.gpsOnlyFallback === true);
  const [busy, setBusy] = useState(false);

  const toggle = async (v: boolean) => {
    setBusy(true);
    setGpsOnly(v);
    try { await NativeRedNose.setGpsOnlyFallback(v); } catch { setGpsOnly(!v); }
    finally { setBusy(false); }
  };

  const clear = () => {
    Alert.alert('clear enrollment?', 'wipes the stored key and stops the service.', [
      { text: 'cancel', style: 'cancel' },
      {
        text: 'clear', style: 'destructive',
        onPress: async () => {
          try { await NativeRedNose.clearEnrollment(); } catch { /* surface next tick */ }
          props.onClose();
        },
      },
    ]);
  };

  return (
    <View style={styles.sheetBackdrop}>
      <View style={styles.sheet}>
        <Text style={styles.sheetHeading}>settings</Text>
        <View style={styles.settingRow}>
          <Text style={styles.settingLabel}>GPS-only fallback</Text>
          <Switch value={gpsOnly} onValueChange={toggle} disabled={busy} />
        </View>
        <TouchableOpacity onLongPress={clear} delayLongPress={1200} style={styles.clearButton}>
          <Text style={styles.clearText}>clear enrollment (hold to confirm)</Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={props.onClose} style={styles.closeButton}>
          <Text style={styles.closeText}>close</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { padding: 16, backgroundColor: '#111', minHeight: '100%' },
  card: { backgroundColor: '#1b1b1b', padding: 12, borderRadius: 8 },
  row: { flexDirection: 'row', justifyContent: 'space-between', marginVertical: 4 },
  rowLabel: { color: '#aaa' },
  rowValue: { color: '#fff', fontVariant: ['tabular-nums'] },
  revokedBanner: { backgroundColor: '#c02', padding: 10, borderRadius: 8, marginBottom: 12 },
  revokedText: { color: '#fff', fontWeight: '700', textAlign: 'center' },
  sheetButton: { marginTop: 12, padding: 12, backgroundColor: '#222', borderRadius: 8, alignItems: 'center' },
  sheetText: { color: '#fff' },
  sheetBackdrop: { flex: 1, justifyContent: 'flex-end', backgroundColor: '#0008' },
  sheet: { backgroundColor: '#111', padding: 20, borderTopLeftRadius: 12, borderTopRightRadius: 12 },
  sheetHeading: { color: '#fff', fontSize: 18, fontWeight: '700', marginBottom: 12 },
  settingRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginVertical: 8 },
  settingLabel: { color: '#fff' },
  clearButton: { marginTop: 16, padding: 12, borderRadius: 8, backgroundColor: '#2a0f10', borderColor: '#c02', borderWidth: 1, alignItems: 'center' },
  clearText: { color: '#f88' },
  closeButton: { marginTop: 12, padding: 12, alignItems: 'center' },
  closeText: { color: '#aaa' },
});
