# Development

## Prerequisites

- **Docker or rootless Podman** with Compose. Integration tests need it too (Testcontainers).
- **JDK 25** and **lefthook**, pinned in `mise.toml`. Run `mise install && lefthook install` once; mise activates the JDK inside the repo and lefthook installs the git hooks. The Gradle wrapper downloads Gradle. Plugin and library versions live in `gradle/libs.versions.toml`; Spring Boot-managed dependencies take their version from the Boot BOM.
- With rootless Podman, export `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock` (for example in `~/.zshrc`). Non-interactive shells that do not load `~/.zshrc` need `zsh -ic '<command>'`.

### Podman API idle timeout during long test runs

Podman's API service defaults to a five-second idle timeout. A socket-activated service may exit while Testcontainers retains a pooled connection, causing `ContainerLaunchException` with `Broken pipe` when a later Spring context starts another container. Keep a dedicated local API service alive in one terminal:

```bash
podman system service --time=0 "unix://$XDG_RUNTIME_DIR/scheduler-verification.sock"
```

In another terminal, run the complete suite against that socket:

```bash
DOCKER_HOST="unix://$XDG_RUNTIME_DIR/scheduler-verification.sock" \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="$XDG_RUNTIME_DIR/scheduler-verification.sock" \
./gradlew clean build
```

Stop the dedicated service with Ctrl-C after verification. This changes neither the normal Podman service nor the test assertions.

## Running

### Full stack in containers (recommended)

Copy the local example once (keep an existing `.env` if already configured):

```bash
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
curl -sf http://localhost:8080/actuator/health
```

The base Compose file defaults to `prod` and publishes no host ports. The development override builds the Dockerfile locally as `scheduler:local`, selects `dev` and binds the API (`8080`), debugger (`5005`), PostgreSQL (`5432`), and Redis (`6379`) to `127.0.0.1` only. It enables JDWP through `JAVA_TOOL_OPTIONS`, preserving the Dockerfile's Java entrypoint. `CORS_ORIGINS` remains configurable in `.env`.

The example supplies `SCHEDULER_IMAGE=scheduler:local` for base-file interpolation (also add it to an existing local `.env`), local database credentials and R2 placeholders so Compose interpolation succeeds; image uploads require real R2 settings. Do not use this file or the development override in production.

### App on the host

Start only the databases using the same override so their loopback ports are available. Export `.env` for `bootRun`, because Gradle does not load it automatically (the example is shell-compatible; quote any custom shell-sensitive values):

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db redis
(set -a; . ./.env; set +a; ./gradlew bootRun --args='--spring.profiles.active=dev')
```

Without `--spring.profiles.active=dev` no profile is active: nothing is seeded, SQL logging is off, and API docs are disabled. The app sets its own default time zone to Europe/Warsaw, so the host zone does not matter ([time handling](./time-handling.md)).

### API docs

In the `dev` profile, Swagger UI is at <http://localhost:8080/swagger-ui.html> and the OpenAPI JSON at <http://localhost:8080/v3/api-docs>. Sign in through the API in the browser; it retains the HttpOnly session cookie automatically. Fetch `/api/auth/csrf` before login and again afterward; Swagger UI sends the CSRF cookie value as `X-XSRF-TOKEN`. See [Signing in with cookies and CSRF](#signing-in-with-cookies-and-csrf).

## Dev data

The `dev` profile runs `DataInitializer` on startup. It is idempotent.

| What | Details |
| ---- | ------- |
| Company | `Ka.Cosmetology` (the first company in the database) |
| Owner | `gabinet@kacosmetology.pl` / `admin123` |
| Employee | `pracownik@kacosmetology.pl` / `employee123` |
| Catalog | 4 categories and 26 offerings (seeded only if no offerings exist) |
| Work schedules | **None.** Availability returns no slots, and customer bookings are rejected with 409, until an owner sets a schedule with `PUT /api/employees/{id}/work-schedule`. |
| Customers, reservations | None |

### Signing in with cookies and CSRF

```bash
# A fresh development seed has one company for this staff email.
# jq is used only to extract the CSRF token; the session identifier is never read.
csrf=$(curl -fsS -c /tmp/scheduler-cookies http://localhost:8080/api/auth/csrf | jq -r .token)
curl -fsS -b /tmp/scheduler-cookies -c /tmp/scheduler-cookies \
  http://localhost:8080/api/auth/login-staff-email \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
  -d '{"email":"gabinet@kacosmetology.pl","password":"admin123"}'
# Authentication clears CSRF: refresh before any further mutation.
csrf=$(curl -fsS -b /tmp/scheduler-cookies -c /tmp/scheduler-cookies \
  http://localhost:8080/api/auth/csrf | jq -r .token)
curl -fsS -b /tmp/scheduler-cookies http://localhost:8080/api/auth/me
curl -fsS -b /tmp/scheduler-cookies -c /tmp/scheduler-cookies -X POST \
  -H "X-XSRF-TOKEN: $csrf" http://localhost:8080/api/auth/logout
rm -f /tmp/scheduler-cookies
```

For customers, obtain CSRF first, call `request-code`, then `verify-code` with the same cookie jar/header. In development inspect the local Redis `otp:<companyId>:<canonical-phone>` key; codes are never logged. Both request and verification require `companyId`; verification otherwise needs only phone and six-digit code; names are collected atomically with the first successful salon booking. Refresh CSRF after verification. Authentication JSON identifies the authenticated account and contains no credential. Browser clients use `credentials: 'include'`; follow the [frontend contract](./architecture.md#csrf-cookies-and-frontend-contract).


## Configuration

Environment variables override `application.yaml`. Spring's relaxed binding means any property can be set as an upper-case variable (for example `SPRING_DATASOURCE_URL`); the table lists the variables the project uses.

| Variable | Default | Purpose |
| -------- | ------- | ------- |
| `SCHEDULER_IMAGE` | required by base Compose | Approved GHCR digest in production; `scheduler:local` in local `.env`, overridden by the development Compose local build |
| `SPRING_PROFILE` | `prod` | Compose only: maps to `SPRING_PROFILES_ACTIVE` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/scheduler_db` | Compose overrides this with `jdbc:postgresql://db:5432/scheduler_db` |
| `DB_USERNAME`, `DB_PASSWORD` | required by Compose | Database credentials; Compose passes these as `SPRING_DATASOURCE_USERNAME/PASSWORD` and initializes PostgreSQL with the same values |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | `redis`, `6379` in Compose | Compose connects to the `redis` service; host-side runs use these same standard Spring Boot variables |
| `SPRING_SESSION_TIMEOUT` | `24h` | Anonymous session default; authenticated customers have absolute 24h expiry and staff have 24h inactivity expiry |
| `SPRING_SESSION_DATA_REDIS_NAMESPACE` | `scheduler:sessions` | Application-specific session namespace; share between API instances |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | request HTTPS detection; true in prod | Keep true in production; no cookie Domain; SameSite=Lax |
| `CORS_ORIGINS` | required by Compose | Comma-separated allowed origins; set to the exact dashboard origin(s) |
| `OTP_GLOBAL_DAILY_SMS_CAP` | required by Compose | Positive operator-configured shared SMS attempt budget required in prod, UTC calendar day; OTP issuance reserves the first attempt and definite-temporary retries reserve again. Fixed OTP rules: 5-minute lifetime, 60-second cooldown, 3 failed attempts, 3 accepted phone issuances/rolling 5 minutes, 10 issuance requests/IP/rolling 15 minutes, 10 verification requests/IP/rolling 5 minutes. |
| `JUSTSEND_APP_KEY`, `JUSTSEND_SENDER` | required in prod | JustSend API App-Key and permitted sender; passed through by Compose. Keep the key in a local ignored `.env` or deployment secrets. |
| `SMS_WORKER_OTP_CONCURRENCY`, `SMS_WORKER_NOTIFICATION_CONCURRENCY` | `2`, `4` | Isolated provider concurrency, each 1–16; durable backlog stays in PostgreSQL. Passed through by Compose. |
| `TRUSTED_PROXIES` | empty | Regex of trusted direct proxy peers; empty trusts none. See [deployment.md](./deployment.md#client-ip-for-rate-limiting). |
| `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER` | `X-Forwarded-For` in Compose | Client-IP header accepted only from trusted peers; Cloudflare prerequisites in [deployment.md](./deployment.md#client-ip-for-rate-limiting). |
| `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES` | 10 / 1 | Staff logins per IP per window (not passed through by Compose; edit `docker-compose.yml` to override) |
| `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`, `R2_PUBLIC_URL` | required by Compose | Cloudflare R2 image storage configuration |
| `TZ` | `Europe/Warsaw` in Compose | Container OS zone; the app sets its JVM default zone to Europe/Warsaw regardless |

Profiles:

- `dev` (`application-dev.yaml`): SQL logging (`org.hibernate.SQL` at DEBUG), DEBUG for the app and Spring Security, extra actuator endpoints, seed data, Swagger UI.
- `prod` (`application-prod.yaml`): INFO/WARN logging as ECS JSON on the console, no SQL logging. `JustSendSmsSender` sends real SMS and requires nonblank `JUSTSEND_APP_KEY` and `JUSTSEND_SENDER`; `ConsoleSmsSender` is disabled.

Every request gets an ID (`RequestIdFilter`): a valid incoming `X-Request-Id` (up to 64 characters of `[A-Za-z0-9-]`) is reused, otherwise a UUID is generated. It is returned in the `X-Request-Id` response header and stored in the log MDC as `requestId`, so plain-text log lines show it in brackets and ECS logs carry it as a field.
- `test`: set by Gradle for every test run; uses local test infrastructure.

## Production deployment

See [deployment.md](./deployment.md) for Dokploy networking, required environment variables, proxy trust, persistence, and verification. Production SMS is implemented by `JustSendSmsSender` (ECO variant). Missing or blank JustSend credentials fail startup. See [SMS operations](./notifications.md#sms-observability-and-segment-estimates) for safe request logs, multipart warnings, segment and worker metrics, and asynchronous delivery behavior; the daily budget limits attempts, not billable segments.

## Testing

```bash
./gradlew test                                   # everything (needs Docker/Podman)
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest"
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest.full customer registration and login via SMS should create session"
```

Every feature needs a service unit test and an HTTP integration test. Test names are backtick strings in English or Polish.

| Kind | Naming | Setup | Use for |
| ---- | ------ | ----- | ------- |
| Service unit test | `*ServiceTest` | `@ExtendWith(MockKExtension::class)`, MockK mocks for repositories | Business rules, branches, error cases |
| Integration test | `*IntegrationTest` | `@SpringBootTest`, `@AutoConfigureMockMvc`, `@Import(TestcontainersConfiguration::class)`; `@MockkBean` for `SmsSender` and `S3Client` | Endpoints end to end: security, validation, status codes, persistence |
| Repository test | `*RepositoryTest` | `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = NONE)`, `@Import(TestcontainersConfiguration::class)` | Custom JPQL queries and database constraints |

`TestcontainersConfiguration` starts `postgres:18-alpine` and `redis:8-alpine` with `@ServiceConnection`, so no connection properties are needed. Flyway runs the real migrations against the container.

Load tests (k6, Prometheus, Grafana) are described in [`perf-tests/README.md`](../perf-tests/README.md).

### Coverage reports and CI gate

Coverage includes all production code, with unit, repository, and integration tests contributing to one backend-wide result. Kover uses its default coverage engine with no manual production-code exclusions. CI requires at least **80% line coverage** and **60% branch coverage**; these backend-wide thresholds remain independent of the additional module gates below.

Critical modules also have independent minimums. Each module is evaluated separately, aggregating its package and all subpackages (including DTOs and, for `auth`, SMS delivery); coverage elsewhere cannot compensate for a module below its minimum.

| Package under `pl.kacosmetology.scheduler` | Minimum lines | Minimum branches |
| --- | --- | --- |
| `auth` | 90% | 70% |
| `reservation` (booking) | 90% | 70% |
| `security` | 90% | 70% |
| `user` (company-local customer identity and blocks) | 90% | 60% |
| `company` (company and staff membership) | 90% | 60% |

`koverVerify` enforces the global gate and all five module gates using the same test run. For a single module, use `./gradlew koverVerifyAuth` (or the `Reservation`, `Security`, `User`, or `Company` suffix). All gates stay explicit rather than running with ordinary local `check`/`build`.

Tenant isolation spans multiple modules. These gates complement the existing cross-company integration scenarios in `CompanyCustomerIsolationIntegrationTest`, `CompanyCustomerRepositoryTest`, `StaffIdentityIntegrationTest`, and `SessionSecurityIntegrationTest`; coverage percentages alone do not verify tenant isolation.

```bash
./gradlew koverHtmlReport koverXmlReport          # Run tests and generate reports
./gradlew koverVerify                            # Generate reports and enforce global and module thresholds
./gradlew build koverVerify                      # Same checks as CI
```

Open `build/reports/kover/html/index.html` for source-level coverage. The machine-readable XML report is `build/reports/kover/report.xml`. Both reports are generated before verification and remain available if a threshold fails. CI includes them in the existing `backend-test-reports` artifact.

Use the complete test suite for authoritative coverage; filtered `test --tests ...` runs produce partial coverage and can fail the gate. The existing Docker/Podman prerequisites also apply to coverage tasks. Ordinary `test`, `build`, and `build -x test` do not enforce thresholds; coverage verification is an explicit task.

## Formatting

Kotlin sources and Gradle scripts are formatted with ktfmt (kotlinlang style, 120 columns) through Spotless. `./gradlew build` and CI run `spotlessCheck` and fail on unformatted code.

The lefthook pre-commit hook (`lefthook.yml`) runs `./gradlew spotlessApply` whenever a `.kt`/`.kts` file is staged and re-stages the formatted files, so commits are always formatted. It runs Gradle through `mise exec` so it also works from IDE commits outside a mise-activated shell. Unstaged hunks of partially staged files are left untouched. Skip it once with `LEFTHOOK=0 git commit ...`, or format by hand with `./gradlew spotlessApply`. For format-on-save, install the IntelliJ ktfmt plugin and set it to the kotlinlang style with a max line length of 120.

The initial whole-repo reformat is listed in `.git-blame-ignore-revs`. GitHub skips it automatically; for local `git blame`, run once:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

## Continuous integration

GitHub Actions runs `./gradlew build koverVerify --no-daemon --console=plain --stacktrace`.
The Gradle `check` lifecycle already includes `spotlessCheck`, so formatting and tests
are checked in one invocation. The explicit `koverVerify` task also enforces coverage
and generates HTML/XML reports. The wrapper download has a SHA-256 checksum, a
30-second network timeout and three retries. `setup-gradle` also validates the wrapper
JAR before execution.

CI uses Ubuntu 24.04, Temurin 25.0.4.1+1, commit-pinned actions, UTC, read-only repository
permissions and a 20-minute job timeout. Only main/develop runs may write the Gradle
cache. Dependabot maintains action pins; review JDK and wrapper updates explicitly.
The hosted runner image and Testcontainers image tags still receive upstream updates.
A newer run cancels an obsolete run for the same event and PR/branch; workflow dispatch
supports manual runs.

The `backend-test-reports` artifact contains the Gradle console log, HTML reports,
JUnit XML and generated OpenAPI document, including on failures, for seven days.
Docker availability is checked before building, and failed jobs print container state.
The required check name remains `Build & Test`.

PR security scanners run in a separate workflow, without integration tests. On pushes
to `main`, Security calls this CI workflow via `workflow_call` and publishes the scanned
image only after tests and all security checks succeed for the same commit. CI still runs
directly for PRs, pushes to `develop`, and manual dispatch; it does not duplicate the main
push build. Container scans run on PR/main/manual events, not the weekly schedule. See
[security scanning](./security.md) for PR/main/weekly coverage, report artifacts,
required-check configuration and vulnerability exceptions.


## Paired PR evidence

Breaking API changes and consumer-visible semantic changes require a paired
[scheduler-dashboard PR](https://github.com/s24407-pj/scheduler-dashboard/pulls) before merge.
Other relevant changes require explicit compatibility evidence proportionate to their
consumer impact; purely internal changes do not require a dashboard PR. Documentation
and test-only changes may omit paired verification with a rationale covering the entire
diff. Record the classification and rationale in the existing PR template.

Use the PR templates in both repositories to record:

- Reciprocal backend/dashboard PR links, the full backend PR head SHA and dashboard SHA.
- A successful Dashboard CI run for that dashboard revision, with
  `.github/scheduler-api-revision` equal to the exact backend PR head.
- Consumer-visible changes and verification: name tests/scenarios and results,
  including semantics OpenAPI cannot express, such as session/authorization scope,
  errors and retry behavior, booking rules, time/pricing calculations or data visibility.
- Merge/deployment order and a compatible rollback approach, including write
  maintenance and client refresh requirements for incompatible changes.

The dashboard must regenerate `openapi/openapi.json` and `src/api/schema.d.ts` using
`SCHEDULER_API_ROOT=/path/to/matching/scheduler bun run api:generate` from its repository
root, and commit them with the pin. If semantic-only adoption leaves generated files
unchanged, explain that result and why existing tests suffice or add regression coverage.
A paired dashboard PR can consist of a pin update and regeneration when existing
consumer code already supports the change.

Reviewers must verify the reciprocal links, exact pin, successful CI and semantic
coverage before merge. Refresh evidence after either PR changes. Passing backend CI
does not establish dashboard compatibility; the dashboard's OpenAPI drift check alone
cannot verify semantic changes. This is a reviewer-enforced merge requirement;
existing CI does not enforce the presence or adequacy of paired evidence.

After a squash merge, update the dashboard pin to the resulting backend commit,
regenerate and rerun CI before deployment. Require successful final Dashboard CI
against the backend commit selected for deployment. Record the final revisions and
run in the PRs; see [consumer compatibility before promotion](./deployment.md#consumer-compatibility-before-promotion).

## Company-local customer baseline (2026-10-24)

The authorized fresh schema consolidates former V1–V4 into `V1__init_schema.sql` and removes global customer accounts. Existing databases are incompatible: stop the old application, explicitly recreate only the intended development database, and clear that environment's Redis session/OTP/cache data before applying V1. Startup does not reset databases. Do not bypass Flyway checksums or mark the old schema as migrated. This baseline provides no data-preserving upgrade.

Deploy clients and API together. OTP bodies now require a salon `companyId`; customer `/api/auth/me` includes that company. Protected customer writes require `X-Expected-Session-Context: v1:<userId>:<companyId>:null:ROLE_CUSTOMER`. Names are required at the first successful booking, and pending OTP identities are not listed to staff. Staff login and its existing context format remain unchanged.
