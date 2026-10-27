#!/usr/bin/env node
// CLI entry, run through tsx (red-nose.md 17, docs/soak/README.md):
//
//   npm run soak:observe                     observe
//   npm run soak:report -- <csv> [beaconId]  report
//
// Observe reads the API base URL, the admin key, the output CSV path, and
// optionally SOAK_BEACON_IDS from environment variables and appends one CSV
// row per beacon per minute.  Report reads a CSV the observer wrote and
// prints a summary per beacon with a PASS or FAIL line; it exits 0 whatever
// the verdict, and 2 when the file is missing or holds no rows.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { CSV_HEADER, parseBeaconIds, runObserver } from './observer';
import { formatReport, parseCsv, summarize } from './report';

function required(name: string): string {
  const v = process.env[name];
  if (!v) {
    process.stderr.write(`missing env var: ${name}\n`);
    process.exit(2);
  }
  return v;
}

function usage(message: string): never {
  process.stdout.write(`${message}\n`);
  process.exit(2);
}

function report(args: string[]): void {
  const [csvPath, idArg] = args;
  if (!csvPath) usage('usage: soak:report -- <csv> [beaconId]');
  let beaconId: number | undefined;
  if (idArg !== undefined) {
    if (!/^\d+$/.test(idArg.trim())) usage(`not a beacon id: ${idArg}`);
    beaconId = Number(idArg.trim());
  }
  if (!fs.existsSync(csvPath) || !fs.statSync(csvPath).isFile()) {
    usage(`no such file: ${csvPath}`);
  }
  const rows = parseCsv(fs.readFileSync(csvPath, 'utf8'));
  if (rows.length === 0) usage(`no rows in ${csvPath}`);
  const summaries = summarize(rows, beaconId);
  if (summaries.length === 0) usage(`no rows for beacon ${beaconId} in ${csvPath}`);
  process.stdout.write(formatReport(summaries) + '\n');
}

async function observe(): Promise<void> {
  const apiBaseUrl = required('SOAK_API_BASE_URL');
  const adminKey = required('SOAK_ADMIN_KEY');
  const csvPath = required('SOAK_CSV_PATH');
  const intervalMs = Number(process.env.SOAK_INTERVAL_MS ?? 60_000);
  let beaconIds: number[];
  try {
    beaconIds = parseBeaconIds(process.env.SOAK_BEACON_IDS);
  } catch (err) {
    process.stderr.write(`SOAK_BEACON_IDS: ${(err as Error).message}\n`);
    process.exit(2);
  }

  fs.mkdirSync(path.dirname(path.resolve(csvPath)), { recursive: true });

  const controller = new AbortController();
  const shutdown = () => controller.abort();
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  const stream = fs.createWriteStream(csvPath, { flags: 'a' });
  const isEmpty = !fs.existsSync(csvPath) || fs.statSync(csvPath).size === 0;
  let headerWritten = !isEmpty;

  await runObserver({
    apiBaseUrl,
    adminKey,
    beaconIds,
    intervalMs,
    signal: controller.signal,
    onPoll: ({ rows }) => {
      if (!headerWritten) {
        stream.write(CSV_HEADER + '\n');
        headerWritten = true;
      }
      for (const row of rows) stream.write(row + '\n');
    },
    onError: err => {
      process.stderr.write(`poll failed: ${(err as Error).message ?? String(err)}\n`);
    },
  });

  stream.end();
}

async function main(): Promise<void> {
  const [command, ...rest] = process.argv.slice(2);
  if (command === 'report') {
    report(rest);
    return;
  }
  await observe();
}

main().catch(err => {
  process.stderr.write(`${(err as Error).stack ?? String(err)}\n`);
  process.exit(1);
});
