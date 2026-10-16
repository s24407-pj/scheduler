# ADR-0002: Reservation lifecycle and cancellation policy

**Status:** Superseded by [ADR-0016](./0016-reservation-contract-and-durable-submissions.md). Historical context below; current behavior is in [reservations](../reservations.md).

## Context

`ReservationStatus` defines `PENDING`, `CONFIRMED`, `CANCELLED`, `COMPLETED`, and `NO_SHOW`. Earlier docs described a `PENDING → CONFIRMED` step, but no code path ever sets `CONFIRMED`, and the salon has no confirmation workflow. Only customers could cancel, with no deadline.

## Decision

**Lifecycle**

- A new reservation is `PENDING`, which means **booked**. There is no approval or confirmation step.
- `CONFIRMED` has no meaning in the business process. Code treats it exactly like `PENDING` until it is removed.
- There is no `EXPIRED` state.
- Terminal states: `CANCELLED`, `COMPLETED`, `NO_SHOW`.

**Cancellation**

- Both the customer who owns the reservation and staff (OWNER or EMPLOYEE) of the reservation's company can cancel.
- Customers can cancel only until **24 hours before** `startTime`.
- Staff can cancel at any time before the reservation reaches a terminal state, consistent with other staff overrides (business hours, minimum booking advance).
- Every cancellation triggers the cancellation SMS.

**Unchanged**

- No-show can be marked by staff on any `PENDING`/`CONFIRMED` reservation; there is no "after start time" check.
- OWNER can hard-delete a reservation (`DELETE /api/reservations/{id}`).
- An hourly job marks `PENDING`/`CONFIRMED` reservations whose `endTime` has passed as `COMPLETED`.

## Consequences

- The reservation docs describe a four-state model in practice: active (`PENDING`), and three terminal states.
- The 24-hour cutoff is evaluated in the business time zone (see [ADR-0003](./0003-single-time-zone.md)).

## Pending

- Remove `CONFIRMED` from `ReservationStatus` and from the repository queries that reference it.

Implemented since: staff cancellation (`PATCH /api/reservations/{id}/cancel-by-staff`). The 24-hour cutoff and the "no after-start check" rule for no-shows were replaced by ADR-0010 instead of being implemented.
