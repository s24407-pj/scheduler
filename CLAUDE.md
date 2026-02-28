# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A beauty/barber salon scheduling system with a Spring Boot backend and React frontend. Customers authenticate via SMS OTP; staff authenticate via email/password. Both flows issue JWT tokens.

## Commands

### Backend (Kotlin/Spring Boot)

```bash
# Run all tests (requires Docker for Testcontainers)
./gradlew test

# Run a single test class
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest"

# Run a single test method
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest.full customer registration and login via SMS should return token"

# Build (includes tests)
./gradlew build

# Build without tests
./gradlew build -x test
```

### Frontend (React/Vite)

```bash
cd frontend

# Install dependencies (uses pnpm)
pnpm install

# Start dev server (proxies /api to localhost:8080)
pnpm dev

# Build for production
pnpm build

# Lint
pnpm lint
```

### Docker

```bash
# Run full stack (prod-like)
docker compose up --build

# Run with dev profile + remote debugger on port 5005
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
```

## Architecture

### Backend Structure

```
src/main/kotlin/pl/kacosmetology/scheduler/
├── auth/           # SMS OTP + staff login → JWT issuance
├── availability/   # Available time slot calculation (public endpoint)
├── company/        # Company and employee-role entities
├── config/         # Security, CORS, Redis, DataInitializer (dev seed)
├── reservation/    # Booking lifecycle (PENDING → CONFIRMED → COMPLETED/CANCELLED)
├── scheduleblock/  # Employee time blocks (breaks, unavailability)
├── security/       # JWT filter, CustomUserDetails, CustomUserDetailsService
├── treatment/      # Service catalog (ProvidedService entity mapped to `services` table)
└── user/           # User profile management
```

**Layering:** Controller → Service → Repository. No cross-module service calls; modules communicate through IDs only.

### Key Design Decisions

**Authentication:** Two flows merge into a single JWT standard:
- Customers: `POST /api/auth/request-code` (OTP via SMS) → `POST /api/auth/verify-code` → JWT
- Staff: `POST /api/auth/login-staff` (email + password) → JWT

**Authorization roles:** `CUSTOMER`, `EMPLOYEE`, `OWNER` stored in `company_employees`. `CustomUserDetails` carries `companyId` and role as Spring authorities (`ROLE_OWNER`, etc.). Method-level security uses `@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")`.

**OTP/Rate limiting:** `OtpStore` uses Redis with key prefixes `otp:<phone>` (TTL from config) and `rate:sms:<phone>` (sliding window counter). `SmsSender` is a `ConsoleSmsSender` stub in dev.

**Optimistic locking:** `Reservation` has a `@Version` field to prevent double-booking race conditions.

**Availability calculation:** `AvailabilityService` computes free slots by comparing company opening/closing hours and slot intervals against existing reservations **and schedule blocks** for that employee.

**Schedule blocks:** Employees can block time ranges (breaks, personal unavailability) via `POST /api/schedule-blocks`. Blocks are validated with `@Future` on `startTime` (DTO layer) and checked for overlap with existing reservations and other blocks (service layer). Blocked slots are excluded from availability.

**Staff booking:** Staff can create a reservation on behalf of a client via `POST /api/reservations/staff`. If the client's phone number is not found in the database, a new `User` is auto-created — `firstName` and `lastName` are required in that case.

### Database Schema

PostgreSQL with Flyway migrations (`src/main/resources/db/migration/`). Key tables:
- `users` — unified table for customers and staff (distinguished by `company_employees` membership)
- `company_employees` — join table assigning users to a company with a role (`OWNER`/`EMPLOYEE`)
- `services` — treatment catalog (named `ProvidedService` in Kotlin, mapped to `services` table)
- `reservations` — stores price snapshot at booking time, has `@Version` for optimistic locking
- `schedule_blocks` — employee time blocks; checked by `AvailabilityService` alongside reservations (V2 migration)

### Frontend Structure

React 19 + React Router 7 + Tailwind CSS 4 + Axios. Built with Vite, package manager is **pnpm**.

- `src/api.ts` — Axios instance with `/api` base URL; attaches JWT from `localStorage`; redirects to `/login` on 401 (except public endpoints)
- `src/AuthContext.tsx` — React context holding token, user info, login/logout helpers
- `src/pages/` — One file per page route; customer and staff flows are separate pages

### Testing

Integration tests use **Testcontainers** (PostgreSQL + Redis containers spin up automatically). `TestcontainersConfiguration` is imported via `@Import` in each integration test.

Unit tests use **MockK** and **SpringMockK** (`@MockkBean`). Test naming uses backtick strings in English (or Polish) describing the scenario.

**Every new backend feature must be covered by tests** — both a unit test for the service layer and an integration test for the HTTP endpoint.

## Development Checklist

When adding or modifying backend functionality, always:

1. **Tests** — add unit tests (`*ServiceTest`) and integration tests (`*IntegrationTest`) covering the happy path and relevant error cases.
2. **Flyway migration** — any schema change requires a new versioned migration file in `src/main/resources/db/migration/` (e.g. `V2__description.sql`). Never modify existing migration files.
3. **Validation** — DTOs must have Jakarta Validation annotations (`@NotBlank`, `@Min`, `@Max`, `@PositiveOrZero`, `@Future`, etc.) on all fields that have constraints. Prefer annotation-based validation on DTOs over manual checks in service methods — services should only validate cross-field or cross-entity business rules (e.g. overlap checks).
4. **KDocs** — all public classes, functions, and non-trivial properties must have a KDoc comment (`/** ... */`).

### Configuration

Environment variables override application YAML values. Key vars for docker-compose:
- `JWT_SECRET`, `JWT_EXPIRATION_MS`
- `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`
- `SPRING_DATA_REDIS_HOST/PORT`
- `CORS_ORIGINS`
- `OTP_TTL_MINUTES`, `OTP_MAX_ATTEMPTS`, `OTP_RATE_WINDOW_MINUTES`

Dev profile (`application-dev.yaml`) enables SQL logging and DEBUG-level logging for the app and Spring Security.
