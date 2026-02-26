import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/**
 * Fetches public services for company 1 (cached in Redis).
 */
export function getPublicServices() {
  const res = http.get(`${BASE_URL}/api/services/public/company/1`);
  check(res, {
    'services status 200': (r) => r.status === 200,
    'services is array': (r) => Array.isArray(r.json()),
  });
  return res;
}

/**
 * Fetches available time slots for a given date.
 */
export function getAvailability(employeeId, serviceId, date) {
  const res = http.get(
    `${BASE_URL}/api/availability?employeeId=${employeeId}&serviceId=${serviceId}&date=${date}`
  );
  check(res, {
    'availability status 200': (r) => r.status === 200,
    'availability is array': (r) => Array.isArray(r.json()),
  });
  return res;
}

/**
 * Returns a future date string (YYYY-MM-DD) offset from today.
 */
export function futureDate(daysFromNow) {
  const d = new Date();
  d.setDate(d.getDate() + daysFromNow);
  return d.toISOString().split('T')[0];
}

