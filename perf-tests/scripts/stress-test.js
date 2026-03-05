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
 * STRESS TEST
 *
 * Gradually increases load to find the breaking point.
 * Steps: 0 -> 50 -> 100 -> 200 -> 300 VU, each stage 3 min.
 * Then ramp-down to observe recovery.
 *
 * Traffic mix:
 *   35% - browse offerings (public)
 *   25% - check availability
 *   15% - authenticated offerings list
 *   10% - employee list
 *   10% - staff booking (write)
 *    5% - health check
 */
export const options = {
  stages: [
    { duration: '2m', target: 50 },
    { duration: '3m', target: 50 },
    { duration: '2m', target: 100 },
    { duration: '3m', target: 100 },
    { duration: '2m', target: 200 },
    { duration: '3m', target: 200 },
    { duration: '2m', target: 300 },
    { duration: '3m', target: 300 },
    { duration: '2m', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.05'],
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
  const thinkTime = Math.random() * 1.5 + 0.2;

  if (rand < 0.35) {
    getPublicOfferings(1);
  } else if (rand < 0.60) {
    getAvailability(randomFrom(data.employeeIds), randomFrom(data.offeringIds), date);
  } else if (rand < 0.75) {
    getAuthOfferings(data.token, 1);
  } else if (rand < 0.85) {
    getEmployees(data.token);
  } else if (rand < 0.95) {
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
