# ADR-0007: Single-instance deployment

**Status:** Accepted

## Context

`NotificationScheduler` runs two hourly jobs in-process with Spring `@Scheduled`: auto-completing elapsed reservations and sending reminder SMS. Neither job uses a distributed lock.

## Decision

Production runs **one application instance**. In-process scheduled jobs without distributed locking are acceptable.

## Consequences

- Reminders are sent at most once per reservation (the `reminder_sent` flag is set after the send loop), and auto-complete runs once per hour.
- Rate limiting and OTP state already live in Redis and do not depend on this decision.
- **Before scaling to more than one instance**, add a scheduler lock (for example ShedLock with the existing PostgreSQL or Redis) and supersede this ADR. Without it, every instance sends the same reminders.
