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

```bash
docker compose up --build
curl -sf http://localhost:8080/actuator/health
```

Compose starts PostgreSQL 18, Redis 8, and the app with the `dev` profile (override with `SPRING_PROFILE` in `.env`). Copy `.env.example` to `.env` to change values.

Remote debugging on port 5005, with CORS for `:5173`, `:5174`, and `:3000`:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
```

### App on the host

Start only the databases, then run the app with the `dev` profile:

```bash
docker compose up -d db redis
./gradlew bootRun --args='--spring.profiles.active=dev'
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
# Fresh seeded database: companyId=1. For an existing database use its seeded company ID.
# jq is used only to extract the CSRF token; the session identifier is never read.
csrf=$(curl -fsS -c /tmp/scheduler-cookies http://localhost:8080/api/auth/csrf | jq -r .token)
curl -fsS -b /tmp/scheduler-cookies -c /tmp/scheduler-cookies \
  http://localhost:8080/api/auth/login-staff \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
  -d '{"companyId":1,"email":"gabinet@kacosmetology.pl","password":"admin123"}'
# Authentication clears CSRF: refresh before any further mutation.
csrf=$(curl -fsS -b /tmp/scheduler-cookies -c /tmp/scheduler-cookies \
  http://localhost:8080/api/auth/csrf | jq -r .token)
curl -fsS -b /tmp/scheduler-cookies http://localhost:8080/api/auth/me
curl -fsS -b /tmp/scheduler-cookies -c /tmp/scheduler-cookies -X POST \
  -H "X-XSRF-TOKEN: $csrf" http://localhost:8080/api/auth/logout
rm -f /tmp/scheduler-cookies
```

For customers, obtain CSRF first, call `request-code`, then `verify-code` with the same cookie jar/header. In development inspect the local Redis `otp:<canonical-phone>` key; codes are never logged. Verification needs only phone and six-digit code; names are collected atomically with the first successful salon booking. Refresh CSRF after verification. Authentication JSON identifies the authenticated account and contains no credential. Browser clients use `credentials: 'include'`; follow the [frontend contract](./architecture.md#csrf-cookies-and-frontend-contract).


## Configuration

Environment variables override `application.yaml`. Spring's relaxed binding means any property can be set as an upper-case variable (for example `SPRING_DATASOURCE_URL`); the table lists the variables the project uses.

| Variable | Default | Purpose |
| -------- | ------- | ------- |
| `SPRING_PROFILE` | `dev` | Compose only: maps to `SPRING_PROFILES_ACTIVE` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/scheduler_db` | Set by Compose to the `db` container |
| `DB_USERNAME`, `DB_PASSWORD` | `postgres` / `password` | Database credentials (Compose also passes them as `SPRING_DATASOURCE_USERNAME/PASSWORD`) |
| `REDIS_HOST`, `REDIS_PORT` | `localhost` / `6379` | Redis (Compose sets `SPRING_DATA_REDIS_HOST/PORT` instead) |
| `SPRING_SESSION_TIMEOUT` | `24h` | Anonymous session default; authenticated customers have absolute 24h expiry and staff have 24h inactivity expiry |
| `SPRING_SESSION_DATA_REDIS_NAMESPACE` | `scheduler:sessions` | Application-specific session namespace; share between API instances |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | request HTTPS detection; true in prod | Keep true in production; no cookie Domain; SameSite=Lax |
| `CORS_ORIGINS` | `http://localhost:5173` | Comma-separated allowed origins |
| `OTP_GLOBAL_DAILY_SMS_CAP` | 0 outside prod | Positive operator-configured shared SMS attempt budget required in prod, UTC calendar day; OTP issuance reserves the first attempt and definite-temporary retries reserve again. Fixed OTP rules: 5-minute lifetime, 60-second cooldown, 3 failed attempts, 3 accepted phone issuances/rolling 5 minutes, 10 issuance requests/IP/rolling 15 minutes, 10 verification requests/IP/rolling 5 minutes. |
| `TRUSTED_PROXIES` | empty | Regex of reverse-proxy IPs whose `X-Forwarded-For` is trusted for the client IP; empty trusts none |
| `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES` | 10 / 1 | Staff logins per IP per window (not passed through by Compose; edit `docker-compose.yml` to override) |
| `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`, `R2_PUBLIC_URL` | endpoint and bucket preset, keys empty | Image storage. Without keys the app starts but uploads fail. |
| `TZ` | `Europe/Warsaw` in Compose | Container OS zone; the app sets its JVM default zone to Europe/Warsaw regardless |

Profiles:

- `dev` (`application-dev.yaml`): SQL logging (`org.hibernate.SQL` at DEBUG), DEBUG for the app and Spring Security, extra actuator endpoints, seed data, Swagger UI.
- `prod` (`application-prod.yaml`): INFO/WARN logging as ECS JSON on the console, no SQL logging. `ConsoleSmsSender` is disabled, so a real `SmsSender` is required.

Every request gets an ID (`RequestIdFilter`): a valid incoming `X-Request-Id` (up to 64 characters of `[A-Za-z0-9-]`) is reused, otherwise a UUID is generated. It is returned in the `X-Request-Id` response header and stored in the log MDC as `requestId`, so plain-text log lines show it in brackets and ECS logs carry it as a field.
- `test`: set by Gradle for every test run; uses local test infrastructure.

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

## Formatting

Kotlin sources and Gradle scripts are formatted with ktfmt (kotlinlang style, 120 columns) through Spotless. `./gradlew build` and CI run `spotlessCheck` and fail on unformatted code.

The lefthook pre-commit hook (`lefthook.yml`) runs `./gradlew spotlessApply` whenever a `.kt`/`.kts` file is staged and re-stages the formatted files, so commits are always formatted. It runs Gradle through `mise exec` so it also works from IDE commits outside a mise-activated shell. Unstaged hunks of partially staged files are left untouched. Skip it once with `LEFTHOOK=0 git commit ...`, or format by hand with `./gradlew spotlessApply`. For format-on-save, install the IntelliJ ktfmt plugin and set it to the kotlinlang style with a max line length of 120.

The initial whole-repo reformat is listed in `.git-blame-ignore-revs`. GitHub skips it automatically; for local `git blame`, run once:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```
