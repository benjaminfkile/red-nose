// Soak observer: poll GET /admin/beacons on a schedule and append one CSV row
// per beacon per poll (red-nose.md 17).  The service side of things owns the
// live telemetry; this tool just records what the API answers with, so the
// drill logs read the same numbers everyone else does.

export type SoakBeacon = {
  id: number;
  name: string;
  lastHeartbeatAt: string | null;
  telemetry: {
    health?: {
      batteryPercent?: number | null;
      socketState?: string | null;
    } | null;
    debug?: {
      process?: { serviceRestartCount?: number | null } | null;
      power?: { charging?: boolean | null } | null;
    } | null;
  } | null;
};

export type BeaconsResponse = { items: SoakBeacon[]; staleAfterS: number };

export type PollOptions = {
  apiBaseUrl: string;
  adminKey: string;
  fetchImpl?: typeof fetch;
  now?: () => Date;
};

export const CSV_HEADER =
  'observedAt,beaconId,name,heartbeatAgeS,socketState,serviceRestartCount,batteryPercent,charging';

function trimBase(apiBaseUrl: string): string {
  return apiBaseUrl.endsWith('/') ? apiBaseUrl.slice(0, -1) : apiBaseUrl;
}

function csvField(value: string | number | boolean | null | undefined): string {
  if (value === null || value === undefined) return '';
  const s = String(value);
  if (/[",\r\n]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

function heartbeatAgeSeconds(observedAt: Date, lastHeartbeatAt: string | null): number | null {
  if (!lastHeartbeatAt) return null;
  const t = Date.parse(lastHeartbeatAt);
  if (Number.isNaN(t)) return null;
  const ageMs = observedAt.getTime() - t;
  if (ageMs < 0) return 0;
  return Math.round(ageMs / 1000);
}

function isoZ(d: Date): string {
  // Millisecond RFC 3339 with a `Z` suffix, matching the rest of the wire.
  return d.toISOString();
}

export function formatCsvRow(observedAt: Date, b: SoakBeacon): string {
  const socketState = b.telemetry?.health?.socketState ?? null;
  const restart = b.telemetry?.debug?.process?.serviceRestartCount ?? null;
  const battery = b.telemetry?.health?.batteryPercent ?? null;
  const charging = b.telemetry?.debug?.power?.charging ?? null;
  return [
    csvField(isoZ(observedAt)),
    csvField(b.id),
    csvField(b.name),
    csvField(heartbeatAgeSeconds(observedAt, b.lastHeartbeatAt)),
    csvField(socketState),
    csvField(restart),
    csvField(battery),
    csvField(charging),
  ].join(',');
}

export async function pollBeacons(
  opts: PollOptions,
): Promise<{ observedAt: Date; rows: string[]; response: BeaconsResponse }> {
  const fetchImpl = opts.fetchImpl ?? fetch;
  const now = opts.now ?? (() => new Date());
  const url = `${trimBase(opts.apiBaseUrl)}/admin/beacons`;
  const res = await fetchImpl(url, {
    method: 'GET',
    headers: { 'X-Beacon-Key': opts.adminKey, Accept: 'application/json' },
  });
  if (!res.ok) {
    throw new Error(`GET ${url} -> ${res.status} ${res.statusText}`);
  }
  const body = (await res.json()) as BeaconsResponse;
  const observedAt = now();
  const rows = body.items.map(item => formatCsvRow(observedAt, item));
  return { observedAt, rows, response: body };
}

export type RunOptions = PollOptions & {
  intervalMs?: number;
  onPoll?: (result: { observedAt: Date; rows: string[]; header?: string }) => void | Promise<void>;
  onError?: (err: unknown) => void;
  signal?: AbortSignal;
};

export async function runObserver(opts: RunOptions): Promise<void> {
  const intervalMs = opts.intervalMs ?? 60_000;
  let wroteHeader = false;
  const tick = async () => {
    try {
      const result = await pollBeacons(opts);
      const header = wroteHeader ? undefined : CSV_HEADER;
      wroteHeader = true;
      await opts.onPoll?.({ observedAt: result.observedAt, rows: result.rows, header });
    } catch (err) {
      opts.onError?.(err);
    }
  };
  await tick();
  await new Promise<void>(resolve => {
    if (opts.signal?.aborted) {
      resolve();
      return;
    }
    const timer = setInterval(() => {
      void tick();
    }, intervalMs);
    opts.signal?.addEventListener('abort', () => {
      clearInterval(timer);
      resolve();
    });
  });
}
