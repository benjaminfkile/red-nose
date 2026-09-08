// Failure log (red-nose.md 10): every WARN/ERROR line, which covers failed
// sends and failed heartbeats with `code`, `requestId`, or the hub's text.

import React from 'react';
import { LogStreamScreen } from './LogStreamScreen';

const LEVEL_RE = /\s(WARN|ERROR)\s/;

export function FailureLogScreen(props: { lines: string[] }) {
  return (
    <LogStreamScreen
      title="failure log"
      lines={props.lines}
      filter={l => LEVEL_RE.test(l)}
    />
  );
}
