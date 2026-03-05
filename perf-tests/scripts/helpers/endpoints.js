import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/**
 * Fetches public offerings for a given company.
 */
export function getPublicOfferings(companyId) {
  const res = http.get(`${BASE_URL}/api/offerings/public/company/${companyId}`, {
    timeout: '10s',
  });
  check(res, {
    'offerings status 200': (r) => r.status === 200,
    'offerings is array': (r) => r.status === 200 && Array.isArray(r.json()),
  });
  return res;
}

/**
 * Fetches available time slots for a given date.
 */
export function getAvailability(employeeId, offeringId, date) {
  const res = http.get(
    `${BASE_URL}/api/availability?employeeId=${employeeId}&serviceId=${offeringId}&date=${date}`,
    { timeout: '10s' }
  );
  check(res, {
    'availability status 200': (r) => r.status === 200,
    'availability is array': (r) => r.status === 200 && Array.isArray(r.json()),
  });
  return res;
}

/**
 * Fetches the employee list for the authenticated user's company.
 */
export function getEmployees(token) {
  const res = http.get(`${BASE_URL}/api/company/employees`, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    timeout: '10s',
  });
  check(res, {
    'employees status 200': (r) => r.status === 200,
    'employees is array': (r) => r.status === 200 && Array.isArray(r.json()),
  });
  return res;
}

/**
 * Fetches authenticated offerings list for a given company.
 */
export function getAuthOfferings(token, companyId) {
  const res = http.get(`${BASE_URL}/api/offerings/company/${companyId}`, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    timeout: '10s',
  });
  check(res, {
    'auth offerings 200': (r) => r.status === 200,
  });
  return res;
}

/**
 * Creates a staff booking. Returns the response (may be 201 or 409 on conflict).
 */
export function createStaffBooking(token, employeeId, offeringId, startTime, phone) {
  const res = http.post(
    `${BASE_URL}/api/reservations/staff`,
    JSON.stringify({
      employeeId,
      serviceId: offeringId,
      startTime,
      customerPhone: phone,
      customerFirstName: 'Perf',
      customerLastName: 'Test',
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      timeout: '10s',
    }
  );
  check(res, {
    'booking status 201 or 409': (r) => r.status === 201 || r.status === 409,
  });
  return res;
}

/**
 * Returns a future date string (YYYY-MM-DD) offset from today.
 */
export function futureDate(daysFromNow) {
  const d = new Date();
  d.setDate(d.getDate() + daysFromNow);
  return d.toISOString().split('T')[0];
}

/**
 * Logs in as staff, fetches offerings and employees, returns setup data for VUs.
 * Returns null if login fails.
 */
export function loadSetupData() {
  const loginRes = http.post(
    `${BASE_URL}/api/auth/login-staff`,
    JSON.stringify({
      email: __ENV.STAFF_EMAIL || 'tomek@barbershop.pl',
      password: __ENV.STAFF_PASSWORD || 'admin123',
    }),
    {
      headers: { 'Content-Type': 'application/json' },
      timeout: '10s',
    }
  );

  if (loginRes.status !== 200) {
    console.error(`Setup failed: login returned ${loginRes.status}`);
    return null;
  }

  const token = loginRes.json('token');

  // Fetch real offering and employee IDs
  const offeringsRes = http.get(`${BASE_URL}/api/offerings/public/company/1`, {
    timeout: '10s',
  });
  let offeringIds = [1, 2, 3];
  if (offeringsRes.status === 200) {
    const offerings = offeringsRes.json();
    if (Array.isArray(offerings) && offerings.length > 0) {
      offeringIds = offerings.map((o) => o.id);
    }
  }

  const employeesRes = http.get(`${BASE_URL}/api/company/employees`, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    timeout: '10s',
  });
  let employeeIds = [1];
  if (employeesRes.status === 200) {
    const employees = employeesRes.json();
    if (Array.isArray(employees) && employees.length > 0) {
      employeeIds = employees.map((e) => e.id);
    }
  }

  return { token, offeringIds, employeeIds };
}

/**
 * Picks a random element from an array.
 */
export function randomFrom(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
