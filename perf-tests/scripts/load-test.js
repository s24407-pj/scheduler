import { sleep } from 'k6';
import { loadSetupData } from './helpers/endpoints.js';
import { runTrafficIteration } from './helpers/traffic.js';

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

export function setup() {
  return loadSetupData();
}

export default function (data) {
  if (!data) return;

  const thinkTime = Math.random() * 2.5 + 0.3;
  runTrafficIteration(data);

  sleep(thinkTime);
}
