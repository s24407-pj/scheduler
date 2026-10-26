# Error contract

Every error is RFC 9457 Problem Details (`application/problem+json`) with stable top-level `code`, status and Polish `detail`. DTO validation additionally includes `errors` mapping field names to Polish messages. Raw database constraint values, credentials and internal exception messages are never returned.

| Status | Meaning |
| --- | --- |
| 400 | Invalid input, unknown JSON fields, fractional integer values, malformed/offset-bearing or ambiguous appointment times |
| 401 | Missing/revoked/expired authentication or failed credentials/OTP |
| 403 | Authenticated role lacks permission or CSRF is invalid |
| 404 | Opaque nonexistent/foreign resource, including absent local customer relationship |
| 409 | Booking/current-rule, revision, lifecycle, idempotency or expected session-context conflict |
| 429 | Atomic request/SMS allowance exceeded; includes Retry-After seconds |

| Code | HTTP |
| --- | --- |
| `VALIDATION_FAILED` | 400 |
| `MALFORMED_REQUEST` | 400 |
| `INVALID_PARAMETER` | 400 |
| `BAD_REQUEST` | 400 |
| `INVALID_LOCAL_TIME` | 400 |
| `OFFERING_UNAVAILABLE` | 409 |
| `EMPLOYEE_NOT_IN_COMPANY` | 400 |
| `EMPLOYEE_OFFERING_NOT_ASSIGNED` | 409 |
| `CUSTOMER_BLOCKED` | 409 |
| `BOOKING_ADVANCE_REQUIRED` | 409 |
| `CUSTOMER_NAME_REQUIRED` | 400 |
| `CUSTOMER_PHONE_ALREADY_EXISTS` | 409 |
| `CUSTOMER_PROFILE_CHANGED` | 409 |
| `UNAUTHORIZED` | 401 |
| `INVALID_CREDENTIALS` | 401 |
| `ACCESS_DENIED` | 403 |
| `NOT_FOUND` | 404 |
| `RESERVATION_NOT_FOUND` | 404 |
| `CUSTOMER_NOT_FOUND` | 404 |
| `SCHEDULE_BLOCK_NOT_FOUND` | 404 |
| `CONFLICT` | 409 |
| `SESSION_CONTEXT_REQUIRED` | 400 |
| `SESSION_CONTEXT_CHANGED` | 409 |
| `IDEMPOTENCY_CONFLICT` | 409 |
| `IDEMPOTENCY_IN_PROGRESS` | 409 |
| `QUOTE_RECONFIRMATION_REQUIRED` | 409 |
| `BOOKING_LIMIT_REACHED` | 409 |
| `BOOKING_WINDOW_EXCEEDED` | 409 |
| `REVISION_REQUIRED` | 400 |
| `SLOT_UNAVAILABLE` | 409 |
| `INVALID_STATUS_TRANSITION` | 409 |
| `CONCURRENT_MODIFICATION` | 409 |
| `DATA_CONFLICT` | 409 |
| `RATE_LIMITED` | 429 |
| `INTERNAL_ERROR` | 500 |

Booking keys remain bound after failed input/business validation. `IDEMPOTENCY_CONFLICT` means a changed payload; `IDEMPOTENCY_IN_PROGRESS` means another execution held the key beyond five seconds. `REVISION_REQUIRED` identifies a missing or invalid `If-Match` header on reservation and schedule-block updates. `CONCURRENT_MODIFICATION` identifies a stale revision. A replayed lifecycle command after reversal conflicts with `INVALID_STATUS_TRANSITION`.

Foreign staff/customer/company resources return the same opaque missing error as nonexistent ones. A completed visit may be cancelled by staff with a private reason; elapsed or corrected outcomes follow the current [reservation contract](../reservations.md).

Email-first credentials, overflow beyond five candidates, invalid/expired/reused selection grants and unauthorized selections share 401 `INVALID_CREDENTIALS` with `Nieprawidłowe dane logowania`. No membership information is included. Login while authenticated returns 409; missing CSRF returns 403; IP/email allowances return 429 with Retry-After.

Staff mutations require `X-Expected-Session-Context` with the scope that opened the form: `v1:userId:companyId:employmentId:ROLE_OWNER` (or `ROLE_EMPLOYEE`), with lowercase canonical UUIDs. Missing, malformed or duplicate headers return `400 SESSION_CONTEXT_REQUIRED`. A different current authenticated account/company/employment/role returns `409 SESSION_CONTEXT_CHANGED` before CSRF or business execution; the valid current session is not invalidated. The header is only an equality precondition, never an authentication credential or a substitute for CSRF, role and company authorization. A revoked/expired session still follows the usual 401/CSRF contract. Clients must not automatically replay the rejected write under the new identity.

Company-local customers: missing OTP companies return `NOT_FOUND` (404); foreign customer booking/quote resources remain opaque 404. Pending customers are invisible to staff (404). Customer writes now use the existing `SESSION_CONTEXT_REQUIRED` (400) and `SESSION_CONTEXT_CHANGED` (409) contracts; see [ADR-0017](../adr/0017-company-local-customers.md).

`CUSTOMER_PHONE_ALREADY_EXISTS` rejects staff new-customer intent for an already established same-salon phone. Its `existingCustomer:{id}` is visible only to authorized staff in that salon; no booking or profile changes commit. `CUSTOMER_PROFILE_CHANGED` rejects customer first-visit details for an established profile, including a staff-establishment race; refresh readiness and require deliberate acknowledgement, never automatically drop details and resubmit. Both checks occur after a successful idempotent replay lookup.

Profile PUT uses the same `REVISION_REQUIRED`/`CONCURRENT_MODIFICATION` contract as reservations. A later authentication/context/CSRF/rate error does not resolve an earlier uncertain submission. Preserve the original key/body until its outcome is known. This pre-production replacement rejects removed flat booking/OTP fields as unknown input; there is no historical-payload compatibility adapter.
