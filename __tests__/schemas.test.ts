// Validate the wire bodies against the vendored contracts schemas
// (contracts 13; red-nose.md 15).  A POST /locations body is the fix minus
// seqLocal; the POST /beacons/heartbeat body is the Heartbeat object.

import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const locationSchema = require('../contracts/schema/location.schema.json');
// eslint-disable-next-line @typescript-eslint/no-var-requires
const heartbeatSchema = require('../contracts/schema/heartbeat.schema.json');
// eslint-disable-next-line @typescript-eslint/no-var-requires
const locationFixture = require('../contracts/fixtures/location.json');
// eslint-disable-next-line @typescript-eslint/no-var-requires
const heartbeatFixture = require('../contracts/fixtures/heartbeat.json');

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);

describe('location.schema.json', () => {
  const validate = ajv.compile(locationSchema);

  test('accepts the canonical fixture', () => {
    const ok = validate(locationFixture);
    expect(validate.errors ?? null).toBeNull();
    expect(ok).toBe(true);
  });

  test('accepts a fix with every optional field null', () => {
    const body = {
      lat: 46.87,
      lng: -114.0,
      recordedAt: '2026-12-22T01:31:07.412Z',
      speedMps: null,
      altitudeM: null,
      headingDeg: null,
      accuracyM: null,
    };
    expect(validate(body)).toBe(true);
  });

  test('rejects an unknown property', () => {
    const body = { ...locationFixture, seqLocal: 12 };
    expect(validate(body)).toBe(false);
  });

  test('rejects a missing lat / lng type mismatch', () => {
    const body = { ...locationFixture, lat: 'north' };
    expect(validate(body)).toBe(false);
  });

  test('rejects a non-RFC3339 recordedAt', () => {
    const body = { ...locationFixture, recordedAt: 'yesterday' };
    expect(validate(body)).toBe(false);
  });
});

describe('heartbeat.schema.json', () => {
  const validate = ajv.compile(heartbeatSchema);

  test('accepts the canonical fixture', () => {
    const ok = validate(heartbeatFixture);
    expect(validate.errors ?? null).toBeNull();
    expect(ok).toBe(true);
  });

  test('accepts a body with every group null', () => {
    const body = {
      sentAt: '2026-12-22T01:31:07.412Z',
      power: null,
      radio: null,
      gps: null,
      transport: null,
      process: null,
      identity: null,
    };
    expect(validate(body)).toBe(true);
  });

  test('rejects an unknown top-level key', () => {
    const body = { ...heartbeatFixture, unknown: 1 };
    expect(validate(body)).toBe(false);
  });

  test('rejects the wrong type on a leaf', () => {
    const body = {
      ...heartbeatFixture,
      power: { ...heartbeatFixture.power, batteryPercent: 'high' },
    };
    expect(validate(body)).toBe(false);
  });
});
