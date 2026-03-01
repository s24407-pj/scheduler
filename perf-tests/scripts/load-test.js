import http from 'k6/http';
import { check, sleep } from 'k6';
import { loginStaff, authHeaders } from './helpers/auth.js';
import { getPublicServices, getAvailability, getEmployees, futureDate } from './helpers/endpoints.js';

/**
 * LOAD TEST
 *
 * Simulates normal traffic patterns.
 * Ramp-up to 50 VU over 2 min → sustain for 5 min → ramp-down over 1 min.
 *
 * Traffic mix (realistic distribution):
 *   35% — browse services (public, cached)
 *   25% — check availability
 *   15% — staff login
 *   10% — authenticated services list
 *   10% — employee list
 *    5% — health check
 */
export const options = {
  stages: [
    { duration: '2m', target: 50 },   // ramp-up
    { duration: '5m', target: 50 },   // sustain
    { duration: '1m', target: 0 },    // ramp-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1500'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const rand = Math.random();
  const date = futureDate(Math.floor(Math.random() * 7) + 1);

  if (rand < 0.35) {
    // 35% — Public services (Redis cached)
    getPublicServices();
  } else if (rand < 0.60) {
    // 25% — Availability check
    const serviceId = Math.floor(Math.random() * 3) + 1;
    getAvailability(1, serviceId, date);
  } else if (rand < 0.75) {
    // 15% — Staff login
    loginStaff();
  } else if (rand < 0.85) {
    // 10% — Authenticated services list
    const token = loginStaff();
    const res = http.get(`${BASE_URL}/api/services/company/1`, authHeaders(token));
    check(res, {
      'auth services 200': (r) => r.status === 200,
    });
  } else if (rand < 0.95) {
    // 10% — Employee list
    const token = loginStaff();
    getEmployees(token);
  } else {
    // 5% — Health
    const res = http.get(`${BASE_URL}/actuator/health`);
    check(res, {
      'health 200': (r) => r.status === 200,
    });
  }

  sleep(Math.random() * 2 + 0.5); // 0.5–2.5s think time
}

