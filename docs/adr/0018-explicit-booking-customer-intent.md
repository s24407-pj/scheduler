# ADR-0018: Explicit customer selection and profile establishment intent

**Status:** Accepted (2026-10-25).

## Context

Phone-only staff booking could silently attach an appointment to an established profile while ignoring entered names. Flat customer name fields could also appear to update a profile, including when staff established it during the OTP booking journey. Separate frontend guesses cannot make either association safe.

## Decision

Keep ADR-0017's company-local phone identity and first-successful-booking visibility. Booking now expresses intent:

- Staff `customer` is an exclusive discriminated union: `{kind:"existing",customerId}` or `{kind:"new",phoneNumber,firstName,lastName,contactEmail?}`. Existing IDs must be established and owned by the session salon; pending/foreign IDs return opaque 404. New mode may reuse a pending row but returns `409 CUSTOMER_PHONE_ALREADY_EXISTS` for an established row, with authorized `existingCustomer:{id}`. No reservation or profile changes commit on conflict. Staff must confirm the person before explicitly selecting the existing ID; a changed payload needs a new key.
- Customer `firstVisitDetails:{firstName,lastName,contactEmail?}` is establishment intent. A pending profile requires details (`400 CUSTOMER_NAME_REQUIRED` when absent). An established profile plus details returns `409 CUSTOMER_PROFILE_CHANGED` before allocation, rather than silently discarding names. Refresh readiness and require a deliberate acknowledgement or salon assistance; never automatically strip details and resubmit. Returning bookings omit details. Identity and company come exclusively from the authenticated principal.
- `/api/users/me` adds `companyId` and `profileState:PENDING|ESTABLISHED`, without exposing staff-managed names, email or notes. Readiness is advisory; the locked booking transaction is authoritative. `PENDING` describes profile establishment, not OTP verification. Staff can create and establish an identity without OTP. Challenge/session and reservation/submission states are independent.
- Profile establishment and staff profile edits share a row `@Version`, added by migration V2. Staff profile PUT requires `If-Match`; missing/invalid is `400 REVISION_REQUIRED`, stale is `409 CONCURRENT_MODIFICATION`. List/detail returns `revision`. Booking an established ID does not require a profile revision: a name edit is not an identity change.
- Preserve permanent account-scoped payload binding, including failed inputs. Successful replay lookup precedes all new-intent/profile/collision checks, returns 200 and the **current** reservation (possibly cancelled or archived), and performs no new side effects. Staff responses add `customerId`; customer responses omit it. Do not treat HTTP 200 as a new confirmation.
- This is a pre-production contract replacement. Flat booking fields and OTP name fields are removed and rejected as unknown input. There is no compatibility path or historical-payload adapter.
- OTP/new-customer input requires explicit international syntax `^\+[1-9][0-9]{8,14}$`. This validates representation, **not** country allocation, number reachability or equivalence of old national keys. No country inference, legacy rewriting or merges. Existing legacy profiles remain selectable by ID. Both clients must obtain a country-code-inclusive number; audit ambiguous legacy national/international pairs before native booking rollout. Any normalization migration needs independent review and identity collision handling.

## Recovery and operational limits

Retain exact key/body and original realm/account/company while a submission outcome is uncertain. A later 401/403/429 or session-context rejection does not establish that the original request failed. Reauthenticate the original scope and replay unchanged. Corrected input requires a new attempt/key after a known failure. Browser storage recovery is bounded; if the exact payload is lost, preserve an unresolved marker and obtain salon assistance. Absence from filtered history is not proof of failure. Do not invent a new key automatically.

Phone possession authenticates a phone account, not a physical person. One profile per phone per salon cannot represent family/shared phones, recycled numbers or mistaken staff numbers safely by matching names. The UI must describe the salon record for the verified phone and offer salon contact. There is no phone-edit, reassignment or automatic merge endpoint in this change. This operational gap remains explicit.

Pending IDs and successful idempotency records are not automatically expired/deleted. A retention policy must preserve identity, foreign keys and permanent outcome recovery; OTP/session expiry alone cannot authorize deletion.

Use a same-origin `/api` reverse proxy with existing Spring cookies/CSRF/context where possible. A deployment adapter must isolate browser cookies per request and preserve approved Set-Cookie/CSRF semantics; it must not add a shared service identity or parallel session store.

## Consequences

This supersedes the ambiguous flat-field booking input in ADR-0016/0017 while retaining tenant isolation, allocation locks, database exclusions, immutable accepted terms, notification privacy and durable replay. All three applications consume the new contract together. V1 remains unchanged; V2 upgrades existing databases in place.
