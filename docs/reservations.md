# Reservations

A booking is automatic. Its initial status is `BOOKED`; there is no approval, hold, `PENDING` or `CONFIRMED` stage. Customers authenticate by phone to one salon, whose `company_customers.id` identifies their local account. OTP creates a pending phone identity. The first successful booking requires first/last name and sets `first_booking_at` in the booking transaction, establishing staff visibility. Failed bookings leave that identity pending. Existing profile values can only be changed by staff.

## Submission and response

Both `POST /api/reservations` (CUSTOMER) and `POST /api/reservations/staff` (OWNER/EMPLOYEE) require `Idempotency-Key`, the session cookie, CSRF header and captured `X-Expected-Session-Context` header. A key belongs to the authenticated realm/account (`customer:<companyId>:<customerId>` for customers). Its original payload is durably bound even when validation fails; retrying that payload may later succeed, while changing it returns 409. After success, the key permanently identifies the reservation. Replays return its current details, including after edits, outcomes and archive, with no allocation or repeated side effect. Concurrent identical submissions wait up to five seconds, then return an explicit in-progress conflict if necessary.

Staff responses also contain `customerId`; customer responses omit it. Responses contain the reservation/company/service/employee IDs; current company name/address and employee name; accepted service name, price and duration; start/end, status, revision, archive flag and original creation time. Private cancellation reasons and internal notes never appear in customer responses. The obsolete `serviceNameSource` response property was removed by [ADR-0019](./adr/0019-remove-obsolete-compatibility-code.md); accepted service names remain stored on reservations.

Every booking POST counts toward rolling request limits: 10/account/minute and 60/IP/minute, including retries. Limits are atomic in Redis and return 429 with `Retry-After`. The company future customer-created booking cap defaults to 10; staff-created appointments are exempt, and reducing the cap preserves existing bookings.

## Customer booking

First booking supplies `firstVisitDetails:{firstName,lastName,contactEmail?}`. Pending profiles without it return `CUSTOMER_NAME_REQUIRED`; established profiles with it return `CUSTOMER_PROFILE_CHANGED` before allocation. Readiness from `/api/users/me` is advisory. After a race, require explicit refreshed acknowledgement before omitting details and creating a new attempt. Never overwrite names through booking. Successful exact-key replay is evaluated first and remains recoverable after the original booking established the profile.

Customer requests may reference only services, employees and quotes of the session salon; foreign resources return opaque 404. The customer endpoint accepts an optional employee ID; null requests any eligible employee. Minimum notice defaults to zero, meaning strictly future start. Positive notice is inclusive and uses elapsed minutes. The maximum horizon defaults to 90 Warsaw calendar days, including the entire final date. The full accepted duration must fit the effective shift and shift-relative grid. Current qualification, staff activity, blocks, customer blocks, employee conflicts and company-local customer conflicts apply. Missing schedules offer no slots. Appointment times must be unique Warsaw local times and cannot span a DST transition.

Any-employee allocation runs under the company lock. Among eligible free staff it minimizes booked-plus-completed service minutes for the requested date divided by full effective shift minutes; blocks do not reduce the denominator. Ties are randomized.

Pricing and ten-minute quotes are described in [pricing](./pricing.md). A quote protects the accepted price, duration and service name without holding a slot; it does not bypass current hard scheduling rules.

## Manual booking and editing

Staff must explicitly select `customer:{kind:"existing",customerId}` or `customer:{kind:"new",phoneNumber,firstName,lastName,contactEmail?}`. Existing requires an established local ID, never a phone re-lookup. New reuses a pending local identity atomically; an established phone returns `409 CUSTOMER_PHONE_ALREADY_EXISTS` with authorized `existingCustomer:{id}`, without booking/profile changes. Confirm the person and select existing with a new attempt/key, or contact the salon to resolve a different person sharing a phone. Mixed variants are rejected. International syntax is required for new phones; legacy keys are not rewritten.

Both staff roles may book future or ongoing appointments whose resulting end remains future. Manual bookings bypass customer grid, hours, notice and horizon, use regular catalog price by default, and may explicitly override whole-złoty price, duration and staff-only `internalNotes`. Active qualified staff, active service, customer restrictions and both conflict invariants remain. Only an owner may explicitly override a stored employee block.

`PUT /api/reservations/{id}` requires `If-Match` with the last observed revision (numeric or quoted ETag). Scheduling and commercial edits apply only to `BOOKED` visits whose current and resulting ends remain future; staff-only `internalNotes` may be added, updated or cleared on any unarchived visit (including after completion, cancellation or no-show) without changing its time or status. IDs, creation time and customer remain unchanged. For the same service, accepted name/price/duration are retained unless price/duration are explicitly overridden; an unchanged inactive service may remain. Changing service defaults to its regular price, duration and current name. Customers cannot self-reschedule. A blocked customer's ongoing visit may keep its original start for finishing edits but cannot move to a future start.

## Lifecycle

Lifecycle PATCH requests require `If-Match`. A successful cancellation/completion/no-show command can be repeated without further effects while its original outcome remains unchanged. Replaying an old command after reversal/correction conflicts, even if a later command reached the same status again. Stale ordinary writes return 409.

| Transition | Who | Conditions |
| --- | --- | --- |
| BOOKED → CANCELLED | Owning customer | Strictly before start; optional reason |
| BOOKED/COMPLETED → CANCELLED | Either staff role | Any time; private internal reason required; separate customer explanation optional |
| BOOKED → COMPLETED | Either staff role | From scheduled start |
| BOOKED → COMPLETED | Automatic | Every minute, catches up after restart; each row audited/versioned |
| BOOKED/COMPLETED → NO_SHOW | Staff | From start; employee deadline is end +24 elapsed hours, owner unlimited |
| NO_SHOW → COMPLETED/CANCELLED | Staff | Employee same deadline, owner unlimited; remove mistaken penalty |
| NO_SHOW → BOOKED | Staff | Ongoing visit only, correction deadline and current hard checks |
| CANCELLED → BOOKED | Staff | Future/ongoing, unarchived, current restoration hard checks; preserve agreement |

Completed appointments retain their full original occupied interval. Corrections cannot violate employee or customer exclusions.

Owner-only `/archive` and `/unarchive` retain the database record, audit and submission identity. Only CANCELLED visits may be archived. Archived visits are hidden from normal customer and staff lists and must be unarchived before restoration. There is no permanent deletion endpoint.

## Blocking and no-show history

`ReservationService` coordinates no-show penalty creation/correction and automatic block provenance. `CustomerBlockingPolicy` supplies the shared current count and block-driven cancellation operation for both manual and automatic blocking; manual block/unblock also enter through this policy. Cancellation joins the caller's transaction under the company lock and preserves per-row audit/versioning and the summary event. See [write ownership](./architecture.md#reads-and-write-ownership).

Owner manual block/unblock is company-local and does not affect authentication. Blocking cancels future BOOKED visits, preserves ongoing visits, and prevents both customer and staff future bookings. Unblocking never restores visits and never resets penalties. Manual and automatic block provenance are separate.

Durable no-show penalties count by Warsaw appointment date over the inclusive interval from today's date minus one calendar year through today. Default threshold is three; zero disables new automatic blocks. Aging decreases the displayed current count without removing an existing block. Correcting a mistaken no-show removes its penalty and may remove an automatic block below threshold, preserving manual blocks. An owner unblock grants reprieve; the next counted no-show may block again. Threshold changes do not retroactively add/remove blocks.

## Atomicity, audit and notifications

Booking obtains the company row lock and the company/employee row lock before authoritative clock checks. Company policy mutations use the same lock order. PostgreSQL exclusion constraints independently protect `(company, employee)` and `(company, customer)` occupied intervals. Optimistic revisions protect record changes. Every actual creation, edit, outcome, restoration, archive and unarchive is durably audited with actor, account, time and before/after state.

Customer-created bookings have page/API confirmation only. Staff create/edit/restore/cancel SMS is opt-in, default off, and only actual changes generate it. All sends occur after commit through durable delivery state. Block cancellations generate one filtered mandatory summary. Reminder timing/retry and ambiguous-provider rules are described in [notifications](./notifications.md).

## Submission contract and uncertain outcomes

Flat staff/customer booking fields are removed and rejected as unknown input; OTP accepts no profile names. This pre-production contract uses only the nested intent DTOs. See [ADR-0018](./adr/0018-explicit-booking-customer-intent.md).

Retain an unresolved exact request after transport failure even when a later retry receives 401, 403, 429 or changed context before outcome lookup. Recover only in the original account/company. Corrected known failures use a new key; unknown outcomes never do automatically. Browser payload loss requires manual salon resolution; filtered history cannot prove failure. Permanent server binding does not imply unlimited browser recovery.
