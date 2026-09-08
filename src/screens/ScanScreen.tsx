// QR scan step of enrollment (red-nose.md 9.1).  Uses Vision Camera's built-in
// code scanner (v5 useObjectOutput with types: ['qr']); a scanned value is
// handed to parseEnrollUrl and, on a valid URL, to enrollApi.exchange.  The
// system camera reaches the same code through the rednose://enroll intent
// filter and NativeRedNose.getInitialEnrollUrl.

import React, { useEffect, useMemo, useState } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, ActivityIndicator, Alert } from 'react-native';
import {
  Camera, useCameraDevice, useCameraPermission, useObjectOutput,
} from 'react-native-vision-camera';
import { isScannedCode } from 'react-native-vision-camera';
import type { ScannedObject } from 'react-native-vision-camera';
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
  const permission = useCameraPermission();
  const device = useCameraDevice('back');

  useEffect(() => {
    if (!permission.hasPermission && permission.canRequestPermission) {
      void permission.requestPermission();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [permission.hasPermission, permission.canRequestPermission]);

  useEffect(() => {
    let cancelled = false;
    NativeRedNose.getInitialEnrollUrl()
      .then(url => {
        if (!cancelled && url) void handleValue(url);
      })
      .catch(() => {
        // no initial URL; scan or type manually
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

  const codeOutput = useObjectOutput({
    types: ['qr'],
    onObjectsScanned: (objects: ScannedObject[]) => {
      for (const o of objects) {
        if (isScannedCode(o) && o.value) {
          void handleValue(o.value);
          return;
        }
      }
    },
  });

  const outputs = useMemo(() => [codeOutput], [codeOutput]);
  const previewReady = device != null && permission.hasPermission;

  return (
    <View style={styles.root}>
      <View style={styles.previewBox}>
        {previewReady ? (
          <Camera
            style={StyleSheet.absoluteFill}
            device={device!}
            isActive={!busy}
            outputs={outputs}
          />
        ) : permission.hasPermission === false ? (
          <Text style={styles.error}>camera permission missing (re-run provision.sh)</Text>
        ) : (
          <ActivityIndicator />
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
  previewBox: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  footer: { padding: 16, backgroundColor: '#000' },
  error: { color: '#fff', textAlign: 'center', padding: 24 },
  msg: { color: '#ffb', paddingBottom: 12, textAlign: 'center' },
  button: { padding: 14, borderRadius: 8, backgroundColor: '#222', alignItems: 'center' },
  buttonText: { color: '#fff', fontWeight: '600' },
});
