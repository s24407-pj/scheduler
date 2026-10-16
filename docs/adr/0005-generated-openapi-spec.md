# ADR-0005: Generated OpenAPI specification

**Status:** Accepted — implemented

Authentication references below describe the original credential scheme. [ADR-0011](./0011-spring-session-redis.md) replaces it with cookie sessions and CSRF; the error contract/generated documentation decision remains accepted.

## Context

The API has about 50 endpoints. Their contracts were described only in prose and in the hand-maintained [endpoint index](../api/endpoints.md), which drifts as controllers change. Frontends hand-write their request and response types.

## Decision

- Use **springdoc-openapi** (`springdoc-openapi-starter-webmvc-ui`, the 3.x line for Spring Boot 4) to generate an OpenAPI 3 spec from controllers, DTOs, and Jakarta Validation annotations.
- `/v3/api-docs` and Swagger UI are **disabled by default** (`application.yaml`) and enabled only in the `dev` profile (`application-dev.yaml`). `SecurityConfig` permits the docs paths; with the docs disabled they return 404.
- `config/OpenApiConfig.kt` declares the API title and the HttpOnly SESSION cookie and X-XSRF-TOKEN security schemes, with explicit public/protected per-operation requirements. Local appointment times use an offset-free local-date-time schema.
- Kotlin KDoc is not read by springdoc. Add `@Operation(summary = ...)` and `@ApiResponse` annotations only where the generated output is unclear, mainly for documenting error responses with their `code` values.
- [endpoints.md](../api/endpoints.md) stays as a short index of endpoints and authorization rules; request and response shapes live in the generated spec.

## Alternatives considered

- **Hand-written OpenAPI YAML.** Full control, but it drifts exactly like the prose docs.
- **Keep prose only.** Lowest effort, highest drift.

## Consequences

- One new dependency. Production exposes no API docs unless `springdoc.api-docs.enabled` is set explicitly.
- Validation constraints in the spec stay accurate automatically.
- OpenApiIntegrationTest verifies required submission/revision headers, cookie/CSRF behavior, exact lifecycle enum and local-time schemas, and exports `build/generated/openapi.json` for client generation.
