# Endpoint index

A short index of paths and authorization rules. Request and response schemas come from the generated OpenAPI spec ([ADR-0005](../adr/0005-generated-openapi-spec.md)), available in the `dev` profile at `/swagger-ui.html` and `/v3/api-docs`. Update this table in the same change as any controller mapping or `@PreAuthorize` change.

**Access:** *Public* = no authentication (listed in `SecurityConfig`). *Authenticated* = any valid authenticated session, no `@PreAuthorize`. Roles = `@PreAuthorize`. All staff operations are limited to the authenticated session's company. A missing or invalid authenticated session on a non-public path returns 401; a role mismatch returns 403.

All unsafe methods require `X-XSRF-TOKEN` and its cookie, including public auth calls. Successful login returns status and sets HttpOnly `SESSION`; refresh `/api/auth/csrf` after authentication/logout. See the [frontend contract](../architecture.md#csrf-cookies-and-frontend-contract).

Times and dates: see [time handling](../time-handling.md). Errors: see [errors](./errors.md).

## Quotes — `QuoteController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| POST | `/api/quotes` | CUSTOMER | Creates durable ten-minute commercial quote; 201; no slot hold; CSRF required |

## Auth — `AuthController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| POST | `/api/auth/request-code` | Public | Sends six-digit OTP after commit; 60s cooldown, rolling phone/IP limits and global daily cap |
| POST | `/api/auth/verify-code` | Public | Creates phone-only customer HttpOnly session without names; atomic single-use OTP, rolling IP limit |
| POST | `/api/auth/login-staff` | Public | `companyId`, normalized `email`, `password`; failed authentication is generic 401; compatibility/high-membership fallback |
| POST | `/api/auth/login-staff-email` | Public, anonymous only | `{email,password}`; authenticate one verified account or return verified company choices plus a 120s opaque grant; generic 401, IP/email 429 |
| POST | `/api/auth/complete-staff-login` | Public, anonymous only | `{selectionToken,companyId}`; anonymous SESSION cookie binding, atomic single use, locked version/generation revalidation; generic 401 on invalid/expired/wrong-browser grant, independent IP 429 |
| POST | `/api/auth/staff-password` | Public, CSRF required | One-use 24h setup/recovery token, sets password without login; 204 |
| POST | `/api/auth/logout` | Public, CSRF required | Revokes the session of the current browser; 204 |
| GET | `/api/auth/csrf` | Public | Sets XSRF-TOKEN; returns CSRF token/header name |
| GET | `/api/auth/me` | Authenticated | Safe account ID/username/authorities/company/employment scope |
| POST | `/api/auth/logout-all` | Authenticated | Revokes all devices for the caller's customer or company staff account only; 204 |

## Availability — `AvailabilityController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/availability?employeeId&serviceId&date` | Public | See [availability](../availability.md) |

## Company — `CompanyController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/company/public/{companyId}` | Public | Public salon profile |
| GET | `/api/company/employees` | Authenticated | Staff of the caller's company |
| GET | `/api/company/settings` | Authenticated | Caller's company settings |
| PUT | `/api/company/settings` | OWNER | Includes notice/horizon/customer booking cap |
| PUT | `/api/company/profile` | OWNER | Company-local public name/address |

## Employees — `CompanyEmployeeController`, `EmployeeController`, `WorkScheduleController`, `EmployeeOfferingController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/employees/public/company/{companyId}?offeringId` | Public | Employees eligible for the offering |
| GET | `/api/employees` | OWNER, EMPLOYEE | Includes current ALL_SERVICES / SPECIFIC_SERVICES eligibility mode; `userId` is the identifier used by employee subresources |
| POST | `/api/employees` | OWNER | Creates EMPLOYEE account plus initial setup token; 201 |
| PUT | `/api/employees/{id}` | OWNER | Company-local names, login email, optional contact phone |
| PUT | `/api/employees/{id}/enabled` | OWNER | Revokes access/links when disabled; returns preserved future booking warnings |
| POST | `/api/employees/{id}/password-link` | OWNER | Replaces one-use 24h setup/recovery token |
| GET | `/api/staff/me` | OWNER, EMPLOYEE | Current company-local staff profile |
| POST | `/api/staff/password` | OWNER, EMPLOYEE | Current password required; revokes all account sessions; 204 |
| POST | `/api/employees/{id}/photo` | OWNER | `multipart/form-data` field `photo` |
| DELETE | `/api/employees/{id}/photo` | OWNER | |
| GET | `/api/employees/{employeeId}/work-schedule` | OWNER, EMPLOYEE | authenticated session company's schedule only |
| PUT | `/api/employees/{employeeId}/work-schedule` | OWNER | Replaces whole week at the authenticated session company |
| GET | `/api/employees/{employeeId}/schedule-overrides?start&end` | OWNER, EMPLOYEE | Inclusive date range at session company |
| PUT | `/api/employees/{employeeId}/schedule-overrides/{date}` | OWNER | Replacement same-day shift or day off |
| DELETE | `/api/employees/{employeeId}/schedule-overrides/{date}` | OWNER | Restore weekly schedule |
| GET | `/api/employees/{employeeId}/offerings` | OWNER, EMPLOYEE | Assignments at the authenticated session company |
| PATCH | `/api/employees/{employeeId}/offering-eligibility` | OWNER | Explicit ALL_SERVICES / SPECIFIC_SERVICES mode |
| POST | `/api/employees/{employeeId}/offerings/{offeringId}` | OWNER | Idempotent |
| DELETE | `/api/employees/{employeeId}/offerings/{offeringId}` | OWNER | |

## Offerings — `OfferingController`, `OfferingCategoryAssignmentController`, `EmployeeOfferingController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/offerings/public/company/{companyId}` | Public | Cached (`companyServices`) |
| GET | `/api/offerings/public/company/{companyId}/employee/{employeeId}` | Public | Active offerings following explicit qualification; SPECIFIC_SERVICES with no assignments returns []; disabled/foreign employee is 404 |
| GET | `/api/offerings` | Authenticated | |
| GET | `/api/offerings/{id}` | Authenticated | |
| POST | `/api/offerings` | OWNER | |
| PUT | `/api/offerings/{id}` | OWNER | |
| PATCH | `/api/offerings/{id}/activate` | OWNER | Reactivates |
| DELETE | `/api/offerings/{id}` | OWNER | Soft delete (sets inactive) |
| POST | `/api/offerings/{id}/image` | OWNER | `multipart/form-data` field `image`; max 5 per offering |
| DELETE | `/api/offerings/{id}/image/{imageId}` | OWNER | |
| PATCH | `/api/offerings/{offeringId}/category` | OWNER | |

## Offering categories — `OfferingCategoryController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/offering-categories/public/company/{companyId}` | Public | Tenant-scoped public category names |
| GET | `/api/offering-categories` | Authenticated | |
| POST | `/api/offering-categories` | OWNER | |
| DELETE | `/api/offering-categories/{id}` | OWNER | Offerings keep existing with `category_id = null` |

## Reservations — `ReservationController`

See [reservations](../reservations.md).

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| POST | `/api/reservations` | CUSTOMER | Required Idempotency-Key; optional employee means atomic any-employee allocation; first local profile requires names; 201 or current replay 200 |
| POST | `/api/reservations/staff` | OWNER, EMPLOYEE | Required Idempotency-Key; manual regular-price booking, explicit duration/price/block override and SMS opt-in |
| PUT | `/api/reservations/{id}` | OWNER, EMPLOYEE | Direct edit, required If-Match; preserves ID/creation time/customer |
| PATCH | `/api/reservations/{id}/cancel` | CUSTOMER | Required If-Match; own booking strictly before start; optional reason |
| PATCH | `/api/reservations/{id}/cancel-by-staff` | OWNER, EMPLOYEE | Required If-Match and private reason; optional separate customer explanation |
| PATCH | `/api/reservations/{id}/complete` | OWNER, EMPLOYEE | Required If-Match; from scheduled start; supports no-show correction |
| PATCH | `/api/reservations/{id}/no-show` | OWNER, EMPLOYEE | Required If-Match; from start; employee deadline end +24 elapsed hours; owner unlimited |
| PATCH | `/api/reservations/{id}/restore` | OWNER, EMPLOYEE | Required If-Match; cancelled future/ongoing or mistaken ongoing no-show; current hard rules |
| PATCH | `/api/reservations/{id}/archive` | OWNER | Required If-Match; only CANCELLED; no permanent deletion |
| PATCH | `/api/reservations/{id}/unarchive` | OWNER | Required If-Match; required before restoration |
| GET | `/api/reservations/me` | CUSTOMER | Own unarchived history; no private staff reasons |
| GET | `/api/reservations/employee?start&end` | OWNER, EMPLOYEE | Caller's company-local schedule |
| GET | `/api/reservations?employeeId&start&end` | OWNER, EMPLOYEE | Either staff role may read a company employee calendar; foreign/nonexistent target 404 |

## Schedule blocks — `ScheduleBlockController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| POST | `/api/schedule-blocks` | OWNER, EMPLOYEE | OWNER may target any company employee; EMPLOYEE only own, explicit unauthorized target rejected |
| DELETE | `/api/schedule-blocks/{id}` | OWNER, EMPLOYEE | EMPLOYEE only own blocks |
| GET | `/api/schedule-blocks/employee?start&end[&employeeId]` | OWNER, EMPLOYEE | Either staff role may read coworkers’ intervals/reasons within company |

## Customers — `CustomerController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/customers` | OWNER, EMPLOYEE | Customers of the caller's company |
| GET | `/api/customers/{id}` | OWNER, EMPLOYEE | Company-scoped status (no-shows, blocked) |
| PUT | `/api/customers/{id}/notes` | OWNER, EMPLOYEE | Existing local profile required; never creates a relationship |
| PUT | `/api/customers/{id}/profile` | OWNER, EMPLOYEE | Updates local names and optional contact email |
| GET | `/api/customers/{id}/visits` | OWNER, EMPLOYEE | Company-scoped visit history, existing relationship required |
| PATCH | `/api/customers/{id}/block` | OWNER | |
| PATCH | `/api/customers/{id}/unblock` | OWNER | Preserves penalty history; clears manual/automatic block, never restores appointments |

## Users — `UserController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/users/me` | CUSTOMER | Global canonical phone identity only |

## API docs

`/v3/api-docs/**`, `/swagger-ui/**`, and `/swagger-ui.html` are public but enabled only in the `dev` profile; elsewhere they return 404.

## Actuator

Only `/actuator/health` is public and returns aggregate status without details/components. Every other `/actuator/**` route is denied through the application HTTP interface, including metrics, Prometheus, info and readiness/liveness groups. Operator telemetry must use a separately secured deployment channel. Company staff and customers have no diagnostics permission.

Generated OpenAPI marks public operations explicitly and describes `sessionAuth` (HttpOnly `SESSION` cookie) separately from `csrfToken` (`X-XSRF-TOKEN` header). Every unsafe operation requires CSRF, including anonymous login/OTP/logout.

Resource IDs in paths, queries and JSON are UUID strings (`format: uuid`); newly generated persisted IDs are UUIDv7. Revision/If-Match remains numeric.
