# ADR-0015: Durable notification delivery and bounded reminder runs

**Status:** Accepted

Customer identity/scope and OTP/profile establishment rules are superseded where applicable by [ADR-0017](./0017-company-local-customers.md). Other decisions remain in force.

## Decision

Store SMS intent before commit in its originating transaction, then deliver after commit. OTP intent uses a separate phone-only outbox without creating an unverified account/profile; it retries only a still-current Redis code within its original lifetime and scrubs plaintext on terminal/expired state. Customer booking sends no confirmation SMS/email. Actual staff changes opt in explicitly, default off; actual edits opting out invalidate obsolete pending messages. Blocking sends one mandatory dynamically filtered cancellation summary.

Persist `PENDING`, `SENDING`, `ACCEPTED`, `TEMPORARY_FAILURE`, `AMBIGUOUS`, `PAUSED` or `DISCARDED`, attempt count, scheduling/deadline data, reservation revision/date and reminder run UUID. Commit `SENDING` before invoking the provider outside the transaction. On restart convert uncompleted `SENDING` to `AMBIGUOUS`; never blindly retry it. Categorize provider results as accepted, definite temporary rejection, or ambiguous. Unknown exceptions are ambiguous.

A sent boolean cannot provide crash-safe at-most-once or exactly-once delivery. A crash between claim and provider call may miss delivery; a crash after provider acceptance may leave an ambiguous outcome. These are accepted v1 risks. No claim of exactly-once acceptance is made.

Run reminders at 09:00 Warsaw on the previous calendar date, deadline 10:00. Only an uninterrupted in-memory run may retry definite temporary failures. Never recover a missed reminder run after restart. Durable audit excludes late creation and every post-cutoff start-time move, including within one date; non-time edits remain eligible and unique reservation/date identity prevents duplicate same-date reminders. Accepted reminders suppress obsolete pending confirmations; ambiguous reminders pause potentially conflicting confirmations.

Automatic completion remains a separate audited reservation job and catches up after restart. V1 remains one backend instance, without distributed scheduler locks. OTP and notification sends share one Redis-time UTC-day operator budget. Issuance atomically reserves the first OTP attempt; additional definite-temporary retries reserve another allowance without resetting phone issuance/cooldown counters. Ambiguous OTP outcomes are never retried.

## Bounded asynchronous execution (2026-10-23)

Use two isolated in-process SMS worker pools over the existing PostgreSQL outboxes: OTP concurrency 2 and notification concurrency 4 by default, independently configurable from 1 to 16. After-commit and scheduler entrypoints only wake a coalescing coordinator. Bound admitted work to pool capacity; keep the backlog durable, claim only during execution, and refill on completion. Provider calls never run on request or scheduler threads and never hold database transactions open.

Serialize OTP by phone and notifications by company/customer, with reminder priority and a locked recheck of state, retry time and current eligibility. Recheck deadlines immediately before invoking the provider. Already-started requests retain their actual outcomes; pending expired work is discarded. Result-persistence uncertainty blocks related work instead of repeating an unknown send. Graceful shutdown stops admission and waits at most 15 seconds across both pools before interruption. Preserve existing restart, retry, budget and reminder-run semantics; introducing RabbitMQ or multiple instances is outside this decision.

## Reminder delivery window extension (2026-10-23)

Extend the delivery deadline from 09:05 to 10:00 Warsaw to give the bounded notification pool more time to drain and recover from definite temporary provider failures. Keep the trigger and eligibility cutoff at 09:00, the same uninterrupted run token, 30-second retries and shared daily attempt budget. Do not start any provider request at or after 10:00; an already-started attempt records its actual outcome. The longer window can consume more budget on temporary failures, but never authorizes retrying accepted or ambiguous sends or recovering a missed run after restart.

## Initialization and operational recovery (2026-10-23)

Retain a live reminder initialization request, including its date and stable run token, until initialization commits or its original 10:00 deadline expires. Later coordinator wakes may retry an initialization transaction that failed; neither a restart nor an expired request reconstructs a missed run. Keep the same token when a commit acknowledgement is uncertain.

Partial `SENDING` lane indexes support anti-joins independently of terminal history. Aggregate count/age metrics and warnings flag unresolved attempts. An operator may resolve a confirmed inactive, stale, unchanged `SENDING` row to `AMBIGUOUS` using guarded transactional scripts, without restarting the application or resending it. Ambiguous reminder resolution pauses related commercial confirmations; OTP resolution scrubs the code. Never automatically resolve a possibly live provider call based on age alone. See [SMS operations](../sms-operations.md) and [synthetic query plans](../verification/sms-queue-query-plans.md).

## Consequences

Delivery intent and its state survive process failure. Provider failure never rolls back an appointment. Database state supports operator investigation without exposing OTP codes or internal cancellation reasons. A real provider must classify definite temporary rejection explicitly; timeouts and unknown failures are ambiguous. See [notification rules](../notifications.md).
