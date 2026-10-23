-- Operator-only: first confirm the provider attempt AND its worker have finished (see docs/sms-operations.md).
-- Preserve attempt history, scrub the secret, and never move unknown work back to a retryable state.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10s';
UPDATE otp_deliveries
SET state = 'AMBIGUOUS', code = NULL, updated_at = CURRENT_TIMESTAMP
WHERE id = :'delivery_id'::uuid AND state = 'SENDING'
  AND updated_at = :'expected_updated_at'::timestamptz
  AND updated_at <= CURRENT_TIMESTAMP - INTERVAL '2 minutes'
RETURNING id, state;
COMMIT;
