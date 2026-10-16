# ADR-0003: Single business time zone (Europe/Warsaw)

**Status:** Superseded by [ADR-0016](./0016-reservation-contract-and-durable-submissions.md). Historical decision retained below.

## Context

All salons operate in Poland. The zone `Europe/Warsaw` used to be applied in several independent places: hardcoded `ZoneId.of("Europe/Warsaw")` calls in `ReservationService`, `AvailabilityService`, `Company.effectivePrice`, and `NotificationScheduler`, plus `hibernate.jdbc.time_zone` and `TZ` in `docker-compose.yml`.

Other code depended on the JVM default zone instead: Jakarta `@Future` validation on request DTOs, and `@Scheduled` cron jobs. On a developer machine in another zone, or in CI, these behaved differently than in Docker.

Database columns are `TIMESTAMP WITH TIME ZONE`; entities and API payloads use `LocalDateTime`.

## Decision

1. **API times are local wall-clock times in Europe/Warsaw, without an offset**, for example `2026-10-12T14:30:00`. Dates are `YYYY-MM-DD`, times `HH:mm[:ss]`. Clients display them as-is.
2. **Storage is an instant.** Hibernate converts `LocalDateTime` to `TIMESTAMPTZ` using Europe/Warsaw, so the database holds correct UTC instants.
3. **One source of truth for the zone.** `config/TimeConfig.kt` defines `BUSINESS_ZONE` and a `java.time.Clock` bean in that zone. Code that needs "now" injects the `Clock` and calls `LocalDateTime.now(clock)`; `ZoneId.of(...)` and argument-less `now()` are not used in business logic. `SchedulerApplication.main` sets the JVM default zone to `BUSINESS_ZONE`, the Gradle test task sets `user.timezone`, and both cron jobs declare `zone = BUSINESS_ZONE_ID`, so `@Future` and schedules agree in every environment.
4. **Daylight saving.**
   - Business hours are assumed never to include 02:00–03:00 local time, so the ambiguous and skipped hours on transition days are otherwise out of scope.
   - Booking requests for a local time that does not exist (the skipped hour in March) are rejected with 400 `INVALID_LOCAL_TIME`.
   - Durations are added in wall-clock time (`startTime.plusMinutes(duration)`).
5. **Recurring availability** is a weekly schedule per `DayOfWeek` (`employee_work_schedules`). Date-specific exceptions are expressed only as schedule blocks.

## Alternatives considered

- **ISO-8601 with offsets in the API (`2026-10-12T14:30:00+02:00`).** More explicit, but every client must handle offsets and every salon is in one zone. Rejected for now as cost without benefit.
- **Per-company time zone.** Needed only if salons outside Poland are onboarded. Migration path: add `companies.time_zone`, resolve the zone per company instead of the `Clock` default, and keep the API in company-local time.

## Consequences

- No client changes. Behaviour is identical across Docker, local runs, and CI.
- Time-dependent logic (minimum advance, last-minute discount, reminders, auto-complete, slot filtering) is unit-testable with `Clock.fixed(...)`.
