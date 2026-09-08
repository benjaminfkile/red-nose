// URL grammar for the QR code and the rednose:// deep link (red-nose.md 9.1):
//   rednose://enroll?api=<encoded https url>&token=<wet_ token>
// The token matches ^wet_[A-Za-z0-9_-]{43}$; the API URL must be https (or http
// in the dev flavour).  Anything else is rejected.

export type ParsedEnrollUrl = {
  apiBaseUrl: string;
  token: string;
};

export type ParseOptions = {
  /** When true, http:// is accepted for the api URL (dev flavour, red-nose.md 15). */
  allowHttp?: boolean;
};

const TOKEN_RE = /^wet_[A-Za-z0-9_-]{43}$/;

export function parseEnrollUrl(raw: string, opts: ParseOptions = {}): ParsedEnrollUrl {
  if (typeof raw !== 'string') throw new Error('not an enrollment code');
  const value = raw.trim();
  if (value.length === 0) throw new Error('not an enrollment code');

  // scheme://host?query -- no path segments, no fragment
  const schemeIdx = value.indexOf('://');
  if (schemeIdx < 0) throw new Error('not an enrollment code');
  const scheme = value.slice(0, schemeIdx).toLowerCase();
  if (scheme !== 'rednose') throw new Error('not an enrollment code');
  const rest = value.slice(schemeIdx + 3);
  if (rest.indexOf('#') >= 0) throw new Error('not an enrollment code');

  const q = rest.indexOf('?');
  if (q < 0) throw new Error('not an enrollment code');
  const host = rest.slice(0, q);
  const query = rest.slice(q + 1);
  if (host.toLowerCase() !== 'enroll') throw new Error('not an enrollment code');
  if (query.length === 0) throw new Error('not an enrollment code');

  const params: Record<string, string> = {};
  for (const pair of query.split('&')) {
    if (pair.length === 0) throw new Error('not an enrollment code');
    const eq = pair.indexOf('=');
    if (eq < 0) throw new Error('not an enrollment code');
    const k = pair.slice(0, eq);
    const v = pair.slice(eq + 1);
    if (k.length === 0) throw new Error('not an enrollment code');
    if (Object.prototype.hasOwnProperty.call(params, k)) {
      throw new Error('not an enrollment code');
    }
    try {
      params[k] = decodeURIComponent(v);
    } catch {
      throw new Error('not an enrollment code');
    }
  }

  const keys = Object.keys(params);
  if (keys.length !== 2 || !('api' in params) || !('token' in params)) {
    throw new Error('not an enrollment code');
  }

  const apiBaseUrl = params.api;
  const token = params.token;

  if (!TOKEN_RE.test(token)) throw new Error('not an enrollment code');
  if (!isAllowedApiUrl(apiBaseUrl, opts.allowHttp === true)) {
    throw new Error('not an enrollment code');
  }
  return { apiBaseUrl, token };
}

function isAllowedApiUrl(url: string, allowHttp: boolean): boolean {
  if (typeof url !== 'string' || url.length === 0) return false;
  const lower = url.toLowerCase();
  if (lower.startsWith('https://')) return url.length > 'https://'.length;
  if (allowHttp && lower.startsWith('http://')) return url.length > 'http://'.length;
  return false;
}
