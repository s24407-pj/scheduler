# ADR-0007: Single-instance deployment

**Status:** Accepted

## Context

In-process Spring schedules trigger durable notification retries, the previous-day 09:00 Warsaw reminder run and a separate reservation completion job. None uses a distributed lock. Delivery semantics are defined by [ADR-0015](./0015-durable-notification-delivery.md).

## Decision

Production runs **one application instance**. In-process scheduled jobs without distributed locking are acceptable.

## Consequences

- Durable reminder identity is per reservation and appointment date. Unknown provider outcomes are not retried; a sent flag cannot establish crash-safe delivery. Completion catches up after restart; missed reminder runs do not.
- Rate limiting and OTP state already live in Redis and do not depend on this decision.
- **Before scaling to more than one instance**, add a scheduler lock (for example ShedLock with the existing PostgreSQL or Redis) and supersede this ADR. The current dispatcher and in-memory reminder run ownership assume a single process.
