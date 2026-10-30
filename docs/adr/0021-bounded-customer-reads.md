# ADR-0021: Bounded customer reads

Status: Accepted (2026-10-30, user-authorized audit remediation).

## Context

Customer directories and histories returned unbounded arrays. Directory penalty counts, calendar profile lookups, history presentation and personal availability conflict checks repeated database reads for each row or slot.

## Decision

Keep existing JSON arrays. GET /api/customers, /api/customers/{id}/visits and /api/reservations/me accept zero-based page (default 0) and size (default 100, 1–200). Reject negative or non-representable offsets with INVALID_PARAMETER. Read slices without full counts. Order directories by last name, first name, ID; histories by start descending, ID descending. X-Next-Page contains the next integer, absent on the last page. X-Page-Context captures the same public immutable scope as the write-context header; CORS exposes both headers.

Clients must follow continuation when they need the whole list, cancel reads on identity changes and never combine pages with different contexts. The dashboard collects sequential pages and fails the whole read on continuation errors. Page traversal is not a database snapshot: edits between requests can shift offsets. Refresh the list after mutations; immutable export/snapshot semantics are outside this contract.

Calendar reads require a positive interval of at most 93 local days. Consumers needing longer periods must request separate windows. Customer conflict checks use one bounded interval query; directory penalties/blocks and calendar profiles use batches. Customer history loads company/staff presentation once per page. All tenant, archive and privacy predicates remain authoritative.

## Deployment and consequences

Deploy the compatible dashboard before the backend, with successful CI pinned to its exact backend head. Coordinate the separate customer website's /api/reservations/me pagination before deployment; its implementation is outside these repositories. Old consumers would otherwise silently show only the first 100 records. Rolling back the dashboard alone against the new backend has the same risk; revert the backend read contract first. V5 adds read indexes only and can remain on rollback.

This changes the unbounded read behavior in ADR-0016/0017; lifecycle, identity and write invariants are unchanged. Bounded pages limit individual database/result allocations; the dashboard still collects complete lists for its existing search UI and may need server-side search as datasets grow.
