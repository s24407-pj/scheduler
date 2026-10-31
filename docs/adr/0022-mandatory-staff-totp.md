# ADR-0022: Mandatory company-scoped staff TOTP

**Status:** Accepted (2026-10-31). Supersedes the staff password-only authentication/session-establishment portions of ADR-0011 and ADR-0012; other identity and session rules remain in force.

Recovery resumption, post-commit allowance cleanup and the exact Redis encryption scope are refined by [ADR-0023](./0023-resumable-totp-recovery.md).

## Decision

Every OWNER and EMPLOYEE must enroll and prove a company-owned TOTP credential before receiving an authenticated staff session. Customers retain company-local SMS OTP. A successful password check, including company-scoped fallback or email-first company selection, returns only a browser-bound challenge. No partially authenticated staff principal exists.

Use RFC 6238 HMAC-SHA1, six ASCII digits, 30-second Unix-time steps and a ±1-step window. Persist the last accepted counter under the account row lock and accept only strictly newer counters. Secrets have 160 random bits. Standard otpauth URIs include Scheduler, company/account identification, issuer, algorithm, digits and period; QR generation stays local to the dashboard.

Store active secrets as versioned AES-256-GCM envelopes bound to the employment ID. TOTP_ENCRYPTION_KEY is a persistent private 32-byte Base64 key, independent of PostgreSQL/Redis. Redis challenge payloads are also encrypted and bound to their hashed token lookup. Tokens have 256 random bits, expire after ten minutes, bind the anonymous SESSION and preserve exact identity/auth_version/generation. Selection retains its existing 120-second lifetime. Proof submission claims its grant atomically before database verification; an incorrect code restores the same token with its original deadline and failure budget. Concurrent submissions cannot both claim a grant. Invalid/expired/incompatible/wrong-browser grants fail without authenticating. Redis admission and grant failures fail closed.

Reserve failure allowances atomically before verification: five per challenge, ten per account per five minutes. A successful proof releases its allowance; unsuccessful attempts retain it. IP admission counts all requests, at most thirty per five minutes, using remoteAddr. Redis TTLs are not refreshed by attempts. Exhaustion returns 429 and Retry-After.

Only owners receive ten one-use 128-bit recovery codes. Display them before activation and store only SHA-256 after successful enrollment. A password plus recovery code authorizes replacement enrollment only, not panel access. Consume the code immediately; keep the previous TOTP active until the replacement is confirmed. Confirmation atomically replaces the secret and all recovery hashes and advances auth_version. An abandoned recovery does not restore its spent code.

Owners reset only company-local EMPLOYEE accounts after verifying their own current password and an unused TOTP. Reset removes the employee secret and advances auth_version, requiring enrollment at next login. Password changes/reset never clear TOTP. There is no staff API to disable 2FA or reset an owner. Transfer between phones uses the authenticator's own capabilities. Owner recovery without any second factor is an operator procedure with independent identity verification.

Staff sessions contain a server-only MFA proof equal to their auth_version. Generic password-verification attributes never include that proof. All authorization checks require it together with the existing principal/version/generation checks. Enrollment/recovery/reset invalidate old sessions and grants durably, even if Redis cleanup is delayed. A successful enrollment retains the challenge's original generation: never rebase a stale verification onto a newer logout-all generation. Authentication still occurs after commit through the existing session-fixation and CSRF strategies.

## Delivery

Apply additive Flyway V6; existing migration checksums are unchanged. Deploy the matching API/dashboard together before production. Old staff sessions lack the MFA proof and cease to authorize; customers are unaffected. Configure and back up the encryption key before deployment. Roll back both applications together under maintenance; rolling back to password-only code withdraws mandatory 2FA and must be an explicit operator decision. Do not drop V6 columns or recovery data during an application rollback.

## Verification

RFC vectors, encrypted-envelope binding, cookie/CSRF enrollment, all login entrypoints, replay/concurrency, company isolation, owner recovery, employee reset, limits, expiry and session revocation are covered by automated tests. Real browser helpers use an independent test authenticator and private temporary credentials, never a runtime bypass. Physical Google/Microsoft Authenticator scans remain a manual acceptance check.
