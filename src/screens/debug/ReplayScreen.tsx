// Replay screen (red-nose.md 11).  Runs a route from an https URL or a picked
// file (content:// URI through pickRouteFile) at 1..10 fixes/second.  Refused
// when the enrolled apiBaseUrl equals REDNOSE_PROD_API_BASE_URL, which the
// service surfaces as `replayAllowed === false`.

import React, { useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View,
} from 'react-native';
import { NativeRedNose } from '../../native/NativeRedNose';
import type { ServiceState } from '../../native/NativeRedNose';

export const REPLAY_MIN_RATE = 1;
export const REPLAY_MAX_RATE = 10;

export type ReplayScreenProps = {
  state: ServiceState;
};

export function ReplayScreen(props: ReplayScreenProps) {
  const s = props.state;
  const [url, setUrl] = useState('');
  const [rate, setRate] = useState('1');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  if (!s.replayAllowed) {
    return (
      <View style={styles.root}>
        <Text style={styles.title}>replay</Text>
        <View style={styles.blocked}>
          <Text style={styles.blockedText}>
            replay is disabled: the enrolled API is the production URL
          </Text>
        </View>
      </View>
    );
  }

  const parsedRate = clampRate(rate);
  const rateInvalid = parsedRate == null;

  const startFromUrl = async () => {
    if (busy || rateInvalid) return;
    const src = url.trim();
    if (!src) {
      setMessage('enter a URL or pick a file');
      return;
    }
    if (!isHttpUrl(src)) {
      setMessage('URL must start with http:// or https://');
      return;
    }
    await start(src, parsedRate);
  };

  const startFromFile = async () => {
    if (busy || rateInvalid) return;
    setBusy(true);
    setMessage(null);
    try {
      const picked = await NativeRedNose.pickRouteFile();
      if (!picked) {
        setBusy(false);
        return;
      }
      await start(picked, parsedRate);
    } catch (e: unknown) {
      const err = e as { message?: string };
      setMessage(err.message ?? 'pick failed');
      setBusy(false);
    }
  };

  const start = async (source: string, ratePerSecond: number) => {
    setBusy(true);
    setMessage(null);
    try {
      await NativeRedNose.startReplay(source, ratePerSecond);
    } catch (e: unknown) {
      const err = e as { message?: string };
      setMessage(err.message ?? 'start failed');
    } finally {
      setBusy(false);
    }
  };

  const stop = async () => {
    if (busy) return;
    setBusy(true);
    setMessage(null);
    try {
      await NativeRedNose.stopReplay();
    } catch (e: unknown) {
      const err = e as { message?: string };
      setMessage(err.message ?? 'stop failed');
    } finally {
      setBusy(false);
    }
  };

  const running = s.replay?.running === true;

  return (
    <ScrollView contentContainerStyle={styles.root}>
      <Text style={styles.title}>replay</Text>

      <View style={styles.card}>
        <Text style={styles.label}>route URL (https recommended)</Text>
        <TextInput
          style={styles.input}
          value={url}
          onChangeText={setUrl}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          placeholder="https://example.org/routes/2025.json"
          placeholderTextColor="#666"
          editable={!busy}
        />
        <Text style={styles.label}>rate (fixes / second, 1..10)</Text>
        <TextInput
          style={[styles.input, rateInvalid ? styles.inputBad : null]}
          value={rate}
          onChangeText={setRate}
          keyboardType="number-pad"
          editable={!busy}
        />
        {rateInvalid ? (
          <Text style={styles.warn}>rate must be an integer between 1 and 10</Text>
        ) : null}

        <View style={styles.buttons}>
          <TouchableOpacity
            style={[styles.button, (busy || rateInvalid) && styles.buttonDisabled]}
            onPress={startFromUrl}
            disabled={busy || rateInvalid}
          >
            <Text style={styles.buttonText}>start from URL</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.button, (busy || rateInvalid) && styles.buttonDisabled]}
            onPress={startFromFile}
            disabled={busy || rateInvalid}
          >
            <Text style={styles.buttonText}>pick file</Text>
          </TouchableOpacity>
        </View>

        {message ? <Text style={styles.msg}>{message}</Text> : null}
        {busy ? <ActivityIndicator style={{ marginTop: 12 }} color="#fff" /> : null}
      </View>

      <View style={styles.card}>
        <Text style={styles.subtitle}>status</Text>
        {s.replay == null ? (
          <Text style={styles.dim}>not running</Text>
        ) : (
          <View>
            <Row label="running" value={String(s.replay.running)} />
            <Row label="source" value={s.replay.source} />
            <Row label="progress" value={`${s.replay.index} / ${s.replay.total}`} />
            <Row label="rate" value={`${s.replay.ratePerSecond}/s`} />
          </View>
        )}
        {running ? (
          <TouchableOpacity style={styles.stopButton} onPress={stop} disabled={busy}>
            <Text style={styles.buttonText}>stop replay</Text>
          </TouchableOpacity>
        ) : null}
      </View>
    </ScrollView>
  );
}

export function clampRate(input: string): number | null {
  const t = input.trim();
  if (!/^-?\d+$/.test(t)) return null;
  const n = parseInt(t, 10);
  if (!Number.isInteger(n) || n < REPLAY_MIN_RATE || n > REPLAY_MAX_RATE) return null;
  return n;
}

function isHttpUrl(s: string): boolean {
  return /^https?:\/\//i.test(s);
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
  title: { color: '#f88', fontWeight: '700', marginBottom: 8 },
  subtitle: { color: '#8cf', fontWeight: '700', marginBottom: 8 },
  card: { backgroundColor: '#1b1b1b', padding: 12, borderRadius: 8, marginBottom: 12 },
  label: { color: '#ccc', marginTop: 8, marginBottom: 4 },
  input: {
    backgroundColor: '#222', color: '#fff',
    paddingHorizontal: 12, paddingVertical: 10, borderRadius: 8,
  },
  inputBad: { borderColor: '#c02', borderWidth: 1 },
  warn: { color: '#f88', marginTop: 4 },
  buttons: { flexDirection: 'row', marginTop: 12, gap: 8 },
  button: {
    flex: 1, padding: 12, backgroundColor: '#c02', borderRadius: 8, alignItems: 'center',
  },
  buttonDisabled: { backgroundColor: '#4a1010' },
  buttonText: { color: '#fff', fontWeight: '700' },
  stopButton: {
    marginTop: 12, padding: 12, backgroundColor: '#403', borderRadius: 8, alignItems: 'center',
  },
  msg: { color: '#ffb', marginTop: 12 },
  row: { flexDirection: 'row', justifyContent: 'space-between', marginVertical: 2 },
  rowLabel: { color: '#aaa', marginRight: 12 },
  rowValue: { color: '#fff', flexShrink: 1, textAlign: 'right' },
  dim: { color: '#666' },
  blocked: {
    backgroundColor: '#2a0f10', padding: 12, borderRadius: 8,
    borderColor: '#c02', borderWidth: 1, marginTop: 12,
  },
  blockedText: { color: '#f88' },
});
