# ADR-0023: Resumable TOTP recovery and post-commit cleanup

**Status:** Accepted (2026-10-31). Refines ADR-0022's recovery grant lifecycle, limiter cleanup failure behavior and encryption scope; its remaining decisions stay in force.

## Decision

Consume an owner recovery code and persist a recovery authorization for the original challenge's SHA-256 token hash in one PostgreSQL transaction under the account lock. Keep the original browser-bound VERIFY challenge in Redis. Return that same token with TOTP_SETUP_REQUIRED, without issuing another Redis grant after consumption. Retrying recovery on this authorized challenge reuses the authorization, without checking or spending another recovery code. A different challenge cannot use the spent code or authorization.

The original challenge can start replacement setup directly after a lost recovery response. Setup checks the durable authorization and exact account state before disclosing a new secret. Retain this challenge when issuing a CONFIRM grant, allowing setup to be repeated after a failed Redis write or a lost setup response. Each setup attempt generates a fresh secret and code set; confirmation of any one attempt advances auth_version, rejecting all other pending attempts. Confirmation removes the account's recovery authorizations together with replacing the recovery codes.

Authorization retains the original ten-minute deadline and anonymous browser/session binding, requires unchanged generation/auth_version and grants replacement enrollment only. An expired or lost original Redis challenge cannot be reconstructed from a spent recovery code. Abandonment leaves the code spent. Retained receipts contain only account IDs and token hashes, and are bounded by the ten recovery codes per enrolled credential; they disappear on replacement confirmation or account deletion.

Redis admission and grant reads/writes still fail closed. Releasing a successful proof's reserved failure allowance is best-effort after the SQL transaction commits. Cleanup failure emits a safe warning and retains the allowance until its original TTL; it must not turn committed verify, confirm, recover or reset into an API failure. Subsequent session establishment still depends on Redis and preserves existing fail-closed behavior.

ADR-0022's encrypted Redis challenge payloads mean `TotpGrantStore` payloads, including pending TOTP secrets and recovery codes. `StaffLoginSelectionStore` retains its existing plaintext JSON scalar account snapshots and hashed browser binding, with a 120-second TTL. It contains no passwords, password hashes, TOTP secrets or recovery codes. This clarification does not introduce encryption for company-selection payloads.

## Delivery and compatibility

Apply additive Flyway V7; do not modify V6. HTTP request/response shapes are unchanged. Consumers must treat challenge tokens as opaque: recovery can return the original token. They may retry recovery or setup with that token in the same anonymous browser before its original deadline. No automatic replay of a committed TOTP proof is introduced.

This retry behavior is consumer-visible and requires paired dashboard PR evidence and Dashboard CI pinned to the exact backend head before merge/deployment, per the development guide. The existing dashboard retains the challenge and recovery input after request failure and uses the returned token for setup. Add paired regression evidence for lost recovery/setup responses. Deploy the backend migration before the matching dashboard; a backend rollback abandons these resumable authorizations but leaves existing TOTP secrets and code consumption intact. Keep V7 during rollback.

## Verification

HTTP integration tests cover the last recovery code, lost recovery/setup responses, failed Redis setup writes, same-challenge retries without a recovery proof, wrong browsers/challenges, prevention of reuse in a fresh challenge, replacement confirmation and receipt cleanup. Redis allowance-release failure is injected after real SQL commits for enrollment, verification, recovery and employee reset; responses and durable state must remain successful.
