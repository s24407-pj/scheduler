# ADR-0004: Error contract based on ProblemDetail

**Status:** Accepted — implemented

## Context

Errors used to be returned as `ApiError { message }` or `ValidationError { message, errors }`, with status codes derived from generic exception types (`IllegalArgumentException` → 400, `IllegalStateException` → 409, `NoSuchElementException` → 404). That produced inconsistencies:

- A missing reservation returned 400 on cancel/complete but 404 on no-show/delete.
- Accessing another company's resource returned 409 for reservations but 403 for offerings.
- A request without a JWT returned 403 with no body, the same status as "role not allowed".
- Database constraint violations and `@Version` conflicts returned 500.
- Messages mixed Polish and English and there was no machine-readable code, so clients had to match human text.

The existing frontend is a demo that will be rebuilt, so no backward-compatible `message` field is kept.

## Decision

1. **Format:** RFC 9457 Problem Details using Spring's `ProblemDetail`, content type `application/problem+json`.

   ```json
   {
     "type": "about:blank",
     "title": "Conflict",
     "status": 409,
     "detail": "Ten termin jest już zajęty",
     "instance": "/api/reservations",
     "code": "SLOT_UNAVAILABLE"
   }
   ```

   - `detail`: human-readable message in Polish, safe to show to end users.
   - `code`: stable, English, `UPPER_SNAKE_CASE`. Clients branch on this, never on text.
   - `errors`: present only for validation failures, a map of field name to message.

2. **Coded exceptions:** services throw `ApiException(ErrorCode, detail)` (`config/ApiException.kt`). Each `ErrorCode` entry owns its HTTP status, so the status and code cannot drift apart. One exception type with an enum was chosen over one class per error to keep the code list in a single reviewable place. Generic `IllegalArgumentException` / `IllegalStateException` / `NoSuchElementException` mappings remain as a fallback with the generic codes `BAD_REQUEST`, `CONFLICT`, and `NOT_FOUND`.

3. **Status code rules:**

   | Situation | Status |
   | --------- | ------ |
   | Malformed body, invalid parameter, DTO validation failure, business-rule rejection of the request itself (blocked customer, minimum advance) | 400 |
   | Missing, invalid, or revoked JWT | 401 |
   | Authenticated but not allowed (role, or another employee's resource in the same company) | 403 |
   | Resource does not exist, **or belongs to another company** | 404 |
   | State conflict: slot taken, invalid status transition, concurrent modification, constraint violation | 409 |
   | Rate limit exceeded | 429 |
   | Unexpected failure | 500 |

   Returning 404 for other companies' resources avoids revealing that an ID exists. A request body that references an unknown or inactive offering (for example `serviceId` in a booking) is a 400 `OFFERING_UNAVAILABLE`, because the requested URL itself exists.

4. **Security errors use the same format:** `SecurityConfig` registers an `AuthenticationEntryPoint` that delegates to the MVC `HandlerExceptionResolver`, so `RestExceptionHandler` renders 401 responses too.

## Consequences

- Every error body, including framework and security errors, has `status`, `detail`, and `code`. The full code list is in [API errors](../api/errors.md) and is part of the public contract: renaming a code is a breaking change.
- New business errors need an `ErrorCode` entry; tests assert on `code` rather than on message text.
- Modules not yet migrated to `ApiException` (offerings, categories, users, work schedules) still produce the generic fallback codes. Migrate them when they are next touched.
