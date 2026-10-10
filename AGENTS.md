# AGENTS.md

Instructions for AI coding agents working in this repository. Detailed documentation lives in [`docs/`](./docs/README.md); this file holds commands, invariants, and guardrails.

## Commands

```bash
./gradlew test # All tests (requires Docker for Testcontainers)
./gradlew build # Build including tests
./gradlew build -x test # Build without tests
docker compose up --build # Full stack (PostgreSQL + Redis + app)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build # Dev profile + debugger on :5005
```

Run a single test class:

```bash
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest"
```

Run a single test method:

```bash
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest.full customer registration and login via SMS should return token"
```

Before finishing a task, run `./gradlew test` (or a relevant subset).

## Local Dev Checklist

```bash
docker compose up --build # or: docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
curl -sf http://localhost:8080/actuator/health
```

Dev accounts: owner `gabinet@kacosmetology.pl` / `admin123`, employee `pracownik@kacosmetology.pl` / `employee123`. Seed data has no work schedules, so availability is empty until one is set. Details: [docs/development.md](./docs/development.md).

JDK 25 is pinned in `mise.toml` (`mise install` once; mise switches to it inside the repo). Testcontainers and `docker compose` use rootless Podman via `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock`, exported in `~/.zshrc`; non-interactive shells need `zsh -ic '...'` to pick up both. Distrobox is no longer required.

## Project Overview

Beauty/barber salon scheduling backend: **Kotlin**, **Spring Boot 4** (Web MVC, Security, Data JPA, Validation, Actuator), **PostgreSQL** + **Flyway**, **Redis** (OTP, rate limiting, cache), **JWT**, **Cloudflare R2** for images, **Testcontainers** + **MockK**/**SpringMockK** for tests.

Customers authenticate via SMS OTP; staff via email/password. Frontends: `../scheduler-dashboard-start` (TanStack Start, public booking + studio, recommended) and `../scheduler-dashboard` (legacy React admin).

```
src/main/kotlin/pl/kacosmetology/scheduler/
├── auth/ # SMS OTP + staff login → JWT issuance
├── availability/ # Available slot calculation (public), shared EmployeeAvailabilityPolicy
├── company/ # Company entity, settings, public booking profile
├── config/ # Security, CORS, Redis, R2, RestExceptionHandler, DataInitializer (dev seed)
├── employeeoffering/ # Which offerings an employee performs
├── notification/ # SMS notifications, hourly reminder and auto-complete jobs
├── offering/ # Offering catalog, categories, images
├── reservation/ # Booking lifecycle
├── scheduleblock/ # Employee time blocks (breaks, unavailability)
├── security/ # JWT filter, SessionStore, CustomUserDetails, CustomUserDetailsService
├── user/ # Profiles, customers (block/unblock, notes), employee photos
└── workschedule/ # Employee weekly work schedules
```

## Where to Read Before Changing Code

| If you touch... | Read |
| --------------- | ---- |
| Booking, cancellation, status changes, pricing | [docs/reservations.md](./docs/reservations.md), [ADR-0001](./docs/adr/0001-double-booking-prevention.md), [ADR-0002](./docs/adr/0002-reservation-lifecycle-and-cancellation.md) |
| Availability, work schedules, blocks, assignments, company settings | [docs/availability.md](./docs/availability.md) |
| Dates, times, "now", scheduled jobs | [docs/time-handling.md](./docs/time-handling.md) |
| Auth, security, notifications, caching, R2, schema | [docs/architecture.md](./docs/architecture.md) |
| Controllers, status codes, error bodies | [docs/api/endpoints.md](./docs/api/endpoints.md), [docs/api/errors.md](./docs/api/errors.md) |
| Config, seed data, test setup | [docs/development.md](./docs/development.md) |

## Invariants

- **Layering:** Controller → Service → Repository. No cross-module service calls; modules communicate through IDs. `EmployeeAvailabilityPolicy` is the shared exception.
- **Company scoping:** staff JWTs carry exactly one `employmentId`, `companyId`, and role (`ROLE_OWNER`/`ROLE_EMPLOYEE`). Every staff operation must verify the target belongs to `userDetails.companyId`. Customer JWTs carry only `ROLE_CUSTOMER`.
- **Sessions:** issue tokens only through `JwtService`, which registers the `jti` in `SessionStore` (Redis). A token whose session is gone is rejected, so logout and `logout-all` take effect immediately ([ADR-0009](./docs/adr/0009-revocable-sessions.md)).
- **Client IP:** use `request.remoteAddr` for rate limiting; never parse `X-Forwarded-For` by hand (Tomcat applies it only for `TRUSTED_PROXIES`).
- **Double booking:** any code that creates reservations or schedule blocks must take the `company_employees` row lock (`findByCompanyIdAndUserIdForUpdate`) before checking overlap. `@Version` does not prevent double booking.
- **Reservation status:** `PENDING` means booked; `CONFIRMED` is unused. Active = not `CANCELLED`/`NO_SHOW`.
- **Time:** API date-times are Europe/Warsaw local time without offset. Get "now" from the injected `Clock` bean (`LocalDateTime.now(clock)`); never call `now()` without it or hardcode `ZoneId.of(...)`.
- **Errors:** throw `ApiException(ErrorCode.X, "<Polish detail>")` for business errors; responses are ProblemDetail with a stable `code`. Another company's resource is 404, not 403/409. Add new codes to `ErrorCode` and `docs/api/errors.md`.
- **Money:** prices are `Int` whole złoty. No grosze, no decimals.
- **Side effects:** SMS is sent after commit via `@TransactionalEventListener`; failures are logged and never propagate.
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
