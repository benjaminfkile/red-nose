// Manual enrollment path (red-nose.md 9.2): API base URL + key, GET /beacons/me,
// then saveEnrollment.  Prefilled with REDNOSE_DEFAULT_API_BASE_URL when supplied.

import React, { useState } from 'react';
import { StyleSheet, Text, View, TextInput, TouchableOpacity, ActivityIndicator } from 'react-native';
import { NativeRedNose } from '../native/NativeRedNose';
import { fetchMe, EnrollError } from '../enroll/enrollApi';

export type ManualEnrollScreenProps = {
  defaultApiBaseUrl?: string;
  onEnrolled: () => void;
  onBack: () => void;
};

export function ManualEnrollScreen(props: ManualEnrollScreenProps) {
  const [api, setApi] = useState(props.defaultApiBaseUrl ?? '');
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const submit = async () => {
    if (busy) return;
    setBusy(true);
    setMessage(null);
    try {
      const me = await fetchMe(api.trim(), key.trim());
      await NativeRedNose.saveEnrollment({
        apiBaseUrl: me.apiBaseUrl,
        hubUrl: me.hubUrl,
        ingestChannel: me.ingestChannel,
        beaconId: me.beaconId,
        name: me.name,
        key: key.trim(),
      });
      props.onEnrolled();
    } catch (e: unknown) {
      if (e instanceof EnrollError) {
        if (e.status === 401) {
          setMessage('key not accepted');
        } else {
          const suffix = e.requestId ? ` (${e.requestId})` : '';
          setMessage(`${e.code}${suffix}`);
        }
      } else {
        setMessage('network error');
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={styles.root}>
      <Text style={styles.label}>API base URL</Text>
      <TextInput
        style={styles.input}
        value={api}
        onChangeText={setApi}
        autoCapitalize="none"
        autoCorrect={false}
        keyboardType="url"
        placeholder="https://api.example.org"
        placeholderTextColor="#666"
      />
      <Text style={styles.label}>key</Text>
      <TextInput
        style={styles.input}
        value={key}
        onChangeText={setKey}
        autoCapitalize="none"
        autoCorrect={false}
        placeholder="wbk_..."
        placeholderTextColor="#666"
        secureTextEntry
      />
      {message ? <Text style={styles.msg}>{message}</Text> : null}
      <TouchableOpacity style={styles.primary} onPress={submit} disabled={busy}>
        {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryText}>enroll</Text>}
      </TouchableOpacity>
      <TouchableOpacity style={styles.secondary} onPress={props.onBack} disabled={busy}>
        <Text style={styles.secondaryText}>back to scan</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, padding: 20, backgroundColor: '#111' },
  label: { color: '#ccc', marginTop: 12, marginBottom: 6 },
  input: {
    backgroundColor: '#222', color: '#fff',
    paddingHorizontal: 12, paddingVertical: 10, borderRadius: 8,
  },
  msg: { color: '#f66', marginTop: 12 },
  primary: {
    marginTop: 20, backgroundColor: '#c02', padding: 14, borderRadius: 8, alignItems: 'center',
  },
  primaryText: { color: '#fff', fontWeight: '700' },
  secondary: { marginTop: 12, padding: 14, alignItems: 'center' },
  secondaryText: { color: '#aaa' },
});
