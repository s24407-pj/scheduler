import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STAFF_EMAIL = __ENV.STAFF_EMAIL || 'gabinet@kacosmetology.pl';
const STAFF_PASSWORD = __ENV.STAFF_PASSWORD || 'admin123';
const STAFF_COMPANY_ID = (__ENV.STAFF_COMPANY_ID || '00000000-0000-0000-0000-000000000001').trim();

/** Standard cookie/CSRF login. k6's cookie jar retains HttpOnly cookies as an HTTP client. */
export function loginStaff() {
  const before = http.get(`${BASE_URL}/api/auth/csrf`, { timeout: '10s' });
  if (before.status !== 200) return null;
  let res = http.post(
    `${BASE_URL}/api/auth/login-staff`,
    JSON.stringify({ email: STAFF_EMAIL, password: STAFF_PASSWORD, companyId: STAFF_COMPANY_ID }),
    {
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': before.json('token') },
      timeout: '10s',
    }
  );
  if (res.status === 200 && res.json('status') === 'TOTP_SETUP_REQUIRED') {
    throw new Error('Enroll the performance-test staff account with an Authenticator before running k6.');
  }
  if (res.status === 200 && res.json('status') === 'TOTP_REQUIRED') {
    const secret = (__ENV.STAFF_TOTP_SECRET || '').trim().toUpperCase();
    if (!secret) throw new Error('Set STAFF_TOTP_SECRET for the enrolled disposable performance-test account.');
    res = http.post(`${BASE_URL}/api/auth/totp/verify`, JSON.stringify({
      challengeToken: res.json('challengeToken'), code: totpCode(secret),
    }), { headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': before.json('token') }, timeout: '10s' });
  }
  const authenticated = res.status === 200 && res.json('status') === 'AUTHENTICATED';
  check(res, {
    'staff login authenticated': () => authenticated,
    'staff login response time < 2s': (r) => r.timings.duration < 2000,
  });
  if (!authenticated) return null;
  const after = http.get(`${BASE_URL}/api/auth/csrf`, { timeout: '10s' });
  if (after.status !== 200) return null;
  // Setup and VUs have separate cookie jars; explicitly pass standard HTTP cookie values.
  // Never print setup data: it contains the test client's cookies.
  const jar = http.cookieJar().cookiesForURL(BASE_URL);
  return {
    cookies: { SESSION: jar.SESSION[0], 'XSRF-TOKEN': jar['XSRF-TOKEN'][0] },
    csrfToken: after.json('token'),
  };
}

/** HTTP-client cookie credentials plus the non-credential CSRF and session-context headers. */
export function authHeaders(session) {
  const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': session.csrfToken };
  if (session.expectedContext) {
    headers['X-Expected-Session-Context'] = session.expectedContext;
  }
  return {
    cookies: session.cookies,
    headers,
  };
}

/** RFC 6238 authenticator for an explicitly supplied disposable test credential. */
function totpCode(secret) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const bytes = [];
  let buffer = 0;
  let bits = 0;
  for (const char of secret) {
    const digit = alphabet.indexOf(char);
    if (digit < 0) throw new Error('Invalid STAFF_TOTP_SECRET encoding.');
    buffer = (buffer << 5) | digit;
    bits += 5;
    if (bits >= 8) { bits -= 8; bytes.push((buffer >>> bits) & 255); }
  }
  const counter = new ArrayBuffer(8);
  const view = new DataView(counter);
  const step = Math.floor(Date.now() / 30000);
  view.setUint32(0, Math.floor(step / 4294967296));
  view.setUint32(4, step >>> 0);
  const digest = new Uint8Array(crypto.hmac('sha1', new Uint8Array(bytes).buffer, counter, 'binary'));
  const offset = digest[19] & 15;
  const number = new DataView(digest.buffer).getUint32(offset) & 0x7fffffff;
  return String(number % 1000000).padStart(6, '0');
}
