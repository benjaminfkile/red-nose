// Socket log (red-nose.md 10): connection attempts, joins, evictions, closes
// with cause, backoff waits, retryNow signals.

import React from 'react';
import { LogStreamScreen } from './LogStreamScreen';

const SOCKET_RE = /\bsocket\b|\bhub\b|\bjoin\b|\bevict/i;

export function SocketLogScreen(props: { lines: string[] }) {
  return (
    <LogStreamScreen
      title="socket log"
      lines={props.lines}
      filter={l => SOCKET_RE.test(l)}
    />
  );
}
