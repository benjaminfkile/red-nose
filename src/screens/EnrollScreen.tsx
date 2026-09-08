// Enroll flow shell (red-nose.md 9): shows Scan by default and swaps to Manual on
// request.  When enrollment succeeds, the parent switches to the Status screen.

import React, { useState } from 'react';
import { ScanScreen } from './ScanScreen';
import { ManualEnrollScreen } from './ManualEnrollScreen';

export type EnrollScreenProps = {
  onEnrolled: () => void;
  defaultApiBaseUrl?: string;
  allowHttp?: boolean;
};

export function EnrollScreen(props: EnrollScreenProps) {
  const [mode, setMode] = useState<'scan' | 'manual'>('scan');
  if (mode === 'manual') {
    return (
      <ManualEnrollScreen
        defaultApiBaseUrl={props.defaultApiBaseUrl}
        onEnrolled={props.onEnrolled}
        onBack={() => setMode('scan')}
      />
    );
  }
  return (
    <ScanScreen
      onEnrolled={props.onEnrolled}
      onManual={() => setMode('manual')}
      allowHttp={props.allowHttp}
    />
  );
}
