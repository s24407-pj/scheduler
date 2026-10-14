# Testy wydajnościowe (k6)

Testy obciążeniowe API aplikacji Scheduler napisane w [k6](https://grafana.com/docs/k6/).

## Wymagania

Zainstaluj k6:

```bash
# macOS
brew install k6

# Windows (Chocolatey)
choco install k6

# Windows (winget)
winget install k6 --source winget

# Docker (bez instalacji)
docker run --rm -i --network host grafana/k6 run - <scripts/smoke-test.js
```

## Uruchomienie

Upewnij się, że aplikacja działa (np. `docker compose up -d`), a następnie:

```bash
# Smoke test — szybka walidacja (1-2 VU, 30s)
k6 run perf-tests/scripts/smoke-test.js

# Load test — normalny ruch (do 50 VU, 8 min)
k6 run perf-tests/scripts/load-test.js

# Stress test — szukanie limitu (do 300 VU, 15 min)
k6 run perf-tests/scripts/stress-test.js

# Spike test — nagły skok obciążenia
k6 run perf-tests/scripts/spike-test.js
```

## Konfiguracja

Zmienne środowiskowe:

| Zmienna    | Domyślnie                  | Opis              |
|------------|----------------------------|--------------------|
| `BASE_URL` | `http://localhost:8080`    | URL backendu       |
| `STAFF_EMAIL` | `tomek@barbershop.pl`  | Email pracownika   |
| `STAFF_PASSWORD` | `admin123`           | Hasło pracownika   |

Przykład:

```bash
k6 run -e BASE_URL=http://staging:8080 perf-tests/scripts/load-test.js
```

## Scenariusze

| Scenariusz   | VU      | Czas    | Cel                                           |
|-------------|---------|---------|-----------------------------------------------|
| Smoke       | 1-2     | 30s     | Walidacja, że środowisko działa               |
| Load        | 0→50    | 8 min   | Normalny ruch, sprawdzenie p95 < 500ms        |
| Stress      | 0→300   | 22 min  | Znalezienie punktu złamania                   |
| Spike       | 10→200  | 7 min   | Zachowanie pod nagłym skokiem                  |

## Progi (thresholds)

Każdy scenariusz ma zdefiniowane progi akceptacji:
- **Smoke**: p95 < 1s, error rate < 1%
- **Load**: p95 < 500ms, error rate < 1%
- **Stress**: p95 < 2s, error rate < 5%
- **Spike**: p95 < 2s, error rate < 10%


Authentication uses normal session cookies and CSRF. Setup logs in with `STAFF_EMAIL` / `STAFF_PASSWORD` (and `STAFF_EMPLOYMENT_ID` when multiple employments exist), refreshes CSRF after login, and obtains company scope from `/api/auth/me`. k6 setup and VUs have separate cookie jars, so setup passes the test client's cookie values to request parameters. Never log setup data or cookies. These scripts do not model browser SameSite/CORS enforcement; session/security integration tests cover those boundaries.
