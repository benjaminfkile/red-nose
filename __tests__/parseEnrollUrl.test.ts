// URL grammar tests for the QR code and the rednose:// deep link (red-nose.md 9.1).

import { parseEnrollUrl } from '../src/enroll/parseEnrollUrl';

const TOK = 'wet_' + 'A'.repeat(43);

describe('parseEnrollUrl', () => {
  test('accepts the canonical URL', () => {
    const r = parseEnrollUrl(
      `rednose://enroll?api=${encodeURIComponent('https://api.example.org')}&token=${TOK}`,
    );
    expect(r.apiBaseUrl).toBe('https://api.example.org');
    expect(r.token).toBe(TOK);
  });

  test('accepts token=then=api order', () => {
    const r = parseEnrollUrl(
      `rednose://enroll?token=${TOK}&api=${encodeURIComponent('https://api.example.org')}`,
    );
    expect(r.apiBaseUrl).toBe('https://api.example.org');
    expect(r.token).toBe(TOK);
  });

  test('accepts http api URL only when allowHttp is true', () => {
    const url = `rednose://enroll?api=${encodeURIComponent('http://10.0.2.2:5000')}&token=${TOK}`;
    expect(() => parseEnrollUrl(url)).toThrow('not an enrollment code');
    const r = parseEnrollUrl(url, { allowHttp: true });
    expect(r.apiBaseUrl).toBe('http://10.0.2.2:5000');
  });

  test('rejects a scheme other than rednose', () => {
    expect(() => parseEnrollUrl(
      `wmsfo://enroll?api=${encodeURIComponent('https://api.example.org')}&token=${TOK}`,
    )).toThrow('not an enrollment code');
    expect(() => parseEnrollUrl(
      `https://enroll?api=${encodeURIComponent('https://api.example.org')}&token=${TOK}`,
    )).toThrow('not an enrollment code');
  });

  test('rejects a host other than enroll', () => {
    expect(() => parseEnrollUrl(
      `rednose://enrol?api=${encodeURIComponent('https://api.example.org')}&token=${TOK}`,
    )).toThrow('not an enrollment code');
    expect(() => parseEnrollUrl(
      `rednose://other?api=${encodeURIComponent('https://api.example.org')}&token=${TOK}`,
    )).toThrow('not an enrollment code');
  });

  test('rejects a missing query string', () => {
    expect(() => parseEnrollUrl('rednose://enroll')).toThrow('not an enrollment code');
    expect(() => parseEnrollUrl('rednose://enroll?')).toThrow('not an enrollment code');
  });

  test('rejects a missing api or token', () => {
    expect(() => parseEnrollUrl(`rednose://enroll?token=${TOK}`)).toThrow('not an enrollment code');
    expect(() => parseEnrollUrl(
      `rednose://enroll?api=${encodeURIComponent('https://api.example.org')}`,
    )).toThrow('not an enrollment code');
  });

  test('rejects extra query parameters', () => {
    expect(() => parseEnrollUrl(
      `rednose://enroll?api=${encodeURIComponent('https://api.example.org')}&token=${TOK}&extra=x`,
    )).toThrow('not an enrollment code');
  });

  test('rejects a duplicate parameter', () => {
    expect(() => parseEnrollUrl(
      `rednose://enroll?api=${encodeURIComponent('https://api.example.org')}&api=${encodeURIComponent('https://other')}&token=${TOK}`,
    )).toThrow('not an enrollment code');
  });

  test('rejects a token that does not match the grammar', () => {
    const cases = [
      'wet_short',
      'wet_' + 'A'.repeat(42),
      'wet_' + 'A'.repeat(44),
      'wet_' + '!'.repeat(43),
      'wet_' + 'A'.repeat(43) + '$',
      'foo_' + 'A'.repeat(43),
      'WET_' + 'A'.repeat(43),
    ];
    for (const bad of cases) {
      expect(() =>
        parseEnrollUrl(`rednose://enroll?api=${encodeURIComponent('https://api.example.org')}&token=${bad}`),
      ).toThrow('not an enrollment code');
    }
  });

  test('rejects a non-https API URL by default', () => {
    expect(() => parseEnrollUrl(
      `rednose://enroll?api=${encodeURIComponent('http://api.example.org')}&token=${TOK}`,
    )).toThrow('not an enrollment code');
    expect(() => parseEnrollUrl(
      `rednose://enroll?api=${encodeURIComponent('ftp://api.example.org')}&token=${TOK}`,
    )).toThrow('not an enrollment code');
  });

  test('rejects an empty api URL', () => {
    expect(() => parseEnrollUrl(
      `rednose://enroll?api=&token=${TOK}`,
    )).toThrow('not an enrollment code');
  });

  test('rejects a URL with a fragment', () => {
    expect(() => parseEnrollUrl(
      `rednose://enroll?api=${encodeURIComponent('https://api.example.org')}&token=${TOK}#f`,
    )).toThrow('not an enrollment code');
  });

  test('rejects malformed percent encoding', () => {
    expect(() => parseEnrollUrl(
      `rednose://enroll?api=%ZZ&token=${TOK}`,
    )).toThrow('not an enrollment code');
  });

  test('rejects an empty input', () => {
    expect(() => parseEnrollUrl('')).toThrow('not an enrollment code');
    expect(() => parseEnrollUrl('   ')).toThrow('not an enrollment code');
  });

  test('rejects a non-string input', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(() => parseEnrollUrl(null as any)).toThrow('not an enrollment code');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(() => parseEnrollUrl(undefined as any)).toThrow('not an enrollment code');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(() => parseEnrollUrl(42 as any)).toThrow('not an enrollment code');
  });

  test('rejects a URL that omits ://', () => {
    expect(() => parseEnrollUrl(
      `rednose:enroll?api=${encodeURIComponent('https://api.example.org')}&token=${TOK}`,
    )).toThrow('not an enrollment code');
  });

  test('rejects a URL whose param has no =', () => {
    expect(() => parseEnrollUrl(`rednose://enroll?api&token=${TOK}`)).toThrow('not an enrollment code');
  });
});
