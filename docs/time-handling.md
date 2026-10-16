# Time handling

Policy and rationale: [ADR-0003](./adr/0003-single-time-zone.md).

## Rules

- **One business time zone: `Europe/Warsaw`.** All salons are assumed to be in it. The constant is `BUSINESS_ZONE` in `config/TimeConfig.kt`.
- **API date-times reject `Z` and every numeric offset**, both in JSON bodies and query parameters. Strict parsing never discards zone data. **API date-times have no offset** and mean Warsaw wall-clock time: ISO-8601 local format such as `2026-10-12T14:30:00`. Dates are `2026-10-12`. Responses also omit the offset.
- **Storage:** `TIMESTAMP WITH TIME ZONE` columns; entities use `LocalDateTime`. `BusinessDateTimeConverter` explicitly maps them to/from `OffsetDateTime` in Warsaw. This avoids relying on JVM/JDBC default-zone conversion.
- **"Now"** comes from the injected `java.time.Clock` bean: `LocalDateTime.now(clock)`. Do not call `now()` without a clock or `ZoneId.of(...)` in business code.
- **JVM default zone** is set to `BUSINESS_ZONE` in `SchedulerApplication.main`, while the entire Gradle test task uses `user.timezone=UTC` to verify business-zone independence. Booking temporal validation uses the injected clock after locks, rather than JVM-dependent `@Future`. Cron jobs declare `zone = BUSINESS_ZONE_ID`.
- Appointment end is start plus agreed minutes; unique endpoints and transition-spanning rejection ensure actual elapsed duration agrees. Relative notice/discount/quote/correction deadlines use instants; horizon and annual penalties use Warsaw calendar dates.
- **Weekly schedules** use `DayOfWeek` and `LocalTime`; date-specific replacement shifts/days off override the week. Employee blocks are separate intervals.

## Daylight saving

- A booking whose `startTime` falls in the skipped hour (last Sunday of March, 02:00–02:59) is rejected with 400 `INVALID_LOCAL_TIME`.
- Repeated-hour endpoints in October are rejected, as are appointment intervals spanning a DST transition. Date-specific availability omits these candidates.
- Employee blocks may span a transition over multiple days; their local endpoints must each be unambiguous.
- Notice and shift-minute allocation use elapsed time. Booking horizon uses Warsaw calendar dates, including the whole final date.
- Availability derives business time from `clock.instant()` in `BUSINESS_ZONE`, independently of the JVM/default clock zone.

## Testing

- Unit tests mock or fix the `Clock` (`Clock.fixed(instant, BUSINESS_ZONE)`) to make "now" deterministic. See `AvailabilityServiceTest` and `NotificationSchedulerTest`.
- Integration tests use the real clock; compute times relative to `LocalDateTime.now(BUSINESS_ZONE)`.

JSON/query timestamps support at most microsecond precision, matching PostgreSQL; finer values are rejected. All appointment endpoints reject gaps and overlaps, not merely missing DST times.
