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
├── notification/      # SMS confirmation/cancellation/reminders, durable delivery and Warsaw reminder runs
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
| Staff | `POST /api/auth/login-staff` with `companyId`, `email`, `password` | Exactly one `employmentId`, its `companyId`, and `ROLE_OWNER` or `ROLE_EMPLOYEE` |

All unsafe calls, including anonymous authentication endpoints, require CSRF. Staff credentials belong to one company. Email is trimmed and lowercased before lookup; there is no employment selection flow or global staff credential lookup. An already authenticated browser must log out first; login returns 409 rather than replacing its identity or company scope. See [identity and staff administration](./identity.md) and [ADR-0012](./adr/0012-separate-account-realms.md).

After verification, `AuthService` returns only a safe identity after its transaction commits. `SessionAuthenticationService` invokes Spring's `ChangeSessionIdAuthenticationStrategy` and `CsrfAuthenticationStrategy`, creates a fresh `SecurityContext`, and explicitly saves it through the shared `HttpSessionSecurityContextRepository`. Setting the holder alone is insufficient with Security 7's explicit-save mode. The credential is Spring Session's normal `SESSION` cookie; auth JSON contains status, never a credential. `GET /api/auth/me` returns `userId`, `username`, `authorities`, `companyId`, and `employmentId`; `/api/users/me` returns customer phone identity only; `/api/staff/me` returns company-local staff presentation.

### Redis sessions, revocation and serialization

Boot's `spring-boot-starter-session-data-redis` configures `RedisIndexedSessionRepository` (`spring.session.data.redis.repository-type=indexed`), with namespace `scheduler:sessions`. Authenticated CUSTOMER sessions have an immutable deadline 24 elapsed hours after login, enforced before authorization using the injected clock; activity cannot extend it. Authenticated STAFF sessions have a 24-hour inactivity timeout and activity refreshes it. The anonymous Spring Session default remains `spring.session.timeout=24h`; authenticated durations are explicitly enforced by the application. Redis remains required for OTP, rate limits and cache as well.

Spring Session persists the SecurityContext using its supported default JDK serializer. The final `CustomUserDetails` is an immutable, versioned `UserDetails` containing only account ID, phone/customer username or staff login email, role, company ID, employment ID and durable auth version. It contains no JPA entities, lazy proxies, password hashes, OTPs, repositories or session IDs; Authentication credentials/details are null. Only trusted application instances may access Redis. Serialization changes require a deliberate compatibility decision or session invalidation; no custom domain-model serialization or polymorphic JSON configuration is introduced.

`SessionAuthorizationFilter` runs after context restoration and before CSRF/authorization. It reloads the customer account or exact staff account from PostgreSQL on every authenticated request and compares identity, staff enabled state/auth version, role, company and authentication authorities. Deleted users, recycled/changed phone identities, removed/reassigned employments or changed roles/companies invalidate the session and clear authentication. It never refreshes an old session into a different scope. Database failures fail the request; they cannot allow cached privileges. Existing method security and company checks remain in force.

- `POST /api/auth/logout`: Spring's `SecurityContextLogoutHandler` invalidates only the current HttpSession; Spring Session expires `SESSION`, and `CsrfLogoutHandler` clears `XSRF-TOKEN`. Returns 204 and is idempotent with a fresh CSRF token even when anonymous.
- `POST /api/auth/logout-all`: requires authentication; an atomic Redis generation increment revokes this realm/account, then `FindByIndexNameSessionRepository.findByPrincipalName` deletes the previous generation and logs out this browser. Principal indexes use `customer:<accountId>:<generation>` or `staff:<staffAccountId>:<generation>` (the employment primary key). Customer sessions and other company staff accounts remain untouched. Returns 204; later calls with deleted credentials are unauthenticated.
- Expired/revoked sessions fail on subsequent requests, including requests routed through a different application instance. Already-authorized requests may finish. Every authenticated request checks its account generation, so a delayed save of an old-generation session cannot restore access. A concurrent login may survive only under the new generation, with its own principal index. Generation keys never expire, avoiding reuse while revoked sessions remain in Redis.
- Losing/flushing Redis logs everyone out. Old bearer credentials no longer authenticate. ADR-0009 is preserved as history and [superseded by ADR-0011](./adr/0011-spring-session-redis.md).

### CSRF, cookies and frontend contract

Security 7's `csrf.spa()` uses standard cookie tokens and SPA request handling, including raw headers and BREACH-safe request attributes. The repository is shared with REST login/logout strategies. CSRF is enabled for every unsafe method; there are no authentication exclusions.

1. Fetch `GET /api/auth/csrf` with `credentials: 'include'` before the first unsafe call. It materializes a JavaScript-readable, host-only `XSRF-TOKEN` cookie (Path=/, SameSite=Lax) and returns `{token, headerName: "X-XSRF-TOKEN"}`. Its `token` is only a CSRF token, never the session credential. Cross-origin frontends should use this response rather than trying to read the API host's cookie.
2. Send `X-XSRF-TOKEN: <token>` and credentials/cookies on POST/PUT/PATCH/DELETE, including login, OTP requests/verification and both logout endpoints. Missing/invalid CSRF returns 403 ProblemDetail.
3. Successful authentication and logout clear the prior CSRF cookie. Fetch `/api/auth/csrf` again afterward before the next unsafe request. Refresh on a CSRF failure; do not blindly retry non-idempotent operations.
4. Remove localStorage JWT handling and bearer headers, and stop decoding credential claims. Use `/api/auth/me` for scope/authorities, `/api/users/me` for customer phone identity, and `/api/staff/me` for company-local staff presentation. Handle 401 by clearing local identity state and returning to sign-in. Use fetch `credentials: 'include'` or Axios `withCredentials: true` on all API requests.

`SESSION` is HttpOnly, Path=/, host-only (no Domain), SameSite=Lax and Secure in `prod`; development works on HTTP localhost. Different localhost ports and HTTPS subdomains under the same registrable domain are cross-origin but same-site, so Lax works with explicit credentialed CORS origins. Deploy frontend/API on the same site. Unrelated sites require a deliberate deployment change (including Secure SameSite=None cookies and browser third-party-cookie constraints); they are not assumed here. Never use wildcard origins with credentialed CORS. OPTIONS preflight remains unauthenticated and bypasses CSRF normally.

### Login hardening

Staff login keeps the same error message for unknown email, SMS-only accounts and wrong passwords and runs one bcrypt comparison in each case. OTP identity is global and phone-only: normalize to `+digits` before lookup; verification requires no name and creates no company customer profile. OTP SMS dispatch runs after transaction commit; failures are logged without secrets and never escape into the request.

### Rate limiting (Redis)

| Limiter | Redis key | Limit (default) | Config |
| ------- | --------- | --------------- | ------ |
| Accepted OTP issuances per phone | `rate:sms:<phone>` | 3 per rolling 5 min | Fixed contract |
| OTP resend cooldown | `otp:cooldown:<phone>` | 60 seconds | Fixed contract |
| OTP requests per client IP | `rate:otp-request:<ip>` | 10 per rolling 15 min | Fixed contract |
| OTP failed attempts per code | stored with `otp:<phone>` | 3 | Fixed contract |
| OTP verification per client IP | `rate:otp-verify:<ip>` | 10 per rolling 5 min | Fixed contract |
| Global daily SMS cap | `otp:global-daily` | Operator-defined, positive in prod | `OTP_GLOBAL_DAILY_SMS_CAP` |
| Staff login per client IP | `rate:login:<ip>` | 10 per 1 min | `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES` |

OTP codes contain exactly six digits and expire after five minutes. Lua scripts enforce limits using Redis server time, atomically issue replacements, preserve expiry after failures and consume a correct code only once. A replacement differs from the preceding code, invalidates it, and receives a new expiry and attempt allowance. The phone limit counts accepted issuances only; every issuance request, including cooldown denials, counts against the IP limit. IP verification requests count regardless of outcome. No browser bot challenge is required.

The global daily cap uses UTC calendar days from Redis time and counts accepted OTP issuances and notification send reservations conservatively before dispatch. Failed or rolled-back dispatches do not refund capacity. Production refuses to start unless `OTP_GLOBAL_DAILY_SMS_CAP` is positive. Request limits and accepted-issuance accounting fail closed if Redis is unavailable. Development dispatch logs never include codes; inspect the local Redis `otp:<canonical-phone>` key for manual testing.

Client IP is `request.remoteAddr`. With `server.forward-headers-strategy: native`, Tomcat replaces it with the `X-Forwarded-For` client only when the direct peer matches the `TRUSTED_PROXIES` regex; the default (empty) trusts no proxy, so a client-supplied header cannot bypass the limits. Behind a reverse proxy, set `TRUSTED_PROXIES` to the proxy's address, otherwise every client shares the proxy's limit. For Dokploy behind Cloudflare, see [deployment.md](./deployment.md#client-ip-for-rate-limiting). Exceeding a limit returns 429 with `Retry-After`.

## Authorization and company isolation

- Method security: `@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")` or `hasRole('OWNER')`. Endpoints without `@PreAuthorize` require a valid authenticated session. Public paths are listed in `SecurityConfig` (see [endpoints](./api/endpoints.md)).
- Customer booking/history/cancellation routes require `ROLE_CUSTOMER`; a staff session does not qualify as a customer session.
- Actuator exposes only aggregate public `/actuator/health`; every other actuator route is denied to application principals.
- Every staff operation is scoped to the session principal's `companyId`. Services verify that the target resource (reservation, offering, employee, block) belongs to that company. Foreign and nonexistent resource lookups return opaque 404.
- Database-level isolation exists for schedule blocks: a composite foreign key `(company_id, employee_id)` → `company_employees (company_id, user_id)`.

## Customer blocking and no-shows

Blocks and durable no-show penalties are company-local. Manual and automatic provenance are separate. The inclusive rolling Warsaw calendar-year count ages without clearing an existing block. Correcting a mistaken counted no-show can remove its automatic block below threshold while preserving a manual block. Owner unblock preserves history and gives reprieve until another counted no-show. Threshold zero disables new automatic blocks; setting changes are not retroactive. Blocking cancels future BOOKED visits, preserves ongoing visits, and prevents both customer and staff future creation without affecting authentication. See [reservations](./reservations.md).

## Notifications and scheduled jobs

- Customer booking sends no confirmation SMS/email. Staff create/edit/restore/cancel SMS is explicit opt-in, default off; actual edits invalidate obsolete pending messages even when opting out. Blocking produces a mandatory filtered summary.
- Transactional events persist delivery intent **before commit**. Provider calls run **after commit** using durable state. `SENDING` is committed before each attempt; restart converts an unknown in-flight outcome to `AMBIGUOUS`, never blindly retried. Provider failure cannot roll back a booking.
- Reminders run at 09:00 Warsaw on the previous calendar day, with a 09:05 deadline and uninterrupted in-memory run token. Durable audit excludes post-cutoff creations/start-time moves; unique reservation/date identity prevents same-date repeats. Missed reminder runs are not recovered after restart.
- Reservation completion has a separate audited job that catches up after restart. Jobs assume one instance ([ADR-0007](./adr/0007-single-instance-deployment.md)).
- See [complete delivery rules](./notifications.md) and [ADR-0015](./adr/0015-durable-notification-delivery.md). A sent boolean does not establish crash-safe delivery. Production requires a real SMS adapter; the development console adapter is disabled in prod.

## Caching

Redis-backed Spring Cache (`RedisConfig`), JSON values, default TTL 10 minutes. The `companyServices` cache (company offering lists, 15 minutes) is evicted by offering and category mutations, including `OfferingCategoryService.deleteCategory()` and `assignCategory()`.

## File storage (Cloudflare R2)

S3-compatible client from `R2Config`. With no access key and secret key configured, it uses anonymous credentials (startup succeeds, uploads fail).

- Offering images: gallery capacity is serialized by a pessimistic lock on the tenant-owned offering row. Uploaded objects are compensated after database rollback; deleted objects are removed only after database commit (storage errors can leave an orphan object, never a live database reference to a prematurely deleted object). Actual JPEG/PNG/WebP bytes are decoded, MIME headers are not trusted; decoded dimensions are limited to 25 million pixels. Up to 5 per offering, `POST /api/offerings/{id}/image` (multipart field `image`), JPEG/PNG/WebP, max 5 MB. `OfferingImageService`.
- Employee photo: one per company employee, `POST /api/employees/{id}/photo` (multipart field `photo`), same byte validation/limits. Stored as `company_employees.photo_url`. The employment row serializes replacement; after-commit deletion retains any object referenced by another legacy company profile, and rollback compensates new uploads. `EmployeePhotoService`.

## Database

PostgreSQL, schema managed by Flyway (`src/main/resources/db/migration/`). `V1__init_schema.sql` is the consolidated pre-production baseline. Never edit an applied migration; add `V<n>__description.sql`.

| Table | Purpose |
| ----- | ------- |
| `companies` | Salons; settings: hours, `slot_interval_minutes`, `max_no_shows`, `last_minute_discount_percent`, `last_minute_discount_hours`, `min_booking_advance_minutes` |
| `customer_accounts` | Global phone-only customer authentication identity |
| `company_employees` | Company-owned staff credentials, local presentation/photo, role, enabled state, eligibility and auth version; booking lock target |
| `staff_password_links` | One-use hash-only setup/recovery token per staff account, 24h expiry |
| `company_customers` | Company-local names, optional contact email and staff-only notes |
| `company_customer_blocks`, `no_show_penalties` | Manual/automatic block provenance and durable rolling-year penalty events |
| `offerings`, `offering_categories`, `offering_images` | Catalog |
| `employee_offerings` | Which offerings an employee performs |
| `employee_work_schedules`, `employee_schedule_overrides` | Weekly shift plus date replacements/days off |
| `schedule_blocks` | Employee unavailability; GiST exclusion constraint against overlaps |
| `reservations`, `reservation_audit`, `reservation_commands` | Accepted commercial snapshots, revisions, durable mutation history and stale-command protection; employee/customer GiST exclusions |
| `booking_submissions`, `booking_quotes` | Permanent account-scoped request identity and expiring commercial guarantees |
| `notification_deliveries`, `notification_delivery_reservations` | Durable SMS attempts, provider uncertainty, retry deadlines and summary membership |

## Strict request input

Jackson rejects unknown JSON properties and fractional values for every integral property, including identifiers, PLN prices and durations. Date-time JSON/query input uses strict ISO local parsing. Failures return Problem Details (`MALFORMED_REQUEST` for JSON, `INVALID_PARAMETER` for query/path conversion).

The baseline was consolidated with explicit pre-production authorization on 2026-10-15. Existing developer databases must be recreated before using this baseline; the application never resets a database automatically. No legacy global user/profile table is part of the new schema.
