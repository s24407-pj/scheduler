# Architecture Decision Records

Short records of decisions that shape the system. Each ADR states its status:

- **Proposed** — recommended, not yet agreed; do not build against it.
- **Accepted** — agreed. If the code does not match yet, the ADR lists what is pending.
- **Superseded** — replaced by a later ADR (linked).

To add one, copy the structure of an existing ADR, use the next number, and add it to the table below. Never rewrite an accepted ADR's decision; supersede it with a new one.

| ADR | Title | Status |
| --- | ----- | ------ |
| [0001](./0001-double-booking-prevention.md) | Double-booking prevention | Superseded by 0016 |
| [0002](./0002-reservation-lifecycle-and-cancellation.md) | Reservation lifecycle and cancellation | Superseded by 0016 |
| [0003](./0003-single-time-zone.md) | Single business time zone (Europe/Warsaw) | Superseded by 0016 |
| [0004](./0004-error-contract-problem-detail.md) | Error contract based on ProblemDetail | Accepted |
| [0005](./0005-generated-openapi-spec.md) | Generated OpenAPI specification | Accepted |
| [0006](./0006-booking-idempotency.md) | Booking idempotency | Superseded by 0016 |
| [0007](./0007-single-instance-deployment.md) | Single-instance deployment | Accepted |
| [0008](./0008-whole-zloty-pricing.md) | Whole-złoty pricing | Accepted |
| [0009](./0009-revocable-sessions.md) | Revocable sessions behind JWTs | Superseded by 0011 |
| [0010](./0010-cancellation-window-and-no-show-correction.md) | Cancellation window and no-show correction | Superseded by 0016 |
| [0011](./0011-spring-session-redis.md) | Spring Security sessions backed by Redis | Accepted — implemented |
| [0012](./0012-separate-account-realms.md) | Separate customer and company staff realms | Accepted — implemented |
| [0013](./0013-commercial-quotes.md) | Durable commercial quotes without capacity holds | Accepted — implemented |
| [0014](./0014-effective-schedules-and-explicit-qualification.md) | Effective schedules and explicit qualification | Accepted — implemented |
| [0015](./0015-durable-notification-delivery.md) | Durable notification delivery | Accepted — implemented |
| [0016](./0016-reservation-contract-and-durable-submissions.md) | Reservation contract and durable submissions | Accepted |
| [0017](./0017-company-local-customers.md) | Company-local customer identity and OTP | Accepted |
| [0018](./0018-explicit-booking-customer-intent.md) | Explicit customer selection and profile establishment intent | Accepted |
| [0019](./0019-remove-obsolete-compatibility-code.md) | Remove obsolete compatibility code | Accepted |
| [0020](./0020-bounded-booking-admission.md) | Bounded booking admission and fingerprint storage | Accepted |
