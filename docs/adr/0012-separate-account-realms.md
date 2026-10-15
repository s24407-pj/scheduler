# ADR-0012: Separate customer and company-owned staff realms

**Status:** Accepted — implemented (2026-10-15).

## Decision

Global customer accounts contain canonical phone authentication identity only. Salon names, optional contact email, and notes belong to a company/customer profile established by a successful booking. Staff may edit that local profile; notes cannot create a relationship. Customer self-editing cannot alter salon presentation.

Each employment is an independent company-owned staff account with normalized login email, password hash, local names/contact phone/photo, role, enabled state, explicit eligibility mode, and credential version. Authentication requires company plus normalized email plus password. Phone never joins customer and staff identities. Owner provisioning and role changes stay operator-controlled.

Staff public identifiers are independent from customer IDs, while the employment primary key identifies the authentication account. Session, audit and idempotency scopes must include the realm even when numeric identifiers coincide. Spring Session revocation indexes remain `staff:<accountId>:<generation>` and `customer:<accountId>:<generation>`; the expiry policy in ADR-0011 remains CUSTOMER absolute 24h, STAFF idle 24h.

Password setup/recovery stores one token hash per account, expires after 24 elapsed hours, and consumes it once under the account row lock. Replacement and disable invalidate prior links. Password change requires the current password without creating a session. Login and password mutations lock the same credential row; login captures revocation generation before validation. Credential changes increment a durable version checked on every request, then clear Redis sessions after commit. This rejects stale login responses and protects revocation even when cleanup fails.

Company-local photos never update a customer account or another employment. After-commit deletion checks remaining legacy references and rollback removes newly uploaded objects.

## Migration

V3 preserves legacy data as archives while introducing phone-only customer accounts and local profiles/credentials. It does not infer company relationships from arbitrary notes or copy staff email as customer contact information. Phone canonicalization collisions stop migration for operator reconciliation rather than silently merging accounts. V5 adds durable staff credential versions and hash-only one-use links. Existing staff sessions require login again because their usernames/versions no longer match the current account.
