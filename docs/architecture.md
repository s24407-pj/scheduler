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

**Layering:** Controller → Service → Repository. Application classes must not depend on `*Service` types from other modules, except the explicitly shared infrastructure types `security.SessionAuthenticationService` and `security.CustomUserDetailsService`. This does not exempt other services in `security` or `config`. Modules may share IDs, repositories, domain events and shared policies (`EmployeeAvailabilityPolicy`, `BookableSlotPolicy`, `QuotePolicy`, `CustomerBlockingPolicy`). Cross-module repository dependencies and the package cycles they create are permitted.

`ArchitectureTest` uses ArchUnit to inspect compiled production dependencies, independently of import syntax. Controllers (identified by name or Spring controller annotations) cannot depend directly on application repositories, Spring Data repositories, JDBC operations or `EntityManager`, whether through constructors, properties, method signatures or calls. The service boundary follows the `*Service` naming contract rather than all Spring `@Service` beans, preserving shared policies. Architecture tests run without Spring Context or containers.

### Reads and write ownership

Permitted cross-module repository dependencies are not blanket permission to mutate another domain. Scoped reads may support a journey or projection; writes must follow the owning workflow or an explicitly authorized shared policy. These responsibilities are a review contract, not additional rules enforced by `ArchitectureTest`.

| Data or operation | Write responsibility and authorized crossings |
| ----------------- | --------------------------------------------- |
| Reservation lifecycle and no-show outcomes | `ReservationService` coordinates lifecycle transitions, durable penalty creation/correction and resulting automatic block updates in the reservation transaction. |
| Customer blocks and shared blocking operations | `CustomerBlockingPolicy` owns manual block/unblock and shared current counting and future-booking cancellation. It may write reservation cancellations while the caller holds the company lock, retaining per-row audit, optimistic versions and the mandatory summary event. Automatic blocking delegates these shared operations to the policy; penalty and automatic-provenance SQL remain in `ReservationService`. |
| Notification delivery | The notification module owns outbox creation, eligibility and delivery transitions. One narrow cross-module write is retained: a notes-only reservation edit advances `reservation_revision` on matching `PENDING`, `TEMPORARY_FAILURE` and `PAUSED` deliveries in the same transaction. It preserves queued messages across a private-note revision without changing their delivery state or sending a new message. |
| Company-local customer profiles and history | Customer profile writes follow company-local identity/profile workflows, including atomic establishment by booking. `CompanyCustomerRepository.findVisitHistory` intentionally reads unarchived reservation history scoped by company and customer, after the caller verifies established customer visibility; it grants no reservation mutation authority. |

See [reservation atomicity and blocking](./reservations.md#blocking-and-no-show-history) and [notification delivery](./notifications.md). Preserve company-before-employment locking where both locks are required, transaction boundaries, audit and tenant checks across every authorized write.

Dashboard effective-shift calculations, conflict previews and permission controls are advisory presentation logic. They may project backend data and hide unavailable actions, but never authorize a write or establish bookability. Spring remains authoritative for validation, authorization and tenant isolation, including when client projections are stale or bypassed.

## Authentication

| Flow | Endpoint(s) | Authenticated session scope |
| ---- | ----------- | --------------------------- |
| Customer | `POST /api/auth/request-code` → `POST /api/auth/verify-code` | `ROLE_CUSTOMER`, exactly one company/local customer, no employment |
| Staff | `POST /api/auth/login-staff-email` → optional `POST /api/auth/complete-staff-login`; company-scoped `POST /api/auth/login-staff` remains supported | Exactly one `employmentId`, its `companyId`, and `ROLE_OWNER` or `ROLE_EMPLOYEE` |

All unsafe calls, including anonymous authentication endpoints, require CSRF. Staff credentials belong to one company. Email is trimmed and lowercased before lookup; email-first discovery verifies every candidate account independently before revealing company names. It never creates shared credentials. An already authenticated browser must log out first; login returns 409 rather than replacing its identity or company scope. See [identity and staff administration](./identity.md) and [ADR-0012](./adr/0012-separate-account-realms.md).

After verification, `AuthService` returns only a safe identity after its transaction commits. `SessionAuthenticationService` invokes Spring's `ChangeSessionIdAuthenticationStrategy` and `CsrfAuthenticationStrategy`, creates a fresh `SecurityContext`, and explicitly saves it through the shared `HttpSessionSecurityContextRepository`. Setting the holder alone is insufficient with Security 7's explicit-save mode. The credential is Spring Session's normal `SESSION` cookie; auth JSON contains status, never a credential. `GET /api/auth/me` returns `userId`, `username`, `authorities`, `companyId`, and `employmentId`; `/api/users/me` returns local customer ID and phone only; `/api/auth/me.companyId` is populated for customers too; `/api/staff/me` returns company-local staff presentation.

### Redis sessions, revocation and serialization

Boot's `spring-boot-starter-session-data-redis` configures `RedisIndexedSessionRepository` (`spring.session.data.redis.repository-type=indexed`), with namespace `scheduler:sessions`. Authenticated CUSTOMER sessions have an immutable deadline 24 elapsed hours after login, enforced before authorization using the injected clock; activity cannot extend it. Authenticated STAFF sessions have a 24-hour inactivity timeout and activity refreshes it. The anonymous Spring Session default remains `spring.session.timeout=24h`; authenticated durations are explicitly enforced by the application. Redis remains required for OTP, rate limits and cache as well.

Spring Session persists the SecurityContext using its supported default JDK serializer. The final `CustomUserDetails` is an immutable, versioned `UserDetails` containing only account ID, phone/customer username or staff login email, role, company ID, employment ID and durable auth version. It contains no JPA entities, lazy proxies, password hashes, OTPs, repositories or session IDs; Authentication credentials/details are null. Only trusted application instances may access Redis. Serialization changes require a deliberate compatibility decision or session invalidation; no custom domain-model serialization or polymorphic JSON configuration is introduced.

`SessionAuthorizationFilter` runs after context restoration and before CSRF/authorization. It reloads the exact company/customer account or exact staff account from PostgreSQL on every authenticated request and compares identity, staff enabled state/auth version, role, company and authentication authorities. Deleted users, recycled/changed phone identities, removed/reassigned employments or changed roles/companies invalidate the session and clear authentication. It never refreshes an old session into a different scope. Database failures fail the request; they cannot allow cached privileges. Existing method security and company checks remain in force.

- `POST /api/auth/logout`: Spring's `SecurityContextLogoutHandler` invalidates only the current HttpSession; Spring Session expires `SESSION`, and `CsrfLogoutHandler` clears `XSRF-TOKEN`. Returns 204 and is idempotent with a fresh CSRF token even when anonymous.
- `POST /api/auth/logout-all`: requires authentication; an atomic Redis generation increment revokes this realm/account, then `FindByIndexNameSessionRepository.findByPrincipalName` deletes the previous generation and logs out this browser. Principal indexes use `customer:<companyId>:<customerId>:<generation>` or `staff:<staffAccountId>:<generation>` (the employment primary key). Customer sessions and other company staff accounts remain untouched. Returns 204; later calls with deleted credentials are unauthenticated.
- Expired/revoked sessions fail on subsequent requests, including requests routed through a different application instance. Already-authorized requests may finish. Every authenticated request checks its account generation, so a delayed save of an old-generation session cannot restore access. A concurrent login may survive only under the new generation, with its own principal index. Generation keys never expire, avoiding reuse while revoked sessions remain in Redis.
- Losing/flushing Redis logs everyone out. Old bearer credentials no longer authenticate. ADR-0009 is preserved as history and [superseded by ADR-0011](./adr/0011-spring-session-redis.md).

### CSRF, cookies and frontend contract

Security 7's `csrf.spa()` uses standard cookie tokens and SPA request handling, including raw headers and BREACH-safe request attributes. The repository is shared with REST login/logout strategies. CSRF is enabled for every unsafe method; there are no authentication exclusions.

1. Fetch `GET /api/auth/csrf` with `credentials: 'include'` before the first unsafe call. It materializes a JavaScript-readable, host-only `XSRF-TOKEN` cookie (Path=/, SameSite=Lax) and returns `{token, headerName: "X-XSRF-TOKEN"}`. Its `token` is only a CSRF token, never the session credential. Cross-origin frontends should use this response rather than trying to read the API host's cookie.
2. Send `X-XSRF-TOKEN: <token>` and credentials/cookies on POST/PUT/PATCH/DELETE, including login, OTP requests/verification and both logout endpoints. Missing/invalid CSRF returns 403 ProblemDetail.
3. Successful authentication and logout clear the prior CSRF cookie. Fetch `/api/auth/csrf` again afterward before the next unsafe request. Refresh on a CSRF failure; do not blindly retry non-idempotent operations.
4. Remove localStorage JWT handling and bearer headers, and stop decoding credential claims. Use `/api/auth/me` for scope/authorities, `/api/users/me` for customer phone identity, and `/api/staff/me` for company-local staff presentation. Handle 401 by clearing local identity state and returning to sign-in. Use fetch `credentials: 'include'` or Axios `withCredentials: true` on all API requests.

Staff writes also carry `X-Expected-Session-Context: v1:userId:companyId:employmentId:ROLE_OWNER` (or `ROLE_EMPLOYEE`), using the public `/api/auth/me` identity fields and lowercase canonical UUIDs. Capture this scope with the authenticated UI/form and before any asynchronous CSRF bootstrap; never derive it from the newest cookie or a refreshed account at send time. The header is mandatory for protected unsafe customer and staff API calls, including `/api/auth/logout-all`; public login/OTP/recovery and `/api/auth/logout` are excluded. Customer writes require `v1:customerId:companyId:null:ROLE_CUSTOMER`, using the same lowercase UUID and captured-form rules. The literal `null` denotes no employment.

`ExpectedSessionContextFilter` runs after the stored principal has been revalidated and before CSRF. Missing, malformed or repeated headers yield 400 `SESSION_CONTEXT_REQUIRED`; mismatch yields 409 `SESSION_CONTEXT_CHANGED`, with no business execution and no logout of the valid current session. Thus a category form opened for Alpha cannot silently create a category in Beta after another tab changes the shared cookie. The context is an equality precondition, not a secret or authorization grant; cookies, CSRF and resource/role authorization remain mandatory. Relogin to the same scope may reuse the same context. Requests already authorized may finish under their captured principal, as before.

The frontend can verify session identity silently on focus while retaining its UI. Confirmed session changes discard private cached data and drafts; expired-session and context-change responses must not automatically retry a mutation. A network/server error is not proof of logout. No client-side focus check can close the race between checking a cookie and sending a write; this server precondition closes that account-scope race for writes.

This is a required client contract change: deploy updated clients and API together, or explicitly disable staff writes during the rollout. Previous clients without the header receive 400 rather than being silently allowed. Feature-test Redis fixtures carry the client-side scope snapshot in a test cookie wrapper and a MockMvc request customizer emits the normal header; boundary tests use real login/cookies/CSRF and explicitly set, omit or change the header.

`SESSION` is HttpOnly, Path=/, host-only (no Domain), SameSite=Lax and Secure in `prod`; development works on HTTP localhost. Different localhost ports and HTTPS subdomains under the same registrable domain are cross-origin but same-site, so Lax works with explicit credentialed CORS origins. Deploy frontend/API on the same site. Unrelated sites require a deliberate deployment change (including Secure SameSite=None cookies and browser third-party-cookie constraints); they are not assumed here. Never use wildcard origins with credentialed CORS. OPTIONS preflight remains unauthenticated and bypasses CSRF normally.

### Login hardening

Staff login keeps the same error message for unknown email, disabled/passwordless accounts and wrong passwords. Company-scoped login runs one BCrypt comparison. Email-first login runs exactly five comparisons (dummy padding included) with a six-ID indexed query; overflow fails with the same generic 401 without revealing counts or partial matches. See the safe company-scoped fallback in [identity](./identity.md). OTP identity is company-local and phone-only: both OTP bodies require `companyId`, normalize the phone to `+digits`, and use `(companyId, phone)` for codes. Verification requires no name and upserts a pending local customer, invisible to staff until the first successful booking. OTP intent is durable before commit in `otp_deliveries`; classified provider delivery runs after commit, checks original Redis validity, clears terminal secrets and conservatively marks unknown in-flight sends ambiguous on restart. Failures are logged without secrets and never escape into the request.

### Rate limiting (Redis)

| Limiter | Redis key | Limit (default) | Config |
| ------- | --------- | --------------- | ------ |
| Accepted OTP issuances per phone | `rate:sms:<phone>` | 3 per rolling 5 min | Fixed contract |
| OTP resend cooldown | `otp:cooldown:<phone>` | 60 seconds | Fixed contract |
| OTP requests per client IP | `rate:otp-request:<ip>` | 10 per rolling 15 min | Fixed contract |
| OTP failed attempts per code | stored with `otp:<companyId>:<phone>` | 3 | Fixed contract |
| OTP verification per client IP | `rate:otp-verify:<ip>` | 10 per rolling 5 min | Fixed contract |
| Global daily SMS cap | `otp:global-daily` | Operator-defined, positive in prod | `OTP_GLOBAL_DAILY_SMS_CAP` |
| Email-first login per normalized email | `rate:login:email:<SHA-256(email)>` | Same allowance/window as IP; one count per attempt across all companies/IPs | `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES` |
| Staff password login per client IP | `rate:login:<ip>` | 10 per 1 min | `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES` |
| Staff selection completion per client IP | `rate:login:completion:<ip>` | Independent 10 per 1 min, all outcomes | `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES` |

OTP codes contain exactly six digits and expire after five minutes. Lua scripts enforce limits using Redis server time, atomically issue replacements, preserve expiry after failures and consume a correct code only once. A replacement differs from the preceding code, invalidates it, and receives a new expiry and attempt allowance. The phone limit counts accepted issuances only; every issuance request, including cooldown denials, counts against the IP limit. IP verification requests count regardless of outcome. No browser bot challenge is required.

The global daily cap uses UTC calendar days from Redis time and reserves first OTP attempts at accepted issuance, additional definite-temporary OTP retries and notification attempts conservatively before dispatch. Failed or rolled-back dispatches do not refund capacity. Production refuses to start unless `OTP_GLOBAL_DAILY_SMS_CAP` is positive. Request limits and accepted-issuance accounting fail closed if Redis is unavailable. Development dispatch logs never include codes; inspect the local Redis `otp:<companyId>:<canonical-phone>` key for manual testing.

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
- OTP and notifications use separate bounded SMS pools (default 2/4 calls) over PostgreSQL outboxes. Listeners and scheduler triggers only wake coordinators; provider latency cannot occupy their threads. Work is serialized per phone or company/customer, with deadlines rechecked before dispatch.
- Reminders run at 09:00 Warsaw on the previous calendar day, with a 10:00 deadline and uninterrupted in-memory run token. Durable audit excludes post-cutoff creations/start-time moves; unique reservation/date identity prevents same-date repeats. Missed reminder runs are not recovered after restart.
- Reservation completion has a separate audited job that catches up after restart. Jobs assume one instance ([ADR-0007](./adr/0007-single-instance-deployment.md)).
- See [complete delivery rules](./notifications.md) and [ADR-0015](./adr/0015-durable-notification-delivery.md). A sent boolean does not establish crash-safe delivery. Production uses JustSend for single-message SMS; the development console adapter is disabled in prod.

## Caching

Redis-backed Spring Cache (`RedisConfig`), JSON values, default TTL 10 minutes. The `companyServices` cache (company offering lists, 15 minutes) is evicted by offering and category mutations, including `OfferingCategoryService.deleteCategory()` and `assignCategory()`.

## File storage (Cloudflare R2)

S3-compatible client from `R2Config`. With no access key and secret key configured, it uses anonymous credentials (startup succeeds, uploads fail).

- Offering images: gallery capacity is serialized by a pessimistic lock on the tenant-owned offering row. Uploaded objects are compensated after database rollback; deleted objects are removed only after database commit (storage errors can leave an orphan object, never a live database reference to a prematurely deleted object). Actual JPEG/PNG/WebP bytes are decoded, MIME headers are not trusted; decoded dimensions are limited to 25 million pixels. Up to 5 per offering, `POST /api/offerings/{id}/image` (multipart field `image`), JPEG/PNG/WebP, max 5 MB. `OfferingImageService`.
- Employee photo: one per company employee, `POST /api/employees/{id}/photo` (multipart field `photo`), same byte validation/limits. Stored as `company_employees.photo_url`. The employment row serializes replacement; after-commit deletion retains any object referenced by another legacy company profile, and rollback compensates new uploads. `EmployeePhotoService`.

## Database

PostgreSQL 18, schema managed by Flyway (`src/main/resources/db/migration/`). `V1__init_schema.sql` is the consolidated pre-production baseline. Never edit an applied migration; add `V<n>__description.sql`.

| Table | Purpose |
| ----- | ------- |
| `companies` | Salons; settings: hours, `slot_interval_minutes`, `max_no_shows`, `last_minute_discount_percent`, `last_minute_discount_hours`, `min_booking_advance_minutes` |
| `company_employees` | Company-owned staff credentials, local presentation/photo, role, enabled state, eligibility and auth version; booking lock target |
| `staff_password_links` | One-use hash-only setup/recovery token per staff account, 24h expiry |
| `company_customers` | Local phone identity, unique company/phone; optional pending names; first-booking visibility, email and notes |
| `company_customer_blocks`, `no_show_penalties` | Manual/automatic block provenance and durable rolling-year penalty events |
| `offerings`, `offering_categories`, `offering_images` | Catalog |
| `employee_offerings` | Which offerings an employee performs |
| `employee_work_schedules`, `employee_schedule_overrides` | Weekly shift plus date replacements/days off |
| `schedule_blocks` | Employee unavailability; GiST exclusion constraint against overlaps |
| `reservations`, `reservation_audit`, `reservation_commands` | Accepted commercial snapshots, revisions, durable mutation history and stale-command protection; employee/customer GiST exclusions |
| `booking_submissions`, `booking_quotes` | Permanent account-scoped request identity and expiring commercial guarantees |
| `notification_deliveries`, `notification_delivery_reservations` | Durable SMS attempts, provider uncertainty, retry deadlines and summary membership |

Entity primary keys and resource references use native PostgreSQL `uuid`, Kotlin `UUID`, and OpenAPI `string`/`uuid`. Hibernate 7.4 generates UUIDv7 using `@UuidGenerator(style = VERSION_7)`; pre-persistence quote/OTP identifiers use the same Hibernate strategy. SQL-created records default to PostgreSQL 18 `uuidv7()`. The independent public employee `user_id` also uses UUIDv7; employment and customer realms retain their existing scope checks. Revisions, `@Version`, auth versions, counters, prices and durations remain numeric.

The UUID baseline was consolidated into V1 with explicit pre-production authorization on 2026-10-17. Recreate existing development databases and clear existing Redis sessions/caches before running it. That migration used principal serialization version 2; the current company-local customer baseline uses version 3 and requires fresh login.

## Strict request input

Jackson rejects unknown JSON properties and fractional values for every integral property, including PLN prices and durations; identifiers require UUID strings. Date-time JSON/query input uses strict ISO local parsing. Failures return Problem Details (`MALFORMED_REQUEST` for JSON, `INVALID_PARAMETER` for query/path conversion).

The baseline was consolidated with explicit pre-production authorization on 2026-10-15. Existing developer databases must be recreated before using this baseline; the application never resets a database automatically. No global customer or legacy user/profile table is part of the new schema.

The 2026-10-24 authorized baseline consolidates former V1–V4 and company-local customers into V1. See [ADR-0017](./adr/0017-company-local-customers.md). Recreate development databases and clear existing Redis sessions, OTP state and caches before using it; principal serialization version 3 deliberately rejects old sessions. The application never resets an existing environment automatically.

V3 removes obsolete reservation reminder/provenance fields and requires an explicit creation
source for new writes; existing provenance and audit JSON remain intact. The staff directory
is `/api/employees` only. See [ADR-0019](./adr/0019-remove-obsolete-compatibility-code.md)
for the paired consumer change and migration/rollback requirements.

Catalog category create/delete/assignment mutations acquire the company row lock before
loading catalog entities, matching offering writes and booking. Category assignment cannot
flush an older offering snapshot over a concurrent price, duration or activation change.
