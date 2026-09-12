// QR step of enrollment (red-nose.md 9.1).  The Google Play services code
// scanner (a full-screen system activity behind NativeRedNose.scanQrCode)
// reads the QR; the app never opens the camera and needs no CAMERA
// permission.  A resolved string is fed to handleValue; a cancel resolves
// with null and returns to this screen; a scanner_unavailable rejection
// falls back to manual entry.  The rednose://enroll intent path through
// getInitialEnrollUrl is unchanged.

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, ActivityIndicator } from 'react-native';
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
  const scanOpenRef = useRef(false);
  const busyRef = useRef(false);

  const handleValue = useCallback(async (value: string) => {
    if (busyRef.current) return;
    busyRef.current = true;
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
      busyRef.current = false;
      setBusy(false);
    }
  }, [props]);

  const openScanner = useCallback(async () => {
    if (scanOpenRef.current || busyRef.current) return;
    scanOpenRef.current = true;
    setMessage(null);
    try {
      const value = await NativeRedNose.scanQrCode();
      if (value != null) {
        await handleValue(value);
      }
    } catch (e: unknown) {
      const err = e as { code?: string; message?: string };
      if (err.code === 'scanner_unavailable' || err.code === 'no_activity') {
        setMessage('scanner unavailable (Play services): enter the code manually');
      } else {
        setMessage(err.message ?? 'scanner error');
      }
    } finally {
      scanOpenRef.current = false;
    }
  }, [handleValue]);

  useEffect(() => {
    let cancelled = false;
    NativeRedNose.getInitialEnrollUrl()
      .then(url => {
        if (cancelled) return;
        if (url) {
          void handleValue(url);
        } else {
          void openScanner();
        }
      })
      .catch(() => {
        if (!cancelled) void openScanner();
      });
    return () => { cancelled = true; };
  }, [handleValue, openScanner]);

  return (
    <View style={styles.root}>
      <View style={styles.body}>
        {busy ? (
          <ActivityIndicator />
        ) : (
          <Text style={styles.info}>
            Point the phone at the enrollment QR code.
          </Text>
        )}
      </View>
      <View style={styles.footer}>
        {message ? <Text style={styles.msg}>{message}</Text> : null}
        <TouchableOpacity
          style={styles.button}
          onPress={() => { void openScanner(); }}
        >
          <Text style={styles.buttonText}>scan a code</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.button, styles.buttonSecondary]}
          onPress={() => { if (!busyRef.current) props.onManual(); }}
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
  button: { padding: 14, borderRadius: 8, backgroundColor: '#222', alignItems: 'center', marginTop: 8 },
  buttonSecondary: { backgroundColor: '#1a1a1a' },
  buttonText: { color: '#fff', fontWeight: '600' },
});
