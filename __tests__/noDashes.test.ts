// Guard test (R7 ac 891): every file under src/, __tests__/, android/app/src/,
// and provisioning/ must be free of the em dash (U+2014) and the en dash
// (U+2013).  R7 removed them from screen copy and comments; this test keeps
// them from creeping back in through a follow-up edit or paste.
//
// The regex is built from String.fromCharCode so the two forbidden
// characters never appear literally in this test file; otherwise the scan
// below would flag it.

import * as fs from 'fs';
import * as path from 'path';

const ROOT = path.resolve(__dirname, '..');
const SCAN_DIRS = [
  'src',
  '__tests__',
  path.join('android', 'app', 'src'),
  'provisioning',
];
const EN_DASH = String.fromCharCode(0x2013);
const EM_DASH = String.fromCharCode(0x2014);
const DASH_REGEX = new RegExp('[' + EN_DASH + EM_DASH + ']');

function walk(dir: string, out: string[]): void {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.isFile()) out.push(full);
  }
}

describe('no em or en dashes under scanned folders (R7 ac 891)', () => {
  for (const dir of SCAN_DIRS) {
    test(`${dir} is dash-free`, () => {
      const absDir = path.join(ROOT, dir);
      expect(fs.existsSync(absDir)).toBe(true);
      const files: string[] = [];
      walk(absDir, files);
      const hits: string[] = [];
      for (const f of files) {
        const body = fs.readFileSync(f, 'utf8');
        if (DASH_REGEX.test(body)) hits.push(path.relative(ROOT, f));
      }
      expect(hits).toEqual([]);
    });
  }
});
