# ADR-0010: Cancellation window and no-show correction

**Status:** Accepted — implemented. Supersedes the customer cancellation cutoff and the no-show rule of [ADR-0002](./0002-reservation-lifecycle-and-cancellation.md).

## Context

ADR-0002 planned a 24-hour customer cancellation cutoff and allowed staff to mark a no-show at any time. In practice:

- A cutoff pushes customers who can't come into simply not showing up, which the no-show counter then punishes.
- Customers could still cancel a visit that had already started or ended, before the hourly auto-complete job ran.
- A no-show could be marked hours before a visit (for example by a mis-click), auto-blocking the customer.
- The hourly job sets elapsed visits to `COMPLETED`, a terminal state, so staff who recorded a no-show after the job ran got 409 and the counter never moved.

## Decision

**Customer cancellation**

- The owning customer can cancel while `now < startTime`. There is no earlier cutoff.
- At or after `startTime` the request returns 409 `INVALID_STATUS_TRANSITION`.

**Staff cancellation** (unchanged from ADR-0002)

- OWNER/EMPLOYEE of the reservation's company can cancel any non-terminal reservation at any time, including after start, via `PATCH /api/reservations/{id}/cancel-by-staff`.

**No-show**

- `PENDING`/`CONFIRMED` can become `NO_SHOW` only once `now >= startTime`.
- `COMPLETED` can be corrected to `NO_SHOW` until `endTime + 24 h` (`NO_SHOW_CORRECTION_HOURS` in `ReservationService`). This applies whether staff or the hourly job completed the visit, because the two are not distinguished in the data.
- Outside these windows the request returns 409 `INVALID_STATUS_TRANSITION`.

All times use the business clock and time zone ([ADR-0003](./0003-single-time-zone.md)).

## Consequences

- Customers can cancel minutes before a visit; late cancellations are not tracked.
- A staff mistake in completing a visit can be corrected to a no-show within the window, but a mistaken no-show cannot be reverted (an owner can unblock the customer, which resets the counter).
- The 24-hour correction window is a constant, not a company setting.
