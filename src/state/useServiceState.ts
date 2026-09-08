// Subscribes to the service's 1 Hz `rednose.state` events (red-nose.md 4.2) and
// exposes the parsed ServiceState.  On mount the current state is fetched once
// so the UI is not blank until the next tick.

import { useEffect, useState } from 'react';
import { AppState } from 'react-native';
import type { AppStateStatus } from 'react-native';
import { NativeRedNose, onServiceState } from '../native/NativeRedNose';
import type { ServiceState } from '../native/NativeRedNose';

export function useServiceState(): ServiceState | null {
  const [state, setState] = useState<ServiceState | null>(null);

  useEffect(() => {
    let mounted = true;

    const readOnce = () => {
      NativeRedNose.getState()
        .then(s => {
          if (mounted) setState(s);
        })
        .catch(() => {
          // The service may not be bound yet; the emitter will deliver the next tick.
        });
    };

    readOnce();
    const sub = onServiceState(s => {
      if (mounted) setState(s);
    });
    const appSub = AppState.addEventListener('change', (status: AppStateStatus) => {
      if (status === 'active') readOnce();
    });

    return () => {
      mounted = false;
      sub.remove();
      appSub.remove();
    };
  }, []);

  return state;
}
