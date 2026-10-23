import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STAFF_EMAIL = __ENV.STAFF_EMAIL || 'gabinet@kacosmetology.pl';
const STAFF_PASSWORD = __ENV.STAFF_PASSWORD || 'admin123';
const STAFF_COMPANY_ID = Number(__ENV.STAFF_COMPANY_ID || 1);

/** Standard cookie/CSRF login. k6's cookie jar retains HttpOnly cookies as an HTTP client. */
export function loginStaff() {
  const before = http.get(`${BASE_URL}/api/auth/csrf`, { timeout: '10s' });
  if (before.status !== 200) return null;
  const res = http.post(
    `${BASE_URL}/api/auth/login-staff`,
    JSON.stringify({ email: STAFF_EMAIL, password: STAFF_PASSWORD, companyId: STAFF_COMPANY_ID }),
    {
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': before.json('token') },
      timeout: '10s',
    }
  );
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

/** HTTP-client cookie credentials plus the non-credential CSRF header. */
export function authHeaders(session) {
  return {
    cookies: session.cookies,
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': session.csrfToken },
  };
}
