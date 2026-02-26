import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STAFF_EMAIL = __ENV.STAFF_EMAIL || 'tomek@barbershop.pl';
const STAFF_PASSWORD = __ENV.STAFF_PASSWORD || 'admin123';

/**
 * Authenticates a staff member and returns the JWT token.
 */
export function loginStaff() {
  const res = http.post(
    `${BASE_URL}/api/auth/login-staff`,
    JSON.stringify({ email: STAFF_EMAIL, password: STAFF_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(res, {
    'staff login status 200': (r) => r.status === 200,
    'staff login has token': (r) => !!r.json('token'),
  });

  return res.json('token');
}

/**
 * Returns HTTP headers with Authorization Bearer token.
 */
export function authHeaders(token) {
  return {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  };
}

/**
 * Returns common JSON headers (no auth).
 */
export function jsonHeaders() {
  return {
    headers: { 'Content-Type': 'application/json' },
  };
}

