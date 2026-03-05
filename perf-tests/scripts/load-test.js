import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  getPublicOfferings,
  getAvailability,
  getAuthOfferings,
  getEmployees,
  createStaffBooking,
  futureDate,
  loadSetupData,
  randomFrom,
} from './helpers/endpoints.js';

/**
 * LOAD TEST
 *
 * Simulates normal traffic patterns.
 * Ramp-up to 50 VU over 2 min, sustain for 5 min, ramp-down over 1 min.
 *
 * Traffic mix:
 *   35% - browse offerings (public, cached)
 *   25% - check availability
 *   15% - authenticated offerings list
 *   10% - employee list
 *   10% - staff booking (write operation)
 *    5% - health check
 */
export const options = {
  stages: [
    { duration: '2m', target: 50 },
    { duration: '5m', target: 50 },
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1500'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
  return loadSetupData();
}

export default function (data) {
  if (!data) return;

  const rand = Math.random();
  const date = futureDate(Math.floor(Math.random() * 7) + 1);
  const thinkTime = Math.random() * 2.5 + 0.3;

  if (rand < 0.35) {
    getPublicOfferings(1);
  } else if (rand < 0.60) {
    getAvailability(randomFrom(data.employeeIds), randomFrom(data.offeringIds), date);
  } else if (rand < 0.75) {
    getAuthOfferings(data.token, 1);
  } else if (rand < 0.85) {
    getEmployees(data.token);
  } else if (rand < 0.95) {
    // Staff booking — write operation with optimistic locking contention
    const hour = 10 + Math.floor(Math.random() * 8);
    const minute = Math.random() < 0.5 ? '00' : '30';
    const startTime = `${date}T${hour}:${minute}:00`;
    const phone = `+48600${String(Math.floor(Math.random() * 1000000)).padStart(6, '0')}`;
    createStaffBooking(data.token, randomFrom(data.employeeIds), randomFrom(data.offeringIds), startTime, phone);
  } else {
    const res = http.get(`${BASE_URL}/actuator/health`, { timeout: '10s' });
    check(res, { 'health 200': (r) => r.status === 200 });
  }

  sleep(thinkTime);
}
