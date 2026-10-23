import { sleep } from 'k6';
import {
  getPublicOfferings,
  getAvailability,
  getAuthOfferings,
  futureDate,
  loadSetupData,
  randomFrom,
} from './helpers/endpoints.js';
import { createRandomStaffBooking } from './helpers/traffic.js';

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
    getPublicOfferings(data.companyId);
  } else if (rand < 0.75) {
    getAvailability(randomFrom(data.employeeIds), randomFrom(data.offeringIds), date);
  } else if (rand < 0.90) {
    getAuthOfferings(data.session);
  } else {
    createRandomStaffBooking(data, date);
  }

  sleep(thinkTime);
}
