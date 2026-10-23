-- Operator-only: first confirm the provider attempt AND its worker have finished (see docs/sms-operations.md).
-- Never use this to retry an unknown SMS. Parameters are quoted by psql, not interpolated as SQL identifiers.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10s';
WITH resolved AS (
    UPDATE notification_deliveries
    SET state = 'AMBIGUOUS', updated_at = CURRENT_TIMESTAMP
    WHERE id = :'delivery_id'::uuid AND state = 'SENDING'
      AND updated_at = :'expected_updated_at'::timestamptz
      AND updated_at <= CURRENT_TIMESTAMP - INTERVAL '2 minutes'
    RETURNING id, kind, reservation_id, appointment_date
), paused AS (
    UPDATE notification_deliveries d
    SET state = 'PAUSED', updated_at = CURRENT_TIMESTAMP
    FROM resolved r
    WHERE r.kind = 'REMINDER' AND d.reservation_id = r.reservation_id
      AND d.appointment_date = r.appointment_date
      AND d.kind IN ('CREATE', 'EDIT', 'RESTORE') AND d.state IN ('PENDING', 'TEMPORARY_FAILURE')
    RETURNING d.id
)
SELECT id, kind, (SELECT count(*) FROM paused) AS paused_confirmations FROM resolved;
COMMIT;
