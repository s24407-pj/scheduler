# ADR-0015: Durable notification delivery and bounded reminder runs

**Status:** Accepted

## Decision

Store SMS intent before commit in the reservation transaction, then deliver after commit. Customer booking sends no confirmation SMS/email. Actual staff changes opt in explicitly, default off; actual edits opting out invalidate obsolete pending messages. Blocking sends one mandatory dynamically filtered cancellation summary.

Persist `PENDING`, `SENDING`, `ACCEPTED`, `TEMPORARY_FAILURE`, `AMBIGUOUS`, `PAUSED` or `DISCARDED`, attempt count, scheduling/deadline data, reservation revision/date and reminder run UUID. Commit `SENDING` before invoking the provider outside the transaction. On restart convert uncompleted `SENDING` to `AMBIGUOUS`; never blindly retry it. Categorize provider results as accepted, definite temporary rejection, or ambiguous. Unknown exceptions are ambiguous.

A sent boolean cannot provide crash-safe at-most-once or exactly-once delivery. A crash between claim and provider call may miss delivery; a crash after provider acceptance may leave an ambiguous outcome. These are accepted v1 risks. No claim of exactly-once acceptance is made.

Run reminders at 09:00 Warsaw on the previous calendar date, deadline 09:05. Only an uninterrupted in-memory run may retry definite temporary failures. Never recover a missed reminder run after restart. Durable audit distinguishes late creation/date changes from same-date edits; unique reservation/date identity prevents duplicate same-date reminders. Accepted reminders suppress obsolete pending confirmations; ambiguous reminders pause potentially conflicting confirmations.

Automatic completion remains a separate audited reservation job and catches up after restart. V1 remains one backend instance, without distributed scheduler locks. OTP and notification sends share one Redis-time UTC-day operator budget.

## Consequences

Delivery intent and its state survive process failure. Provider failure never rolls back an appointment. Database state supports operator investigation without exposing OTP codes or internal cancellation reasons. A real provider must classify definite temporary rejection explicitly; timeouts and unknown failures are ambiguous. See [notification rules](../notifications.md).
