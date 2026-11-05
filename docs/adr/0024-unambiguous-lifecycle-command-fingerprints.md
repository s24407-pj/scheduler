# ADR-0024: Unambiguous lifecycle command fingerprints

**Status:** Accepted — implemented (2026-11-05). Refines lifecycle retries in [ADR-0016](./0016-reservation-contract-and-durable-submissions.md).

## Context

Lifecycle commands retained only SHA-256 of `List.toString()`. That representation loses field boundaries and types: reason `a, b` with explanation `c` collides with reason `a` and explanation `b, c`; null also collides with the text `null`. SHA-256 cannot recover information already lost before hashing. A changed command could therefore receive a successful replay acknowledgement.

## Decision

Hash the UTF-8 JSON array `[reason, customerExplanation, notifyCustomer, overrideEmployeeBlock]`, retaining null values, string escaping and boolean types in fixed positions. The authenticated account, reservation, target status and expected revision remain the durable command scope. An exact new-format retry succeeds only while the original outcome has not been reversed. Changed requests return the existing `409 INVALID_STATUS_TRANSITION` before mutation.

Do not fall back to the ambiguous hash. Existing rows contain no complete original request, so they cannot be safely rehashed. Preserve those rows and their successful outcomes; a pre-upgrade command retry returns a conflict and asks the caller to refresh the appointment and inspect its result. It must never execute again or be rebound to a new payload. Booking submission fingerprints and permanent booking POST replay are unchanged.

## Rollout and verification

No schema migration or history rewrite is required. Refresh open clients during rollout. A previously uncertain lifecycle operation must be resolved from current state after the conflict, rather than automatically resubmitted. Dashboard conflict handling already refreshes lifecycle state; no new error code or endpoint is introduced. A rollback likewise rejects new-format command retries with a conflict while retaining outcomes; it cannot safely restore the ambiguous implementation as a long-term solution.

Integration regressions cover field-boundary collisions, null versus text in both explanation fields, Unicode and escaped text, changed options, exact retries and preserved pre-upgrade rows/audit. Retain the dashboard's lifecycle conflict/recovery transport and verify its refresh behavior. Regenerate API artifacts and verify both working trees; exact-head pinning and paired CI evidence are required when these changes are committed and prepared for deployment.
