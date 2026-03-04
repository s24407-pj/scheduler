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
├── employeeoffering/ # Employee–offering assignments (which offerings an employee performs)
├── reservation/    # Booking lifecycle (PENDING → CONFIRMED → COMPLETED/CANCELLED/NO_SHOW)
├── scheduleblock/  # Employee time blocks (breaks, unavailability)
├── security/       # JWT filter, CustomUserDetails, CustomUserDetailsService
├── notification/   # SMS notifications (booking confirmation, cancellation, reminders)
├── offering/       # Offering catalog (Offering entity) and offering categories
├── user/           # User profile management; CustomerService/CustomerController for block/unblock
├── whatsapp/       # WhatsApp booking bot (webhook, conversation state machine, Meta sender)
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

**Employee offering assignments:** Owners assign which offerings each employee can perform via `POST /api/employees/{id}/offerings/{offeringId}`. If an employee has any assignments configured, `AvailabilityService` and `ReservationService` reject requests for unassigned offerings. Employees with no assignments at all can perform any offering (backward-compatible default).

**Schedule blocks:** Employees can block time ranges (breaks, personal unavailability) via `POST /api/schedule-blocks`. Blocks are validated with `@Future` on `startTime` (DTO layer) and checked for overlap with existing reservations and other blocks (service layer). Blocked slots are excluded from availability.

**Offering categories:** Owners can group offerings into categories via `POST /api/offering-categories`. Categories are company-scoped; offerings carry an optional `category_id` (set to null on category deletion).

**Staff booking:** Staff can create a reservation on behalf of a client via `POST /api/reservations/staff`. If the client's phone number is not found in the database, a new `User` is auto-created — `firstName` and `lastName` are required in that case.

**Company settings:** Owners can update business hours and slot interval via `PUT /api/company/settings`. `closingTime` must be strictly after `openingTime`.

**SMS notifications:** `NotificationService` sends booking confirmation and cancellation SMS after `ReservationService` saves. `NotificationScheduler` runs hourly (`0 0 * * * *`) and sends reminders for reservations starting in 23–25 h (`reminder_sent` flag on `Reservation` prevents duplicates). SMS failures are logged but never propagate — they are side-effects of the main transaction. `@EnableScheduling` is on `RedisConfig`.

**Offering images:** Owners can upload up to 5 images per offering via `POST /api/offerings/{id}/image` (multipart field `image`; max 5 MB; JPEG/PNG/WebP). Images are stored in Cloudflare R2 (S3-compatible). Delete a single image via `DELETE /api/offerings/{id}/image/{imageId}`. `OfferingController` returns `OfferingResponse` (DTO wrapping entity fields + `images` list). `OfferingImageService` handles all R2 operations. R2 credentials are configured via `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`, `R2_PUBLIC_URL`. In tests, `S3Client` is replaced with `@MockkBean`.

**Employee photos:** Owners can upload a single profile photo per employee via `POST /api/employees/{id}/photo` (multipart field `photo`; max 5 MB; JPEG/PNG/WebP). Uploading replaces any existing photo (old R2 object deleted). Delete via `DELETE /api/employees/{id}/photo`. Photo URL is stored as `photo_url` on the `users` table (in V1 schema). `EmployeePhotoService` handles R2 operations. `EmployeeController` at `/api/employees` serves both endpoints (OWNER only). `photoUrl` is included in `UserProfileResponse` and `CompanyEmployeeResponse`.

**WhatsApp booking bot:** Clients can book via WhatsApp without logging in — Meta verifies the phone number. `WhatsAppWebhookController` at `GET/POST /api/whatsapp/webhook` (public, no auth) handles Meta webhook subscription and message ingestion. `ConversationHandler` is a Redis-backed state machine (`ConversationStore`, key `whatsapp:conv:<phone>`, TTL 30 min) that guides clients through service → employee → date → time → confirmation. New clients go through a name-collection sub-flow (`AWAITING_FIRST_NAME` → `AWAITING_LAST_NAME`); existing clients (looked up by phone) skip it. Reservation is created via `ReservationService.createReservationByStaff()`. Sending is abstracted behind `WhatsAppSender`: `ConsoleWhatsAppSender` (dev default, `whatsapp.sender=console`) logs to SLF4J; `MetaWhatsAppSender` (`whatsapp.sender=meta`) calls the Meta Graph API v21.0 using `RestClient`. `WhatsAppProperties` holds `verifyToken`, `accessToken`, `phoneNumberId`, `companyId`, `sender`. Errors are logged but never propagated (200 always returned to Meta). Phone numbers are normalised to E.164 (`+` prepended if missing).

**No-show tracking:** Staff can mark a reservation as `NO_SHOW` via `PATCH /api/reservations/{id}/no-show` (OWNER or EMPLOYEE). This increments the customer's `no_show_count` in `company_customer_blocks` and auto-blocks the customer (`blocked = true`) at that company when the count reaches `companies.max_no_shows` (configured via `PUT /api/company/settings`; `maxNoShows = 0` disables auto-block). Block state is **company-scoped** — a block at Company A does not affect bookings at Company B. A blocked customer who calls `POST /api/reservations` for an offering belonging to the blocking company receives HTTP 400. Owners can manually block/unblock any customer via `PATCH /api/customers/{id}/block` and `PATCH /api/customers/{id}/unblock` (OWNER only, HTTP 204); unblocking resets `noShowCount` to 0. Customer status (name, noShowCount, blocked) is readable by staff at `GET /api/customers/{id}` (requires OWNER or EMPLOYEE; returns company-scoped values). The `blocked` guard runs in `ReservationService.createReservation()` after loading the offering (so `offering.companyId` is available), using `CompanyCustomerBlockRepository.findByCompanyIdAndCustomerId()`.

**JWT role claim:** `JwtService.generateToken()` includes a `role` claim (lowercase: `owner`, `employee`, `customer`) so the frontend can distinguish OWNER from EMPLOYEE without an extra API call.

### Database Schema

PostgreSQL with Flyway migrations in `src/main/resources/db/migration/`. Key tables (after V4):
- `users` — unified table for customers and staff (distinguished by `company_employees` membership); has `photo_url` column
- `company_customer_blocks` — company-scoped block/no-show state per customer; columns: `company_id`, `customer_id`, `no_show_count`, `blocked`; UNIQUE `(company_id, customer_id)`
- `company_employees` — join table assigning users to a company with a role (`OWNER`/`EMPLOYEE`)
- `offerings` — offering catalog (`Offering` entity); has optional `category_id`
- `offering_categories` — company-scoped groupings for offerings
- `offering_images` — up to 5 images per offering, references `offerings(id)` ON DELETE CASCADE; column `offering_id`
- `companies` — has `max_no_shows` column (auto-block threshold)
- `reservations` — stores price snapshot at booking time, has `@Version` for optimistic locking, `reminder_sent` flag for deduplication; status enum includes `NO_SHOW`
- `schedule_blocks` — employee time blocks; checked by `AvailabilityService` alongside reservations
- `employee_work_schedules` — per-employee, per-day-of-week working hours
- `employee_offerings` — which offerings each employee is allowed to perform; column `offering_id`

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
- `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_COMPANY_ID`, `WHATSAPP_SENDER`

Dev profile (`application-dev.yaml`) enables SQL logging and DEBUG-level logging for the app and Spring Security.
