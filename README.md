# Scheduler

Spring Boot API for a beauty and cosmetology salon scheduler. Customers book with SMS OTP; staff use a separate TanStack Start dashboard with Spring Session authentication.

**Related local projects:**

- `../../scheduler-dashboard` — current staff dashboard repository (Bun/TanStack Start)
- `../scheduler-dashboard-start` and `../scheduler-dashboard` — older frontend prototypes with obsolete JWT contracts

## Features

- Customer booking with SMS OTP login and Redis-backed HttpOnly sessions
- Company-scoped staff login (email + password) with role-based access (`OWNER`, `EMPLOYEE`)
- Availability calculation from work schedules, reservations, and schedule blocks
- Offering catalog with categories and image uploads (Cloudflare R2)
- SMS notifications (confirmation, cancellation, reminders)
- Public booking endpoints (salon profile, bookable employees per offering)
- No-show tracking with company-scoped customer blocking
- Last-minute discounts and minimum booking advance settings

## Quick Start

Requires Docker (or rootless Podman) with Compose.

```bash
docker compose up --build
curl -sf http://localhost:8080/actuator/health
```

The API runs at `http://localhost:8080` with the `dev` profile, which seeds a company, a catalog, and two staff accounts:

| Role     | Email                        | Password      |
| -------- | ---------------------------- | ------------- |
| Owner    | `gabinet@kacosmetology.pl`   | `admin123`    |
| Employee | `pracownik@kacosmetology.pl` | `employee123` |

Customer OTP codes are not logged; development instructions explain how to inspect the local Redis key. The seed has no work schedules, so set one before expecting available slots.

Running on the host, configuration, seed details, and testing: [docs/development.md](./docs/development.md).

## Commands

| Command | Description |
| ------- | ----------- |
| `./gradlew test` | Run all tests (needs Docker/Podman for Testcontainers) |
| `./gradlew build` | Build including tests |
| `./gradlew build -x test` | Build without tests |
| `docker compose up --build` | Run full stack |
| `docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build` | Dev profile + debugger on `:5005` |

## Documentation

- [docs/](./docs/README.md) — architecture, reservations, availability, time handling, API reference, decision records
- [perf-tests/](./perf-tests/README.md) — k6 load and stress tests
- [AGENTS.md](./AGENTS.md) — instructions for AI coding agents
