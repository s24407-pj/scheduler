# Reservations

A reservation books one employee for one offering at one company, from `startTime` to `endTime` (`startTime + offering.durationMinutes`). Code: `reservation/`.

## Lifecycle

Decision record: [ADR-0002](./adr/0002-reservation-lifecycle-and-cancellation.md).

| Status | Meaning | Active (occupies the slot)? |
| ------ | ------- | --------------------------- |
| `PENDING` | Booked. Initial state for every reservation. No confirmation step exists. | Yes |
| `CONFIRMED` | Not used by the business process; never set by code. Treated like `PENDING`. Scheduled for removal. | Yes |
| `COMPLETED` | Visit took place (set by staff or by the hourly auto-complete job). | No |
| `CANCELLED` | Cancelled. | No |
| `NO_SHOW` | Customer did not attend. Increments the company-scoped no-show counter. | No |

"Active" means the reservation blocks the slot in availability, in the overlap check, and in the database exclusion constraint.

### Transitions

| From | To | Who | Endpoint | Conditions |
| ---- | -- | --- | -------- | ---------- |
| — | `PENDING` | Customer | `POST /api/reservations` | See [booking rules](#booking-rules) |
| — | `PENDING` | OWNER, EMPLOYEE | `POST /api/reservations/staff` | See [booking rules](#booking-rules) |
| `PENDING` | `CANCELLED` | Owning customer | `PATCH /api/reservations/{id}/cancel` | Currently no time limit. **Pending:** only until 24 h before `startTime`. |
| `PENDING` | `CANCELLED` | OWNER, EMPLOYEE | — | **Pending:** staff cancellation, any time. |
| `PENDING` | `COMPLETED` | OWNER, EMPLOYEE | `PATCH /api/reservations/{id}/complete` | Same company |
| `PENDING` | `COMPLETED` | System | Hourly job | `endTime` has passed |
| `PENDING` | `NO_SHOW` | OWNER, EMPLOYEE | `PATCH /api/reservations/{id}/no-show` | Same company. No check that the visit has started. |
| any | (deleted) | OWNER | `DELETE /api/reservations/{id}` | Same company. Hard delete, no SMS. |

Every `PENDING` row above applies equally to `CONFIRMED`. Transitions out of terminal states (`COMPLETED`, `CANCELLED`, `NO_SHOW`) are rejected with 409 `INVALID_STATUS_TRANSITION`. A reservation of another company, or another customer's reservation on cancel, is reported as 404 `RESERVATION_NOT_FOUND`.

## Booking rules

Both endpoints end in `ReservationService.createReservation()`. Checks run in this order; failures use the [error codes](./api/errors.md) shown:

| Check | Customer booking | Staff booking | Failure |
| ----- | ---------------- | ------------- | ------- |
| `startTime` is in the future (`@Future`) | Yes | Yes | 400 `VALIDATION_FAILED` |
| Offering exists (staff: and belongs to the staff member's company) | — | Yes, checked first | 400 `OFFERING_UNAVAILABLE` |
| Customer resolved by phone; created if unknown, then `customerFirstName` and `customerLastName` are required | — | Yes | 400 `CUSTOMER_NAME_REQUIRED` |
| `startTime` exists in Europe/Warsaw (not in the DST gap) | Yes | Yes | 400 `INVALID_LOCAL_TIME` |
| Customer exists | Yes (from JWT) | Yes | 404 `CUSTOMER_NOT_FOUND` |
| Offering exists and is active | Yes | Yes | 400 `OFFERING_UNAVAILABLE` |
| Employee is employed by the offering's company (and row lock is taken) | Yes | Yes | 400 `EMPLOYEE_NOT_IN_COMPANY` |
| Identical active booking already exists → return it with 200 ([retries](#retries-and-idempotency)) | Yes | Yes | — |
| Employee performs the offering (only if the employee has any assignments) | Yes | Yes | 400 `EMPLOYEE_OFFERING_NOT_ASSIGNED` |
| Customer not blocked at the company | Yes | Yes | 400 `CUSTOMER_BLOCKED` |
| `startTime` at least `minBookingAdvanceMinutes` from now | Yes | **No** | 400 `BOOKING_ADVANCE_REQUIRED` |
| No overlap with active reservations or schedule blocks | Yes | Yes | 409 `SLOT_UNAVAILABLE` |
| Inside the employee's work schedule and aligned to the slot interval | **No** | **No** | — |

The last row is a known gap for customer bookings: the API accepts any future time that does not overlap, even outside working hours or off the slot grid. Customer clients should only offer times returned by [availability](./availability.md). Staff are intentionally allowed to book outside working hours.

## Concurrency

Decision record: [ADR-0001](./adr/0001-double-booking-prevention.md).

- Bookings and block creation for one employee in one company are serialized by a `PESSIMISTIC_WRITE` lock on the `company_employees` row.
- The overlap check runs under that lock; a database exclusion constraint is the backstop.
- `@Version` protects status changes on an existing reservation, not slot conflicts.
- An exclusion-constraint violation returns 409 `SLOT_UNAVAILABLE`; a `@Version` conflict returns 409 `CONCURRENT_MODIFICATION`.
- **Pending:** reservation overlap is currently checked per employee across all companies; it must be per (company, employee).

## Retries and idempotency

Decision record: [ADR-0006](./adr/0006-booking-idempotency.md). There are no payments or webhooks and no `Idempotency-Key` header.

- Both booking endpoints are naturally idempotent. If the customer already has an active reservation with the same `employeeId`, `serviceId`, and `startTime`, the response is **200** with that reservation instead of **201** with a new one. No second confirmation SMS is sent.
- The check runs under the employment row lock, so concurrent retries cannot both create a reservation.
- Staff bookings for an unknown phone number create the customer with `INSERT ... ON CONFLICT DO NOTHING`; concurrent requests for the same number share one account.

## Pricing

Whole złoty, integer ([ADR-0008](./adr/0008-whole-zloty-pricing.md)). `reservations.price` is a snapshot at booking time: catalog price, minus the company's last-minute discount if `startTime` falls within `lastMinuteDiscountHours` from now, rounded down. An idempotent replay returns the original snapshot.

## Side effects

- Confirmation SMS after a booking commits; cancellation SMS after a cancellation commits. Failures are logged only.
- Reminder SMS 23–25 hours before `startTime` (hourly job, deduplicated by `reminder_sent`).
- See [architecture: notifications](./architecture.md#notifications-and-scheduled-jobs) for the known SMS gap.

## Reading reservations

| Endpoint | Who | Returns |
| -------- | --- | ------- |
| `GET /api/reservations/me` | Customer | Own reservations, newest first (`ReservationResponse`) |
| `GET /api/reservations/employee?start=&end=` | OWNER, EMPLOYEE | Caller's own schedule in the range, all statuses |
| `GET /api/reservations?employeeId=&start=&end=` | OWNER (any employee in company), EMPLOYEE (self only, else 403) | `DashboardReservationResponse` with customer names; used by dashboard calendar |

Ranges match reservations that overlap `[start, end)`. `start` and `end` are ISO date-times without offset ([time handling](./time-handling.md)).
