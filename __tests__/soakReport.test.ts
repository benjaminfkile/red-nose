// The soak report reads the observer's CSV and prints a summary per beacon
// with a PASS or FAIL line (red-nose.md 17, docs/soak/README.md).

import { CSV_HEADER } from '../tools/soak-observer/observer';
import {
  formatReport,
  formatSummary,
  parseCsv,
  summarize,
  type BeaconSummary,
} from '../tools/soak-observer/report';

// Two beacons, interleaved as the observer writes them.  Beacon 22 has a
// quoted name with a comma, one empty age, one age of 90, a restart count
// that moves from 0 to 1, one row reconnecting, and a battery of 80.
// Beacon 7 stays within 60 s and goes down to a battery of 55.
const CSV = [
  CSV_HEADER,
  '2026-10-27T10:00:00.000Z,22,"phone, main",12,connected,0,80,true',
  '2026-10-27T10:00:00.000Z,7,phone-b,5,connected,3,61,false',
  '2026-10-27T10:01:00.000Z,22,"phone, main",,connected,0,80,true',
  '2026-10-27T10:01:00.000Z,7,phone-b,60,connected,3,,false',
  '2026-10-27T10:02:00.000Z,22,"phone, main",90,reconnecting,1,80,true',
  '2026-10-27T10:02:00.000Z,7,phone-b,20,connected,3,55,false',
  '2026-10-27T10:03:00.000Z,22,"phone, main",30,connected,1,80,true',
  '',
].join('\n');

describe('parseCsv', () => {
  test('reads every row after the header, quoted fields included', () => {
    const rows = parseCsv(CSV);
    expect(rows).toHaveLength(7);
    expect(rows[0]).toEqual({
      observedAt: '2026-10-27T10:00:00.000Z',
      beaconId: 22,
      name: 'phone, main',
      heartbeatAgeS: 12,
      socketState: 'connected',
      serviceRestartCount: 0,
      batteryPercent: 80,
      charging: true,
    });
    expect(rows[2].heartbeatAgeS).toBeNull();
    expect(rows[3].batteryPercent).toBeNull();
    expect(rows[1].charging).toBe(false);
  });

  test('undoes doubled quotes and reads CRLF line ends', () => {
    const text = `${CSV_HEADER}\r\n2026-10-27T10:00:00.000Z,1,"say ""hi"", ok",3,connected,0,50,\r\n`;
    const rows = parseCsv(text);
    expect(rows).toHaveLength(1);
    expect(rows[0].name).toBe('say "hi", ok');
    expect(rows[0].charging).toBeNull();
  });

  test('an empty file or a header alone holds no rows', () => {
    expect(parseCsv('')).toEqual([]);
    expect(parseCsv(`${CSV_HEADER}\n`)).toEqual([]);
  });
});

describe('summarize', () => {
  const rows = parseCsv(CSV);

  test('one summary per beacon in id order with every field', () => {
    const summaries = summarize(rows);
    expect(summaries.map(s => s.beaconId)).toEqual([7, 22]);
    expect(summaries[0]).toEqual<BeaconSummary>({
      beaconId: 7,
      name: 'phone-b',
      firstAt: '2026-10-27T10:00:00.000Z',
      lastAt: '2026-10-27T10:02:00.000Z',
      rows: 3,
      maxHeartbeatAgeS: 60,
      maxHeartbeatAgeAt: '2026-10-27T10:01:00.000Z',
      rowsOver60S: 0,
      restartCountFirst: 3,
      restartCountLast: 3,
      rowsSocketNotConnected: 0,
      minBatteryPercent: 55,
    });
    expect(summaries[1]).toEqual<BeaconSummary>({
      beaconId: 22,
      name: 'phone, main',
      firstAt: '2026-10-27T10:00:00.000Z',
      lastAt: '2026-10-27T10:03:00.000Z',
      rows: 4,
      maxHeartbeatAgeS: 90,
      maxHeartbeatAgeAt: '2026-10-27T10:02:00.000Z',
      rowsOver60S: 2,
      restartCountFirst: 0,
      restartCountLast: 1,
      rowsSocketNotConnected: 1,
      minBatteryPercent: 80,
    });
  });

  test('the beacon filter keeps one beacon, none when it has no rows', () => {
    const only = summarize(rows, 22);
    expect(only).toHaveLength(1);
    expect(only[0].beaconId).toBe(22);
    expect(only[0].rows).toBe(4);
    expect(summarize(rows, 99)).toEqual([]);
  });

  test('the name is the last row\'s', () => {
    const renamed = parseCsv(
      [
        CSV_HEADER,
        '2026-10-27T10:00:00.000Z,5,old,10,connected,0,90,true',
        '2026-10-27T10:01:00.000Z,5,new,10,connected,0,90,true',
      ].join('\n'),
    );
    expect(summarize(renamed)[0].name).toBe('new');
  });

  test('ages, batteries, and an empty socket state with no values', () => {
    const blank = parseCsv(
      [CSV_HEADER, '2026-10-27T10:00:00.000Z,5,phone,,,,,'].join('\n'),
    );
    const [s] = summarize(blank);
    expect(s.maxHeartbeatAgeS).toBeNull();
    expect(s.maxHeartbeatAgeAt).toBeNull();
    expect(s.rowsOver60S).toBe(1);
    expect(s.restartCountFirst).toBeNull();
    expect(s.restartCountLast).toBeNull();
    expect(s.rowsSocketNotConnected).toBe(1);
    expect(s.minBatteryPercent).toBeNull();
  });
});

describe('formatSummary', () => {
  const [b7, b22] = summarize(parseCsv(CSV));

  test('a beacon within 60 s throughout passes', () => {
    const text = formatSummary(b7);
    const lines = text.split('\n');
    expect(lines[0]).toBe('Beacon 7 (phone-b)');
    expect(text).toContain('2026-10-27T10:00:00.000Z to 2026-10-27T10:02:00.000Z, 3 rows');
    expect(text).toContain('largest heartbeat age: 60 s at 2026-10-27T10:01:00.000Z');
    expect(text).toContain('service restart count: 3 at start, 3 at end');
    expect(text).toContain('lowest battery: 55 percent');
    expect(lines.filter(l => /^(PASS|FAIL)/.test(l))).toEqual(['PASS']);
  });

  test('a beacon past 60 s fails with the reason', () => {
    const text = formatSummary(b22);
    const verdict = text.split('\n').filter(l => /^(PASS|FAIL)/.test(l));
    expect(verdict).toHaveLength(1);
    expect(verdict[0]).toMatch(/^FAIL: /);
    expect(verdict[0]).toContain('largest heartbeat age 90 s is over 60 s');
    expect(verdict[0]).toContain('2 rows over 60 s');
    expect(text).toContain('rows over 60 s: 2');
    expect(text).toContain('rows with the socket not connected: 1');
    expect(text).toContain('service restart count: 0 at start, 1 at end');
  });

  test('a beacon that never reported an age fails', () => {
    const [s] = summarize(parseCsv(`${CSV_HEADER}\n2026-10-27T10:00:00.000Z,5,phone,,,,,`));
    const text = formatSummary(s);
    expect(text).toContain('largest heartbeat age: none');
    expect(text).toContain('lowest battery: none');
    expect(text).toMatch(/^FAIL: no heartbeat age recorded/m);
  });

  test('the report puts a blank line between beacons', () => {
    const blocks = formatReport([b7, b22]).split('\n\n');
    expect(blocks).toEqual([formatSummary(b7), formatSummary(b22)]);
  });
});
