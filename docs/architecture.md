# Architecture

Spring Boot backend for beauty and barber salon scheduling. Customers authenticate by SMS one-time code; staff by email and password. Both authenticate using Redis-backed Spring Security sessions.

## Modules

```
src/main/kotlin/pl/kacosmetology/scheduler/
├── auth/              # SMS OTP + staff login → Spring Security sessions, rate limiters, SmsSender
├── availability/      # Free-slot calculation (public), shared EmployeeAvailabilityPolicy
├── company/           # Company entity, settings, public booking profile, public employee list
├── config/            # Security, CORS, Redis + cache, R2 client, error handler, dev seed
├── employeeoffering/  # Which offerings an employee performs
├── notification/      # SMS confirmation/cancellation/reminders, hourly scheduled jobs
├── offering/          # Offering catalog, categories, images (R2)
├── reservation/       # Booking lifecycle
├── scheduleblock/     # Employee time blocks (breaks, unavailability)
├── security/          # session authentication bridge, scope validation filter, scalar CustomUserDetails
├── user/              # Profiles, customers (block/unblock, notes), employee photos
└── workschedule/      # Weekly working hours per employee
```

**Layering:** Controller → Service → Repository. Services do not call services of other modules; modules share data only through IDs and repositories. Exception: `EmployeeAvailabilityPolicy` (in `availability/`) is shared by `AvailabilityService` and `ReservationService` so slot listing and booking use the same conflict rules.

## Authentication

| Flow | Endpoint(s) | Authenticated session scope |
| ---- | ----------- | --------------------------- |
| Customer | `POST /api/auth/request-code` → `POST /api/auth/verify-code` | `ROLE_CUSTOMER`, no company or employment |
| Staff | `POST /api/auth/login-staff` with `email`, `password`, optional `employmentId` | Exactly one `employmentId`, its `companyId`, and `ROLE_OWNER` or `ROLE_EMPLOYEE` |

All unsafe calls, including anonymous authentication endpoints, require CSRF. A staff member with multiple employments who omits `employmentId` receives `EMPLOYMENT_SELECTION_REQUIRED` and the existing sorted options (`employmentId`, `companyId`, `companyName`, `role`). This creates no authenticated session. Repeat the password login with the selected employment. An already authenticated browser must log out first; login returns 409 rather than replacing its identity or company scope.

After verification, `AuthService` returns only a safe identity after its transaction commits. `SessionAuthenticationService` invokes Spring's `ChangeSessionIdAuthenticationStrategy` and `CsrfAuthenticationStrategy`, creates a fresh `SecurityContext`, and explicitly saves it through the shared `HttpSessionSecurityContextRepository`. Setting the holder alone is insufficient with Security 7's explicit-save mode. The credential is Spring Session's normal `SESSION` cookie; auth JSON contains status/options, never a credential. `GET /api/auth/me` returns `userId`, `username`, `authorities`, `companyId`, and `employmentId`; `/api/users/me` loads current profile data separately.

### Redis sessions, revocation and serialization

Boot's `spring-boot-starter-session-data-redis` configures `RedisIndexedSessionRepository` (`spring.session.data.redis.repository-type=indexed`), with namespace `scheduler:sessions`. Authenticated CUSTOMER sessions have an immutable deadline 24 elapsed hours after login, enforced before authorization using the injected clock; activity cannot extend it. Authenticated STAFF sessions have a 24-hour inactivity timeout and activity refreshes it. The anonymous Spring Session default remains `spring.session.timeout=24h`; authenticated durations are explicitly enforced by the application. Redis remains required for OTP, rate limits and cache as well.

Spring Session persists the SecurityContext using its supported default JDK serializer. The final `CustomUserDetails` is an immutable, versioned `UserDetails` containing only account ID, phone/username, role, company ID and employment ID. It contains no JPA entities, lazy proxies, password hashes, OTPs, repositories or session IDs; Authentication credentials/details are null. Only trusted application instances may access Redis. Serialization changes require a deliberate compatibility decision or session invalidation; no custom domain-model serialization or polymorphic JSON configuration is introduced.

`SessionAuthorizationFilter` runs after context restoration and before CSRF/authorization. It reloads the user and exact employment from PostgreSQL on every authenticated request and compares identity, employment ownership, role, company and authentication authorities. Deleted users, recycled/changed phone identities, removed/reassigned employments or changed roles/companies invalidate the session and clear authentication. It never refreshes an old session into a different scope. Database failures fail the request; they cannot allow cached privileges. Existing method security and company checks remain in force.

- `POST /api/auth/logout`: Spring's `SecurityContextLogoutHandler` invalidates only the current HttpSession; Spring Session expires `SESSION`, and `CsrfLogoutHandler` clears `XSRF-TOKEN`. Returns 204 and is idempotent with a fresh CSRF token even when anonymous.
- `POST /api/auth/logout-all`: requires authentication; an atomic Redis generation increment revokes this realm/account, then `FindByIndexNameSessionRepository.findByPrincipalName` deletes the previous generation and logs out this browser. Principal indexes use `customer:<accountId>:<generation>` or `staff:<staffAccountId>:<generation>` (employment ID before the identity migration). Customer sessions and other company staff accounts remain untouched. Returns 204; later calls with deleted credentials are unauthenticated.
- Expired/revoked sessions fail on subsequent requests, including requests routed through a different application instance. Already-authorized requests may finish. Every authenticated request checks its account generation, so a delayed save of an old-generation session cannot restore access. A concurrent login may survive only under the new generation, with its own principal index. Generation keys never expire, avoiding reuse while revoked sessions remain in Redis.
- Losing/flushing Redis logs everyone out. Old bearer credentials no longer authenticate. ADR-0009 is preserved as history and [superseded by ADR-0011](./adr/0011-spring-session-redis.md).

### CSRF, cookies and frontend contract

Security 7's `csrf.spa()` uses standard cookie tokens and SPA request handling, including raw headers and BREACH-safe request attributes. The repository is shared with REST login/logout strategies. CSRF is enabled for every unsafe method; there are no authentication exclusions.

1. Fetch `GET /api/auth/csrf` with `credentials: 'include'` before the first unsafe call. It materializes a JavaScript-readable, host-only `XSRF-TOKEN` cookie (Path=/, SameSite=Lax) and returns `{token, headerName: "X-XSRF-TOKEN"}`. Its `token` is only a CSRF token, never the session credential. Cross-origin frontends should use this response rather than trying to read the API host's cookie.
2. Send `X-XSRF-TOKEN: <token>` and credentials/cookies on POST/PUT/PATCH/DELETE, including login, OTP requests/verification and both logout endpoints. Missing/invalid CSRF returns 403 ProblemDetail.
3. Successful authentication and logout clear the prior CSRF cookie. Fetch `/api/auth/csrf` again afterward before the next unsafe request. Refresh on a CSRF failure; do not blindly retry non-idempotent operations.
4. Remove localStorage JWT handling and bearer headers, and stop decoding credential claims. Use `/api/auth/me` for scope/authorities and `/api/users/me` for profile. Handle 401 by clearing local identity state and returning to sign-in. Use fetch `credentials: 'include'` or Axios `withCredentials: true` on all API requests.

`SESSION` is HttpOnly, Path=/, host-only (no Domain), SameSite=Lax and Secure in `prod`; development works on HTTP localhost. Different localhost ports and HTTPS subdomains under the same registrable domain are cross-origin but same-site, so Lax works with explicit credentialed CORS origins. Deploy frontend/API on the same site. Unrelated sites require a deliberate deployment change (including Secure SameSite=None cookies and browser third-party-cookie constraints); they are not assumed here. Never use wildcard origins with credentialed CORS. OPTIONS preflight remains unauthenticated and bypasses CSRF normally.

### Login hardening

Staff login keeps the same error message for unknown email, SMS-only accounts and wrong passwords and runs one bcrypt comparison in each case. OTP registration, Lua verification/consumption, validation, SMS and rate limits are unchanged.

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

- Method security: `@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")` or `hasRole('OWNER')`. Endpoints without `@PreAuthorize` require a valid authenticated session. Public paths are listed in `SecurityConfig` (see [endpoints](./api/endpoints.md)).
- Every staff operation is scoped to the session principal's `companyId`. Services verify that the target resource (reservation, offering, employee, block) belongs to that company. The status code for a mismatch is currently inconsistent; see [API errors](./api/errors.md).
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
