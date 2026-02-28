import {sleep} from 'k6';
import {loginStaff} from './helpers/auth.js';
import {futureDate, getAvailability, getPublicServices} from './helpers/endpoints.js';

/**
 * SPIKE TEST
 *
 * Simulates a sudden traffic surge (e.g. promo post goes viral).
 * Normal load → instant spike → back to normal.
 * Tests how the system handles sudden pressure and whether it recovers.
 */
export const options = {
    stages: [
        {duration: '1m', target: 10},    // normal traffic
        {duration: '15s', target: 200},   // spike!
        {duration: '1m', target: 200},    // sustain spike
        {duration: '15s', target: 10},    // drop back
        {duration: '2m', target: 10},     // recovery — observe latency return to normal
    ],
    thresholds: {
        http_req_duration: ['p(95)<2000'],
        http_req_failed: ['rate<0.10'],
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
    const rand = Math.random();
    const date = futureDate(Math.floor(Math.random() * 7) + 1);

    if (rand < 0.5) {
        // During spike, most users browse services
        getPublicServices();
    } else if (rand < 0.8) {
        const serviceId = Math.floor(Math.random() * 3) + 1;
        getAvailability(1, serviceId, date);
    } else {
        loginStaff();
    }

    sleep(Math.random() * 1 + 0.2); // 0.2–1.2s think time (fast browsing)
}

