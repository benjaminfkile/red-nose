// Enrollment HTTP calls (red-nose.md 9.1 QR path and 9.2 manual path).
// The key exists in JS memory only between the exchange and saveEnrollment.

export type EnrollResponse = {
  apiBaseUrl: string;
  hubUrl: string;
  ingestChannel: string;
  beaconId: number;
  name: string;
  key: string;
};

export class EnrollError extends Error {
  readonly code: string;
  readonly requestId: string | null;
  readonly status: number;
  constructor(status: number, code: string, message: string, requestId: string | null) {
    super(message);
    this.status = status;
    this.code = code;
    this.requestId = requestId;
  }
}

async function readError(res: Response): Promise<EnrollError> {
  let code = 'http_error';
  let msg = `HTTP ${res.status}`;
  let requestId: string | null = null;
  try {
    const body = (await res.json()) as { code?: string; message?: string; requestId?: string };
    if (typeof body.code === 'string') code = body.code;
    if (typeof body.message === 'string') msg = body.message;
    if (typeof body.requestId === 'string') requestId = body.requestId;
  } catch {
    // ignore; keep defaults
  }
  return new EnrollError(res.status, code, msg, requestId);
}

function trimBase(apiBaseUrl: string): string {
  return apiBaseUrl.endsWith('/') ? apiBaseUrl.slice(0, -1) : apiBaseUrl;
}

// POST /beacons/enroll { token }
export async function exchange(
  apiBaseUrl: string,
  token: string,
  fetchImpl: typeof fetch = fetch,
): Promise<EnrollResponse> {
  const url = `${trimBase(apiBaseUrl)}/beacons/enroll`;
  const res = await fetchImpl(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ token }),
  });
  if (!res.ok) throw await readError(res);
  return (await res.json()) as EnrollResponse;
}

// GET /beacons/me with the typed key; the manual path (red-nose.md 9.2).
export async function fetchMe(
  apiBaseUrl: string,
  key: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Omit<EnrollResponse, 'key'>> {
  const url = `${trimBase(apiBaseUrl)}/beacons/me`;
  const res = await fetchImpl(url, {
    method: 'GET',
    headers: { 'X-Beacon-Key': key, Accept: 'application/json' },
  });
  if (!res.ok) throw await readError(res);
  return (await res.json()) as Omit<EnrollResponse, 'key'>;
}
