# AGENTS.md

Instructions for AI coding agents working in this repository.

## Commands

```bash
./gradlew test                              # All tests (requires Docker for Testcontainers)
./gradlew build                             # Build including tests
./gradlew build -x test                     # Build without tests
docker compose up --build                   # Full stack (PostgreSQL + Redis + app)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build  # Dev profile + debugger on :5005
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
docker compose up --build                    # or: docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
curl -sf http://localhost:8080/actuator/health
```

Dev accounts: owner `gabinet@kacosmetology.pl` / `admin123`, employee `pracownik@kacosmetology.pl` / `employee123`.

In distrobox, `DOCKER_HOST` is set via `~/.bashrc.d/distrobox-podman.sh`. For non-interactive shells use `bash -lc 'docker compose ...'`.

## Project Overview

A beauty/barber salon scheduling system with a Spring Boot backend. Customers authenticate via SMS OTP; staff authenticate via email/password. Both flows issue JWT tokens.

**Related project:** `~/projects/scheduler-project/scheduler-dashboard` — a separate React admin dashboard for owners and employees (see its own AGENTS.md).

## Tech Stack

- **Kotlin** + **Spring Boot 4** (Web MVC, Security, Data JPA, Validation, Actuator)
- **PostgreSQL** with **Flyway** migrations (`src/main/resources/db/migration/`)
- **Redis** — OTP storage, rate limiting, WhatsApp conversation state
- **JWT** auth (customers via SMS OTP, staff via email/password)
- **Cloudflare R2** (S3-compatible) for offering images and employee photos
- **Testcontainers** (PostgreSQL + Redis) + **MockK** / **SpringMockK** for tests

## Directory Layout

```
src/main/kotlin/pl/kacosmetology/scheduler/
├── auth/             # SMS OTP + staff login → JWT issuance
├── availability/     # Available time slot calculation (public endpoint)
├── company/          # Company entity, settings CRUD (hours, slot interval)
├── config/           # Security, CORS, Redis, DataInitializer (dev seed)
├── employeeoffering/ # Employee–offering assignments (which offerings an employee performs)
├── reservation/      # Booking lifecycle (PENDING → CONFIRMED → COMPLETED/CANCELLED/NO_SHOW)
├── scheduleblock/    # Employee time blocks (breaks, unavailability)
├── security/         # JWT filter, CustomUserDetails, CustomUserDetailsService
├── notification/     # SMS notifications (booking confirmation, cancellation, reminders)
├── offering/         # Offering catalog (Offering entity) and offering categories
├── user/             # User profile management; CustomerService/CustomerController for block/unblock
├── whatsapp/         # WhatsApp booking bot (webhook, conversation state machine, Meta sender)
└── workschedule/     # Employee weekly work schedules (per-day hours)
```

**Layering:** Controller → Service → Repository. No cross-module service calls; modules communicate through IDs only.

## Key Design Decisions

**Authentication:** Two flows issue role-specific JWTs:
- Customers: `POST /api/auth/request-code` (OTP via SMS) → `POST /api/auth/verify-code` → an unscoped customer JWT (no company or employment claims).
- Staff: `POST /api/auth/login-staff` accepts email, password, and optional `employmentId`. A user with one employment receives a JWT immediately. A user with multiple employments and no selection receives `EMPLOYMENT_SELECTION_REQUIRED` plus `employments` entries containing `employmentId`, `companyId`, `companyName`, and `role`; repeat the request with the selected `employmentId`.

**Authorization roles:** `CUSTOMER`, `EMPLOYEE`, `OWNER` stored in `company_employees`. Each staff JWT and `CustomUserDetails` is scoped to exactly one `employmentId`, its `companyId`, and one role (`ROLE_OWNER` or `ROLE_EMPLOYEE`). Customer JWTs carry only `ROLE_CUSTOMER` and remain unscoped. Method-level security uses `@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")`.

**OTP/Rate limiting:** `OtpStore` uses Redis keys `otp:<phone>` (TTL from config) and `rate:sms:<phone>` (request counter). A Lua script atomically verifies and consumes a successful OTP, preserves the code TTL after failures, and limits failed attempts per code via `OTP_VERIFICATION_MAX_ATTEMPTS`. `OtpVerificationRateLimiter` separately limits verification requests per client IP via `OTP_VERIFICATION_IP_MAX_ATTEMPTS` and `OTP_VERIFICATION_IP_RATE_WINDOW_MINUTES`. `SmsSender` has two methods: `sendOtp` (OTP flow) and `sendMessage` (general notifications). `ConsoleSmsSender` is the dev stub for both.

**Login rate limiting:** `LoginRateLimiter` uses Redis key `rate:login:<ip>` to limit staff login attempts per client IP. Default: 10 attempts per 1-minute window. Configured via `LOGIN_MAX_ATTEMPTS` / `LOGIN_RATE_WINDOW_MINUTES`. The controller extracts IP from `X-Forwarded-For` (first value) or falls back to `remoteAddr`. Exceeding the limit throws `RateLimitExceededException` → HTTP 429.

**Optimistic locking:** `Reservation` has a `@Version` field to prevent double-booking race conditions.

**Availability calculation:** `AvailabilityService` computes free slots using the employee's work schedule for opening/closing hours (falls back to empty list if no schedule entry for that day). The employee must belong to the same company as the offering. Slot interval comes from the company config. Filters out slots overlapping with existing reservations and schedule blocks. Returns `List<AvailableSlotResponse>` — each slot has `{ time: LocalTime, price: Int, originalPrice: Int }`. If the company has a last-minute discount configured (`lastMinuteDiscountPercent > 0`), slots starting within `lastMinuteDiscountHours` of now receive a discounted `price`; `originalPrice` always holds the catalog price.

**Work schedules:** Owners set a weekly schedule per employee via `PUT /api/employees/{id}/work-schedule`. If an employee has no entry for a given day of week, `AvailabilityService` returns an empty list for that day.

**Employee offering assignments:** Owners assign which offerings each employee can perform via `POST /api/employees/{id}/offerings/{offeringId}`. If an employee has any assignments configured, `AvailabilityService` and `ReservationService` reject requests for unassigned offerings. Employees with no assignments at all can perform any offering within their own company (backward-compatible default).

**Schedule blocks:** Employees can block time ranges (breaks, personal unavailability) via `POST /api/schedule-blocks`. Owners may create a block for another employee by supplying `employeeId` in the request body; employees always create for themselves (JWT identity). The target must be employed by the authenticated company, enforced by service checks and the composite database foreign key `(company_id, employee_id)`. `DELETE /api/schedule-blocks/{id}`: owners can delete any block within their company; employees can only delete their own. `ScheduleBlockService.deleteBlock()` signature: `(blockId, requesterId, isOwner, companyId)`. Blocks are validated with `@Future` on `startTime`, checked against reservations in the service, and prevented from overlapping other blocks for the same company and employee in both service and database layers. Blocked slots are excluded from availability.

**Offering categories:** Owners can group offerings into categories via `POST /api/offering-categories`. Categories are company-scoped; offerings carry an optional `category_id` (set to null on category deletion).

**Dashboard reservation listing:** Owners and employees can fetch reservations filtered by employee and date range via `GET /api/reservations?employeeId={id}&start={iso}&end={iso}`. Returns `DashboardReservationResponse` (includes both `customerId` and `employeeId`). Authorization: EMPLOYEE may only query their own `employeeId`; OWNER may query any employee within their company. Used by `scheduler-dashboard` CalendarPage and ReservationsPage.

**Staff booking:** Staff can create a reservation on behalf of a client via `POST /api/reservations/staff` only for offerings and employees belonging to the authenticated staff member's company. If the client's phone number is not found in the database, a new `User` is auto-created — `firstName` and `lastName` are required in that case. Note: staff booking bypasses availability/slot boundary validation (intentional — staff can override business hours). Customer bookings via `POST /api/reservations` currently also skip this validation (known gap).

**Company isolation:** `completeReservation` and `markNoShow` both verify `reservation.companyId == userDetails.companyId` before proceeding (throws `IllegalStateException` → 409 if mismatch). Offering mutations check company ownership in the service layer. `OfferingCategoryService.deleteCategory()` and `assignCategory()` both carry `@CacheEvict("companyServices")` to invalidate the offering cache.

**Company settings:** Owners can update business hours, slot interval, and last-minute discount via `PUT /api/company/settings`. `closingTime` must be strictly after `openingTime`. `lastMinuteDiscountPercent` (0–100; 0 disables) and `lastMinuteDiscountHours` (1–168) control the discount window. Both fields default to 0 / 24 and are returned by `GET /api/company/settings`. `minBookingAdvanceMinutes` (0–10080; 0 disables) sets the minimum number of minutes in advance a customer must book — slots within this window are hidden from `AvailabilityService` and rejected by `ReservationService.createReservation()`. Staff bookings (`createReservationByStaff`) bypass this check via `enforceAdvanceCheck = false`.

**SMS notifications:** `NotificationService` sends booking confirmation and cancellation SMS after `ReservationService` saves. `NotificationScheduler` runs hourly (`0 0 * * * *`) and sends reminders for reservations starting in 23–25 h (`reminder_sent` flag on `Reservation` prevents duplicates). SMS failures are logged but never propagate — they are side-effects of the main transaction. `@EnableScheduling` is on `RedisConfig`.

**Offering images:** Owners can upload up to 5 images per offering via `POST /api/offerings/{id}/image` (multipart field `image`; max 5 MB; JPEG/PNG/WebP). Images are stored in Cloudflare R2 (S3-compatible). Delete a single image via `DELETE /api/offerings/{id}/image/{imageId}`. `OfferingController` returns `OfferingResponse` (DTO wrapping entity fields + `images` list). `OfferingImageService` handles all R2 operations. R2 credentials are configured via `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`, `R2_PUBLIC_URL`. In tests, `S3Client` is replaced with `@MockkBean`.

**Employee photos:** Owners can upload a single profile photo per employee via `POST /api/employees/{id}/photo` (multipart field `photo`; max 5 MB; JPEG/PNG/WebP). Uploading replaces any existing photo (old R2 object deleted). Delete via `DELETE /api/employees/{id}/photo`. Photo URL is stored as `photo_url` on the `users` table (in V1 schema). `EmployeePhotoService` handles R2 operations. `EmployeeController` at `/api/employees` serves both endpoints (OWNER only). `photoUrl` is included in `UserProfileResponse` and `CompanyEmployeeResponse`.

**WhatsApp booking bot:** Clients can book via WhatsApp without logging in — Meta verifies the phone number. `WhatsAppWebhookController` at `GET/POST /api/whatsapp/webhook` (public, no auth) handles Meta webhook subscription and message ingestion. `ConversationHandler` is a Redis-backed state machine (`ConversationStore`, key `whatsapp:conv:<phone>`, TTL 30 min) that guides clients through service → employee → date → time → confirmation. New clients go through a name-collection sub-flow (`AWAITING_FIRST_NAME` → `AWAITING_LAST_NAME`); existing clients (looked up by phone) skip it. Reservation is created via `ReservationService.createReservationByStaff()`. Sending is abstracted behind `WhatsAppSender`: `ConsoleWhatsAppSender` (dev default, `whatsapp.sender=console`) logs to SLF4J; `MetaWhatsAppSender` (`whatsapp.sender=meta`) calls the Meta Graph API v21.0 using `RestClient`. `WhatsAppProperties` holds `verifyToken`, `accessToken`, `phoneNumberId`, `companyId`, `sender`. Errors are logged but never propagated (200 always returned to Meta). Phone numbers are normalised to E.164 (`+` prepended if missing).

**No-show tracking:** Staff can mark a reservation as `NO_SHOW` via `PATCH /api/reservations/{id}/no-show` (OWNER or EMPLOYEE). This increments the customer's `no_show_count` in `company_customer_blocks` and auto-blocks the customer (`blocked = true`) at that company when the count reaches `companies.max_no_shows` (configured via `PUT /api/company/settings`; `maxNoShows = 0` disables auto-block). Block state is **company-scoped** — a block at Company A does not affect bookings at Company B. A blocked customer who calls `POST /api/reservations` for an offering belonging to the blocking company receives HTTP 400. Owners can manually block/unblock any customer via `PATCH /api/customers/{id}/block` and `PATCH /api/customers/{id}/unblock` (OWNER only, HTTP 204); unblocking resets `noShowCount` to 0. Customer status (name, noShowCount, blocked) is readable by staff at `GET /api/customers/{id}` (requires OWNER or EMPLOYEE; returns company-scoped values). The `blocked` guard runs in `ReservationService.createReservation()` after loading the offering (so `offering.companyId` is available), using `CompanyCustomerBlockRepository.findByCompanyIdAndCustomerId()`.

**JWT claims:** `JwtService.generateCustomerToken()` includes only the lowercase `customer` role. `JwtService.generateStaffToken()` includes the selected `employmentId`, its `companyId`, and one lowercase `owner` or `employee` role.

**Dev seed (`dev` profile):** `DataInitializer` creates owner `gabinet@kacosmetology.pl` / `admin123` and employee `pracownik@kacosmetology.pl` / `employee123`.

## Database Schema

PostgreSQL with a consolidated pre-production baseline in `src/main/resources/db/migration/V1__init_schema.sql`. Key tables:
- `users` — unified table for customers and staff (distinguished by `company_employees` membership); has `photo_url` column
- `company_customer_blocks` — company-scoped block/no-show state per customer; columns: `company_id`, `customer_id`, `no_show_count`, `blocked`; UNIQUE `(company_id, customer_id)`
- `company_employees` — join table assigning users to a company with a role (`OWNER`/`EMPLOYEE`)
- `offerings` — offering catalog (`Offering` entity); has optional `category_id`
- `offering_categories` — company-scoped groupings for offerings
- `offering_images` — up to 5 images per offering, references `offerings(id)` ON DELETE CASCADE; column `offering_id`
- `companies` — has `max_no_shows` (auto-block threshold), `last_minute_discount_percent`, `last_minute_discount_hours`, and `min_booking_advance_minutes`
- `reservations` — stores price snapshot at booking time, has `@Version` for optimistic locking, `reminder_sent` flag for deduplication; status enum includes `NO_SHOW`
- `schedule_blocks` — company-scoped employee time blocks with composite employment membership and overlap constraints; checked by `AvailabilityService` alongside reservations
- `employee_work_schedules` — per-employee, per-day-of-week working hours
- `employee_offerings` — which offerings each employee is allowed to perform; column `offering_id`

## Testing

Integration tests use **Testcontainers** (PostgreSQL + Redis containers spin up automatically). `TestcontainersConfiguration` is imported via `@Import` in each integration test.

Unit tests use **MockK** and **SpringMockK** (`@MockkBean`). Test naming uses backtick strings in English (or Polish) describing the scenario.

**Every new backend feature must be covered by tests** — both a unit test for the service layer and an integration test for the HTTP endpoint.

## Development Checklist

When adding or modifying backend functionality, always:

1. **Tests** — add unit tests (`*ServiceTest`) and integration tests (`*IntegrationTest`) covering the happy path and relevant error cases.
2. **Flyway migration** — any schema change requires a new versioned migration file in `src/main/resources/db/migration/` (e.g. `V2__description.sql`). Never modify existing migration files.
3. **Validation** — DTOs must have Jakarta Validation annotations (`@NotBlank`, `@Min`, `@Max`, `@PositiveOrZero`, `@Future`, etc.) on all fields that have constraints. Prefer annotation-based validation on DTOs over manual checks in service methods — services should only validate cross-field or cross-entity business rules (e.g. overlap checks).
4. **KDocs** — all public classes, functions, and non-trivial properties must have a KDoc comment (`/** ... */`).

## Configuration

Environment variables override application YAML values. Key vars for docker-compose:
- `JWT_SECRET`, `JWT_EXPIRATION_MS`
- `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`
- `SPRING_DATA_REDIS_HOST/PORT`
- `CORS_ORIGINS`
- `OTP_TTL_MINUTES`, `OTP_MAX_ATTEMPTS`, `OTP_RATE_WINDOW_MINUTES`
- `OTP_VERIFICATION_MAX_ATTEMPTS`, `OTP_VERIFICATION_IP_MAX_ATTEMPTS`, `OTP_VERIFICATION_IP_RATE_WINDOW_MINUTES`
- `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES`
- `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`, `R2_PUBLIC_URL`
- `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_COMPANY_ID`, `WHATSAPP_SENDER`

Dev profile (`application-dev.yaml`) enables SQL logging and DEBUG-level logging for the app and Spring Security.

## Guardrails

- Prefer minimal, focused diffs; avoid over-engineering.
- Do not commit unless explicitly asked.
- Match naming, types, and patterns in surrounding code.
- Never modify existing Flyway migration files.
- Reference existing files with `@` instead of copying large code blocks.
