// Log file screen (red-nose.md 10, 12): the ring-log tail, an upload button
// (POST /beacons/logs through the service), and the last upload outcome
// received on the `rednose.logUpload` event.

import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View,
} from 'react-native';
import { NativeRedNose, onLogUpload } from '../../native/NativeRedNose';
import type { LogUploadResult } from '../../native/NativeRedNose';

export function LogFileScreen() {
  const [lines, setLines] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<LogUploadResult | null>(null);

  const refresh = async () => {
    try {
      const l = await NativeRedNose.getRecentLog(500);
      setLines(l);
    } catch {
      // service may be unbound; the next tick refreshes
    }
  };

  useEffect(() => {
    void refresh();
    const sub = onLogUpload(r => {
      setResult(r);
      setBusy(false);
    });
    return () => sub.remove();
  }, []);

  const upload = async () => {
    if (busy) return;
    setBusy(true);
    setResult(null);
    try {
      await NativeRedNose.uploadLog();
    } catch (e: unknown) {
      const err = e as { message?: string };
      setResult({ ok: false, code: 'call_failed', message: err.message ?? 'call failed' });
      setBusy(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.root}>
      <Text style={styles.title}>log file</Text>
      <TouchableOpacity style={styles.button} onPress={upload} disabled={busy}>
        {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>upload</Text>}
      </TouchableOpacity>
      <TouchableOpacity style={styles.secondary} onPress={refresh}>
        <Text style={styles.secondaryText}>refresh tail</Text>
      </TouchableOpacity>
      {result ? <ResultCard result={result} /> : null}
      <Text style={styles.tailLabel}>tail ({lines.length} lines)</Text>
      <View style={styles.tail}>
        {lines.length === 0 ? (
          <Text style={styles.empty}>no lines</Text>
        ) : (
          lines.map((line, i) => (
            <Text key={`${i}-${line.length}`} style={styles.line}>{line}</Text>
          ))
        )}
      </View>
    </ScrollView>
  );
}

function ResultCard({ result }: { result: LogUploadResult }) {
  if (result.ok) {
    return (
      <View style={[styles.result, styles.ok]}>
        <Text style={styles.resultText}>uploaded {result.sizeBytes} bytes</Text>
        <Text style={styles.resultText}>id {result.id}</Text>
        <Text style={styles.resultText}>received {result.receivedAt}</Text>
      </View>
    );
  }
  return (
    <View style={[styles.result, styles.err]}>
      <Text style={styles.resultText}>{result.code}: {result.message}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { padding: 12, backgroundColor: '#111', minHeight: '100%' },
  title: { color: '#f88', fontWeight: '700', marginBottom: 8 },
  button: { padding: 12, backgroundColor: '#c02', borderRadius: 8, alignItems: 'center' },
  buttonText: { color: '#fff', fontWeight: '700' },
  secondary: { marginTop: 8, padding: 12, alignItems: 'center' },
  secondaryText: { color: '#aaa' },
  result: { marginTop: 12, padding: 12, borderRadius: 8 },
  ok: { backgroundColor: '#0f2a10', borderColor: '#2c2', borderWidth: 1 },
  err: { backgroundColor: '#2a0f10', borderColor: '#c02', borderWidth: 1 },
  resultText: { color: '#fff' },
  tailLabel: { color: '#aaa', marginTop: 12, marginBottom: 4 },
  tail: { backgroundColor: '#1b1b1b', padding: 8, borderRadius: 8 },
  empty: { color: '#666', fontStyle: 'italic' },
  line: { color: '#ddd', fontFamily: 'monospace', fontSize: 11 },
});
