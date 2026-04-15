# Bedok Backend

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

# 2. Run the application
./gradlew bootRun

# API available at http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html
```

---

## Environment Variables

| Variable | Default (dev) | Description |
|----------|---------------|-------------|
| `JWT_SECRET` | `bedok-dev-secret-key-...` | HS256 signing key (min 256 bits) |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/bedok` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `bedok` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | `bedok` | DB password |
| `SPRING_PROFILES_ACTIVE` | — | Set to `prod` for JSON structured logs |

---

## Build & Test

```bash
./gradlew compileJava          # Compile only
./gradlew test                 # Full test suite (requires Docker for Testcontainers)
./gradlew build                # Compile + checkstyle + test + jar
```

---

## Docker

```bash
# Build production image
docker build -f docker/Dockerfile -t bedok-be:latest .

# Run
docker run -p 8080:8080 \
  -e JWT_SECRET=<your-secret> \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/bedok \
  -e SPRING_DATASOURCE_USERNAME=bedok \
  -e SPRING_DATASOURCE_PASSWORD=<password> \
  bedok-be:latest
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

Auth endpoints are rate-limited to **10 requests/minute per IP**.

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
| `GET/POST/PUT/DELETE` | `/api/v1/properties` | Admin/Planner/PropertyAdmin |
| `GET/POST/PUT/DELETE` | `/api/v1/properties/{id}/rooms` | Admin/PropertyAdmin |

### Stays

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/v1/stays` | List stays (filter by worker, property, status, dates) |
| `POST`   | `/api/v1/stays` | Plan a stay — runs constraint engine |
| `PUT`    | `/api/v1/stays/{id}` | Update stay |
| `DELETE` | `/api/v1/stays/{id}` | Cancel stay |
| `GET`    | `/api/v1/stays/arrivals` | Expected arrivals for a property |
| `POST`   | `/api/v1/stays/{id}/check-in` | Check in |
| `POST`   | `/api/v1/stays/{id}/check-out` | Check out |
| `POST`   | `/api/v1/stays/{id}/no-show` | Mark no-show |
| `POST`   | `/api/v1/stays/{id}/move` | Move to another room (atomic) |
| `POST`   | `/api/v1/stays/bulk-assign` | Bulk plan multiple stays |
| `POST`   | `/api/v1/stays/bulk-checkout` | Bulk checkout multiple stays |

### Occupancy

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/properties/{id}/occupancy` | Room-by-room occupants |
| `GET` | `/api/v1/properties/{id}/exceptions` | Over-capacity / unassigned |
| `GET` | `/api/v1/properties/{id}/inspection` | Nightly inspection roster |
| `POST` | `/api/v1/properties/{id}/inspection` | Submit discrepancy report |
| `GET` | `/api/v1/properties/{id}/occupancy/export` | CSV export (lang=EN\|PL) |
| `GET` | `/api/v1/properties/{id}/arrivals/export` | CSV export (lang=EN\|PL) |
| `GET` | `/api/v1/properties/{id}/exceptions/export` | CSV export (lang=EN\|PL) |

### Audit

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/audit` | Paginated audit trail (filter by entity, actor, dates) |

---

## Roles

| Role | Access |
|------|--------|
| `AGENCY_ADMIN` | Full access across the agency |
| `AGENCY_PLANNER` | Read workers, plan/view stays, view properties |
| `PROPERTY_ADMIN` | Manage assigned properties, check-in/out, inspection |
| `FRONT_DESK` | Check-in/out, arrivals list, read-only otherwise |

---

## Constraint Engine

Stay creation and updates run a constraint engine before persisting:

- **CapacityConstraint** (hard) — room capacity minus blocked spots
- **DoubleBookingConstraint** (hard) — same worker overlapping stays
- **BlockedRoomConstraint** (hard) — room or property not in ACTIVE status
- **GenderConstraint** (soft) — gender rule on room

Soft violations return `HTTP 422` with violation details. Re-submit with `overrideReason` to force the operation.

---

## Health Check

```
GET /actuator/health
```
