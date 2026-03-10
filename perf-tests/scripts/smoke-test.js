import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  getPublicOfferings,
  getAvailability,
  getAuthOfferings,
  getEmployees,
  futureDate,
  loadSetupData,
  randomFrom,
} from './helpers/endpoints.js';

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

export function setup() {
  return loadSetupData();
}

export default function (data) {
  if (!data) return;

  // 1. Public offerings
  getPublicOfferings(1);
  sleep(0.5);

  // 2. Availability
  const date = futureDate(2);
  getAvailability(randomFrom(data.employeeIds), randomFrom(data.offeringIds), date);
  sleep(0.5);

  // 3. Authenticated offerings
  getAuthOfferings(data.token, 1);
  sleep(0.5);

  // 4. Employee list
  getEmployees(data.token);
  sleep(0.5);

  // 5. Health endpoint
  const healthRes = http.get(`${BASE_URL}/actuator/health`, { timeout: '10s' });
  check(healthRes, {
    'health status 200': (r) => r.status === 200,
  });
  sleep(0.5);
}
