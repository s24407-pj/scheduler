import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  getPublicOfferings,
  getAvailability,
  getAuthOfferings,
  createStaffBooking,
  futureDate,
  loadSetupData,
  randomFrom,
} from './helpers/endpoints.js';

/**
 * SPIKE TEST
 *
 * Simulates a sudden traffic surge (e.g. promo post goes viral).
 * Normal load -> instant spike -> sustain spike -> back to normal -> observe recovery.
 *
 * Traffic mix:
 *   50% - browse offerings (public)
 *   25% - check availability
 *   15% - authenticated offerings
 *   10% - staff booking (write)
 */
export const options = {
  stages: [
    { duration: '1m', target: 10 },
    { duration: '30s', target: 200 },
    { duration: '2m', target: 200 },
    { duration: '30s', target: 10 },
    { duration: '3m', target: 10 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    http_req_failed: ['rate<0.10'],
  },
};

export function setup() {
  return loadSetupData();
}

export default function (data) {
  if (!data) return;

  const rand = Math.random();
  const date = futureDate(Math.floor(Math.random() * 7) + 1);
  const thinkTime = Math.random() * 1.2 + 0.1;

  if (rand < 0.50) {
    getPublicOfferings(1);
  } else if (rand < 0.75) {
    getAvailability(randomFrom(data.employeeIds), randomFrom(data.offeringIds), date);
  } else if (rand < 0.90) {
    getAuthOfferings(data.token, 1);
  } else {
    const hour = 10 + Math.floor(Math.random() * 8);
    const minute = Math.random() < 0.5 ? '00' : '30';
    const startTime = `${date}T${hour}:${minute}:00`;
    const phone = `+48600${String(Math.floor(Math.random() * 1000000)).padStart(6, '0')}`;
    createStaffBooking(data.token, randomFrom(data.employeeIds), randomFrom(data.offeringIds), startTime, phone);
  }

  sleep(thinkTime);
}
