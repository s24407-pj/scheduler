# Scheduler

Spring Boot backend for a beauty and cosmetology salon scheduling system. Customers book via SMS OTP authentication; owners and employees manage reservations through a separate admin dashboard.

**Related projects:**

- [scheduler-dashboard](../scheduler-dashboard) — React admin UI for owners and employees
- [kacosmetology](../kacosmetology) — public salon website

## Features

- Customer booking with SMS OTP login and JWT sessions
- Staff login (email + password) with role-based access (`OWNER`, `EMPLOYEE`)
- Availability calculation from work schedules, reservations, and schedule blocks
- Offering catalog with categories and image uploads (Cloudflare R2)
- SMS notifications (confirmation, cancellation, reminders)
- WhatsApp booking bot (Meta webhook + Redis conversation state)
- No-show tracking with company-scoped customer blocking
- Last-minute discounts and minimum booking advance settings

## Prerequisites

- [Docker](https://www.docker.com/) and Docker Compose (recommended)
- [Java 25](https://adoptium.net/) and Gradle (for local development without Docker)

Integration tests require Docker — Testcontainers starts PostgreSQL and Redis automatically.

## Quick Start

```bash
docker compose up --build
```

The API is available at `http://localhost:8080`. With the default `dev` profile, sample data is seeded on startup.

### Dev accounts

| Role     | Email                        | Password      |
| -------- | ---------------------------- | ------------- |
| Owner    | `gabinet@kacosmetology.pl`   | `admin123`    |
| Employee | `pracownik@kacosmetology.pl` | `employee123` |

Customer OTP codes are logged to the console (`ConsoleSmsSender` in dev).

### Remote debugging

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
```

Attaches a JDWP debugger on port `5005`.

## Local Development (without Docker)

Start PostgreSQL and Redis locally, then:

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Default connection settings are in `src/main/resources/application.yaml`.

## Commands

| Command | Description |
| ------- | ----------- |
| `./gradlew test` | Run all tests |
| `./gradlew build` | Build including tests |
| `./gradlew build -x test` | Build without tests |
| `docker compose up --build` | Run full stack |
| `docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build` | Dev profile + debugger |

Run a single test class:

```bash
./gradlew test --tests "pl.kacosmetology.scheduler.auth.AuthFlowIntegrationTest"
```

## Configuration

Environment variables override values in `application.yaml`. Key variables for Docker Compose:

| Variable | Description |
| -------- | ----------- |
| `JWT_SECRET`, `JWT_EXPIRATION_MS` | JWT signing |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | PostgreSQL |
| `SPRING_DATA_REDIS_HOST/PORT` | Redis |
| `CORS_ORIGINS` | Allowed frontend origins |
| `OTP_TTL_MINUTES`, `OTP_MAX_ATTEMPTS`, `OTP_RATE_WINDOW_MINUTES` | SMS OTP limits |
| `LOGIN_MAX_ATTEMPTS`, `LOGIN_RATE_WINDOW_MINUTES` | Staff login rate limiting |
| `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `R2_BUCKET_NAME`, `R2_PUBLIC_URL` | Cloudflare R2 |
| `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_COMPANY_ID`, `WHATSAPP_SENDER` | WhatsApp bot |

## Performance Tests

Load and stress tests using [k6](https://grafana.com/docs/k6/) are in [`perf-tests/`](./perf-tests/README.md).

## AI Agents

For AI coding agents, see [AGENTS.md](./AGENTS.md).
