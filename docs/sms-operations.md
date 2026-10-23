# Investigating unresolved SMS attempts

A provider response can succeed while recording its result fails. The durable row then remains `SENDING` and intentionally blocks later work in the same phone or company/customer lane. Do not change it to `PENDING`/`TEMPORARY_FAILURE`, delete it, or replay its event. An unknown result never proves a safe retry.

## Detection

Both pools expose `scheduler.sms.worker.sending` (last observed count) and `scheduler.sms.worker.sending.oldest.age` in seconds. Tags contain only `kind=otp|notification`. Age continues to increase from the last observed timestamp even if a later database read fails; diagnostics are sampled on coordinator wake/completion, with the normal 30-second polling fallback. Diagnostics are best-effort: if either backlog or sending-statistics read fails, the previous snapshot is retained and selected delivery work still runs and refills the pool. A diagnostic failure logs only its exception type. They cannot discover a new database state during a complete database outage. Check database availability as well.

An age of at least two minutes emits a safe WARN on observation. This is an investigation threshold, not an automatic resolution timeout. An active but unusually delayed request must not be resolved just because it crossed that threshold.

The example Prometheus rule in [sms-alerts.yml](../perf-tests/sms-alerts.yml) evaluates `scheduler_sms_worker_sending_oldest_age_seconds > 120` for one minute. The optional performance Compose stack loads it. Production collectors must load the rule and have an approved way to collect metrics; configuring Alertmanager/notification routing is deployment-specific. Current application security permits public health only and denies other actuator routes. This change does not expose metrics or grant new access: do not assume the sample HTTP scraper works without an explicitly secured collection arrangement. Log warnings remain available through normal application logging.

List only safe metadata from the affected database (these queries reveal no phones, bodies or codes):

```sql
SELECT id, kind, state, attempts, updated_at
FROM notification_deliveries
WHERE state = 'SENDING' AND updated_at <= CURRENT_TIMESTAMP - INTERVAL '2 minutes'
ORDER BY updated_at;

SELECT id, state, attempts, updated_at
FROM otp_deliveries
WHERE state = 'SENDING' AND updated_at <= CURRENT_TIMESTAMP - INTERVAL '2 minutes'
ORDER BY updated_at;
```

## Resolution without restarting the application

1. Correlate the delivery UUID with the `smsDeliveryId` MDC and the adapter's `attemptId`. Confirm both a completed provider attempt and the subsequent worker/result-persistence failure. The worker must have finished handling that UUID. A provider-completion log alone does not prove the worker has finished its database transaction. If completion cannot be established, leave the record blocked and investigate the running worker; use controlled application shutdown/recovery if quiescence cannot otherwise be established.
2. Check whether another attempt is active in that lane and investigate it first. The stale age and low admitted-count metric alone are insufficient proof that this specific work is inactive. Do not run a resolution against a live provider call.
3. Record the incident, delivery UUID, observed `updated_at`, and supporting completion/failure evidence. Use the least-privileged approved database maintenance connection, not an application endpoint. Copy the complete timestamp including timezone and microseconds.
4. Run the matching checked-in script, providing the UUID and exact observed timestamp. For example, with an operator-configured PostgreSQL service:

```bash
PGSERVICE=scheduler-ops psql -X --set=ON_ERROR_STOP=1 \
  --set=delivery_id='00000000-0000-0000-0000-000000000001' \
  --set=expected_updated_at='2026-10-23 12:00:00.123456+00' \
  --file=scripts/ops/resolve-notification-sending.sql

# For OTP use scripts/ops/resolve-otp-sending.sql with that OTP delivery's own UUID/timestamp.
```

The scripts require `SENDING`, age of at least two minutes, and the unchanged timestamp. They use short lock/statement timeouts and one transaction. A notification becomes `AMBIGUOUS`; an ambiguous reminder also atomically pauses pending/temporarily failed CREATE/EDIT/RESTORE messages for that reservation/date. OTP becomes `AMBIGUOUS` with `code=NULL`. Attempts, identity and historical intent remain. A repeated command or a row changed by a late completion is a no-op. Zero returned rows means nothing was resolved: re-read metadata and investigate instead of weakening the guards.

5. Verify the terminal state, unchanged attempt count, scrubbed OTP secret (query only `code IS NULL`), and paused confirmations where applicable. The next normal wake can process other eligible work in the lane; the resolved SMS is never sent again. An OTP user may request a fresh code subject to existing cooldown/rate limits. Record the returned UUID/state in the incident; do not record secrets.

Resolution does not assert actual delivery and does not create new reminder runs. Reminder initialization requests exist only in the live process, retry until 10:00, and are forgotten on restart.
