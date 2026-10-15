# Time handling

Policy and rationale: [ADR-0003](./adr/0003-single-time-zone.md).

## Rules

- **One business time zone: `Europe/Warsaw`.** All salons are assumed to be in it. The constant is `BUSINESS_ZONE` in `config/TimeConfig.kt`.
- **API date-times reject `Z` and every numeric offset**, both in JSON bodies and query parameters. Strict parsing never discards zone data. **API date-times have no offset** and mean Warsaw wall-clock time: ISO-8601 local format such as `2026-10-12T14:30:00`. Dates are `2026-10-12`. Responses also omit the offset.
- **Storage:** `TIMESTAMP WITH TIME ZONE` columns; entities use `LocalDateTime`. Hibernate converts using `hibernate.jdbc.time_zone: Europe/Warsaw`, so stored instants are correct.
- **"Now"** comes from the injected `java.time.Clock` bean: `LocalDateTime.now(clock)`. Do not call `now()` without a clock or `ZoneId.of(...)` in business code.
- **JVM default zone** is set to `BUSINESS_ZONE` in `SchedulerApplication.main`, and the Gradle test task sets `user.timezone=Europe/Warsaw`, so `@Future` validation behaves the same everywhere. Cron jobs declare `zone = BUSINESS_ZONE_ID`.
- **Durations** are wall-clock: `endTime = startTime.plusMinutes(durationMinutes)`.
- **Weekly schedules** use `DayOfWeek` and `LocalTime`; date-specific exceptions are schedule blocks.

## Daylight saving

- A booking whose `startTime` falls in the skipped hour (last Sunday of March, 02:00–02:59) is rejected with 400 `INVALID_LOCAL_TIME`.
- The repeated hour in October is not distinguished; business hours are assumed never to include 02:00–03:00.

## Testing

- Unit tests mock or fix the `Clock` (`Clock.fixed(instant, BUSINESS_ZONE)`) to make "now" deterministic. See `AvailabilityServiceTest` and `NotificationSchedulerTest`.
- Integration tests use the real clock; compute times relative to `LocalDateTime.now(BUSINESS_ZONE)`.
