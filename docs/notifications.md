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
