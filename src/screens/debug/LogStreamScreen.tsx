// Common log-stream renderer used by the Fix, Socket, and Failure log screens
// (red-nose.md 10).  Each screen supplies a filter over the ring-log lines
// (format: `<rfc3339> <LEVEL> <text>`; red-nose.md 12).

import React from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';

export function LogStreamScreen(props: {
  title: string;
  lines: string[];
  filter: (line: string) => boolean;
  limit?: number;
}) {
  const limit = props.limit ?? 200;
  const filtered = props.lines.filter(props.filter).slice(-limit);
  return (
    <ScrollView contentContainerStyle={styles.root}>
      <Text style={styles.title}>{props.title}</Text>
      {filtered.length === 0 ? (
        <Text style={styles.empty}>no entries</Text>
      ) : (
        filtered.map((line, i) => (
          <View key={`${i}-${line.length}`} style={styles.row}>
            <Text style={styles.line}>{line}</Text>
          </View>
        ))
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { padding: 12, backgroundColor: '#111', minHeight: '100%' },
  title: { color: '#f88', fontWeight: '700', marginBottom: 8 },
  empty: { color: '#666', fontStyle: 'italic' },
  row: { paddingVertical: 2 },
  line: { color: '#ddd', fontFamily: 'monospace', fontSize: 11 },
});
