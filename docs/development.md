# Development

## Prerequisites

- **Docker or rootless Podman** with Compose. Integration tests need it too (Testcontainers).
- **JDK 25**, pinned in `mise.toml`. Run `mise install` once; mise activates it inside the repo. The Gradle wrapper downloads Gradle.
- With rootless Podman, export `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock` (for example in `~/.zshrc`). Non-interactive shells that do not load `~/.zshrc` need `zsh -ic '<command>'`.

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

In the `dev` profile, Swagger UI is at <http://localhost:8080/swagger-ui.html> and the OpenAPI JSON at <http://localhost:8080/v3/api-docs>. Use **Authorize** with a token from [Getting tokens](#getting-tokens).

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

### Getting tokens

```bash
# Staff
curl -s localhost:8080/api/auth/login-staff -H 'Content-Type: application/json' \
  -d '{"email":"gabinet@kacosmetology.pl","password":"admin123"}'

# Customer: request a code, read it from the app log ("MOCK SMS to: ... | OTP code: ..."), verify
curl -s localhost:8080/api/auth/request-code -H 'Content-Type: application/json' -d '{"phoneNumber":"+48500600700"}'
docker compose logs app | grep 'OTP code'
```

```bash
curl -s localhost:8080/api/auth/verify-code -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"+48500600700","code":"<code>","firstName":"Anna","lastName":"Nowak"}'
```

`firstName` and `lastName` are needed only when the phone number has no account yet.

## Configuration

Environment variables override `application.yaml`. Spring's relaxed binding means any property can be set as an upper-case variable (for example `SPRING_DATASOURCE_URL`); the table lists the variables the project uses.

| Variable | Default | Purpose |
| -------- | ------- | ------- |
| `SPRING_PROFILE` | `dev` | Compose only: maps to `SPRING_PROFILES_ACTIVE` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/scheduler_db` | Set by Compose to the `db` container |
| `DB_USERNAME`, `DB_PASSWORD` | `postgres` / `password` | Database credentials (Compose also passes them as `SPRING_DATASOURCE_USERNAME/PASSWORD`) |
| `REDIS_HOST`, `REDIS_PORT` | `localhost` / `6379` | Redis (Compose sets `SPRING_DATA_REDIS_HOST/PORT` instead) |
| `JWT_SECRET`, `JWT_EXPIRATION_MS` | dev secret / 24 h | Token signing and session lifetime. Startup fails if the secret is under 32 bytes, or is the dev default outside the `dev`/`test` profiles |
| `CORS_ORIGINS` | `http://localhost:5173` | Comma-separated allowed origins |
| `OTP_TTL_MINUTES` | 5 | OTP lifetime |
| `OTP_MAX_ATTEMPTS`, `OTP_RATE_WINDOW_MINUTES` | 3 / 5 | OTP requests per phone per window |
| `OTP_VERIFICATION_MAX_ATTEMPTS` | 3 | Failed attempts per code |
| `OTP_VERIFICATION_IP_MAX_ATTEMPTS`, `OTP_VERIFICATION_IP_RATE_WINDOW_MINUTES` | 10 / 5 | Verifications per IP per window |
| `OTP_REQUEST_IP_MAX_ATTEMPTS`, `OTP_REQUEST_IP_RATE_WINDOW_MINUTES` | 10 / 15 | OTP send requests per IP per window |
| `TRUSTED_PROXIES` | empty | Regex of reverse-proxy IPs whose `X-Forwarded-For` is trusted for the client IP; empty trusts none |
| `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES` | 10 / 1 | Staff logins per IP per window (not passed through by Compose; edit `docker-compose.yml` to override) |
| `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`, `R2_PUBLIC_URL` | endpoint and bucket preset, keys empty | Image storage. Without keys the app starts but uploads fail. |
| `TZ` | `Europe/Warsaw` in Compose | Container OS zone; the app sets its JVM default zone to Europe/Warsaw regardless |

Profiles:

- `dev` (`application-dev.yaml`): SQL logging, DEBUG for the app and Spring Security, extra actuator endpoints, seed data, Swagger UI.
- `prod` (`application-prod.yaml`): INFO/WARN logging, no SQL logging.
- `test`: set by Gradle for every test run; allows the dev `JWT_SECRET` default.

## Testing

```bash
./gradlew test                                   # everything (needs Docker/Podman)
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest"
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest.full customer registration and login via SMS should return token"
```

Every feature needs a service unit test and an HTTP integration test. Test names are backtick strings in English or Polish.

| Kind | Naming | Setup | Use for |
| ---- | ------ | ----- | ------- |
| Service unit test | `*ServiceTest` | `@ExtendWith(MockKExtension::class)`, MockK mocks for repositories | Business rules, branches, error cases |
| Integration test | `*IntegrationTest` | `@SpringBootTest`, `@AutoConfigureMockMvc`, `@Import(TestcontainersConfiguration::class)`; `@MockkBean` for `SmsSender` and `S3Client` | Endpoints end to end: security, validation, status codes, persistence |
| Repository test | `*RepositoryTest` | `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = NONE)`, `@Import(TestcontainersConfiguration::class)` | Custom JPQL queries and database constraints |

`TestcontainersConfiguration` starts `postgres:18-alpine` and `redis:8-alpine` with `@ServiceConnection`, so no connection properties are needed. Flyway runs the real migrations against the container.

Load tests (k6, Prometheus, Grafana) are described in [`perf-tests/README.md`](../perf-tests/README.md).
