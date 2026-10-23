# Durable SMS delivery

Customer booking has page/API confirmation only: no confirmation SMS or email. Actual staff create, edit, restore or cancel operations may opt into SMS; the default is off. Cancellation messages are eligible only while the visit is future or ongoing. An actual edit opting out still invalidates obsolete pending staff confirmations. Internal cancellation reasons are never rendered into customer messages.

`ReservationChangedEvent` and `CustomerBlockedBookingsEvent` produce outbox rows before the booking transaction commits. A rollback therefore sends nothing and leaves no eligible delivery. Provider calls run only after commit, outside the claiming transaction, and provider failures cannot roll back the booking. Each provider attempt first commits `SENDING`, then records the provider result in a separate transaction:

| Result | Durable outcome | Automatic retry |
| --- | --- | --- |
| `ACCEPTED` | `ACCEPTED` | Never |
| `DEFINITE_TEMPORARY_FAILURE` | `TEMPORARY_FAILURE`, or `DISCARDED` at the deadline | Every 30 seconds while eligible |
| `AMBIGUOUS` or unclassified exception | `AMBIGUOUS` | Never |

Other durable states are `PENDING`, `PAUSED` and `DISCARDED`. On restart, an old `SENDING` row becomes `AMBIGUOUS`: the provider may have accepted it even when the process failed before recording the response. This may miss an SMS if the crash happened before the provider call. It avoids blindly repeating unknown sends and makes no exactly-once guarantee. A legacy adapter's successful `sendMessage` implies acceptance; modern adapters should override `deliverMessage` to classify definite temporary failure accurately.

For an appointment still in the future, staff-message retries stop at its current start. A reschedule invalidates obsolete messages; a new opted-in edit uses the new start as its deadline. An appointment already ongoing gets one provider attempt while its end is still future. The dispatcher reads accepted service names from the reservation and current customer phone, company name and company-local employee names; it never reconstructs historical names from the current catalog or global employee profiles.

Company blocking produces one mandatory summary. Its original affected reservation IDs are durable, but each attempt includes only records still cancelled and strictly future at that company for that customer. Restored records drop out; an empty summary is discarded. Definite temporary failures retry while at least one affected future cancellation remains.

## Isolated SMS workers

PostgreSQL outboxes are the durable queues. After-commit listeners, startup wakeups and periodic triggers only signal a coordinator and return; neither an HTTP request nor Spring's shared scheduler waits for provider calls. Each module completes recovery before accepting wakeups. The reminder trigger requests run initialization on the notification coordinator, so even its database work stays off the cron thread.

OTP has two provider threads by default and notifications have four. Configure `sms.worker.otp-concurrency` / `SMS_WORKER_OTP_CONCURRENCY` and `sms.worker.notification-concurrency` / `SMS_WORKER_NOTIFICATION_CONCURRENCY` (both 1–16). Each pool has its own coordinator; wakeups are coalesced. No more references are admitted, including executor-queued tasks, than the configured concurrency. Payloads are loaded only on execution. Excess work stays in the database, and completing a task immediately refills a free slot. There is no caller-runs fallback. A task/preparation failure releases its slot; polling remains a fallback for deferred work.

OTP is serialized per canonical phone (the coordinator uses a transient MD5 routing key, never a raw phone or OTP). Notifications are serialized per `(companyId, customerId)`, including summaries. SQL selects one eligible candidate per free lane and excludes unresolved `SENDING` records in that lane; unrelated customers can proceed even when one customer's backlog is large. Reminder candidates have priority, then deadline and ID order. Existing accepted/ambiguous reminder suppression still runs before another related confirmation can start. An unresolved result-persistence failure can block that lane until recovery (or OTP expiry cleanup); it must never trigger a blind resend.

Claim transactions recheck state and `next_attempt_at`, current data and deadlines, then commit `SENDING` before the provider call. Eligibility is checked again after the claim: a reminder cannot begin at or after 09:05, and an OTP cannot begin after expiry or consumption/replacement. A request that already started may finish after its deadline and records its actual provider outcome. Live OTP expiry cleanup excludes admitted lanes, so it does not overwrite a running attempt's result. Safe logs carry an MDC `smsDeliveryId` for correlation with the adapter's individual `attemptId`; phone/lane/content/OTP are never logged or used as metric tags.

On shutdown, stop admission in both pools first and allow at most 15 seconds total for admitted work to finish while repositories and Redis remain available. Interrupt unfinished work after the grace period; unknown `SENDING` outcomes retain the existing conservative restart recovery. No database schema or REST response changes are needed. This remains a single-instance design: moving workers to other instances requires revisiting reminder ownership and scheduling coordination.

Worker metrics use only `kind=otp|notification`: `scheduler.sms.worker.admitted` counts admitted references, `scheduler.sms.worker.pending` is the last observed durable pending/retry backlog (including future retries), and `scheduler.sms.worker.wait` records the age of a work item when an execution begins. For retries this age includes time since original creation. Watch sustained slot saturation, growing backlog and waiting time approaching OTP/reminder deadlines. Concurrency bounds simultaneous calls, not requests per second or spending; retain provider limits and the shared daily attempt budget.

## OTP delivery

OTP requests also persist phone-only intent in `otp_deliveries` before commit, without creating a customer account or company profile. Every provider attempt commits `SENDING` first. Successful legacy `sendOtp` maps to `ACCEPTED`; modern adapters can override `deliverOtp` to distinguish a definite temporary failure from an ambiguous outcome. Unknown exceptions are ambiguous and are never blindly retried.

A pending/temporarily rejected code can retry only within its remaining original five-minute Redis lifetime, while that exact code remains current, unconsumed and below its verification-attempt limit. Replacement invalidates older pending delivery; a delayed old issuance callback cannot invalidate the newer code. Successful verification scrubs matching pending intent, and the worker independently detects consumption/expiry. Terminal/expired records clear plaintext code and retain metadata. Restart marks an unknown `SENDING` record `AMBIGUOUS` and clears its secret; live pending work may resume. Issuance callback identity is durable, so repeating the same callback does not add another delivery.

Atomic issuance reserves the first provider attempt against the shared UTC-day SMS budget. Each definite-temporary retry reserves another allowance without changing accepted phone-issuance or cooldown counters. A first attempt delayed across UTC midnight reserves against the new day. Budget denial can never extend the original OTP lifetime; a next allowance beyond expiry discards the delivery. Failed/ambiguous attempts never refund capacity.

## Reminders

The sole scheduled reminder trigger runs at **09:00 Europe/Warsaw on the previous calendar day**, with a fixed **09:05** deadline. It has an in-memory run UUID also attached to durable reminder rows. Definite temporary failures retry only while that same uninterrupted run remains active and before its deadline. Restart discards uncompleted reminder work and never reconstructs a missed run. Automatic reservation completion is a separate reservation job that does catch up after restart.

Creation and any appointment start-time move after the 09:00 cutoff exclude a booking from that day's reminder run, including a move within the same date. The dispatcher checks durable reservation audit timestamps and before/after start instants, not a `reminder_sent` boolean. Non-time edits preserve cutoff eligibility, and same-date edits retain the reminder identity. A different appointment date may receive a new reminder on its corresponding previous-day run. Database uniqueness enforces one reminder record per reservation and appointment date.

An accepted reminder discards obsolete pending commercial confirmations for the covered revision/date. An ambiguous reminder pauses potentially conflicting same-date confirmations. Those states remain inspectable for operator handling; ambiguous deliveries are never automatically retried.

## Operations

V1 runs one backend instance. `scheduler.jobs.enabled=false` disables automatic scheduler/startup entrypoints in tests; tests call dispatcher/recovery methods explicitly. Production defaults to enabled. The shared Redis `otp:global-daily` UTC-day send reservation budget includes the pre-reserved first OTP attempt, additional OTP retry attempts and notification provider attempts. Production requires positive `OTP_GLOBAL_DAILY_SMS_CAP`. Attempts and failed/ambiguous or rolled-back reservations do not refund the conservative budget.

`ConsoleSmsSender` is a development adapter only. Production uses JustSend's single-message API with the `ECO` variant, a configured App-Key and sender, and canonical phone digits without `+`. OTP text is `Kod weryfikacyjny: {code}`; appointment text is passed through unchanged. A 2xx HTTP response means `ACCEPTED`, 429 means `DEFINITE_TEMPORARY_FAILURE`, and all other responses or transport failures mean `AMBIGUOUS`. Connection timeout is 3 seconds and response timeout is 10 seconds. Provider error bodies are ignored; delivery-status postbacks are not integrated. Delivery state persists in `notification_deliveries` and summary membership in `notification_delivery_reservations`; the old reservation `reminder_sent` column is not authoritative.


## SMS observability and segment estimates

Every JustSend invocation gets a fresh random `attemptId`, shared by its multipart warning and completion log. It identifies that adapter invocation, not the durable outbox row or the provider message ID. Completion logs carry `httpStatus` (null if no response), `exceptionType` (class name only, null on success), monotonic `durationMs`, delivery result, encoding and estimated segment count. No API key, phone number, message content, OTP, response body, exception message or stack trace is logged. These metadata fields are structured key/value fields in production ECS logs; existing request MDC remains available.

`scheduler.sms.segments.attempted` is a Micrometer counter with only bounded `encoding` (`GSM7`/`UCS2`) and `result` tags. Each invocation adds its estimated segments, including rejected and ambiguous attempts; it counts neither confirmed delivery nor provider charges. No attempt IDs, phone numbers or content become metric tags. The counter resets with the process; collect it in the existing metrics infrastructure for historical totals. Actuator access restrictions remain in force.

Estimates use GSM-7's 160-septet single-part / 153-septet multipart capacities, with extension-table characters costing two septets. Other characters (including Polish diacritics and `—`) select UCS-2 for the whole message: 70 UTF-16 units for one part, 67 per multipart segment, with supplementary characters costing two units. Escape and surrogate pairs stay together. No content is shortened or transliterated. Provider transformations and actual billing may differ from these estimates. See [encoding rules](https://developers.gms.net/cpaas/sms-encoding/).

Every message estimated at more than one segment emits a WARN before dispatch, with only the attempt ID, encoding, unit count and segment count. Representative Polish appointment templates currently take two segments; permitted long names can take ten, and cancellation summaries grow with the number of appointments. Tests render the real templates and assert these lengths. Monitor increases in multipart warnings and in segment-counter rate, rather than treating send count as cost.

`OTP_GLOBAL_DAILY_SMS_CAP` continues to reserve **provider attempts**, not segments. Segment estimation and warnings add visibility; they do not impose a hard spending or per-message limit. A multi-part send can consume several billable segments for one daily-budget reservation. Set an independent provider spending limit where available if a hard cost bound is required.
