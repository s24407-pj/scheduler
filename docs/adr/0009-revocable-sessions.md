# ADR-0009: Revocable sessions behind JWTs

**Status:** Accepted — implemented

## Context

Customers (SMS OTP) and staff (email and password) receive an HS256 JWT valid for `JWT_EXPIRATION_MS` (24 hours by default). Both frontends store it in `localStorage` and decode its payload for the role and company.

The tokens were purely stateless: there was no logout endpoint, and a leaked token (for example through XSS) stayed valid until it expired. At the same time, `JwtAuthenticationFilter` already reloads the user and employment from PostgreSQL on every request, so the app was paying for a lookup without getting revocation in return.

Options considered:

- **Harden the stateless JWT only** (shorter TTL, secret check). Cheapest, but logout still only clears the browser.
- **Short-lived access JWT plus a rotating refresh token in an HttpOnly cookie.** Best protection against token theft by XSS, but needs cross-origin cookies, CSRF protection for the refresh endpoint, rotation with reuse detection, and silent refresh in both frontends.
- **External identity provider.** MFA and password reset out of the box, but a large migration and a poor fit for phone-first customers and per-employment staff scoping.
- **JWT backed by a Redis session registry.** Revocation with no change to the token contract.

## Decision

**Keep the JWT format and register every token as a session in Redis.**

- `JwtService` creates a session through `SessionStore` and puts its ID in the standard `jti` claim. Redis key `session:<jti>` → user ID, TTL equal to the token lifetime. `user-sessions:<userId>` indexes a user's session IDs.
- `JwtAuthenticationFilter` parses the token once and accepts it only if `session:<jti>` exists and belongs to the loaded user. Tokens without `jti` are rejected.
- `POST /api/auth/logout` revokes the current session. `POST /api/auth/logout-all` revokes all of the user's sessions. Both require authentication and return 204.

The app runs as a single instance with Redis already required ([ADR-0007](./0007-single-instance-deployment.md)), so the registry adds no new infrastructure.

## Consequences

- Logout and "log out everywhere" take effect on the next request. Frontends should call `/api/auth/logout` before clearing their stored token.
- The token contract is unchanged; frontends that decode the payload keep working. Tokens issued before this change have no `jti` and are rejected, so users log in again once.
- Every authenticated request does one extra Redis `GET`.
- Losing or flushing Redis logs everyone out. That is acceptable: Redis also holds OTP and rate-limit state.
- Tokens in `localStorage` can still be stolen by XSS; revocation now limits the damage. Moving the token to an HttpOnly cookie remains possible later without changing the session registry.
