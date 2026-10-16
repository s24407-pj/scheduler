# ADR-0013: Durable commercial quotes without capacity holds

**Status:** Accepted

## Decision

Persist server-generated UUID quotes scoped to a customer authentication account, company, service and requested Warsaw start instant. Save the accepted service name, whole-złoty price and duration with a ten-elapsed-minute expiration. Quotes do not bind an employee or hold a slot.

Booking resolves commercial terms against an authoritative instant obtained after the booking locks/replay lookup. Before expiration, preserve the quoted agreement through catalog deactivation, renaming, duration and price changes, including a cheaper current price. Current hard eligibility, activity, schedule, block, conflict and booking restrictions remain mandatory.

At or after expiration, accept the saved agreement only when the service is active and its name, current effective price and duration exactly match. Otherwise return a stable 409 `QUOTE_RECONFIRMATION_REQUIRED`. Foreign/missing quotes return opaque 404; changing an owned quote's service/company/start is invalid input.

## Consequences

Quote rows are durable and independent of reservation lifecycle. Server-owned terms prevent price/duration tampering. Booking idempotency and database conflict protections remain responsible for duplicate submissions; quoting does not weaken them. Reservations must retain the accepted service name and commercial values even when catalog/display values later change.
