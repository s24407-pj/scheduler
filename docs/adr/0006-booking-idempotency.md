# ADR-0006: Booking idempotency

**Status:** Superseded by [ADR-0016](./0016-reservation-contract-and-durable-submissions.md). Historical context below; current behavior is in [reservations](../reservations.md).

## Context

There are no payments and no webhooks, and none are planned. The only non-idempotent operations that matter are the two booking endpoints, `POST /api/reservations` and `POST /api/reservations/staff`.

Previously, if a client retried a booking after a timeout (or a user double-tapped), the retry hit the reservation created by the first request and received **409 "Ten termin jest już zajęty"**. The client could not tell "you already have this booking" from "someone else took the slot".

Separately, two concurrent staff bookings for a phone number with no account both tried to create the user; the second failed on the `users.phone_number` unique constraint and returned 500.

## Decision

**Natural idempotency instead of an `Idempotency-Key` header.**

- If the requesting customer already has an active (not `CANCELLED` or `NO_SHOW`) reservation with the same `employeeId`, `serviceId`, and `startTime`, the endpoint returns that reservation with **200 OK** instead of creating a new one (201) or failing (409). `ReservationRepository.findActiveBooking` performs the lookup; `ReservationService` returns a `BookingResult(reservation, created)` and the controller picks the status.
- The check runs after the employment row lock is taken ([ADR-0001](./0001-double-booking-prevention.md)), so concurrent retries are serialized and cannot both create.
- Staff bookings apply the same rule once the customer is resolved by phone number.
- A new customer for a staff booking is created with `INSERT ... ON CONFLICT (phone_number) DO NOTHING` (`UserRepository.insertIfPhoneAbsent`) and then re-read, so a concurrent request for the same phone number resolves to the same account instead of failing.

**Why not an `Idempotency-Key` header now:** it requires client changes, server-side storage of responses, and payload fingerprinting, to protect a single operation that has a natural business key. The header pattern earns its cost with payments and webhooks, which are out of scope.

**If a header is needed later** (for example for payments), the design is:

- Optional `Idempotency-Key` header (client-generated UUID) on create endpoints.
- Redis key `idem:<userId>:<key>`, claimed with `SET NX` and a short "in progress" marker; a concurrent request with the same key gets 409.
- On completion, store the status code, response body, and a hash of the request body for 24 hours.
- A replay with the same key and same body returns the stored response; same key with a different body returns 422.

## Consequences

- No client changes. Retries are safe and return the original booking; clients can treat 200 and 201 alike.
- Confirmation SMS is sent only once, because the replay creates no reservation and publishes no event.
- The replay returns the stored reservation as-is, including its original price snapshot, even if the catalog price or discount has changed since.
- A customer who deliberately wants two identical bookings cannot make them; that is the intended behaviour.
