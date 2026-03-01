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
├── company/        # Company entity, settings CRUD (hours, slot interval)
├── config/         # Security, CORS, Redis, DataInitializer (dev seed)
├── employeeservice/ # Employee–service assignments (which services an employee performs)
├── reservation/    # Booking lifecycle (PENDING → CONFIRMED → COMPLETED/CANCELLED)
├── scheduleblock/  # Employee time blocks (breaks, unavailability)
├── security/       # JWT filter, CustomUserDetails, CustomUserDetailsService
├── notification/   # SMS notifications (booking confirmation, cancellation, reminders)
├── treatment/      # Service catalog (ProvidedService) and service categories
├── user/           # User profile management
└── workschedule/   # Employee weekly work schedules (per-day hours)
```

**Layering:** Controller → Service → Repository. No cross-module service calls; modules communicate through IDs only.

### Key Design Decisions

**Authentication:** Two flows merge into a single JWT standard:
- Customers: `POST /api/auth/request-code` (OTP via SMS) → `POST /api/auth/verify-code` → JWT
- Staff: `POST /api/auth/login-staff` (email + password) → JWT

**Authorization roles:** `CUSTOMER`, `EMPLOYEE`, `OWNER` stored in `company_employees`. `CustomUserDetails` carries `companyId` and role as Spring authorities (`ROLE_OWNER`, etc.). Method-level security uses `@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")`.

**OTP/Rate limiting:** `OtpStore` uses Redis with key prefixes `otp:<phone>` (TTL from config) and `rate:sms:<phone>` (sliding window counter). `SmsSender` has two methods: `sendOtp` (OTP flow) and `sendMessage` (general notifications). `ConsoleSmsSender` is the dev stub for both.

**Login rate limiting:** `LoginRateLimiter` uses Redis key `rate:login:<ip>` to limit staff login attempts per client IP. Default: 10 attempts per 1-minute window. Configured via `LOGIN_MAX_ATTEMPTS` / `LOGIN_RATE_WINDOW_MINUTES`. The controller extracts IP from `X-Forwarded-For` (first value) or falls back to `remoteAddr`. Exceeding the limit throws `RateLimitExceededException` → HTTP 429.

**Optimistic locking:** `Reservation` has a `@Version` field to prevent double-booking race conditions.

**Availability calculation:** `AvailabilityService` computes free slots using the employee's work schedule for opening/closing hours (falls back to empty list if no schedule entry for that day). Slot interval comes from the company config. Filters out slots overlapping with existing reservations and schedule blocks.

**Work schedules:** Owners set a weekly schedule per employee via `PUT /api/employees/{id}/work-schedule`. If an employee has no entry for a given day of week, `AvailabilityService` returns an empty list for that day.

**Employee service assignments:** Owners assign which services each employee can perform via `POST /api/employees/{id}/services/{serviceId}`. If an employee has any assignments configured, `AvailabilityService` and `ReservationService` reject requests for unassigned services. Employees with no assignments at all can perform any service (backward-compatible default).

**Schedule blocks:** Employees can block time ranges (breaks, personal unavailability) via `POST /api/schedule-blocks`. Blocks are validated with `@Future` on `startTime` (DTO layer) and checked for overlap with existing reservations and other blocks (service layer). Blocked slots are excluded from availability.

**Service categories:** Owners can group services into categories via `POST /api/categories`. Categories are company-scoped; services carry an optional `category_id` (set to null on category deletion).

**Staff booking:** Staff can create a reservation on behalf of a client via `POST /api/reservations/staff`. If the client's phone number is not found in the database, a new `User` is auto-created — `firstName` and `lastName` are required in that case.

**Company settings:** Owners can update business hours and slot interval via `PUT /api/company/settings`. `closingTime` must be strictly after `openingTime`.

**SMS notifications:** `NotificationService` sends booking confirmation and cancellation SMS after `ReservationService` saves. `NotificationScheduler` runs hourly (`0 0 * * * *`) and sends reminders for reservations starting in 23–25 h (`reminder_sent` flag on `Reservation` prevents duplicates). SMS failures are logged but never propagate — they are side-effects of the main transaction. `@EnableScheduling` is on `RedisConfig`.

**Service images:** Owners can upload up to 5 images per service via `POST /api/services/{id}/image` (multipart field `image`; max 5 MB; JPEG/PNG/WebP). Images are stored in Cloudflare R2 (S3-compatible). Delete a single image via `DELETE /api/services/{id}/image/{imageId}`. `TreatmentController` returns `ProvidedServiceResponse` (DTO wrapping entity fields + `images` list). `ImageService` handles all R2 operations. R2 credentials are configured via `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`, `R2_PUBLIC_URL`. In tests, `S3Client` is replaced with `@MockkBean`.

**Employee photos:** Owners can upload a single profile photo per employee via `POST /api/employees/{id}/photo` (multipart field `photo`; max 5 MB; JPEG/PNG/WebP). Uploading replaces any existing photo (old R2 object deleted). Delete via `DELETE /api/employees/{id}/photo`. Photo URL is stored as `photo_url` on the `users` table (V2 migration). `EmployeePhotoService` handles R2 operations. `EmployeeController` at `/api/employees` serves both endpoints (OWNER only). `photoUrl` is included in `UserProfileResponse` and `CompanyEmployeeResponse`.

### Database Schema

PostgreSQL with a single Flyway migration (`src/main/resources/db/migration/V1__init_schema.sql`). Key tables:
- `users` — unified table for customers and staff (distinguished by `company_employees` membership); has `photo_url` column (V2)
- `company_employees` — join table assigning users to a company with a role (`OWNER`/`EMPLOYEE`)
- `services` — treatment catalog (named `ProvidedService` in Kotlin); has optional `category_id`
- `service_categories` — company-scoped groupings for services
- `service_images` — up to 5 images per service, references `services(id)` ON DELETE CASCADE
- `reservations` — stores price snapshot at booking time, has `@Version` for optimistic locking, `reminder_sent` flag for deduplication
- `schedule_blocks` — employee time blocks; checked by `AvailabilityService` alongside reservations
- `employee_work_schedules` — per-employee, per-day-of-week working hours
- `employee_services` — which services each employee is allowed to perform

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
- `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES`
- `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`, `R2_PUBLIC_URL`

Dev profile (`application-dev.yaml`) enables SQL logging and DEBUG-level logging for the app and Spring Security.
