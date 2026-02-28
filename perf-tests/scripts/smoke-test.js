import http from 'k6/http';
import { check, sleep } from 'k6';
import { loginStaff, authHeaders } from './helpers/auth.js';
import { getPublicServices, getAvailability, futureDate } from './helpers/endpoints.js';

/**
 * SMOKE TEST
 *
 * Quick validation that the environment is working correctly.
 * Runs 1-2 virtual users for 30 seconds, hitting all key endpoints.
 */
export const options = {
  vus: 2,
  duration: '30s',
  thresholds: {
    http_req_duration: ['p(95)<1000'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  // 1. Public services (cached)
  getPublicServices();
  sleep(0.5);

  // 2. Availability
  const date = futureDate(2);
  getAvailability(1, 1, date);
  sleep(0.5);

  // 3. Staff login
  const token = loginStaff();
  sleep(0.5);

  // 4. Get services (authenticated)
  const authRes = http.get(`${BASE_URL}/api/services/company/1`, authHeaders(token));
  check(authRes, {
    'auth services status 200': (r) => r.status === 200,
  });
  sleep(0.5);

  // 5. Health endpoint
  const healthRes = http.get(`${BASE_URL}/actuator/health`);
  check(healthRes, {
    'health status 200': (r) => r.status === 200,
  });
  sleep(0.5);
}

