# Beduno Backend

Worker housing management system for temporary work agencies.

**Stack**: Java 21 · Spring Boot 3.4 · PostgreSQL 16 · Flyway · JWT

---

## Prerequisites

- Java 21
- Docker (for local PostgreSQL and integration tests)

---

## Quick Start

```bash
# 1. Start PostgreSQL
docker compose -f docker/docker-compose.yml up -d

# 2. Run the application (the dev profile supplies a JWT signing key;
#    the default profile deliberately has none - see Environment Variables)
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun

# API available at http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html
```

---

## Environment Variables

Default / dev profile (no `SPRING_PROFILES_ACTIVE`, or anything other than `prod`):

> **`JWT_SECRET` has no fallback outside the `dev` profile.** A missing value fails
> startup rather than silently signing tokens with a key committed to this repository,
> which anyone could use to forge a token for any agency and role. Run locally with
> `SPRING_PROFILES_ACTIVE=dev`, or export `JWT_SECRET` yourself.

| Variable | Default (dev) | Description |
|----------|---------------|-------------|
| `JWT_SECRET` | `beduno-dev-secret-key-...` (**`dev` profile only**) | HS256 signing key (min 256 bits) |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/beduno` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `beduno` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | `beduno` | DB password |
| `SPRING_PROFILES_ACTIVE` | — | Set to `prod` for JSON structured logs |

**`prod` profile** (`SPRING_PROFILES_ACTIVE=prod`): `application-prod.yml` reads a
different, non-overlapping set of variables. The `SPRING_DATASOURCE_*` variables
above are **not read under this profile** — using them will fail to start.

| Variable | Description |
|----------|-------------|
| `JWT_SECRET` | HS256 signing key (min 256 bits) |
| `DATABASE_URL` | JDBC URL |
| `DATABASE_USERNAME` | DB username |
| `DATABASE_PASSWORD` | DB password |
| `CORS_ALLOWED_ORIGINS` | Comma-separated origin allowlist. Empty (the default) registers no CORS mapping at all |

---

## Build & Test

```bash
./gradlew compileJava          # Compile only
./gradlew test                 # Full test suite (requires Docker for Testcontainers)
./gradlew build                # Compile + checkstyle + test + jar
```

`./gradlew build` (and the `check` task) runs Checkstyle against
`config/checkstyle/checkstyle.xml`; violations fail the build.

---

## Docker

```bash
# Build production image
docker build -f docker/Dockerfile -t beduno-be:latest .

# Run (prod profile — uses DATABASE_*, not SPRING_DATASOURCE_*, see Environment Variables above)
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e JWT_SECRET=<your-secret> \
  -e DATABASE_URL=jdbc:postgresql://<host>:5432/beduno \
  -e DATABASE_USERNAME=beduno \
  -e DATABASE_PASSWORD=<password> \
  beduno-be:latest
```

---

## API Overview

All endpoints are under `/api/v1/`. Full interactive docs at `/swagger-ui.html`.

### Auth

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/auth/login` | Authenticate, receive access + refresh tokens |
| `POST` | `/api/v1/auth/refresh` | Exchange refresh token for a new token pair |
| `GET`  | `/api/v1/auth/me` | Current user profile |

Auth endpoints are rate-limited to **10 requests/minute per IP**. The limit is
keyed on any path under `/api/v1/auth/**`, so it also throttles `GET /auth/me`
(which itself requires a valid token, unlike `/login` and `/refresh`).
Rate-limit responses (`429`) use the standard `ErrorResponse` JSON envelope, the
same shape used for `401`/`403`/`409`/`422` responses throughout the API.

Failed authentication (bad credentials on login, invalid/expired refresh token)
returns **401** with an `ErrorResponse` body. Any request to a protected endpoint
without a valid token also returns **401**; a valid token with an insufficient
role returns **403** — both with the same `ErrorResponse` envelope.

### Workers

| Method | Path | Roles |
|--------|------|-------|
| `GET`    | `/api/v1/workers` | All |
| `GET`    | `/api/v1/workers/{id}` | All |
| `POST`   | `/api/v1/workers` | AGENCY_ADMIN |
| `PUT`    | `/api/v1/workers/{id}` | AGENCY_ADMIN |
| `DELETE` | `/api/v1/workers/{id}` | AGENCY_ADMIN |
| `POST`   | `/api/v1/workers/import` | AGENCY_ADMIN (CSV upload) |

### Properties & Rooms

| Method | Path | Roles |
|--------|------|-------|
| `GET`    | `/api/v1/properties`, `/api/v1/properties/{id}` | AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK |
| `POST`   | `/api/v1/properties` | AGENCY_ADMIN |
| `PUT`    | `/api/v1/properties/{id}` | AGENCY_ADMIN, PROPERTY_ADMIN (own assigned property only) |
| `DELETE` | `/api/v1/properties/{id}` | AGENCY_ADMIN. Hard delete guarded by a `409` conflict check if the property still has rooms or referencing stays — this is not a soft delete. |
| `GET`    | `/api/v1/properties/{id}/rooms`, `/api/v1/properties/{id}/rooms/{roomId}` | AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK |
| `POST`   | `/api/v1/properties/{id}/rooms` | AGENCY_ADMIN, PROPERTY_ADMIN |
| `PUT`    | `/api/v1/properties/{id}/rooms/{roomId}` | AGENCY_ADMIN, PROPERTY_ADMIN |
| `DELETE` | `/api/v1/properties/{id}/rooms/{roomId}` | AGENCY_ADMIN. Hard delete guarded by a `409` conflict check if any stay references the room. |

AGENCY_PLANNER can only read properties and rooms — it has no create/update/delete
access on either.

### Stays

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/v1/stays` | List stays (filter by worker, property, status, dates) |
| `GET`    | `/api/v1/stays/{id}` | Get a single stay |
| `POST`   | `/api/v1/stays` | Plan a stay — runs constraint engine |
| `PUT`    | `/api/v1/stays/{id}` | Update stay — runs constraint engine |
| `DELETE` | `/api/v1/stays/{id}` | Cancel stay |
| `GET`    | `/api/v1/stays/arrivals` | Expected arrivals for a property |
| `POST`   | `/api/v1/stays/{id}/check-in` | Check in — runs constraint engine |
| `POST`   | `/api/v1/stays/{id}/check-out` | Check out |
| `POST`   | `/api/v1/stays/{id}/no-show` | Mark no-show (reason stored in a dedicated `no_show_reason` field, doesn't overwrite `notes`) |
| `POST`   | `/api/v1/stays/{id}/move` | Move to another room (atomic) — runs constraint engine. Returns `409` if attempted on the stay's final day (no night left to reassign). |
| `POST`   | `/api/v1/stays/bulk-assign` | Bulk plan multiple stays — runs constraint engine per item |
| `POST`   | `/api/v1/stays/bulk-checkout` | Bulk checkout multiple stays |

### Occupancy

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/properties/{id}/occupancy` | Room-by-room occupants |
| `GET` | `/api/v1/properties/{id}/exceptions` | Over-capacity / unassigned |
| `GET` | `/api/v1/properties/{id}/inspection` | Nightly inspection roster (PROPERTY_ADMIN only) |
| `POST` | `/api/v1/properties/{id}/inspection` | Submit discrepancy report (PROPERTY_ADMIN only) |
| `GET` | `/api/v1/properties/{id}/occupancy/export` | CSV export (`?language=EN\|PL\|DE\|RU\|UA`, defaults to `EN`) |
| `GET` | `/api/v1/properties/{id}/arrivals/export` | CSV export (`?language=EN\|PL\|DE\|RU\|UA`, defaults to `EN`) |
| `GET` | `/api/v1/properties/{id}/exceptions/export` | CSV export (`?language=EN\|PL\|DE\|RU\|UA`, defaults to `EN`) |

All five languages (EN, PL, DE, RU, UA) are fully translated for exports.

### Audit

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/audit` | Paginated audit trail (filter by entity, actor, dates). AGENCY_ADMIN and AGENCY_PLANNER only. |

---

## Roles

| Role | Access |
|------|--------|
| `AGENCY_ADMIN` | Full access across the agency |
| `AGENCY_PLANNER` | Read workers, plan/view/update stays, view properties/rooms, `POST /stays/bulk-checkout`, full `GET /audit` |
| `PROPERTY_ADMIN` | Update own assigned property, manage rooms, check-in/out/move/no-show, inspection roster and reports, plan/view stays, view properties |
| `FRONT_DESK` | Check-in/out/move/no-show, `GET /properties/{id}/exceptions`, `GET .../arrivals/export`, `POST /stays/bulk-checkout`, read-only otherwise |

**Property scoping is limited.** A `PROPERTY_ADMIN`'s "assigned properties" list
(`assignedPropertyIds` on the JWT) is only actually enforced on two endpoints:
`PUT /properties/{id}` and room create/update (`POST`/`PUT /properties/{id}/rooms/...`).
Everywhere else — stays (including check-in/out/move/no-show), occupancy views,
exceptions, inspection, and all CSV exports — access is agency-wide regardless of
assigned properties. Treat property assignment as advisory outside those two
enforced paths.

---

## Constraint Engine

Stay creation, updates, check-in, move, and bulk-assign all run a constraint
engine before persisting:

- **CapacityConstraint** (hard) — room capacity minus blocked spots
- **DoubleBookingConstraint** (hard) — same worker overlapping stays
- **BlockedRoomConstraint** (hard) — room or property not in ACTIVE status
- **GenderConstraint** (soft) — gender rule on room

Both hard and soft violations return `HTTP 422`. They are distinguished by the
error's message code: `error.constraint.violated` for a hard violation (cannot be
overridden), `error.constraint.soft_violations` for a soft one. Re-submit a soft
violation with `overrideReason` to force the operation.

---

## CORS

`WebConfig` maps `/api/**` against an explicit origin allowlist bound to
`beduno.cors.allowed-origins` (env `CORS_ALLOWED_ORIGINS`, comma-separated). It
defaults to `http://localhost:3000,http://localhost:5173` for local development.

**An empty list registers no CORS mapping at all**, so a deployment that sets no
origins rejects every cross-origin request instead of falling back to something
permissive. `/actuator/**` and `/v3/api-docs` are not covered by this mapping.

---

## Health Check

```
GET /actuator/health
```

Permitted without authentication. Anonymous callers only see `{"status":"UP"}`
(`management.endpoint.health.show-details: when-authorized` hides component
details unless the caller is authorized). `/actuator/info` is exposed but is
**not** on the security permit list, so it requires authentication like any
other endpoint. Only `health` and `info` are exposed via
`management.endpoints.web.exposure.include` — there is no `/actuator/metrics`.
