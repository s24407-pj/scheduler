# Pricing and quotes

Prices are integer whole złoty. Fractional prices are invalid JSON input. Customer prices apply the company's last-minute discount when the appointment starts within the inclusive elapsed interval `[now, now + discount hours]`. The result rounds to the nearest integer with ties to even: 101 zł at 50% becomes 50 zł; 103 zł becomes 52 zł. Arithmetic cannot overflow an integer intermediate. Staff manual booking defaults to the regular catalog price; staff may explicitly override price and duration.

## Customer quote

`POST /api/quotes` requires a CUSTOMER cookie session and CSRF. The body contains `serviceId` and an offset-free Warsaw `startTime`. The service must belong to the session company; foreign services return opaque 404. The write also requires the captured session-context header. The start must be strictly future and must identify a unique Warsaw instant; DST gaps and overlaps are invalid. No employee is bound and no capacity is held.

The 201 response contains a server-generated `quoteId`, company/service IDs, accepted `serviceName`, integer `price`, `durationMinutes`, local `startTime`, and an absolute `expiresAt`. The server persists the customer, company, service, start instant, accepted commercial terms and creation/expiry instants. The customer already has a pending or established local identity. Quote creation does not set `first_booking_at` or establish staff visibility.

A quote protects its accepted name, price and duration for ten elapsed minutes, until but excluding `expiresAt`. Send its UUID in the booking request. The booking transaction resolves it using an authoritative business-clock instant after locking/replay lookup. A valid quote preserves the agreement if the catalog service was renamed, repriced, made cheaper, changed duration or deactivated. All current hard booking rules still apply: qualified active employee, schedules, blocks, overlaps, booking restrictions and valid appointment times.

An expired quote can proceed only while the service remains active and accepted name, effective customer price and duration all still match. Otherwise booking returns 409 `QUOTE_RECONFIRMATION_REQUIRED`, requiring confirmation of new terms or a new quote. A foreign or nonexistent quote returns the same opaque 404. Reusing an owned quote with a changed service, company or start returns 400. Commercial terms are never trusted from the request body.

A quote is not consumed when booking succeeds: persistent booking idempotency and hard conflict invariants control retries and duplicates. The reservation stores accepted service name, price and duration as historical agreement values. Company address/name and employee presentation remain current display values.
