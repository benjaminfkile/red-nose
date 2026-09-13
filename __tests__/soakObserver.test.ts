// The soak observer polls GET /admin/beacons and writes CSV rows
// (red-nose.md 17).  These tests exercise it against a mocked fetch: the
// observer never talks to the network, so we can drive it with canned
// responses and assert the shape of the CSV that gets appended.

import {
  CSV_HEADER,
  formatCsvRow,
  pollBeacons,
  runObserver,
  type BeaconsResponse,
  type SoakBeacon,
} from '../tools/soak-observer/observer';

const OBSERVED_AT = new Date('2026-12-22T01:31:07.000Z');

function mockOk(body: unknown): typeof fetch {
  const impl = async () =>
    ({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => body,
    } as unknown as Response);
  return impl as unknown as typeof fetch;
}

function beacon(overrides: Partial<SoakBeacon> = {}): SoakBeacon {
  return {
    id: 7,
    name: 'phone-1',
    lastHeartbeatAt: '2026-12-22T01:30:55.000Z',
    telemetry: {
      health: { batteryPercent: 87, socketState: 'connected' },
      debug: {
        process: { serviceRestartCount: 2 },
        power: { charging: true },
      },
    },
    ...overrides,
  };
}

describe('CSV formatting', () => {
  test('header names the four required drill columns', () => {
    // Section 17: heartbeat age, socket state, restart count, and battery.
    for (const c of ['heartbeatAgeS', 'socketState', 'serviceRestartCount', 'batteryPercent']) {
      expect(CSV_HEADER.split(',')).toContain(c);
    }
  });

  test('rounds the heartbeat age to whole seconds', () => {
    const row = formatCsvRow(OBSERVED_AT, beacon());
    // OBSERVED_AT - lastHeartbeatAt = 12 s.
    expect(row.split(',')[3]).toBe('12');
  });

  test('renders every recorded telemetry value', () => {
    const row = formatCsvRow(OBSERVED_AT, beacon());
    const cols = row.split(',');
    expect(cols[0]).toBe('2026-12-22T01:31:07.000Z');
    expect(cols[1]).toBe('7');
    expect(cols[2]).toBe('phone-1');
    expect(cols[3]).toBe('12');
    expect(cols[4]).toBe('connected');
    expect(cols[5]).toBe('2');
    expect(cols[6]).toBe('87');
    expect(cols[7]).toBe('true');
  });

  test('empties every column when telemetry has never arrived', () => {
    const row = formatCsvRow(
      OBSERVED_AT,
      beacon({ lastHeartbeatAt: null, telemetry: null }),
    );
    const cols = row.split(',');
    expect(cols[3]).toBe('');
    expect(cols[4]).toBe('');
    expect(cols[5]).toBe('');
    expect(cols[6]).toBe('');
    expect(cols[7]).toBe('');
  });

  test('quotes a name that contains a comma', () => {
    const row = formatCsvRow(OBSERVED_AT, beacon({ name: 'phone, backup' }));
    expect(row.split(',')[2] + ',' + row.split(',')[3]).toBe('"phone, backup"');
  });

  test('clamps a negative heartbeat age to zero', () => {
    // A brief clock skew where lastHeartbeatAt is slightly in the future.
    const row = formatCsvRow(
      OBSERVED_AT,
      beacon({ lastHeartbeatAt: '2026-12-22T01:31:10.000Z' }),
    );
    expect(row.split(',')[3]).toBe('0');
  });
});

describe('pollBeacons', () => {
  test('GETs /admin/beacons with the admin key header', async () => {
    const calls: Array<{ url: string; init: RequestInit | undefined }> = [];
    const spy: typeof fetch = (async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      const body: BeaconsResponse = { items: [beacon()], staleAfterS: 45 };
      return {
        ok: true,
        status: 200,
        statusText: 'OK',
        json: async () => body,
      } as unknown as Response;
    }) as unknown as typeof fetch;

    await pollBeacons({
      apiBaseUrl: 'https://api.example.org/',
      adminKey: 'wbk_secret',
      fetchImpl: spy,
      now: () => OBSERVED_AT,
    });

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('https://api.example.org/admin/beacons');
    expect(calls[0].init?.method).toBe('GET');
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['X-Beacon-Key']).toBe('wbk_secret');
    expect(headers.Accept).toBe('application/json');
  });

  test('returns one CSV row per beacon in the response', async () => {
    const body: BeaconsResponse = {
      items: [
        beacon({ id: 1, name: 'phone-a' }),
        beacon({
          id: 2,
          name: 'phone-b',
          telemetry: {
            health: { batteryPercent: 42, socketState: 'reconnecting' },
            debug: {
              process: { serviceRestartCount: 5 },
              power: { charging: false },
            },
          },
        }),
      ],
      staleAfterS: 45,
    };
    const result = await pollBeacons({
      apiBaseUrl: 'https://api.example.org',
      adminKey: 'wbk_secret',
      fetchImpl: mockOk(body),
      now: () => OBSERVED_AT,
    });
    expect(result.rows).toHaveLength(2);
    expect(result.rows[0].split(',')[1]).toBe('1');
    expect(result.rows[1].split(',')[1]).toBe('2');
    expect(result.rows[1].split(',')[4]).toBe('reconnecting');
    expect(result.rows[1].split(',')[6]).toBe('42');
    expect(result.rows[1].split(',')[7]).toBe('false');
  });

  test('throws on a non-2xx response', async () => {
    const bad: typeof fetch = (async () =>
      ({
        ok: false,
        status: 401,
        statusText: 'Unauthorized',
        json: async () => ({}),
      } as unknown as Response)) as unknown as typeof fetch;
    await expect(
      pollBeacons({ apiBaseUrl: 'https://api.example.org', adminKey: 'nope', fetchImpl: bad }),
    ).rejects.toThrow(/401/);
  });
});

describe('runObserver', () => {
  test('polls immediately, appends the header once, then polls on the interval until aborted', async () => {
    jest.useFakeTimers();
    try {
      let call = 0;
      const impl: typeof fetch = ((): Promise<Response> => {
        call++;
        const body: BeaconsResponse = {
          items: [beacon({ id: 10 + call, name: `phone-${call}` })],
          staleAfterS: 45,
        };
        return Promise.resolve({
          ok: true,
          status: 200,
          statusText: 'OK',
          json: () => Promise.resolve(body),
        } as unknown as Response);
      }) as unknown as typeof fetch;

      const written: string[] = [];
      const controller = new AbortController();

      const done = runObserver({
        apiBaseUrl: 'https://api.example.org',
        adminKey: 'wbk_secret',
        fetchImpl: impl,
        intervalMs: 60_000,
        now: () => OBSERVED_AT,
        signal: controller.signal,
        onPoll: ({ rows, header }) => {
          if (header) written.push(header);
          for (const r of rows) written.push(r);
        },
      });

      // Flush the immediate first tick.
      await jest.advanceTimersByTimeAsync(0);

      expect(call).toBe(1);
      expect(written[0]).toBe(CSV_HEADER);
      expect(written[1].split(',')[1]).toBe('11');

      // Advance one minute; the second tick fires without another header.
      await jest.advanceTimersByTimeAsync(60_000);

      expect(call).toBe(2);
      // Only one header line was written across the whole run.
      expect(written.filter(l => l === CSV_HEADER)).toHaveLength(1);
      expect(written[2].split(',')[1]).toBe('12');

      controller.abort();
      await done;
    } finally {
      jest.useRealTimers();
    }
  });

  test('routes fetch errors to onError and keeps polling', async () => {
    jest.useFakeTimers();
    try {
      const seen: unknown[] = [];
      let call = 0;
      const impl: typeof fetch = ((): Promise<Response> => {
        call++;
        if (call === 1) return Promise.reject(new Error('boom'));
        const body: BeaconsResponse = { items: [beacon()], staleAfterS: 45 };
        return Promise.resolve({
          ok: true,
          status: 200,
          statusText: 'OK',
          json: () => Promise.resolve(body),
        } as unknown as Response);
      }) as unknown as typeof fetch;

      const written: string[] = [];
      const controller = new AbortController();

      const done = runObserver({
        apiBaseUrl: 'https://api.example.org',
        adminKey: 'wbk_secret',
        fetchImpl: impl,
        intervalMs: 60_000,
        now: () => OBSERVED_AT,
        signal: controller.signal,
        onPoll: ({ rows, header }) => {
          if (header) written.push(header);
          for (const r of rows) written.push(r);
        },
        onError: err => seen.push(err),
      });

      await jest.advanceTimersByTimeAsync(0);

      expect(seen).toHaveLength(1);
      expect((seen[0] as Error).message).toBe('boom');
      expect(written).toHaveLength(0);

      await jest.advanceTimersByTimeAsync(60_000);

      // The second tick recovered and produced a header + one row.
      expect(written[0]).toBe(CSV_HEADER);
      expect(written).toHaveLength(2);

      controller.abort();
      await done;
    } finally {
      jest.useRealTimers();
    }
  });
});
