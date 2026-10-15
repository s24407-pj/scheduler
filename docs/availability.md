# Availability and scheduling

`GET /api/availability?employeeId={id}&serviceId={id}&date=YYYY-MM-DD` is public. Slots include `time`, effective `price`, `originalPrice`, and `personalConflict`. An authenticated customer receives the same otherwise-free salon slots with their own overlapping company appointments marked. Staff authentication does not supply a customer identity.

## Effective schedules and grid

Each company employee has at most one continuous same-day weekly shift per weekday. No weekly schedule means no customer slots. Owners replace the week using `PUT /api/employees/{employeeId}/work-schedule`: only explicit `{"entries":[]}` clears it. Missing/null entries reject with 400 and preserve the saved schedule.

Date overrides replace the whole weekly shift for that date. `PUT /api/employees/{employeeId}/schedule-overrides/{date}` accepts either `{"dayOff":true}` or `{"dayOff":false,"startTime":"12:15","endTime":"17:00"}`. A day off forbids times; a working override requires both times and end after start. Deleting the override restores the weekly schedule. Both staff roles can read overrides in an inclusive date range; only owners may write/delete them. Every operation is company scoped.

`BookableSlotPolicy.effectiveShift` resolves an override first, then the weekly shift. The grid begins at the effective shift start, every configured slot interval. The entire accepted duration must fit; ending exactly at shift end is valid. Company opening hours are informational. Invalid Warsaw DST gaps/overlaps and appointment intervals spanning a transition are excluded. The full shift's elapsed minutes are available as the allocation denominator; blocks never reduce it.

## Temporal boundaries

All business dates/times use Europe/Warsaw even when the JVM or injected clock uses UTC. Zero notice requires a strictly future start. Positive notice is inclusive and measured in elapsed minutes. The maximum advance defaults to 90 Warsaw calendar days, including the entire final date. Availability is advisory; booking repeats authoritative checks under its policy locks.

## Qualification

Employees have explicit `ALL_SERVICES` or `SPECIFIC_SERVICES` qualification. `ALL_SERVICES` allows every active company service. `SPECIFIC_SERVICES` allows only explicit assignments: an empty set means none. Disabled staff cannot receive new customer bookings and are absent from public eligibility. Qualification changes use owner-only `PATCH /api/employees/{employeeId}/offering-eligibility` with `{"mode":"SPECIFIC_SERVICES"}` or `{"mode":"ALL_SERVICES"}`. Assignment changes do not silently switch mode.

Public employee lists, public employee services and availability use these same rules. Foreign or disabled employee targets return opaque 404 where an individual employee is requested.

## Blocks and serialization

Employee blocks are separate stored intervals, with optional staff-visible reasons. Blocks may be ongoing or span multiple days, including a DST transition; each endpoint must identify an unambiguous Warsaw local time. A block cannot overlap an occupied reservation interval or another block. Owners manage all company employees' blocks; employees manage only their own. An explicit unauthorized target is rejected, never ignored. Both staff roles may read coworkers' intervals and reasons within the company.

Policy mutations lock the company row before the employee row, matching booking allocation. This serializes weekly/date schedules, qualification/assignments and block changes against booking decisions. Existing appointments remain when a schedule or qualification changes. Employee overlap continues to use the shared employment lock and database exclusion constraint.

## Owner settings and presentation

Owner settings expose `minBookingAdvanceMinutes` (default 0), `maxBookingAdvanceDays` (default 90), `maxFutureCustomerBookings` (default 10), the slot interval, no-show threshold and last-minute discount settings. Reducing a booking limit preserves existing appointments. The company row lock protects changes against concurrent booking policy decisions.

`PUT /api/company/profile` lets the owner update only their company's public name/address. Public company responses include the current address. Public category names are exposed by `/api/offering-categories/public/company/{companyId}`. Catalog and category mutations remain owner-only.
