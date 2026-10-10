# Architecture

Spring Boot backend for beauty and barber salon scheduling. Customers authenticate by SMS one-time code; staff by email and password. Both receive a JWT.

## Modules

```
src/main/kotlin/pl/kacosmetology/scheduler/
├── auth/              # SMS OTP + staff login → JWT issuance, rate limiters, SmsSender
├── availability/      # Free-slot calculation (public), shared EmployeeAvailabilityPolicy
├── company/           # Company entity, settings, public booking profile, public employee list
├── config/            # Security, CORS, Redis + cache, R2 client, error handler, dev seed
├── employeeoffering/  # Which offerings an employee performs
├── notification/      # SMS confirmation/cancellation/reminders, hourly scheduled jobs
├── offering/          # Offering catalog, categories, images (R2)
├── reservation/       # Booking lifecycle
├── scheduleblock/     # Employee time blocks (breaks, unavailability)
├── security/          # JWT filter, SessionStore, secret check, CustomUserDetails, CustomUserDetailsService
├── user/              # Profiles, customers (block/unblock, notes), employee photos
└── workschedule/      # Weekly working hours per employee
```

**Layering:** Controller → Service → Repository. Services do not call services of other modules; modules share data only through IDs and repositories. Exception: `EmployeeAvailabilityPolicy` (in `availability/`) is shared by `AvailabilityService` and `ReservationService` so slot listing and booking use the same conflict rules.

## Authentication

| Flow | Endpoint(s) | Token contents |
| ---- | ----------- | -------------- |
| Customer | `POST /api/auth/request-code` → `POST /api/auth/verify-code` | Role `customer` only. Not scoped to a company. |
| Staff | `POST /api/auth/login-staff` with `email`, `password`, optional `employmentId` | One `employmentId`, its `companyId`, one role (`owner` or `employee`). |

A staff user with several employments who omits `employmentId` gets status `EMPLOYMENT_SELECTION_REQUIRED` and an `employments` list (`employmentId`, `companyId`, `companyName`, `role`); the client repeats the request with the selected `employmentId`.

`JwtService.generateCustomerToken()` and `generateStaffToken()` build the tokens. `CustomUserDetails` exposes `id` (user ID), `sessionId`, and for staff `companyId` and one authority (`ROLE_OWNER` or `ROLE_EMPLOYEE`). Customers get `ROLE_CUSTOMER`.

### Sessions and logout

Every token is HS256-signed and carries a session ID in `jti` ([ADR-0009](./adr/0009-revocable-sessions.md)). `SessionStore` registers it in Redis as `session:<jti>` → user ID with the token's TTL (`JWT_EXPIRATION_MS`) and indexes it in `user-sessions:<userId>`. On every request `JwtAuthenticationFilter` parses the token once, reloads the user (and for staff the employment, rejecting a changed role or company), and accepts the token only if its session still exists for that user. Tokens without `jti` are rejected.

- `POST /api/auth/logout` deletes the current session (204).
- `POST /api/auth/logout-all` deletes every session of the user, on all devices and employments (204).

The token format and claims are unchanged, so frontends that decode the JWT payload keep working. Flushing Redis logs everyone out.

### Secret and login hardening

- `JwtSecretValidator` stops startup when `JWT_SECRET` is shorter than 32 bytes, or equals the public development default while neither the `dev` nor the `test` profile is active. Gradle runs tests with the `test` profile.
- Staff login returns the same message ("Nieprawidłowy email lub hasło") for an unknown email, an SMS-only account without a password, and a wrong password, and runs one bcrypt comparison in each case so response time does not reveal which.

### Rate limiting (Redis)

| Limiter | Redis key | Limit (default) | Config |
| ------- | --------- | --------------- | ------ |
| OTP requests per phone | `rate:sms:<phone>` | 3 per 5 min | `OTP_MAX_ATTEMPTS`, `OTP_RATE_WINDOW_MINUTES` |
| OTP requests per client IP | `rate:otp-request:<ip>` | 10 per 15 min | `OTP_REQUEST_IP_MAX_ATTEMPTS`, `OTP_REQUEST_IP_RATE_WINDOW_MINUTES` |
| OTP failed attempts per code | stored with `otp:<phone>` | 3 | `OTP_VERIFICATION_MAX_ATTEMPTS` |
| OTP verification per client IP | `rate:otp-verify:<ip>` | 10 per 5 min | `OTP_VERIFICATION_IP_MAX_ATTEMPTS`, `OTP_VERIFICATION_IP_RATE_WINDOW_MINUTES` |
| Staff login per client IP | `rate:login:<ip>` | 10 per 1 min | `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES` |

OTP codes live under `otp:<phone>` with TTL `OTP_TTL_MINUTES`. A Lua script verifies and consumes a correct code atomically and keeps the TTL after failed attempts. Client IP is `request.remoteAddr`. With `server.forward-headers-strategy: native`, Tomcat replaces it with the `X-Forwarded-For` client only when the direct peer matches the `TRUSTED_PROXIES` regex; the default (empty) trusts no proxy, so a client-supplied header cannot bypass the limits. Behind a reverse proxy, set `TRUSTED_PROXIES` to the proxy's address, otherwise every client shares the proxy's limit. For Dokploy behind Cloudflare, see [deployment.md](./deployment.md#client-ip-for-rate-limiting). Exceeding a limit returns 429.

## Authorization and company isolation

- Method security: `@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")` or `hasRole('OWNER')`. Endpoints without `@PreAuthorize` require any valid JWT. Public paths are listed in `SecurityConfig` (see [endpoints](./api/endpoints.md)).
- Every staff operation is scoped to the JWT's `companyId`. Services verify that the target resource (reservation, offering, employee, block) belongs to that company. The status code for a mismatch is currently inconsistent; see [API errors](./api/errors.md).
- Database-level isolation exists for schedule blocks: a composite foreign key `(company_id, employee_id)` → `company_employees (company_id, user_id)`.

## Customer blocking and no-shows

Block state is **per company** in `company_customer_blocks` (`no_show_count`, `blocked`).

- Marking a reservation `NO_SHOW` increments `no_show_count`. When it reaches `companies.max_no_shows` (and that is above 0), the customer is blocked at that company.
- A blocked customer gets 400 when booking an offering of that company. The check is in `ReservationService.createReservation()`, so it also applies to staff bookings made for that customer.
- Owners block and unblock manually with `PATCH /api/customers/{id}/block|unblock`. Unblocking resets `no_show_count` to 0.

## Notifications and scheduled jobs

- `ReservationService` publishes `ReservationCreatedEvent` / `ReservationCancelledEvent`. `NotificationEventListener` handles them **after the transaction commits** and sends SMS through `NotificationService`. SMS failures are logged and never roll back or fail the request.
- `NotificationScheduler` (enabled by `@EnableScheduling` on `RedisConfig`) runs two jobs every hour on the hour:
  - **Auto-complete:** `PENDING`/`CONFIRMED` reservations whose `endTime` has passed become `COMPLETED`.
  - **Reminders:** reservations starting in 23–25 hours with `reminder_sent = false` get an SMS; successfully sent ones are flagged.
- Jobs assume a single instance; see [ADR-0007](./adr/0007-single-instance-deployment.md).

> **Known gap:** `ConsoleSmsSender` is the only `SmsSender` implementation. It writes OTP codes and notifications to the application log instead of sending them, and is disabled in the `prod` profile (`@Profile("!prod")`), so `prod` fails at startup until a real `SmsSender` exists.

## Caching

Redis-backed Spring Cache (`RedisConfig`), JSON values, default TTL 10 minutes. The `companyServices` cache (company offering lists, 15 minutes) is evicted by offering and category mutations, including `OfferingCategoryService.deleteCategory()` and `assignCategory()`.

## File storage (Cloudflare R2)

S3-compatible client from `R2Config`. With no access key and secret key configured, it uses anonymous credentials (startup succeeds, uploads fail).

- Offering images: up to 5 per offering, `POST /api/offerings/{id}/image` (multipart field `image`), JPEG/PNG/WebP, max 5 MB. `OfferingImageService`.
- Employee photo: one per employee, `POST /api/employees/{id}/photo` (multipart field `photo`), same limits; replaces and deletes the previous object. Stored as `users.photo_url`. `EmployeePhotoService`.

## Database

PostgreSQL, schema managed by Flyway (`src/main/resources/db/migration/`). `V1__init_schema.sql` is the consolidated pre-production baseline. Never edit an applied migration; add `V<n>__description.sql`.

| Table | Purpose |
| ----- | ------- |
| `companies` | Salons; settings: hours, `slot_interval_minutes`, `max_no_shows`, `last_minute_discount_percent`, `last_minute_discount_hours`, `min_booking_advance_minutes` |
| `users` | Customers and staff in one table; staff are users with `company_employees` rows; `photo_url` |
| `company_employees` | Employment: (company, user, role `OWNER`/`EMPLOYEE`); row lock target for booking ([ADR-0001](./adr/0001-double-booking-prevention.md)) |
| `company_customers` | Per-company customer data (notes) |
| `company_customer_blocks` | Per-company no-show count and block flag |
| `offerings`, `offering_categories`, `offering_images` | Catalog |
| `employee_offerings` | Which offerings an employee performs |
| `employee_work_schedules` | Weekly hours per (company, employee) and day of week |
| `schedule_blocks` | Employee unavailability; GiST exclusion constraint against overlaps |
| `reservations` | Bookings with price snapshot, `version`, `reminder_sent`; GiST exclusion constraint against overlapping active bookings |
