# API errors

Every error response is an RFC 9457 Problem Details body with content type `application/problem+json`, rendered by `config/RestExceptionHandler.kt`. The decision and status rules are in [ADR-0004](../adr/0004-error-contract-problem-detail.md).

## Response body

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

Validation failures (Jakarta Validation on a `@Valid` request body) add `errors`, a map of field name to message:

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Błąd walidacji",
  "instance": "/api/reservations",
  "code": "VALIDATION_FAILED",
  "errors": { "startTime": "Czas rezerwacji musi być w przyszłości" }
}
```

- `detail` is Polish and safe to show to end users. Do not branch on it.
- `code` is stable and part of the contract. Branch on it.
- 401 responses from Spring Security (missing, invalid, or revoked authenticated session) use the same format.

## Error codes

Codes are defined in `config/ApiException.kt` (`ErrorCode`); each code has a fixed status.

| Code | Status | Meaning |
| ---- | ------ | ------- |
| `VALIDATION_FAILED` | 400 | Request body failed DTO validation; see `errors` |
| `MALFORMED_REQUEST` | 400 | Body is not valid JSON or has wrong types |
| `INVALID_PARAMETER` | 400 | Query or path parameter missing or of the wrong type |
| `INVALID_LOCAL_TIME` | 400 | `startTime` falls in the daylight-saving gap and does not exist in Europe/Warsaw |
| `OFFERING_UNAVAILABLE` | 400 | Referenced offering does not exist, is inactive, or (staff booking) belongs to another company |
| `EMPLOYEE_NOT_IN_COMPANY` | 400 | Employee is not employed by the offering's company |
| `EMPLOYEE_OFFERING_NOT_ASSIGNED` | 400 | Employee has offering assignments and the requested offering is not one of them |
| `CUSTOMER_BLOCKED` | 400 | Customer is blocked at the offering's company |
| `BOOKING_ADVANCE_REQUIRED` | 400 | Slot starts sooner than the company's `minBookingAdvanceMinutes` |
| `CUSTOMER_NAME_REQUIRED` | 400 | Staff booking for a new phone number without first and last name |
| `BAD_REQUEST` | 400 | Uncoded business-rule rejection (fallback) |
| `UNAUTHORIZED` | 401 | Missing, invalid, or revoked authenticated session |
| `INVALID_CREDENTIALS` | 401 | Wrong staff email or password |
| `ACCESS_DENIED` | 403 | Role not allowed, or another employee's resource in the same company |
| `RESERVATION_NOT_FOUND` | 404 | Reservation does not exist or is not visible to the caller |
| `CUSTOMER_NOT_FOUND` | 404 | Authenticated customer account no longer exists |
| `SCHEDULE_BLOCK_NOT_FOUND` | 404 | Schedule block does not exist or belongs to another company |
| `NOT_FOUND` | 404 | Uncoded missing resource or unknown URL (fallback) |
| `SLOT_UNAVAILABLE` | 409 | Time range overlaps a reservation or schedule block (service check or database exclusion constraint) |
| `INVALID_STATUS_TRANSITION` | 409 | Reservation status does not allow the action (for example cancelling a completed visit) |
| `CONCURRENT_MODIFICATION` | 409 | `@Version` conflict; retry with fresh data |
| `DATA_CONFLICT` | 409 | Other database constraint violation (for example a duplicate unique value) |
| `CONFLICT` | 409 | Uncoded state conflict (fallback) |
| `RATE_LIMITED` | 429 | OTP or login rate limit exceeded |
| `INTERNAL_ERROR` | 500 | Unexpected failure; logged at ERROR, details hidden |

Other framework errors (for example 405, 413 upload too large, or a controller's `ResponseStatusException`) carry the HTTP status name as `code`, such as `METHOD_NOT_ALLOWED`.

## Visibility rules

- A resource belonging to another company returns **404**, never 403 or 409, so IDs of other tenants cannot be probed. This applies to reservations, schedule blocks, and offerings.
- A customer acting on another customer's reservation also gets 404 `RESERVATION_NOT_FOUND`.
- 403 is reserved for callers who can see the resource but may not act on it (wrong role, or an employee deleting a colleague's schedule block).

## Not yet coded

Offering, category, user, work-schedule, and settings endpoints still raise generic exceptions and therefore return the fallback codes (`BAD_REQUEST`, `CONFLICT`, `NOT_FOUND`) or the HTTP status name. Their statuses follow the rules above; specific codes are added when those modules are next changed.
