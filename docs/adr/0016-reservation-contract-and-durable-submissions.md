# ADR-0016: Reservation contract, concurrency and durable submission identity

**Status:** Accepted. Supersedes lifecycle/idempotency portions of ADR-0001, ADR-0002, ADR-0006 and ADR-0010.

## Decision

The authoritative reservation behavior is documented in [reservations](../reservations.md). BOOKED is the only initial state. IDs/creation time and accepted commercial terms survive edits, outcomes, restoration and archive. Appointment deletion is unavailable.

Company-row serialization coordinates booking caps, any-employee allocation and policy mutations. Employment-row locking also coordinates appointment/block checks. PostgreSQL exclusions independently protect employee and customer occupied intervals per company, including completed appointments. Single-instance deployment does not replace database atomicity.

Required account-scoped booking submission keys bind payload before validation in a separate committed transaction. Execution locks that durable row for up to five seconds. Failed attempts retain their payload identity; successful keys retain a reservation foreign key for its entire lifetime. Replays read current details, never allocate again. A changed payload conflicts.

Revision preconditions prevent stale edits. Durable lifecycle command outcomes distinguish successful retries from old commands replayed after correction. Durable database audit records every actual reservation mutation, with transaction-local actor metadata and before/after JSON. Bulk lifecycle updates that bypass versions are prohibited. No-show penalties are durable appointment-date events; block provenance and owner reprieve are separate from rolling counts.

## Consequences

Salon-wide booking serialization is intentionally simple for the accepted single-instance workload. Database constraints remain the backstop. Every public write must preserve the lock order, revision and audit semantics. Notifications use a separate durable delivery model; database commit does not imply provider delivery.
