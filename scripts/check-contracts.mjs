#!/usr/bin/env node
// Fails when this repo's vendored contracts/ differs from the wmsfo-api
// contracts/ at the commit named in CONTRACTS_SHA (contracts 13).

import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = 'benjaminfkile/wmsfo-api';
const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const CONTRACTS_DIR = join(ROOT, 'contracts');

async function main() {
  const sha = (await readFile(join(ROOT, 'CONTRACTS_SHA'), 'utf8')).trim();
  if (!/^[0-9a-f]{40}$/.test(sha)) {
    throw new Error(`CONTRACTS_SHA is not a 40-char hex commit: "${sha}"`);
  }

  const tmp = await mkdtemp(join(tmpdir(), 'rednose-contracts-'));
  try {
    const tarballName = 'api.tar.gz';
    const extractName = 'api';
    await downloadTarball(sha, join(tmp, tarballName));
    await mkdir(join(tmp, extractName), { recursive: true });
    await extractTarball(tmp, tarballName, extractName);
    const upstreamContracts = await findContractsDir(join(tmp, extractName));

    const local = await hashTree(CONTRACTS_DIR);
    const upstream = await hashTree(upstreamContracts);
    const diffs = diff(local, upstream);
    if (diffs.length) {
      console.error(`contracts/ differs from ${REPO}@${sha}:`);
      for (const line of diffs) console.error(`  ${line}`);
      process.exit(1);
    }
    console.log(`contracts/ matches ${REPO}@${sha} (${local.size} files)`);
  } finally {
    await rm(tmp, { recursive: true, force: true });
  }
}

async function downloadTarball(sha, dest) {
  const url = `https://api.github.com/repos/${REPO}/tarball/${sha}`;
  const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'red-nose-contracts-check' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const res = await fetch(url, { headers, redirect: 'follow' });
  if (!res.ok) throw new Error(`GET ${url} -> ${res.status} ${res.statusText}`);
  await writeFile(dest, Buffer.from(await res.arrayBuffer()));
}

// The tar CLI reads a source path that starts with `<letter>:` as a remote
// host (`user@host:path`). On Windows CI the temp dir is a drive-letter path,
// so we spawn tar with cwd set to the temp directory and hand it relative
// paths for both the archive and the destination.
function extractTarball(cwd, tarballName, destName) {
  return new Promise((resolve, reject) => {
    const child = spawn('tar', ['-xzf', tarballName, '-C', destName], { cwd, stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', (code) => (code === 0 ? resolve() : reject(new Error(`tar exited ${code}`))));
  });
}

async function findContractsDir(root) {
  // GitHub tarballs unpack into a single `<owner>-<repo>-<shortsha>/` directory.
  const entries = await readdir(root, { withFileTypes: true });
  const top = entries.find((e) => e.isDirectory());
  if (!top) throw new Error(`no directory found in extracted tarball at ${root}`);
  const dir = join(root, top.name, 'contracts');
  const st = await stat(dir).catch(() => null);
  if (!st?.isDirectory()) throw new Error(`no contracts/ in ${REPO} at this commit`);
  return dir;
}

async function hashTree(dir) {
  const files = new Map();
  await walk(dir, dir, files);
  return files;
}

async function walk(root, current, out) {
  const entries = await readdir(current, { withFileTypes: true });
  for (const entry of entries) {
    const full = join(current, entry.name);
    if (entry.isDirectory()) {
      await walk(root, full, out);
      continue;
    }
    if (!entry.isFile()) continue;
    const rel = relative(root, full).split(sep).join('/');
    const bytes = await readFile(full);
    const normalized = normalizeLineEndings(bytes);
    out.set(rel, createHash('sha256').update(normalized).digest('hex'));
  }
}

// Normalize CRLF and lone CR to LF so a Windows checkout with core.autocrlf
// reads as equal to the upstream Linux bytes.
function normalizeLineEndings(buf) {
  const out = Buffer.alloc(buf.length);
  let j = 0;
  for (let i = 0; i < buf.length; i++) {
    const b = buf[i];
    if (b === 0x0d) {
      out[j++] = 0x0a;
      if (i + 1 < buf.length && buf[i + 1] === 0x0a) i++;
    } else {
      out[j++] = b;
    }
  }
  return out.subarray(0, j);
}

function diff(local, upstream) {
  const out = [];
  const seen = new Set();
  for (const [path, hash] of local) {
    seen.add(path);
    const other = upstream.get(path);
    if (other === undefined) out.push(`extra:   ${path}`);
    else if (other !== hash) out.push(`changed: ${path}`);
  }
  for (const path of upstream.keys()) {
    if (!seen.has(path)) out.push(`missing: ${path}`);
  }
  return out.sort();
}

main().catch((err) => {
  console.error(err.stack || err.message || String(err));
  process.exit(1);
});
