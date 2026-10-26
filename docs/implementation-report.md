# Revised scheduler contract: implementation and verification

Completed 2026-10-16 on `implementation/revised-scheduler-contract`. Reviewed runtime/test checkpoint: `ae14285`; final client checkpoint: `c8a0c24`. Later commits only save documentation and evidence. Changes are committed locally; no remote push or merge into GitHub master was performed.

## Repository and orchestration

PR [#53](https://github.com/s24407-pj/scheduler/pull/53), `feat/redis-session-auth`, was inspected and fast-forwarded into the integration branch at `ccf376f` before broader work. Its Spring Security + indexed Spring Session foundation was adapted to the revised contract. JWT dependencies, filters and custom JWT sessions were removed. GitHub PR #53 remains open. Relevant merged PR #50 and dependency/tooling PR #54 were also inspected.

Specialists worked in isolated checkouts, with sequential reuse after integration:

| Worktree | Branches / bounded assignments |
| --- | --- |
| `scheduler-session-review` | `agent/session-review`: sessions, identity/admin; `agent/booking-tests`: revised booking tests |
| `scheduler-otp` | `agent/otp`: atomic OTP; `agent/pricing`: pricing/quotes; `agent/notifications`: delivery; `agent/otp-delivery`: durable OTP |
| `scheduler-security` | `agent/security`: tenant/input/images; `agent/availability`: schedules/eligibility; `agent/integration-tests`: fixtures/regressions |
| `scheduler-final-review` | `agent/final-review`: fresh independent read-only adversarial review |
| `scheduler` | Integration, schema ownership, booking/lifecycle/audit/idempotency and review fixes |

Branches remain available. Completed coding worktrees were removed after integration. Four temporary fixture-support copies from the availability checkout were preserved under `.git/agent-worktree-backups` before cleanup. The independent review checkout was retained through final verification.

The session record reports baseline master 467 passing tests and the PR #53 foundation 464 passing tests, each with zero failures/skips, before subsequent phases. Those historical temporary logs did not survive later resumes; they are orchestration records, not the independently verified final evidence below.

## Implemented behavior

- **Authentication:** cookie/CSRF Spring Security + Redis Spring Session; CUSTOMER absolute 24 hours, STAFF 24-hour idle expiry; realm/account generation indexes prevent logout-all races; account-only logout/revocation and credential-version checks close reset/login races.
- **Identity/admin:** company-local phone authentication; OTP creates a pending identity and names/contact email/notes/history become staff-visible atomically with successful booking; company-owned staff credentials/profile/photo. Owner employee provisioning, editing, enable/disable, one-use 24-hour setup/recovery and current-password changes are implemented. Ownership/role provisioning remains operator-controlled.
- **Booking:** immediate BOOKED; staff ongoing/manual creation; durable required submission keys bound even on failed validation/rate rejection; current lifetime replay; customer cap/rate controls; direct revision-protected edits, exact lifecycle/corrections/restoration, owner archive/unarchive and durable audit.
- **Availability/time:** weekly single shifts, explicit clearing, date replacement/day off, separate blocks, explicit ALL/SPECIFIC qualification, atomic least-load any-employee allocation, personal-conflict markings, Warsaw notice/horizon, offsets/DST rejection and UTC-JVM persistence checks.
- **Pricing:** integer PLN, inclusive elapsed discount windows, ties-to-even rounding, regular staff defaults and durable optional 10-minute quotes protecting accepted service name/price/duration without holding capacity.
- **Blocking/no-show:** company-local manual/automatic provenance, future-only cancellations, durable appointment-date penalties, inclusive rolling year, corrections, threshold zero/non-retroactivity and owner reprieve without resetting history.
- **Notifications:** durable booking and phone-only OTP delivery state; committed claims before provider calls; accepted/definite-temporary/ambiguous outcomes; bounded retries and no blind ambiguous retry. Customer booking has no confirmation SMS/email. Staff SMS defaults off. Mandatory filtered block summaries, 09:00 Warsaw previous-day reminders, 10:00 uninterrupted deadline and distinct restart/completion behavior are implemented.
- **Catalog/images/company:** owner profile/catalog/category/image administration, service deactivate/reactivate, category deletion uncategorizes services, actual byte decoding, atomic five-image capacity and after-commit object deletion/rollback compensation.
- **Contract/docs:** generated cookie/CSRF OpenAPI, required submission/revision headers, exact lifecycle enum, local date-time schema and Problem Details align with behavior docs and ADRs 0011–0016. Superseded ADRs are explicitly marked. Optional k6 helpers use current login/routes and booking keys.

## Migration

Only **`V1__init_schema.sql`** remains, consolidated with explicit pre-production authorization. It initializes independent account/profile realms, staff recovery/version state, catalog/schedule/override/block policies, reservation snapshots/revisions/audit/commands, employee and customer exclusion constraints, booking submissions/quotes, no-show/block provenance and durable notification/OTP delivery tables and indexes.

This is a fresh baseline. Existing development databases require explicit recreation; no automatic reset, legacy archive or upgrade compatibility is claimed. Future applied migrations remain immutable. A previous-schema upgrade test is therefore inapplicable to this authorized fresh-only baseline. Final startup applied V1 to an independently created empty database and passed Hibernate validation.

## Security fixes

| Defect | Protection |
| --- | --- |
| Arbitrary foreign customer disclosure | Existing company/customer relationship required in scoped queries and authorization |
| Notes creating arbitrary relationships | Notes update an existing local profile only |
| Cross-company assignment deletion | Employee and service membership validated; delete is company scoped |
| Shared staff photo/profile mutation | Company-owned staff fields/assets; remaining object references checked before deletion |
| Staff principal authorizing customer writes | Explicit CUSTOMER realm guards and realm-qualified account keys |
| Public diagnostics | Minimal health only; metrics/info/Prometheus/internal endpoints denied to salon users |
| Lossy numbers / unknown fields | Strict Jackson coercion and field rejection with Problem Details |
| Offset/DST ambiguity | Explicit Warsaw validation and instant persistence independent of JVM zone |
| OTP/session abuse and revocation races | Atomic Redis scripts, quotas/budgets, fixed lifetimes, generation indexes and durable staff credential versions |
| Customer-facing internal reasons | Separate private cancellation reason and customer explanation projections |

## Atomic concurrency guarantees

| Invariant | Mechanism |
| --- | --- |
| Employee overlap | Company then employment locks plus PostgreSQL GiST exclusion for BOOKED/COMPLETED full intervals |
| Customer overlap | Company/customer GiST exclusion across employees; other companies independent |
| Any-employee allocation and customer cap | Company-row serialization covers eligibility/policy reads, allocation and insert |
| Policy/block changes | Shared company/employment lock order, scoped checks and database backstops |
| Submission identity | Durable unique account/key binding; row execution lock, bounded five-second wait; permanent reservation reference after success |
| Stale edit/outcome | Reservation versions plus durable command outcomes and reversal history |
| Gallery capacity | Offering parent-row lock serializes count and save; rollback compensates uploaded objects |
| OTP and request limits | Atomic Redis Lua operations; no unlocked check-then-save quota logic |

## Final verification

`./gradlew clean build` passed on JDK 25 with the entire test JVM in UTC: **71 suites, 554 tests, 0 failures, 0 errors, 0 skips**. All 12 Gradle tasks executed. This includes all unit/policy tests, real PostgreSQL/Redis integration tests, concurrency regressions, Kotlin warnings-as-errors, architecture checks, Spotless and generated OpenAPI verification. There is no separate configured Detekt task.

IntegrationTest-named suites account for 349 tests in 35 suites; the remaining 205 tests cover unit/policy/architecture and other test classes. These are inventory subtotals, not separate partial verification runs. Per-class counts are saved in [test evidence](./verification/revised-contract-tests.json).

Earlier complete runs exposed Podman's default five-second API idle shutdown, causing container-start `Broken pipe` errors. Those runs were failures and are not combined into a fabricated pass. The final entire clean build used an isolated local `podman system service --time=0` socket. The normal user service was not changed. Reproduction is documented in [development](./development.md#podman-api-idle-timeout-during-long-test-runs).

The final packaged application started against a newly created empty PostgreSQL database plus Redis, applied V1, validated mappings and ran enabled job startup entrypoints. Live checks verified minimal health/public profile/catalog/OpenAPI 200; anonymous diagnostics 401; owner diagnostics 403; missing-CSRF login 403; company + normalized staff email login/session 200; logout 204 and subsequent session 401. See [startup evidence](./verification/revised-contract-startup.json).

All six k6 scripts passed ESM syntax checks. A brief real k6 smoke (one VU, five seconds) passed 18/18 checks, 16 requests, zero HTTP failures, p95 45.7 ms. It is a route/client sanity check, not a throughput claim. Longer load/stress/spike experiments were not run; shared-account writes deliberately remain subject to contractual admission limits. See [smoke evidence](./verification/revised-contract-k6-smoke.json).

## Independent acceptance

A fresh independent reviewer inspected runtime, schema, tests, generated OpenAPI and documentation, then rechecked the final full-suite XML, startup evidence and client-only follow-up. Result: **76 PASS, 0 PARTIAL, 0 FAIL**. The full criterion/location/test/discrepancy matrix is preserved verbatim in [independent review](./verification/revised-contract-independent-review.md).

## Deployment prerequisites and accepted limitations

- Production still needs an operator-configured real `SmsSender` adapter/provider credentials; the development console adapter is disabled in prod. Real SMS acceptance and external R2 credentials were not exercised; integration tests verify delivery/storage boundaries with provider doubles.
- The operator must configure the positive shared daily SMS budget, secure cookies, explicit frontend origins and trusted proxies. Frontends consume the revised cookie/CSRF, realm, idempotency and revision contract.
- The consolidated V1 is intentionally fresh-only; existing development databases must be recreated explicitly.
- Accepted v1 behavior remains: one backend instance; current phone possession grants customer history; no browser challenge, customer self-rescheduling or permanent appointment deletion. Ambiguous provider acceptance may cause a missed SMS; no exactly-once provider guarantee is claimed.


## 2026-10-25: Explicit customer booking intent

This later change is defined by [ADR-0018](./adr/0018-explicit-booking-customer-intent.md). Staff booking now selects an exact established local customer ID or explicitly requests a new phone profile. Established-phone collisions require deliberate existing selection. Customer first-visit details express establishment intent and conflict if a salon profile was established concurrently. Successful durable submission replay precedes those mutable guards and returns the current reservation, including cancellation. Staff profile edits use `If-Match` and a row version shared with establishment; customer readiness exposes no salon-owned names, notes or email. International phone syntax is required without country guessing, key rewriting or identity merging. Removed flat booking/OTP fields have no compatibility adapter.

`./gradlew spotlessApply build --console=plain` passed on JDK 25 with the test JVM in UTC: **78 suites, 641 tests, 0 failures, 0 errors, 0 skips**. This includes the actual simultaneous OTP/staff absent-row race, staff/customer establishment race, same-revision concurrent profile edits, failed-booking rollback, tenant isolation and successful current-state replay. The final build includes Spotless and generated OpenAPI checks. Per-class results are in [customer intent test evidence](./verification/customer-intent-tests.json).

The first complete run exposed six outdated test fixtures; it was a failed run. Returning bookings were updated to omit establishment details, pending-profile tests supply the revision header, and note fixtures preserve the observed entity version. The focused 74-test rerun and the subsequent complete 641-test build both passed; their counts are not combined. Assertions were preserved and cap rejection now also checks the specific error code.

Verification used newly created isolated PostgreSQL/Redis containers and the development SMS adapter. No real messages, existing database resets, commits, pushes or deployment were performed. V2 adds customer row versions to the authorized company-local V1 without rewriting V1. Coordinated client configuration and rollout are documented in [deployment](./deployment.md#native-customer-booking-contract-adr-0018); read-only phone review and unresolved shared/recycled-phone handling are documented in [identity](./identity.md#phone-key-audit-before-enabling-native-booking).
