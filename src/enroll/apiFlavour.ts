// Which API a flavour enrolls against (red-nose.md 9.1, contracts 8.5).  The
// prod flavour admits only the origin of REDNOSE_PROD_API_BASE_URL; every other
// flavour admits any other origin and never that one.  Parsed by hand (no
// global URL class); mirrors android ApiFlavour.kt.

const DEFAULT_PORTS: Record<string, number> = { http: 80, https: 443 };

/**
 * scheme://host[:port], lowercased, the port only when it is not the scheme's
 * default; null for a malformed string.  A path, query, or fragment is ignored.
 */
export function apiOrigin(url: string): string | null {
  if (typeof url !== 'string') return null;
  const sep = url.indexOf('://');
  if (sep <= 0) return null;
  const scheme = url.slice(0, sep).toLowerCase();
  const rest = url.slice(sep + 3);
  let end = rest.length;
  for (const c of ['/', '?', '#']) {
    const i = rest.indexOf(c);
    if (i >= 0 && i < end) end = i;
  }
  const authority = rest.slice(0, end);
  const bracket = authority.lastIndexOf(']');
  const colon = authority.lastIndexOf(':');
  let host = authority;
  let port: number | null = null;
  if (colon > bracket) {
    host = authority.slice(0, colon);
    const digits = authority.slice(colon + 1);
    if (!/^[0-9]+$/.test(digits)) return null;
    port = Number(digits);
    // Kotlin's toIntOrNull fails past Int.MAX_VALUE; match it.
    if (port > 2147483647) return null;
  }
  if (host.length === 0) return null;
  const suffix = port == null || port === DEFAULT_PORTS[scheme] ? '' : `:${port}`;
  return `${scheme}://${host.toLowerCase()}${suffix}`;
}

export function apiAllowedForFlavour(
  apiBaseUrl: string,
  flavour: string,
  prodApiBaseUrl: string,
): boolean {
  const api = apiOrigin(apiBaseUrl);
  const prod = apiOrigin(prodApiBaseUrl);
  if (api == null || prod == null) return false;
  return flavour === 'prod' ? api === prod : api !== prod;
}
