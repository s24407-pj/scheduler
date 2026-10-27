# AGENTS.md

Keep diffs focused, match surrounding patterns, and do not commit unless asked. Read relevant docs before changing behavior; [docs index](./docs/README.md).

## Commands

Use the repository root, JDK 25 and Gradle wrapper. Versions: [mise.toml](./mise.toml), [version catalog](./gradle/libs.versions.toml), [build](./build.gradle.kts). Integration tests need Docker/rootless Podman; unit/architecture tests need no containers.

```bash
./gradlew test                                  # Full suite
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest"
./gradlew build                                 # Build, tests, spotlessCheck; also used by CI
./gradlew build -x test                         # Build and formatting check without tests
./gradlew spotlessApply                         # Format Kotlin and Gradle Kotlin scripts
```

**Local development:** copy [.env.example](./.env.example) to `.env` only if none exists, then:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
curl -sf http://localhost:8080/actuator/health
```

The override selects `dev` and binds API `8080`, debugger `5005`, PostgreSQL `5432`, Redis `6379` to loopback. R2 placeholders permit startup; uploads need real settings. [Development](./docs/development.md) covers accounts, host-side running and seed data; availability is empty until schedules are set.

**Production:** `docker compose -f docker-compose.yml up -d --no-build` defaults to `prod`, pulls the approved GHCR digest via required `SCHEDULER_IMAGE`, requires deployment settings (including JustSend credentials and a positive SMS budget), and publishes no host ports. Follow [deployment](./docs/deployment.md); never use the dev override or example credentials in production.

### Optional workstation setup

`mise install && lefthook install` installs pinned tools/hooks. [lefthook.yml](./lefthook.yml) formats and re-stages staged Kotlin changes.

For rootless Podman, set `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock`. If exports/mise activation live in `~/.zshrc`, use `zsh -ic '<command>'` when needed. This is workstation-specific; [prerequisites](./docs/development.md#prerequisites) include the long-test-run Podman idle-timeout workaround.

## Documentation routing

| When changing... | Read |
| --- | --- |
| Multi-endpoint journeys | [flows](./docs/flows.md) |
| Booking, lifecycle, concurrency, idempotency, revisions, audit | [reservations](./docs/reservations.md), [ADR-0016](./docs/adr/0016-reservation-contract-and-durable-submissions.md) |
| Identity, staff administration, passwords | [identity](./docs/identity.md), [ADR-0012](./docs/adr/0012-separate-account-realms.md) |
| Sessions, revocation, CSRF, CORS, rate limits | [architecture](./docs/architecture.md), [ADR-0011](./docs/adr/0011-spring-session-redis.md) |
| Availability, schedules, blocks, qualification, settings | [availability](./docs/availability.md), [ADR-0014](./docs/adr/0014-effective-schedules-and-explicit-qualification.md) |
| Prices, discounts, quotes | [pricing](./docs/pricing.md), [ADR-0013](./docs/adr/0013-commercial-quotes.md) |
| SMS/OTP delivery, workers, reminders | [notifications](./docs/notifications.md), [SMS operations](./docs/sms-operations.md), [ADR-0015](./docs/adr/0015-durable-notification-delivery.md) |
| Dates, clock, DST, jobs | [time handling](./docs/time-handling.md), ADR-0016 above |
| Controllers, authorization, validation, errors | [endpoints](./docs/api/endpoints.md), [errors](./docs/api/errors.md) |
| Modules, caching, R2, schema | [architecture](./docs/architecture.md) |
| Config, seed, tests, build/CI | [development](./docs/development.md), [CI](./.github/workflows/ci.yml) |
| Deployment, proxy trust, networking, persistence | [deployment](./docs/deployment.md) |
| Load/performance tests | [perf-tests](./perf-tests/README.md) |

Check the [ADR index](./docs/adr/README.md). ADR-0018 supersedes flat-field customer booking intent, adds profile revisions, international input. ADR-0017 supersedes the global-customer portions of ADR-0011/0012 and the profile-establishment rule in ADR-0016; OTP outbox scope refines ADR-0015. ADR-0016 supersedes ADR-0001/0002/0003/0006/0010. Follow current domain docs/accepted decisions; report discrepancies. Do not implement **Proposed** ADRs without user agreement. Supersede changed accepted decisions with a new ADR, preserving history.

## Project and architecture

Kotlin/Spring Boot 4 MVC backend; PostgreSQL/Flyway, Redis (OTP, limits, cache, indexed Spring Session), Cloudflare R2. Tests: JUnit 5, MockK/SpringMockK, Testcontainers, ArchUnit. The staff frontend is separate; see [README](./README.md).

Modules under `src/main/kotlin/pl/kacosmetology/scheduler/`:

- `auth` (including `sms`): OTP, staff login/selection, limits, OTP outbox, SMS adapters/budget/workers.
- `availability`: public slots, shared conflict and effective-shift/grid policies.
- `company`: settings/profile, staff persistence, public employee lists.
- `config`: security/CORS, clock/conversion, Redis/R2, errors/input, workers, dev seed.
- `employeeoffering`: qualification mode and offering assignments.
- `notification`: durable appointment SMS, delivery/recovery, 09:00 reminders.
- `offering`: catalog, categories, images. `quote`: durable commercial quotes/agreement resolution.
- `reservation`: submissions, booking/edit/lifecycle, audit, automatic completion.
- `scheduleblock`: employee unavailability and revision-protected updates.
- `security`: authentication bridge, immutable principals, session authorization/revocation.
- `user`: customer identity/local profiles/blocks, staff administration/passwords/photos.
- `workschedule`: weekly shifts, date replacements, days off.

Controller → Service → Repository; no repositories in controllers. No cross-module service dependencies except `security`/`config` infrastructure. Share IDs/repositories and domain events; reuse shared policies (`EmployeeAvailabilityPolicy`, `BookableSlotPolicy`, `QuotePolicy`, `CustomerBlockingPolicy`). [ArchitectureTest](./src/test/kotlin/pl/kacosmetology/scheduler/architecture/ArchitectureTest.kt) checks compiled dependencies on foreign `*Service` types and controller access to repositories, JDBC and EntityManager. Only `security.SessionAuthenticationService` and `security.CustomUserDetailsService` are shared service exceptions; entire infrastructure packages are not exempt. Cross-module repositories and package cycles remain permitted; preserve the broader layering rule.

## Critical invariants

- **Identity/tenants:** customers are company-owned canonical-phone identities in company_customers; OTP creates a pending identity and the first successful booking establishes staff visibility atomically. Staff credentials/presentation are company-owned; matching phone/email never merges realms. Staff principals have exactly one employment, company and OWNER/EMPLOYEE role; CUSTOMER has exactly one company/local customer and no employment. Verify company ownership on every staff operation; foreign/missing resources return opaque 404. Customer booking/quote/history/cancellation must also stay within the session company. Calendars/assignments require `(company, employee)`, never employee alone.
- **Sessions:** authenticate only anonymous browsers after credentials/OTP succeed and DB commit, through `SessionAuthenticationService`; run fixation/CSRF strategies and explicitly save `SecurityContext`. Principals are immutable scalars, without entities, passwords, OTPs or session IDs. Use indexed Spring Session Redis, never a parallel session store. CUSTOMER expires absolutely after 24h; STAFF after 24h inactivity.
- **Revocation:** before authorization revalidate exact account/employment, enabled state, identity, company, role/authorities, durable auth version, generation and customer deadline; fail closed. Never update stored scope silently. Password changes/reset increment auth version and revoke after commit. Logout-all generations are realm/account-scoped and never expire; delayed saves cannot revive access. Logout before switching identity/company.
- **CSRF/cookies:** protect every unsafe request, including public auth/OTP/password and logout. Fetch `/api/auth/csrf`, send cookies plus `X-XSRF-TOKEN`, refresh after login/logout. SESSION is HttpOnly, host-only, Path=/, SameSite=Lax, Secure in prod. Require explicit credentialed CORS origins and same-site deployment. Never globally bypass CSRF. Protected customer and staff writes require a captured X-Expected-Session-Context; customer employment is the literal null.
- **Limits/IP:** preserve atomic fail-closed Redis limits and shared SMS attempt budgeting. Use `request.remoteAddr`, never manual forwarded-header parsing. Tomcat trusts only explicit `TRUSTED_PROXIES`; default trusts none. Preserve production origin restrictions.
- **Concurrency:** lock company before employment for booking, blocks and policy mutations; perform authoritative time/rule checks after locking. PostgreSQL exclusions protect company/employee and company/customer occupied intervals; COMPLETED retains its full interval. `@Version` protects edits, not allocation. Availability is advisory; quotes hold no capacity and bypass no hard rules.
- **Idempotency:** both booking endpoints require permanent realm/account-scoped `Idempotency-Key`/payload binding before validation in a separately committed transaction, including failed validation. Changed payload conflicts; successful replay returns current details forever without repeated allocation/side effects. Preserve bounded waits.
- **History:** BOOKED is initial, with no approval workflow. `If-Match` revision preconditions and durable command outcomes protect edits/lifecycle retries. Every actual mutation retains audit/versioning, including jobs/block cancellations/archive; no permanent deletion or bulk bypass. Preserve identity, creation time, accepted terms and staff-only field privacy per reservations docs.
- **Schedules/time:** reuse qualification/shift/grid policies; empty specific assignments mean no services, only explicit `[]` clears a week. API appointment times are offset-free Warsaw local times; reject offsets, DST gaps/overlaps and transition-spanning appointments. Use injected `Clock` and `BUSINESS_ZONE`/`BUSINESS_ZONE_ID`, never default-zone `now()` or ad hoc `ZoneId.of(...)`. Elapsed rules use instants; horizon/no-show windows use Warsaw dates. Preserve explicit persistence conversion and UTC test JVM.
- **Money/errors:** prices are `Int` whole złoty, with ties-to-even discount rounding and server-owned quote terms. IDs are UUIDs. Business errors use `ApiException(ErrorCode.X, "<Polish detail>")`; ProblemDetail carries stable `code`.
- **Delivery:** persist OTP/notification intent before commit; commit SENDING before after-commit provider calls outside transactions. Classify/persist provider results as ACCEPTED, DEFINITE_TEMPORARY_FAILURE or AMBIGUOUS; retry only definite temporary failures within eligibility/deadlines, never unknown outcomes. Provider failures cannot roll back bookings. Preserve bounded separate pools, phone/company-customer lanes and budget checks; a sent flag is insufficient.
- **Notifications/jobs:** customer booking sends no confirmation SMS/email; staff SMS is opt-in, default off, with obsolete-message invalidation; blocking sends a filtered summary. Reminders: 09:00 Warsaw, 10:00 deadline, no missed-run recovery; audited completion catches up. Single instance; no distributed job lock.
- **Logging:** never log OTPs, passwords, tokens, provider secrets or raw constraint violations. ConsoleSmsSender omits OTP codes and is disabled in prod. Preserve MDC `requestId`, prod ECS JSON and safe provider metadata logging per notifications docs.

## Change and verification checklist

- **Tests:** add/extend coverage by risk: unit tests for business branches; integration/repository tests for HTTP/security, tenant isolation, persistence, Redis and concurrency; regressions for defects. Avoid redundant coverage at every layer. Feature fixtures use real Redis sessions (`TestSessions`) and explicit `csrf()`; session-boundary tests use actual login/cookies/CSRF.
- **Checks:** for code changes run `./gradlew test` or a relevant subset; broaden for shared behavior. CI runs `./gradlew build` including `spotlessCheck`; format Kotlin/Gradle scripts with Spotless. For docs-only changes validate paths, commands and consistency; application tests are optional. Report checks and unavailable verification.
- **Flyway:** the user-authorized 2026-10-24 fresh baseline consolidates former V1–V4 and company-local customers into V1; existing databases must be recreated explicitly. Subsequent schema changes need a new migration in [db/migration](./src/main/resources/db/migration/). Check the highest existing version and use the next sequential version (`V<n>__description.sql`). Never edit existing migrations, bypass checksum validation, or rebaseline without explicit authorization.
- **Validation/KDoc:** use Jakarta annotations for field constraints; services/policies enforce cross-field/entity rules. Booking time checks use injected clock under locks, not JVM-dependent `@Future`. KDoc explains meaningful public contracts/invariants or non-obvious behavior, without restating declarations.
- **Docs:** update matching domain docs for behavior changes, endpoints for mappings/`@PreAuthorize`, errors for new codes/contracts, and ADRs for decision changes.
