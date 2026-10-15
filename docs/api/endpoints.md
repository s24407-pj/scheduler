# Endpoint index

A short index of paths and authorization rules. Request and response schemas come from the generated OpenAPI spec ([ADR-0005](../adr/0005-generated-openapi-spec.md)), available in the `dev` profile at `/swagger-ui.html` and `/v3/api-docs`. Update this table in the same change as any controller mapping or `@PreAuthorize` change.

**Access:** *Public* = no authentication (listed in `SecurityConfig`). *Authenticated* = any valid authenticated session, no `@PreAuthorize`. Roles = `@PreAuthorize`. All staff operations are limited to the authenticated session's company. A missing or invalid authenticated session on a non-public path returns 401; a role mismatch returns 403.

All unsafe methods require `X-XSRF-TOKEN` and its cookie, including public auth calls. Successful login returns status/options and sets HttpOnly `SESSION`; refresh `/api/auth/csrf` after authentication/logout. See the [frontend contract](../architecture.md#csrf-cookies-and-frontend-contract).

Times and dates: see [time handling](../time-handling.md). Errors: see [errors](./errors.md).

## Auth — `AuthController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| POST | `/api/auth/request-code` | Public | Sends six-digit OTP after commit; 60s cooldown, rolling phone/IP limits and global daily cap |
| POST | `/api/auth/verify-code` | Public | Creates phone-only customer HttpOnly session without names; atomic single-use OTP, rolling IP limit |
| POST | `/api/auth/login-staff` | Public | `email`, `password`, optional `employmentId`; rate-limited per IP |
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
| PUT | `/api/company/settings` | OWNER | |

## Employees — `CompanyEmployeeController`, `EmployeeController`, `WorkScheduleController`, `EmployeeOfferingController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/employees/public/company/{companyId}?offeringId` | Public | Employees eligible for the offering |
| GET | `/api/employees` | OWNER, EMPLOYEE | |
| POST | `/api/employees/{id}/photo` | OWNER | Multipart field `photo` |
| DELETE | `/api/employees/{id}/photo` | OWNER | |
| GET | `/api/employees/{employeeId}/work-schedule` | OWNER, EMPLOYEE | authenticated session company's schedule only |
| PUT | `/api/employees/{employeeId}/work-schedule` | OWNER | Replaces whole week at the authenticated session company |
| GET | `/api/employees/{employeeId}/offerings` | OWNER, EMPLOYEE | Assignments at the authenticated session company |
| POST | `/api/employees/{employeeId}/offerings/{offeringId}` | OWNER | Idempotent |
| DELETE | `/api/employees/{employeeId}/offerings/{offeringId}` | OWNER | |

## Offerings — `OfferingController`, `OfferingCategoryAssignmentController`, `EmployeeOfferingController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/offerings/public/company/{companyId}` | Public | Cached (`companyServices`) |
| GET | `/api/offerings/public/company/{companyId}/employee/{employeeId}` | Public | Active offerings the employee performs at that salon: assigned ones, or all active company offerings if the employee has no assignments there; 404 if not employed there |
| GET | `/api/offerings` | Authenticated | |
| GET | `/api/offerings/{id}` | Authenticated | |
| POST | `/api/offerings` | OWNER | |
| PUT | `/api/offerings/{id}` | OWNER | |
| PATCH | `/api/offerings/{id}/activate` | OWNER | Reactivates |
| DELETE | `/api/offerings/{id}` | OWNER | Soft delete (sets inactive) |
| POST | `/api/offerings/{id}/image` | OWNER | Multipart field `image`; max 5 per offering |
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
| POST | `/api/reservations` | CUSTOMER | Books for the caller (`customerId` = authenticated session user); only slots availability would list; 201, or 200 for an identical active booking |
| POST | `/api/reservations/staff` | OWNER, EMPLOYEE | Books for a client by phone; may create the user; 201 or 200 as above |
| PATCH | `/api/reservations/{id}/cancel` | CUSTOMER | Owning customer only; before `startTime` |
| PATCH | `/api/reservations/{id}/cancel-by-staff` | OWNER, EMPLOYEE | Any active reservation of the company, also after start; 204 |
| PATCH | `/api/reservations/{id}/complete` | OWNER, EMPLOYEE | |
| PATCH | `/api/reservations/{id}/no-show` | OWNER, EMPLOYEE | From `startTime`; `COMPLETED` until `endTime + 24 h` |
| DELETE | `/api/reservations/{id}` | OWNER | Hard delete |
| GET | `/api/reservations/me` | CUSTOMER | Caller's reservations |
| GET | `/api/reservations/employee?start&end` | OWNER, EMPLOYEE | Caller's schedule at the authenticated session's company |
| GET | `/api/reservations?employeeId&start&end` | OWNER, EMPLOYEE | EMPLOYEE only for self |

## Schedule blocks — `ScheduleBlockController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| POST | `/api/schedule-blocks` | OWNER, EMPLOYEE | OWNER may set `employeeId` |
| DELETE | `/api/schedule-blocks/{id}` | OWNER, EMPLOYEE | EMPLOYEE only own blocks |
| GET | `/api/schedule-blocks/employee?start&end[&employeeId]` | OWNER, EMPLOYEE | `employeeId` honoured for OWNER only |

## Customers — `CustomerController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/customers` | OWNER, EMPLOYEE | Customers of the caller's company |
| GET | `/api/customers/{id}` | OWNER, EMPLOYEE | Company-scoped status (no-shows, blocked) |
| PUT | `/api/customers/{id}/notes` | OWNER, EMPLOYEE | |
| PATCH | `/api/customers/{id}/block` | OWNER | |
| PATCH | `/api/customers/{id}/unblock` | OWNER | Resets no-show count |

## Users — `UserController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| GET | `/api/users/me` | Authenticated | |
| PUT | `/api/users/me` | Authenticated | |

## API docs

`/v3/api-docs/**`, `/swagger-ui/**`, and `/swagger-ui.html` are public but enabled only in the `dev` profile; elsewhere they return 404.

## Actuator

Only `/actuator/health` is public and returns aggregate status without details/components. Every other `/actuator/**` route is denied through the application HTTP interface, including metrics, Prometheus, info and readiness/liveness groups. Operator telemetry must use a separately secured deployment channel. Company staff and customers have no diagnostics permission.

Generated OpenAPI marks public operations explicitly and describes `sessionAuth` (HttpOnly `SESSION` cookie) separately from `csrfToken` (`X-XSRF-TOKEN` header). Every unsafe operation requires CSRF, including anonymous login/OTP/logout.
