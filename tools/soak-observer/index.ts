#!/usr/bin/env node
// CLI entry: node --loader tsx tools/soak-observer/index.ts, or a plain node
// invocation after `tsc` emits it.  Reads the API base URL, the admin key,
// and an output CSV path from environment variables and appends one CSV row
// per beacon per minute (red-nose.md 17).

import * as fs from 'node:fs';
import * as path from 'node:path';
import { CSV_HEADER, runObserver } from './observer';

function required(name: string): string {
  const v = process.env[name];
  if (!v) {
    process.stderr.write(`missing env var: ${name}\n`);
    process.exit(2);
  }
  return v;
}

async function main(): Promise<void> {
  const apiBaseUrl = required('SOAK_API_BASE_URL');
  const adminKey = required('SOAK_ADMIN_KEY');
  const csvPath = required('SOAK_CSV_PATH');
  const intervalMs = Number(process.env.SOAK_INTERVAL_MS ?? 60_000);

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

main().catch(err => {
  process.stderr.write(`${(err as Error).stack ?? String(err)}\n`);
  process.exit(1);
});
