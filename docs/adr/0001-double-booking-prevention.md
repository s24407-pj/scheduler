# ADR-0001: Double-booking prevention

**Status:** Accepted — partially implemented (see [Pending](#pending))

## Context

Two customers (or a customer and a staff member) can try to book the same employee for overlapping times at the same moment. A schedule block created concurrently with a booking has the same problem. A plain "check for overlap, then insert" is not safe under concurrent requests.

Staff can be employed by more than one company. An employee's time is **not shared across companies**: a booking or block at salon A must not make the employee unavailable at salon B.

## Decision

Overlap prevention uses three layers, scoped to the pair (company, employee):

1. **Row lock on the employment.** `ReservationService.createReservation()` and `ScheduleBlockService` load the employee's `company_employees` row with `PESSIMISTIC_WRITE` (`CompanyEmployeeRepository.findByCompanyIdAndUserIdForUpdate`). This serializes all bookings and block creations for one employee in one company. The lock is held until the transaction commits.
2. **Application overlap check.** While holding the lock, `EmployeeAvailabilityPolicy.assertAvailable()` checks for active reservations (status not `CANCELLED`/`NO_SHOW`) and schedule blocks overlapping `[start, end)`. A conflict throws `ApiException(SLOT_UNAVAILABLE)` → HTTP 409.
3. **Database exclusion constraints.** GiST exclusion constraints on `reservations` and `schedule_blocks` reject overlapping active ranges even if application code is bypassed or buggy.

`@Version` on `Reservation` is **not** a double-booking mechanism. It guards concurrent status changes to an existing reservation (for example, cancel and complete arriving together).

Conflicts detected by layer 3 (`DataIntegrityViolationException` with SQLSTATE `23P01`) return HTTP 409 `SLOT_UNAVAILABLE`, the same as layer 2. `@Version` conflicts (`ObjectOptimisticLockingFailureException`) return 409 `CONCURRENT_MODIFICATION`. Both are mapped in `RestExceptionHandler`.

## Consequences

- Booking throughput per employee is serialized. At salon scale this is negligible.
- Every new code path that creates reservations or blocks must acquire the same lock before checking overlap, or rely on the constraint and map its violation to 409.
- Bulk JPQL updates (such as `ReservationRepository.autoCompleteElapsed`) do not increment `@Version`. A status change that races with a bulk update is not detected; the last write wins.

## Pending

The code does not yet fully match this decision:

- **Per-company scope for reservations.** `ReservationRepository.findOverlapping` filters by `employeeId` only, and the reservation exclusion constraint in `V1__init_schema.sql` is `(employee_id, tstzrange)`. Both must include `company_id`. Requires a new Flyway migration that drops and recreates the constraint, plus a query change. Schedule blocks are already company-scoped.
