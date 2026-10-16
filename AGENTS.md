# AGENTS.md

Instructions for AI coding agents working in this repository. Detailed documentation lives in [`docs/`](./docs/README.md); this file holds commands, invariants, and guardrails.

## Commands

```bash
./gradlew test # All tests (requires Docker for Testcontainers)
./gradlew build # Build including tests
./gradlew build -x test # Build without tests
./gradlew spotlessApply # Format Kotlin sources (ktfmt); spotlessCheck runs in build
docker compose up --build # Full stack (PostgreSQL + Redis + app)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build # Dev profile + debugger on :5005
```

Run a single test class:

```bash
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest"
```

Run a single test method:

```bash
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest.full customer registration and login via SMS should create session"
```

Before finishing a task, run `./gradlew test` (or a relevant subset).

## Local Dev Checklist

```bash
docker compose up --build # or: docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
curl -sf http://localhost:8080/actuator/health
```

Dev accounts: owner `gabinet@kacosmetology.pl` / `admin123`, employee `pracownik@kacosmetology.pl` / `employee123`. Seed data has no work schedules, so availability is empty until one is set. Details: [docs/development.md](./docs/development.md).

JDK 25 and lefthook are pinned in `mise.toml` (`mise install && lefthook install` once; mise switches to the JDK inside the repo). The lefthook pre-commit hook runs `spotlessApply` on commits that stage Kotlin files and re-stages the result. Testcontainers and `docker compose` use rootless Podman via `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock`, exported in `~/.zshrc`; non-interactive shells need `zsh -ic '...'` to pick up both. Distrobox is no longer required.

## Project Overview

Beauty/barber salon scheduling backend: **Kotlin**, **Spring Boot 4** (Web MVC, Security, Data JPA, Validation, Actuator), **PostgreSQL** + **Flyway**, **Redis** (OTP, rate limiting, cache), **Spring Session Redis**, **Cloudflare R2** for images, **Testcontainers** + **MockK**/**SpringMockK** for tests.

Customers authenticate via SMS OTP; staff via company-scoped email/password. The current staff frontend is the separate repository `../../scheduler-dashboard` (TanStack Start, Bun). Sibling `../scheduler-dashboard-start` and `../scheduler-dashboard` are older JWT-based prototypes.

```
src/main/kotlin/pl/kacosmetology/scheduler/
├── auth/ # SMS OTP + staff login → Spring Security sessions
├── availability/ # Available slot calculation (public), shared EmployeeAvailabilityPolicy and BookableSlotPolicy
├── company/ # Company entity, settings, public booking profile
├── config/ # Security, CORS, Redis, R2, RestExceptionHandler, DataInitializer (dev seed)
├── employeeoffering/ # Which offerings an employee performs
├── notification/ # SMS notifications, durable delivery, 09:00 reminders and audited completion jobs
├── offering/ # Offering catalog, categories, images
├── reservation/ # Booking lifecycle
├── scheduleblock/ # Employee time blocks (breaks, unavailability)
├── security/ # session authorization filter, CustomUserDetails, CustomUserDetailsService
├── user/ # Profiles, customers (block/unblock, notes), employee photos
└── workschedule/ # Employee weekly work schedules
```

## Where to Read Before Changing Code

| If you touch... | Read |
| --------------- | ---- |
| A user journey spanning several endpoints (setup, booking, sign-in, staff work, visit outcome, jobs) | [docs/flows.md](./docs/flows.md) |
| Booking, cancellation, status changes, pricing | [docs/reservations.md](./docs/reservations.md), [ADR-0001](./docs/adr/0001-double-booking-prevention.md), [ADR-0002](./docs/adr/0002-reservation-lifecycle-and-cancellation.md), [ADR-0010](./docs/adr/0010-cancellation-window-and-no-show-correction.md) |
| Availability, work schedules, blocks, assignments, company settings | [docs/availability.md](./docs/availability.md) |
| Dates, times, "now", scheduled jobs | [docs/time-handling.md](./docs/time-handling.md) |
| Auth, security, notifications, caching, R2, schema | [docs/architecture.md](./docs/architecture.md) |
| Controllers, status codes, error bodies | [docs/api/endpoints.md](./docs/api/endpoints.md), [docs/api/errors.md](./docs/api/errors.md) |
| Config, seed data, test setup | [docs/development.md](./docs/development.md) |

## Invariants

- **Layering:** Controller → Service → Repository. No cross-module service calls; modules communicate through IDs. `EmployeeAvailabilityPolicy` and `BookableSlotPolicy` (slot grid shared by availability and customer booking) are the shared exceptions. `ArchitectureTest` (Konsist) enforces this: no `*Service` imports from another module (except `security`/`config`) and no repositories in controllers.
- **Company scoping:** staff session principals carry exactly one `employmentId`, `companyId`, and role (`ROLE_OWNER`/`ROLE_EMPLOYEE`). Every staff operation must verify the target belongs to `userDetails.companyId`. Customer sessions carry only `ROLE_CUSTOMER`.
- **Sessions:** REST login calls `SessionAuthenticationService` after credentials/OTP succeed and the database transaction commits. Run fixation/CSRF strategies and explicitly save the `SecurityContext`. Keep `CustomUserDetails` immutable and scalar-only: no entities, passwords, or session IDs. Use Spring Session's indexed Redis repository, never a second custom store ([ADR-0011](./docs/adr/0011-spring-session-redis.md)).
- **Immediate revocation:** `SessionAuthorizationFilter` reloads the user and exact employment before authorization; changed user ID, phone, employment ownership, company or role invalidates the session. Never silently update its scope. Authenticate only anonymous browsers; log out before switching employment.
- **CSRF/cookies:** require CSRF on all unsafe requests, including public login/OTP and logout. SPA clients fetch `/api/auth/csrf`, send `X-XSRF-TOKEN` with cookies, and refresh after login/logout. `SESSION` is HttpOnly, host-only, Path=/, SameSite=Lax, Secure in prod. CORS must use explicit origins with credentials.
- **Tests:** feature fixtures use real Redis sessions (`TestSessions`) and explicit `csrf()` request processors; session boundary tests use actual cookies/CSRF/login. Never globally bypass CSRF.
- **Client IP:** use `request.remoteAddr` for rate limiting; never parse `X-Forwarded-For` by hand (Tomcat applies it only for `TRUSTED_PROXIES`).
- **Double booking:** lock the company row before the company employee row for booking, blocks and policy changes. PostgreSQL exclusions protect both employee and company/customer occupied intervals; completed visits retain the full interval. `@Version` protects edits, not allocation.
- **Per-company employee time:** reservations, schedule blocks, work schedules, and offering assignments are scoped to (company, employee). Never query them by `employeeId` alone; a person employed by two salons has an independent calendar at each.
- **Reservation status:** `BOOKED` is automatic booking. There is no PENDING/CONFIRMED workflow. BOOKED and COMPLETED retain their occupied intervals.
- **Time:** API date-times are Europe/Warsaw local time without offset. Get "now" from the injected `Clock` bean (`LocalDateTime.now(clock)`); never call `now()` without it or hardcode `ZoneId.of(...)`.
- **Errors:** throw `ApiException(ErrorCode.X, "<Polish detail>")` for business errors; responses are ProblemDetail with a stable `code`. Another company's resource is 404, not 403/409. Add new codes to `ErrorCode` and `docs/api/errors.md`.
- **Money:** prices are `Int` whole złoty. No grosze, no decimals.
- **Side effects:** persist notification/OTP intent before commit; claim delivery in a committed transaction before calling the provider after commit. Classify ACCEPTED, DEFINITE_TEMPORARY_FAILURE and AMBIGUOUS. Never blindly retry ambiguous sends; provider failures cannot roll back booking. A sent flag is not sufficient.
- **Logging:** never log OTP codes, passwords, tokens, or raw constraint-violation messages (they contain row values). `ConsoleSmsSender` logs OTPs and is therefore disabled in `prod`. Log lines carry the MDC `requestId` from `RequestIdFilter`; prod logs are ECS JSON.
- **Deployment:** single instance; scheduled jobs have no distributed lock.

## Development Checklist

When adding or modifying backend functionality, always:

1. **Tests** — add unit tests (`*ServiceTest`) and integration tests (`*IntegrationTest`) covering the happy path and relevant error cases.
2. **Flyway migration** — any schema change requires a new versioned migration file in `src/main/resources/db/migration/` (e.g. `V2__description.sql`). Never modify existing migration files.
3. **Validation** — DTOs must have Jakarta Validation annotations (`@NotBlank`, `@Min`, `@Max`, `@PositiveOrZero`, `@Future`, etc.) on all fields that have constraints. Prefer annotation-based validation on DTOs over manual checks in service methods — services should only validate cross-field or cross-entity business rules (e.g. overlap checks).
4. **KDocs** — all public classes, functions, and non-trivial properties must have a KDoc comment (`/** ... */`).
5. **Docs** — update the matching file in `docs/` (and `docs/api/endpoints.md` for any mapping or `@PreAuthorize` change). If the change implements or alters a recorded decision, update the ADR status or add a new ADR.

## Guardrails

- Prefer minimal, focused diffs; avoid over-engineering.
- Do not commit unless explicitly asked.
- Match naming, types, and patterns in surrounding code.
- Never modify existing Flyway migration files.
- Reference existing files with `@` instead of copying large code blocks.
- Do not build against **Proposed** ADRs without the user's agreement.

## Revised contract invariants

Customer accounts are global phone-only; staff credentials and presentation are company-owned. CUSTOMER sessions expire absolutely after 24h; STAFF sessions expire after 24h inactivity. Booking submissions require permanent account-scoped Idempotency-Key binding, including failed validation, and lifecycle/edit writes require revision preconditions. Company then employment locking serializes booking and policy mutations; database exclusions protect both employee and company-local customer overlaps. All actual appointment mutations retain durable audit and versions. No permanent appointment deletion. Notifications use durable provider-result state and after-commit delivery. Tests run with JVM UTC while business time remains Warsaw. The pre-production schema was consolidated into V1 with explicit user authorization; future applied migrations remain immutable.
