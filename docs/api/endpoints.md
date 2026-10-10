# Endpoint index

A short index of paths and authorization rules. Request and response schemas come from the generated OpenAPI spec ([ADR-0005](../adr/0005-generated-openapi-spec.md)), available in the `dev` profile at `/swagger-ui.html` and `/v3/api-docs`. Update this table in the same change as any controller mapping or `@PreAuthorize` change.

**Access:** *Public* = no JWT (listed in `SecurityConfig`). *Authenticated* = any valid JWT, no `@PreAuthorize`. Roles = `@PreAuthorize`. All staff operations are limited to the JWT's company. A missing or invalid JWT on a non-public path returns 401; a role mismatch returns 403.

Times and dates: see [time handling](../time-handling.md). Errors: see [errors](./errors.md).

## Auth — `AuthController`

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| POST | `/api/auth/request-code` | Public | Sends OTP to phone; rate-limited per phone and IP |
| POST | `/api/auth/verify-code` | Public | Returns customer JWT; rate-limited per code and IP |
| POST | `/api/auth/login-staff` | Public | `email`, `password`, optional `employmentId`; rate-limited per IP |
| POST | `/api/auth/logout` | Authenticated | Revokes the session of the calling token; 204 |
| POST | `/api/auth/logout-all` | Authenticated | Revokes every session of the caller; 204 |

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
| GET | `/api/employees/{employeeId}/work-schedule` | OWNER, EMPLOYEE | JWT company's schedule only |
| PUT | `/api/employees/{employeeId}/work-schedule` | OWNER | Replaces whole week at the JWT company |
| GET | `/api/employees/{employeeId}/offerings` | OWNER, EMPLOYEE | Assignments at the JWT company |
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
| GET | `/api/offering-categories` | Authenticated | |
| POST | `/api/offering-categories` | OWNER | |
| DELETE | `/api/offering-categories/{id}` | OWNER | Offerings keep existing with `category_id = null` |

## Reservations — `ReservationController`

See [reservations](../reservations.md).

| Method | Path | Access | Notes |
| ------ | ---- | ------ | ----- |
| POST | `/api/reservations` | Authenticated | Books for the caller (`customerId` = JWT user); only slots availability would list; 201, or 200 for an identical active booking |
| POST | `/api/reservations/staff` | OWNER, EMPLOYEE | Books for a client by phone; may create the user; 201 or 200 as above |
| PATCH | `/api/reservations/{id}/cancel` | Authenticated | Owning customer only; before `startTime` |
| PATCH | `/api/reservations/{id}/cancel-by-staff` | OWNER, EMPLOYEE | Any active reservation of the company, also after start; 204 |
| PATCH | `/api/reservations/{id}/complete` | OWNER, EMPLOYEE | |
| PATCH | `/api/reservations/{id}/no-show` | OWNER, EMPLOYEE | From `startTime`; `COMPLETED` until `endTime + 24 h` |
| DELETE | `/api/reservations/{id}` | OWNER | Hard delete |
| GET | `/api/reservations/me` | Authenticated | Caller's reservations |
| GET | `/api/reservations/employee?start&end` | OWNER, EMPLOYEE | Caller's schedule at the JWT's company |
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

Public: `/actuator/health/**`, `/actuator/info`, `/actuator/metrics/**`, `/actuator/prometheus`. The `dev` profile exposes more (`env`, `threaddump`, `loggers`, ...) but they still require a JWT.
