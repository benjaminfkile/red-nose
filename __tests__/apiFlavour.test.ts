// The flavour origin rule (red-nose.md 9.1, contracts 8.5); the same cases as
// android ApiFlavourTest.kt.

import { apiOrigin, apiAllowedForFlavour } from '../src/enroll/apiFlavour';

const PROD = 'https://api.example.org';

describe('apiOrigin', () => {
  test('is case-insensitive for scheme and host', () => {
    expect(apiOrigin('HTTPS://API.Example.ORG')).toBe('https://api.example.org');
  });

  test('drops a default port and keeps another', () => {
    expect(apiOrigin('https://api.example.org:443')).toBe('https://api.example.org');
    expect(apiOrigin('http://api.example.org:80')).toBe('http://api.example.org');
    expect(apiOrigin('https://api.example.org:8443')).toBe('https://api.example.org:8443');
    expect(apiOrigin('http://api.example.org:5000')).toBe('http://api.example.org:5000');
  });

  test('ignores a trailing slash and a path', () => {
    expect(apiOrigin('https://api.example.org/')).toBe('https://api.example.org');
    expect(apiOrigin('https://api.example.org/v1/x?q=1#f')).toBe('https://api.example.org');
  });

  test('is null for a malformed string', () => {
    expect(apiOrigin('api.example.org')).toBeNull();
    expect(apiOrigin('https://')).toBeNull();
    expect(apiOrigin('https://host:abc')).toBeNull();
  });
});

describe('apiAllowedForFlavour', () => {
  test('prod admits only the prod origin', () => {
    expect(apiAllowedForFlavour('https://api.example.org/', 'prod', PROD)).toBe(true);
    expect(apiAllowedForFlavour('https://api-dev.example.org', 'prod', PROD)).toBe(false);
    expect(apiAllowedForFlavour('http://api.example.org', 'prod', PROD)).toBe(false);
  });

  test('dev admits every origin but the prod one', () => {
    expect(apiAllowedForFlavour('https://api.example.org', 'dev', PROD)).toBe(false);
    expect(apiAllowedForFlavour('https://api-dev.example.org', 'dev', PROD)).toBe(true);
    expect(apiAllowedForFlavour('http://lan.example.org:5000', 'dev', PROD)).toBe(true);
  });

  test('a malformed api is never admitted', () => {
    expect(apiAllowedForFlavour('api.example.org', 'prod', PROD)).toBe(false);
    expect(apiAllowedForFlavour('api.example.org', 'dev', PROD)).toBe(false);
    expect(apiAllowedForFlavour('https://host:abc', 'dev', PROD)).toBe(false);
  });
});
