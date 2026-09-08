// Fix log (red-nose.md 10): the last 200 fix-level entries from the ring log.

import React from 'react';
import { LogStreamScreen } from './LogStreamScreen';

const FIX_RE = /\bfix\b|seqLocal=/i;

export function FixLogScreen(props: { lines: string[] }) {
  return (
    <LogStreamScreen
      title="fix log"
      lines={props.lines}
      filter={l => FIX_RE.test(l)}
      limit={200}
    />
  );
}
