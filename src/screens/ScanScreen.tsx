// QR step of enrollment (red-nose.md 9.1).  react-native-vision-camera v5 has no
// code scanner on Android (CameraObjectOutput throws "not available on Android"),
// so this screen does not open the camera.  It still receives a value from the
// rednose://enroll intent filter via NativeRedNose.getInitialEnrollUrl, hands it
// to parseEnrollUrl and enrollApi.exchange, and otherwise offers manual entry.
// Choosing a scanning approach is listed in red-nose.md section 19.

import React, { useEffect, useState } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, ActivityIndicator, Alert } from 'react-native';
import { parseEnrollUrl } from '../enroll/parseEnrollUrl';
import { exchange } from '../enroll/enrollApi';
import { NativeRedNose } from '../native/NativeRedNose';

export type ScanScreenProps = {
  onEnrolled: () => void;
  onManual: () => void;
  allowHttp?: boolean;
};

export function ScanScreen(props: ScanScreenProps) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    NativeRedNose.getInitialEnrollUrl()
      .then(url => {
        if (!cancelled && url) void handleValue(url);
      })
      .catch(() => {
        // no initial URL; type the key manually
      });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleValue = async (value: string) => {
    if (busy) return;
    setBusy(true);
    setMessage(null);
    try {
      const parsed = parseEnrollUrl(value, { allowHttp: props.allowHttp });
      const res = await exchange(parsed.apiBaseUrl, parsed.token);
      await NativeRedNose.saveEnrollment({
        apiBaseUrl: res.apiBaseUrl,
        hubUrl: res.hubUrl,
        ingestChannel: res.ingestChannel,
        beaconId: res.beaconId,
        name: res.name,
        role: res.role,
        key: res.key,
      });
      props.onEnrolled();
    } catch (e: unknown) {
      const err = e as { code?: string; message?: string; requestId?: string | null };
      if (err.code === 'enrollment_token_invalid') {
        setMessage('this code was already used or expired; ask for a new one');
      } else if (err.message === 'not an enrollment code') {
        setMessage('not an enrollment code');
      } else {
        const suffix = err.requestId ? ` (${err.requestId})` : '';
        setMessage(`${err.code ?? 'error'}${suffix}`);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={styles.root}>
      <View style={styles.body}>
        {busy ? (
          <ActivityIndicator />
        ) : (
          <Text style={styles.info}>
            QR scanning is not available in this build. Enter the API URL and beacon key by hand,
            or open an enrollment link on this phone.
          </Text>
        )}
      </View>
      <View style={styles.footer}>
        {message ? <Text style={styles.msg}>{message}</Text> : null}
        <TouchableOpacity
          style={styles.button}
          onPress={() => (busy ? Alert.alert('busy') : props.onManual())}
        >
          <Text style={styles.buttonText}>enter manually</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' },
  body: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  footer: { padding: 16, backgroundColor: '#000' },
  info: { color: '#fff', textAlign: 'center', lineHeight: 22 },
  msg: { color: '#ffb', paddingBottom: 12, textAlign: 'center' },
  button: { padding: 14, borderRadius: 8, backgroundColor: '#222', alignItems: 'center' },
  buttonText: { color: '#fff', fontWeight: '600' },
});
