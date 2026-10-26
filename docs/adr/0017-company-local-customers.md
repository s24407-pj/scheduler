# ADR-0017: Company-local customer identity and OTP

**Status:** Accepted (2026-10-24), explicitly authorized with a fresh consolidated V1 baseline.

## Decision

Replace global customer accounts with one company-owned `company_customers` identity per canonical phone. Its UUID primary key is the customer ID in reservations, quotes, blocks, penalties and notifications. Remove `user_id` and `customer_accounts`; use composite company/customer foreign keys. Equal phones across companies establish no shared identity, permissions, presentation or history. Staff credentials remain separate.

Both OTP endpoints require `companyId`; challenge keys and durable delivery intent include company/phone. Missing companies return 404 before issuance/consumption. Code replacement/consumption affects only that salon. Phone-wide cooldown/issuance limits, IP limits, daily budgets and provider phone lanes remain shared and fail closed. Preserve original validity, attempt bounds and conservative delivery/recovery outcomes.

Successful verification atomically upserts a pending phone identity without names. The first successful customer or staff booking requires names and sets `first_booking_at` in the booking transaction. Pending identities are invisible to every staff customer-profile operation, including notes, blocking and visits. Failed booking leaves the marker unset; later archive/cancellation does not hide an established customer. Existing profile edits remain staff-only.

Customer principals contain local account ID, phone and company, with no employment. Revalidate that exact record on every request. Session/command/audit/idempotency account keys are `customer:<companyId>:<customerId>`. Preserve 24-hour absolute customer expiry, indexed Redis sessions, generation revocation and logout-before-switching. All protected customer writes carry captured `v1:<customerId>:<companyId>:null:ROLE_CUSTOMER` context, with the existing 400/409 errors and public-auth/logout exclusions. Foreign booking/quote/lifecycle resources return opaque 404.

## Compatibility and baseline

This supersedes global-customer identity/scope in ADR-0011/0012, the first-booking profile-creation rule in ADR-0016, and refines OTP outbox scoping in ADR-0015. Other session, staff, booking, commercial and delivery decisions remain effective.

Consolidate former V1–V4 and this model into `V1__init_schema.sql` for fresh databases only. Existing development databases must be explicitly recreated and their Redis sessions/OTP/cache state cleared; the application never resets an environment. No upgrade/data migration or checksum bypass is supplied. Principal serialization version 3 rejects old contexts. Coordinate client/API deployment for required OTP company IDs and customer context headers. `/api/auth/me.companyId` is populated for customers; `/api/users/me` retains `{id,phoneNumber}`.

## Verification

Use real PostgreSQL and Redis regression coverage for company isolation, shared phone limits, scoped OTP consumption/replacement and outbox cleanup, pending visibility, atomic first-booking completion/rollback, concurrent identity creation, scoped history/quotes/lifecycle, context mismatch, revocation and fresh V1 startup. Existing reservation and SMS failure/recovery coverage remains required.
