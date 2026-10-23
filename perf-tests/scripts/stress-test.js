import { sleep } from 'k6';
import { loadSetupData } from './helpers/endpoints.js';
import { runTrafficIteration } from './helpers/traffic.js';

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

export function setup() {
  return loadSetupData();
}

export default function (data) {
  if (!data) return;

  const thinkTime = Math.random() * 1.5 + 0.2;
  runTrafficIteration(data);

  sleep(thinkTime);
}
