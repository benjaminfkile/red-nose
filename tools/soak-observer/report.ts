// Soak report: read the observer's CSV and answer the soak question per
// beacon in a few lines (red-nose.md 17, docs/soak/README.md).  The report
// only reads what the observer wrote; it never talks to the API.

export type SoakRow = {
  observedAt: string;
  beaconId: number;
  name: string;
  heartbeatAgeS: number | null;
  socketState: string | null;
  serviceRestartCount: number | null;
  batteryPercent: number | null;
  charging: boolean | null;
};

export type BeaconSummary = {
  beaconId: number;
  name: string;
  firstAt: string;
  lastAt: string;
  rows: number;
  maxHeartbeatAgeS: number | null;
  maxHeartbeatAgeAt: string | null;
  rowsOver60S: number;
  restartCountFirst: number | null;
  restartCountLast: number | null;
  rowsSocketNotConnected: number;
  minBatteryPercent: number | null;
};

const MAX_HEARTBEAT_AGE_S = 60;

// Split CSV text into records of fields.  Fields may be quoted, with a doubled
// quote standing for one quote and commas or line breaks allowed inside, as
// the observer's csvField writes them.
function parseRecords(text: string): string[][] {
  const records: string[][] = [];
  let record: string[] = [];
  let field = '';
  let quoted = false;
  let i = 0;
  const endRecord = () => {
    record.push(field);
    field = '';
    if (record.length > 1 || record[0] !== '') records.push(record);
    record = [];
  };
  while (i < text.length) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i += 2;
          continue;
        }
        quoted = false;
      } else {
        field += c;
      }
      i++;
      continue;
    }
    if (c === '"') {
      quoted = true;
    } else if (c === ',') {
      record.push(field);
      field = '';
    } else if (c === '\r' || c === '\n') {
      endRecord();
      if (c === '\r' && text[i + 1] === '\n') i++;
    } else {
      field += c;
    }
    i++;
  }
  if (field !== '' || record.length > 0) endRecord();
  return records;
}

function numberOrNull(s: string | undefined): number | null {
  if (s === undefined || s.trim() === '') return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

function stringOrNull(s: string | undefined): string | null {
  return s === undefined || s === '' ? null : s;
}

function booleanOrNull(s: string | undefined): boolean | null {
  if (s === 'true') return true;
  if (s === 'false') return false;
  return null;
}

// Parse the observer's CSV, header line first.  Columns are found by their
// header name, so a reordered header still reads.
export function parseCsv(text: string): SoakRow[] {
  const records = parseRecords(text);
  if (records.length === 0) return [];
  const header = records[0].map(h => h.trim());
  const col = (record: string[], name: string): string | undefined => {
    const idx = header.indexOf(name);
    return idx < 0 ? undefined : record[idx];
  };
  const rows: SoakRow[] = [];
  for (const record of records.slice(1)) {
    const beaconId = numberOrNull(col(record, 'beaconId'));
    if (beaconId === null) continue;
    rows.push({
      observedAt: col(record, 'observedAt') ?? '',
      beaconId,
      name: col(record, 'name') ?? '',
      heartbeatAgeS: numberOrNull(col(record, 'heartbeatAgeS')),
      socketState: stringOrNull(col(record, 'socketState')),
      serviceRestartCount: numberOrNull(col(record, 'serviceRestartCount')),
      batteryPercent: numberOrNull(col(record, 'batteryPercent')),
      charging: booleanOrNull(col(record, 'charging')),
    });
  }
  return rows;
}

function summarizeOne(beaconId: number, rows: SoakRow[]): BeaconSummary {
  let maxHeartbeatAgeS: number | null = null;
  let maxHeartbeatAgeAt: string | null = null;
  let rowsOver60S = 0;
  let restartCountFirst: number | null = null;
  let restartCountLast: number | null = null;
  let rowsSocketNotConnected = 0;
  let minBatteryPercent: number | null = null;
  for (const r of rows) {
    const age = r.heartbeatAgeS;
    if (age !== null && (maxHeartbeatAgeS === null || age > maxHeartbeatAgeS)) {
      maxHeartbeatAgeS = age;
      maxHeartbeatAgeAt = r.observedAt;
    }
    if (age === null || age > MAX_HEARTBEAT_AGE_S) rowsOver60S++;
    if (r.serviceRestartCount !== null) {
      if (restartCountFirst === null) restartCountFirst = r.serviceRestartCount;
      restartCountLast = r.serviceRestartCount;
    }
    if (r.socketState !== 'connected') rowsSocketNotConnected++;
    const battery = r.batteryPercent;
    if (battery !== null && (minBatteryPercent === null || battery < minBatteryPercent)) {
      minBatteryPercent = battery;
    }
  }
  return {
    beaconId,
    name: rows[rows.length - 1].name,
    firstAt: rows[0].observedAt,
    lastAt: rows[rows.length - 1].observedAt,
    rows: rows.length,
    maxHeartbeatAgeS,
    maxHeartbeatAgeAt,
    rowsOver60S,
    restartCountFirst,
    restartCountLast,
    rowsSocketNotConnected,
    minBatteryPercent,
  };
}

// One summary per beacon in id order, rows taken in file order.  With a
// beaconId only that beacon is summarized (none when it has no rows).
export function summarize(rows: SoakRow[], beaconId?: number): BeaconSummary[] {
  const byBeacon = new Map<number, SoakRow[]>();
  for (const r of rows) {
    if (beaconId !== undefined && r.beaconId !== beaconId) continue;
    const list = byBeacon.get(r.beaconId);
    if (list) list.push(r);
    else byBeacon.set(r.beaconId, [r]);
  }
  return [...byBeacon.keys()]
    .sort((a, b) => a - b)
    .map(id => summarizeOne(id, byBeacon.get(id)!));
}

// The reasons a beacon fails the soak; empty when it passes.
export function failureReasons(s: BeaconSummary): string[] {
  const reasons: string[] = [];
  if (s.maxHeartbeatAgeS === null) {
    reasons.push('no heartbeat age recorded');
  } else if (s.maxHeartbeatAgeS > MAX_HEARTBEAT_AGE_S) {
    reasons.push(`largest heartbeat age ${s.maxHeartbeatAgeS} s is over ${MAX_HEARTBEAT_AGE_S} s`);
  }
  if (s.rowsOver60S > 0) {
    reasons.push(`${s.rowsOver60S} rows over ${MAX_HEARTBEAT_AGE_S} s or without an age`);
  }
  return reasons;
}

function orNone(v: number | null, unit = ''): string {
  return v === null ? 'none' : `${v}${unit}`;
}

export function formatSummary(s: BeaconSummary): string {
  const maxAge =
    s.maxHeartbeatAgeS === null
      ? 'none'
      : `${s.maxHeartbeatAgeS} s at ${s.maxHeartbeatAgeAt}`;
  const reasons = failureReasons(s);
  return [
    `Beacon ${s.beaconId} (${s.name})`,
    `  window: ${s.firstAt} to ${s.lastAt}, ${s.rows} rows`,
    `  largest heartbeat age: ${maxAge}`,
    `  rows over ${MAX_HEARTBEAT_AGE_S} s: ${s.rowsOver60S}`,
    `  service restart count: ${orNone(s.restartCountFirst)} at start, ${orNone(s.restartCountLast)} at end`,
    `  rows with the socket not connected: ${s.rowsSocketNotConnected}`,
    `  lowest battery: ${orNone(s.minBatteryPercent, ' percent')}`,
    reasons.length === 0 ? 'PASS' : `FAIL: ${reasons.join('; ')}`,
  ].join('\n');
}

// Every summary block, a blank line between them.
export function formatReport(summaries: BeaconSummary[]): string {
  return summaries.map(formatSummary).join('\n\n');
}
