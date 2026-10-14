# ADR-0011: Spring Security sessions backed by Redis

**Status:** Accepted — implemented (2026-10-14); supersedes [ADR-0009](./0009-revocable-sessions.md).

## Context

JWTs already depended on a custom Redis registry and a database check on each request. They offered neither stateless operation nor protection from credential theft in localStorage. Maintaining signing, claims, revocation keys and indexes duplicated standard session infrastructure. Redis is already required by OTP, rate limits and cache. Clients are browser SPAs on localhost ports or intended same-site HTTPS deployment.

## Decision

Use Boot 4's official `spring-boot-starter-session-data-redis`, SecurityContext/HttpSession, and the indexed Spring Session Redis repository. Remove JJWT and the custom registry, bearer filter and signing-secret configuration. Old bearer tokens cease to authenticate; deploy clients and API together and require login again.

REST login invokes Spring's session ID rotation and CSRF authentication strategies, then explicitly saves a fresh context after successful credential validation/DB commit. Multi-employment selection stays anonymous. Existing authenticated browsers must log out before changing scope. Principals contain only immutable scalar identity and scope; standard supported Spring Session serialization never includes a User entity, password, OTP or lazy proxy. Redis must remain private/trusted.

The application namespace is `scheduler:sessions`. `spring.session.timeout` defaults to 24 hours, now a standard sliding inactivity timeout rather than absolute JWT expiry. Boot's `spring.session.data.redis.repository-type=indexed` supports `FindByIndexNameSessionRepository`. The explicit principal index is stable account ID across customer and all staff employments. Logout invalidates only this HttpSession, clears the context and expires cookies. Logout-all deletes the account's indexed sessions then logs out this browser. Revocation applies on subsequent requests; already executing requests and concurrent new logins are not cancelled atomically.

Security 7 SPA CSRF protects all unsafe endpoints, including public OTP/password login and logout. `/api/auth/csrf` supplies `XSRF-TOKEN` and the raw `X-XSRF-TOKEN` header value; refresh it after authentication/logout. `SESSION` remains HttpOnly, Path=/, host-only, SameSite=Lax and Secure in prod; localhost HTTP remains usable. Explicit origins and credentialed CORS permit preflight. Unrelated cross-site deployments are not assumed and require a deliberate Secure cookie/topology change.

A small filter validates current user identity and exact staff employment on every authenticated request before authorization. Deleted/reassigned employments, changed company/role, deleted users and changed/recycled phone identities invalidate the session. Never silently update stored scope. Database failures fail closed. Method authorization and resource/company checks remain unchanged.

## Consequences

Browser clients remove localStorage JWTs, bearer headers and claim decoding, send credentials/cookies, attach CSRF headers, refresh CSRF after login/logout, and query `/api/auth/me` for account/scope (`/api/users/me` for profile). Authentication responses contain no credential. XSS cannot directly read SESSION, but can still make same-origin requests, so normal XSS defenses remain necessary.

Redis loss logs users out. Rolling changes to principal serialization require compatibility or deliberate session invalidation. Redis persistence enables requests on other API instances, but scheduled jobs remain single-instance by ADR-0007. No new identity provider, refresh-token protocol, custom session store/index, or parallel bearer compatibility system is introduced.

## Verification

Integration tests cover OTP/staff login, anonymous employment selection, explicit context persistence, cookie-only protected access, bearer rejection, fixation, timeout, real CSRF and CORS, single/all-session logout across employments/customer mode, DB revocation, scope tampering, safe serialization and independent request pipelines restoring Redis contexts.
