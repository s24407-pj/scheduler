import http from 'k6/http';
import { check, sleep } from 'k6';
import { loginStaff, authHeaders } from './helpers/auth.js';
import { getPublicServices, getAvailability, getEmployees, futureDate } from './helpers/endpoints.js';

/**
 * STRESS TEST
 *
 * Gradually increases load to find the breaking point.
 * Steps: 0 → 50 → 100 → 200 → 300 VU, each stage 3 min.
 * Then ramp-down to observe recovery.
 */
export const options = {
  stages: [
    { duration: '2m', target: 50 },    // warm-up
    { duration: '3m', target: 50 },    // baseline
    { duration: '2m', target: 100 },   // increase
    { duration: '3m', target: 100 },   // sustain
    { duration: '2m', target: 200 },   // push harder
    { duration: '3m', target: 200 },   // sustain
    { duration: '2m', target: 300 },   // breaking point?
    { duration: '3m', target: 300 },   // sustain
    { duration: '2m', target: 0 },     // recovery
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.05'],
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
  } else if (rand < 0.90) {
    // 15% — Authenticated services list
    const token = loginStaff();
    const res = http.get(`${BASE_URL}/api/services/company/1`, authHeaders(token));
    check(res, {
      'auth services 200': (r) => r.status === 200,
    });
  } else {
    // 10% — Employee list
    const token = loginStaff();
    getEmployees(token);
  }

  sleep(Math.random() * 1.5 + 0.3); // 0.3–1.8s think time (faster pace)
}

