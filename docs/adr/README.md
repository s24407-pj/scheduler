# Architecture Decision Records

Short records of decisions that shape the system. Each ADR states its status:

- **Proposed** — recommended, not yet agreed; do not build against it.
- **Accepted** — agreed. If the code does not match yet, the ADR lists what is pending.
- **Superseded** — replaced by a later ADR (linked).

To add one, copy the structure of an existing ADR, use the next number, and add it to the table below. Never rewrite an accepted ADR's decision; supersede it with a new one.

| ADR | Title | Status |
| --- | ----- | ------ |
| [0001](./0001-double-booking-prevention.md) | Double-booking prevention | Accepted — partially implemented |
| [0002](./0002-reservation-lifecycle-and-cancellation.md) | Reservation lifecycle and cancellation policy | Accepted — partially implemented |
| [0003](./0003-single-time-zone.md) | Single business time zone (Europe/Warsaw) | Accepted |
| [0004](./0004-error-contract-problem-detail.md) | Error contract based on ProblemDetail | Accepted |
| [0005](./0005-generated-openapi-spec.md) | Generated OpenAPI specification | Accepted |
| [0006](./0006-booking-idempotency.md) | Booking idempotency | Accepted |
| [0007](./0007-single-instance-deployment.md) | Single-instance deployment | Accepted |
| [0008](./0008-whole-zloty-pricing.md) | Whole-złoty pricing | Accepted |
| [0009](./0009-revocable-sessions.md) | Revocable sessions behind JWTs | Accepted — implemented |
