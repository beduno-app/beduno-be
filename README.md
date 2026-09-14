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

Read under every profile, and only relevant on a database with no users yet — see
[Deployment](#deployment):

| Variable | Description |
|----------|-------------|
| `BOOTSTRAP_ENABLED` | `true` to create the first agency and administrator at startup. Default `false` |
| `BOOTSTRAP_AGENCY_NAME` | Agency display name |
| `BOOTSTRAP_ADMIN_EMAIL` | Login address for the first `AGENCY_ADMIN` (lowercase — matching is case-sensitive) |
| `BOOTSTRAP_ADMIN_PASSWORD` | At least 12 characters |
| `BOOTSTRAP_ADMIN_FIRST_NAME` / `_LAST_NAME` | Default `Agency` / `Admin` |
| `BOOTSTRAP_ADMIN_LANGUAGE` | Default `PL` |

On an empty database, enabled with any of the first three missing, or a password under 12
characters, **fails startup** — the alternative is an API that answers 401 to everything with no
explanation. Once a user exists the runner stops before validating, so leftover variables on a
populated database do nothing.

| Variable | Description |
|----------|-------------|
| `BEDUNO_SEED_ENABLED` | `true` to populate a separate demo agency (4 users, 2 properties, 6 rooms, 8 beds, 8 workers, 7 stays) at startup, once. Default `false`, except the `dev` profile, where it defaults `true` |

Seeded users all share the password `Demo12345678!`: `admin@demo.beduno.dev` (`AGENCY_ADMIN`),
`planner@demo.beduno.dev` (`AGENCY_PLANNER`), `propertyadmin@demo.beduno.dev` (`PROPERTY_ADMIN`),
`frontdesk@demo.beduno.dev` (`FRONT_DESK`). Unlike `BOOTSTRAP_*`, the idempotency check is scoped
to the demo admin account itself (`admin@demo.beduno.dev`), not to the database being empty — it
is safe to enable this on a database that already holds one or more real tenants; it adds its own
separate agency alongside them and never touches their rows. It still writes real rows with a
publicly-known password, so treat it as something to enable deliberately (e.g. for a sales demo
against the live environment), not leave on where it isn't wanted. See `SeedRunner` for exactly
what it creates.

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

## Deployment

Production is **one `t4g.small` in `eu-central-1`** running the app, PostgreSQL and Caddy under
docker compose, stopped whenever it is not in use. Caddy is the only service publishing ports and
terminates TLS with an automatically renewed Let's Encrypt certificate; the app and the database
are reachable on the internal network only. Everything lives in `deploy/`.

| Piece | What |
|-------|------|
| Compute | one EC2 `t4g.small` (arm64, 2 GB), tagged `Name=beduno-api` |
| Image | ECR `beduno-api`, tagged with the commit sha it was built from |
| Config & secrets | SSM Parameter Store under `/beduno/prod/` |
| DNS + TLS | DuckDNS hostname, Caddy with Let's Encrypt (HTTP-01) |
| Shell | SSM Session Manager — there is no port 22 and no key pair |
| Backups | EBS snapshots of the root volume (`deploy/backup.sh`) |

The researched alternative (ECS Express Mode + RDS) costs roughly $55–60/month against roughly
$2.60 here at a couple of hours a day. See `context/foundation/infrastructure.md` and the
addendum recording why the shape changed.

### Parameters

Required before the first launch — `deploy/launch.sh` refuses to run without all five:

| Parameter | Type | Value |
|-----------|------|-------|
| `/beduno/prod/DUCKDNS_DOMAIN` | String | subdomain label only, e.g. `beduno-api` (`boot.sh` appends `.duckdns.org`) |
| `/beduno/prod/DUCKDNS_TOKEN` | SecureString | the token shown on duckdns.org |
| `/beduno/prod/POSTGRES_PASSWORD` | SecureString | database password |
| `/beduno/prod/JWT_SECRET` | SecureString | HS256 signing key, at least 256 bits |
| `/beduno/prod/APP_IMAGE` | String | full ECR image URI including tag (`publish.sh` maintains this) |

Optional, and only until the first login — these create the first agency and administrator.
The Users API (`/api/v1/users`) manages accounts after that point, but it requires an
authenticated AGENCY_ADMIN to call it, and the migrations seed no rows — this bootstrap is the
only way to get the very first admin into an otherwise-empty database:

| Parameter | Type | Value |
|-----------|------|-------|
| `/beduno/prod/BOOTSTRAP_ENABLED` | String | `true` |
| `/beduno/prod/BOOTSTRAP_AGENCY_NAME` | String | agency display name |
| `/beduno/prod/BOOTSTRAP_ADMIN_EMAIL` | String | login address — **lowercase**, matching is case-sensitive (Q28) |
| `/beduno/prod/BOOTSTRAP_ADMIN_PASSWORD` | SecureString | at least 12 characters |

The runner creates them only when the users table is empty and does nothing on every later start.
On an empty database, enabled but incomplete **fails startup** rather than booting into an API
nobody can log into. Once a user exists the runner returns before it validates anything, so a
half-configured `BOOTSTRAP_*` set left behind on a populated database is inert, not fatal.

Optional, and safe to enable even after real tenants exist — it adds a separate demo agency
alongside them rather than refusing to run:

| Parameter | Type | Value |
|-----------|------|-------|
| `/beduno/prod/BEDUNO_SEED_ENABLED` | String | `true` |

```bash
aws ssm put-parameter --name /beduno/prod/BEDUNO_SEED_ENABLED --type String --overwrite --value true
deploy/instance.sh shell   # then: sudo systemctl restart beduno.service
```

Only takes effect once `docker-compose.prod.yml` and `boot.sh` on the box forward the variable —
if the instance was launched before this variable existed, push the updated deploy files onto it
first (see `deploy/render-user-data.py` for what cloud-init would have written at launch).

### First launch

```bash
aws ssm put-parameter --name /beduno/prod/DUCKDNS_DOMAIN --type String       --value 'beduno-api'
aws ssm put-parameter --name /beduno/prod/DUCKDNS_TOKEN  --type SecureString --value '<token>'
# ...and the rest of the table above

deploy/publish.sh          # build arm64, push to ECR, point APP_IMAGE at it
deploy/launch.sh           # create the instance (refuses if one already exists)
deploy/instance.sh status  # state, address, health
```

`launch.sh` uses the existing security group and subnet by default; override with `SG_ID`,
`SUBNET_ID`, `INSTANCE_TYPE` or `VOLUME_GB`. Cloud-init then installs Docker, writes the deploy
files, and starts `beduno.service` — a few minutes, most of it the Let's Encrypt challenge.

Then log in, and **delete the four bootstrap parameters** and restart the service. They are a
standing copy of an administrator password, re-read at every boot.

```bash
curl -s -X POST https://<domain>.duckdns.org/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@agency.pl","password":"..."}'

for p in ENABLED AGENCY_NAME ADMIN_EMAIL ADMIN_PASSWORD; do
  aws ssm delete-parameter --name "/beduno/prod/BOOTSTRAP_$p"
done
deploy/instance.sh shell   # then: sudo systemctl restart beduno.service
```

### Day to day

```bash
deploy/publish.sh            # deploy the current commit end to end
deploy/instance.sh start     # start, and wait for the API to answer
deploy/instance.sh stop      # snapshot the volume, then stop (this is the cost control)
deploy/instance.sh logs app 200
deploy/instance.sh shell
deploy/backup.sh snapshot | list | prune | enable-daily
```

A stop invalidates two things and `boot.sh` refreshes both on the way back up: the public IPv4
address (there is no Elastic IP — an idle one costs more than the disk) and the ECR authorization
token, which lasts 12 hours. That is why **starting the instance is the whole deploy** after the
first time.

### Rollback

Point `APP_IMAGE` at the previous tag and restart the service:

```bash
aws ssm put-parameter --name /beduno/prod/APP_IMAGE --type String --overwrite \
  --value '<account>.dkr.ecr.eu-central-1.amazonaws.com/beduno-api:<previous-sha>'
deploy/instance.sh shell   # then: sudo systemctl restart beduno.service
```

**This reverts the image, not the schema.** Flyway migrations that have run stay run. That is
usually fine: `ddl-auto: validate` only checks that the tables and columns the older code *maps*
still exist with compatible types, so extra columns and tables it knows nothing about do not
bother it, and Flyway tolerates history rows for migrations the older jar does not carry
(`ignoreMigrationPatterns` defaults to `*:future`). A rollback across purely additive migrations
starts cleanly.

It fails loudly — refusing to start rather than corrupting anything — when the newer schema
**dropped or renamed** something the older code still maps. That is the case worth avoiding, and
it is why migrations should stay additive: add columns nullable, and never drop or rename one in
the same release that stops using it. A migration that is not additive needs a forward fix, not a
rollback.

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
