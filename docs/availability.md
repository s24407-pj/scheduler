# Availability and scheduling

How bookable slots are computed, and the data that feeds them. Code: `availability/`, `workschedule/`, `scheduleblock/`, `employeeoffering/`, `company/`.

## Slot calculation

`GET /api/availability?employeeId={id}&serviceId={id}&date=YYYY-MM-DD` (public) returns `List<AvailableSlotResponse>`: `{ time: LocalTime, price: Int, originalPrice: Int }`.

`AvailabilityService.getAvailableSlots()`:

1. Load the offering and its company. The employee must be employed by that company and, if the employee has any offering assignments, be assigned this offering (otherwise 400).
2. Load the employee's work schedule entry for `date.dayOfWeek`. **No entry → empty list.**
3. Walk from the schedule's `startTime` in steps of `companies.slot_interval_minutes`. A candidate slot is `[t, t + durationMinutes)`; it must end at or before the schedule's `endTime`.
4. Drop slots that overlap an active reservation or a schedule block (`EmployeeAvailabilityPolicy`, same rules as booking).
5. Drop slots that do not start after `now + minBookingAdvanceMinutes`. This also removes past slots.
6. `price` = catalog price with the last-minute discount applied if the slot starts within `lastMinuteDiscountHours` from now; `originalPrice` = catalog price.

Company `openingTime`/`closingTime` are **not** used for slot calculation; they are informational and exposed in the public profile. Working hours come only from employee work schedules.

## Work schedules

`PUT /api/employees/{employeeId}/work-schedule` (OWNER) replaces the weekly schedule; `GET` (OWNER, EMPLOYEE) reads it. One entry per day of week with `startTime` < `endTime`. A day without an entry is a day off.

## Schedule blocks

Time ranges when an employee is unavailable (breaks, leave).

- `POST /api/schedule-blocks` (OWNER, EMPLOYEE). Employees create blocks for themselves; owners may pass `employeeId` for any employee of their company. `startTime` must be in the future.
- A block may not overlap an active reservation or another block of the same employee in the same company (service check plus database exclusion constraint). Creation takes the same employment row lock as booking.
- `DELETE /api/schedule-blocks/{id}`: owners delete any block in their company, employees only their own.
- `GET /api/schedule-blocks/employee?start=&end=[&employeeId=]`: employees always get their own; owners may pass `employeeId`.
- Blocks are scoped to one company: a block at salon A does not affect the same person at salon B.

## Offering assignments

`POST|DELETE /api/employees/{employeeId}/offerings/{offeringId}` (OWNER). If an employee has **no** assignments, they can perform every offering of their company (backward-compatible default). Once any assignment exists, only assigned offerings are bookable. The same rule drives the public employee list.

## Company settings that affect booking

`PUT /api/company/settings` (OWNER):

| Setting | Range | Default | Effect |
| ------- | ----- | ------- | ------ |
| `slotIntervalMinutes` | 5–240 | 30 | Step between candidate slot starts |
| `minBookingAdvanceMinutes` | 0–10080 | 0 (off) | Hides and rejects customer bookings starting sooner; staff bypass |
| `lastMinuteDiscountPercent` | 0–100 | 0 (off) | Discount for slots starting soon |
| `lastMinuteDiscountHours` | 1–168 | 24 | Window for the discount |
| `maxNoShows` | ≥ 0 | 3 | Auto-block threshold; 0 disables |
| `openingTime`, `closingTime` | `closingTime` > `openingTime` | 09:00–17:00 | Informational only |

## Public booking endpoints

Unauthenticated, for customer booking pages:

- `GET /api/company/public/{companyId}` → `CompanyPublicProfileResponse` (`id`, `name`, `openingTime`, `closingTime`, `lastMinuteDiscountPercent`, `lastMinuteDiscountHours`). Internal settings such as `maxNoShows` are not exposed.
- `GET /api/employees/public/company/{companyId}?offeringId={id}` → `PublicEmployeeResponse` (`id` = user ID used by availability and reservation APIs, `firstName`, `lastName`, `photoUrl`) for employees eligible for the offering.
- `GET /api/offerings/public/company/{companyId}` and `GET /api/offerings/public/employee/{employeeId}`.

Unknown companies, and offerings that are inactive or belong to another company, return 404.
