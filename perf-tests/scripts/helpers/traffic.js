import http from 'k6/http';
import { check } from 'k6';
import {
  getPublicOfferings,
  getAvailability,
  getAuthOfferings,
  getEmployees,
  createStaffBooking,
  futureDate,
  randomFrom,
} from './endpoints.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/** Runs the request mix shared by the load and stress profiles; callers control pacing. */
export function runTrafficIteration(data) {
  const rand = Math.random();
  const date = futureDate(Math.floor(Math.random() * 7) + 1);

  if (rand < 0.35) {
    getPublicOfferings(data.companyId);
  } else if (rand < 0.60) {
    getAvailability(randomFrom(data.employeeIds), randomFrom(data.offeringIds), date);
  } else if (rand < 0.75) {
    getAuthOfferings(data.session);
  } else if (rand < 0.85) {
    getEmployees(data.session);
  } else if (rand < 0.95) {
    createRandomStaffBooking(data, date);
  } else {
    const res = http.get(`${BASE_URL}/actuator/health`, { timeout: '10s' });
    check(res, { 'health 200': (r) => r.status === 200 });
  }
}

/** Creates a staff booking with randomized time, customer phone, employee and offering. */
export function createRandomStaffBooking(data, date) {
  const hour = 10 + Math.floor(Math.random() * 8);
  const minute = Math.random() < 0.5 ? '00' : '30';
  const startTime = `${date}T${hour}:${minute}:00`;
  const phone = `+48600${String(Math.floor(Math.random() * 1000000)).padStart(6, '0')}`;
  createStaffBooking(data.session, randomFrom(data.employeeIds), randomFrom(data.offeringIds), startTime, phone);
}
