# ADR-0011: Spring Security sessions backed by Redis

**Status:** Accepted — implemented (2026-10-14); supersedes [ADR-0009](./0009-revocable-sessions.md).

Customer identity/scope and OTP/profile establishment rules are superseded where applicable by [ADR-0017](./0017-company-local-customers.md). Other decisions remain in force.

Staff password-only session establishment is superseded by [ADR-0022](./0022-mandatory-staff-totp.md).

## Context

JWTs already depended on a custom Redis registry and a database check on each request. They offered neither stateless operation nor protection from credential theft in localStorage. Maintaining signing, claims, revocation keys and indexes duplicated standard session infrastructure. Redis is already required by OTP, rate limits and cache. Clients are browser SPAs on localhost ports or intended same-site HTTPS deployment.

## Decision

Use Boot 4's official `spring-boot-starter-session-data-redis`, SecurityContext/HttpSession, and the indexed Spring Session Redis repository. Remove JJWT and the custom registry, bearer filter and signing-secret configuration. Old bearer tokens cease to authenticate; deploy clients and API together and require login again.

REST login invokes Spring's session ID rotation and CSRF authentication strategies, then explicitly saves a fresh context after successful credential validation/DB commit. Staff login requires its company namespace; there is no employment selection flow. Existing authenticated browsers must log out before changing scope. Principals contain only immutable scalar identity and scope; standard supported Spring Session serialization never includes a User entity, password, OTP or lazy proxy. Redis must remain private/trusted.

The application namespace is `scheduler:sessions`. CUSTOMER sessions expire absolutely 24 elapsed hours after login; activity never moves the deadline. STAFF sessions use a 24-hour inactivity timeout and activity refreshes idle expiry. The application enforces these authenticated durations, independently of the anonymous session default. Customer deadlines use the injected business clock and are checked before authorization, including the exact expiry boundary.

Boot's `spring.session.data.redis.repository-type=indexed` supports `FindByIndexNameSessionRepository`. Principal indexes are scoped to `customer:<accountId>:<generation>` or `staff:<staffAccountId>:<generation>`; the employment primary key identifies the company-owned staff account. Customer and staff sessions are independent even when legacy data refers to the same user. Logout invalidates only this HttpSession. Logout-all atomically increments the account's Redis generation before deleting sessions from the preceding generation and clearing this browser. Generation keys never expire. Every authenticated request verifies its stored generation. A delayed session save after revocation cannot regain authorization, and a concurrent login that captures the new generation remains indexed separately. Already-authorized requests may finish.

`AccountSessionRegistry.revokeAccount` is the shared revocation operation for password change/reset workflows. Updating credentials must revoke the corresponding staff account without authenticating the password-check request.

Security 7 SPA CSRF protects all unsafe endpoints, including public OTP/password login and logout. `/api/auth/csrf` supplies `XSRF-TOKEN` and the raw `X-XSRF-TOKEN` header value; refresh it after authentication/logout. `SESSION` remains HttpOnly, Path=/, host-only, SameSite=Lax and Secure in prod; localhost HTTP remains usable. Explicit origins and credentialed CORS permit preflight. Unrelated cross-site deployments are not assumed and require a deliberate Secure cookie/topology change.

A small filter validates current user identity and exact staff employment on every authenticated request before authorization. Deleted/reassigned employments, changed company/role, deleted users and changed/recycled phone identities invalidate the session. Never silently update stored scope. Database failures fail closed. Method authorization and resource/company checks remain unchanged.

## Consequences

Browser clients remove localStorage JWTs, bearer headers and claim decoding, send credentials/cookies, attach CSRF headers, refresh CSRF after login/logout, and query `/api/auth/me` for account/scope (`/api/users/me` for profile). Authentication responses contain no credential. XSS cannot directly read SESSION, but can still make same-origin requests, so normal XSS defenses remain necessary.

Sessions created before generation/deadline attributes were introduced require login again. Redis loss logs users out. Rolling changes to principal serialization require compatibility or deliberate session invalidation. Redis persistence enables requests on other API instances, but scheduled jobs remain single-instance by ADR-0007. Spring Session owns session persistence and principal indexes; the application adds only account generation revocation markers. No new identity provider, refresh-token protocol, or parallel bearer compatibility system is introduced.

## Verification

Integration tests cover OTP/staff login, explicit company-owned credentials, explicit context persistence, cookie-only protected access, bearer rejection, fixation, timeout, real CSRF and CORS, single-session logout, account-isolated logout-all, exact customer expiry, staff idle refresh, delayed login saves after revocation, DB revocation, scope tampering, safe serialization and independent request pipelines restoring Redis contexts.
