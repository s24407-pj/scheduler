# ADR-0019: Remove obsolete compatibility code

**Status:** Accepted (2026-10-30), user-authorized backend and dashboard cleanup.

## Decision

Remove the duplicate `GET /api/company/employees` route. Staff consumers use the existing
`GET /api/employees`, which requires OWNER or EMPLOYEE and returns the public employee
identifier in both `id` and `userId`. Customers have no staff-directory access.

Remove `serviceNameSource` from reservation responses and its unused persistence field.
Accepted service names and commercial terms remain on reservations. Historical audit JSON
is preserved verbatim, including any former metadata. This supersedes the provenance-field
portion of the earlier reservation response contract; other ADR-0016 guarantees remain.

Notification delivery state remains authoritative. Remove the obsolete reservation
`reminder_sent` column and require new reservation writes to choose `creation_source`
explicitly. Preserve all existing creation-source values, including historical `LEGACY`.
Replace the old `REDIS_HOST`/`REDIS_PORT` aliases with standard
`SPRING_DATA_REDIS_HOST`/`SPRING_DATA_REDIS_PORT` variables. Remove the unused
local-date-time pricing overload and the old void SMS sender methods;
all adapters implement classified delivery outcomes directly. Worker exception handling
continues to classify unknown outcomes as ambiguous and never blindly retries them.

Keep company-scoped staff login: it is the supported high-membership fallback. Keep shared
photo-reference checks, historical identity keys, superseded ADRs and security rejection
tests. None is an obsolete runtime implementation that can safely be deleted.

## Migration and rollout

V1 and V2 remain immutable. V3 drops only the two obsolete reservation columns and the
creation-source default; it does not delete reservations or rewrite their audit history.
Migrate any host-side Redis alias settings to the standard Spring Boot variables.
Stop the old backend before applying V3: its ORM still references the removed columns.
Deploy the paired dashboard generated against the exact backend PR head, with successful
Dashboard CI. Existing customer clients must tolerate the removed optional provenance field.

Do not roll back to an older backend binary against V3 unchanged. If rollback is necessary,
stop the backend and restore the old schema shape through an explicitly reviewed forward
migration adding `reminder_sent boolean NOT NULL DEFAULT false` and
`service_name_source varchar(40) NOT NULL DEFAULT 'ACCEPTED'`, and restoring the
`creation_source` default `'LEGACY'`. These obsolete fields must not be treated as recovered
historical truth. Then deploy a compatible backend/dashboard pair. No automatic down
migration or Flyway checksum bypass is provided.
