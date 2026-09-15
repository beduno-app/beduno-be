# Beduno Backend

Worker housing management system for temporary work agencies.

**Stack**: Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · JWT

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
first with `deploy/sync.sh`.

### First launch

```bash
aws ssm put-parameter --name /beduno/prod/DUCKDNS_DOMAIN --type String       --value 'beduno-api'
aws ssm put-parameter --name /beduno/prod/DUCKDNS_TOKEN  --type SecureString --value '<token>'
# ...and the rest of the table above

deploy/publish.sh          # build arm64, push to ECR, point APP_IMAGE at it
deploy/launch.sh           # create the instance (refuses if one already exists)
deploy/instance.sh status  # state, address, health
deploy/backup.sh enable-daily   # daily snapshots; until this runs there are none
```

`enable-daily` is not optional in practice. Without it the only snapshots a deployment ever gets
are the one `instance.sh stop` takes and the one `publish.sh` takes before each roll — so an
instance that is never stopped and never rolled has no restore point at all.

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
deploy/publish.sh            # snapshot, then deploy the current commit end to end
deploy/sync.sh               # push docker-compose.prod.yml / Caddyfile / boot.sh to the box
deploy/instance.sh start     # start, and wait for the API to answer
deploy/instance.sh stop      # snapshot the volume, then stop (this is the cost control)
deploy/instance.sh logs app 200
deploy/instance.sh shell
deploy/backup.sh snapshot | list | prune | enable-daily
```

`publish.sh` updates the **image** only. The three deploy files reach `/opt/beduno` through
cloud-init on the instance's first boot and never again, so a change to any of them needs
`deploy/sync.sh`; `publish.sh` prints a reminder when it sees one in recent history. It also
refuses to publish a commit that is not `origin/main`, because the image is built with `-x test`
and CI runs on nothing else — override with `ALLOW_UNTESTED=1` if you mean it.

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

**The rollback floor is `edcbb8d`** (named-beds p4). Earlier images do not start: V9 renamed
`rooms.name` to `room_number` and V13 dropped `capacity` and `blocked_spots`, each in the same
release as the code change, so `ddl-auto: validate` fails against any image that still maps them.
Pointing `APP_IMAGE` below that floor gives a unit that crash-loops on every boot until the
parameter is pointed forward again.

It fails loudly — refusing to start rather than corrupting anything — when the newer schema
**dropped or renamed** something the older code still maps. That is the case worth avoiding, and
it is why migrations should stay additive: add columns nullable, and never drop or rename one in
the same release that stops using it — drop it in the release *after* the code stops mapping it.
A migration that is not additive needs a forward fix, not a rollback. Tightening an existing
column (`SET NOT NULL`, a new CHECK, an exclusion constraint) needs the same care in the other
direction: precede it with an idempotent backfill, or with a pre-flight that fails with an
actionable message, so it cannot half-apply against populated data. V14 has neither; V15 has the
pre-flight.

---

## API Overview

All endpoints are under `/api/v1/`. Full interactive docs at `/swagger-ui.html` (disabled in
`prod` — see `beduno.security.public-api-docs` in `application-prod.yml`).

`openapi.yaml` at the repo root is a **checked-in snapshot** of the live spec, generated from
whatever code was checked out when it was last refreshed — it is not regenerated automatically,
so treat it as current only as of its last commit. Consumers (e.g. the frontend repo generating
types) should re-pull it after backend changes land rather than assume it's fresh. Regenerate it
after any controller/DTO change:

```bash
docker compose -f docker/docker-compose.yml up -d
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun &
curl -s http://localhost:8080/v3/api-docs.yaml -o openapi.yaml
kill %1
docker compose -f docker/docker-compose.yml down
```

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

### Users

| Method | Path | Roles |
|--------|------|-------|
| `GET`    | `/api/v1/users`, `/api/v1/users/{id}` | AGENCY_ADMIN |
| `POST`   | `/api/v1/users` | AGENCY_ADMIN |
| `PUT`    | `/api/v1/users/{id}` | AGENCY_ADMIN |
| `DELETE` | `/api/v1/users/{id}` | AGENCY_ADMIN. Deactivates (`status = INACTIVE`) rather than deleting: `stays.confirmed_by_user_id` references the row. |

Admin-only throughout, because every response carries the email and role of every account in the
agency. Two guards apply to both `PUT` and `DELETE`: the last active `AGENCY_ADMIN` cannot be
demoted or deactivated (`409 error.user.last_admin`), and neither route lets a caller deactivate
themselves (`409 error.user.cannot_deactivate_self`).

Deactivation is a real revocation: login and refresh both refuse a non-ACTIVE account, and
deactivating bumps the user's token version, which invalidates their outstanding refresh token
immediately. Their current access token still works until it expires (at most an hour) — inherent
to a stateless access token.

Email is unique across the whole table, not per agency, because login resolves a user by email
alone with no agency selector. `POST` therefore returns `409 error.user.email_exists` for an
address already used in *any* agency.

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

### Beds

| Method | Path | Roles |
|--------|------|-------|
| `GET`    | `/api/v1/properties/{id}/rooms/{roomId}/beds`, `.../beds/{bedId}` | AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK |
| `POST`   | `/api/v1/properties/{id}/rooms/{roomId}/beds` | AGENCY_ADMIN, PROPERTY_ADMIN |
| `POST`   | `/api/v1/properties/{id}/rooms/{roomId}/beds/bulk-generate` | AGENCY_ADMIN, PROPERTY_ADMIN. Adds `count` beds (max 200) labelled as integers continuing from the room's highest numeric label. |
| `PUT`    | `/api/v1/properties/{id}/rooms/{roomId}/beds/{bedId}` | AGENCY_ADMIN, PROPERTY_ADMIN. Relabel, or set `status` to `BLOCKED` / `ACTIVE`. |
| `DELETE` | `/api/v1/properties/{id}/rooms/{roomId}/beds/{bedId}` | AGENCY_ADMIN. Hard delete guarded by a `409` conflict check if any stay references the bed. |

A bed is the unit of occupancy: a stay names one, `stays.bed_id` is `NOT NULL`, and a `BLOCKED`
bed is excluded from auto-assignment and rejected on explicit assignment. Bed endpoints are
property-scoped like rooms.

### Stays

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/v1/stays` | List stays (filter by worker, property, status, dates) |
| `GET`    | `/api/v1/stays/{id}` | Get a single stay |
| `POST`   | `/api/v1/stays` | Plan a stay — runs constraint engine. A stay whose `dateFrom` is today or earlier is created `EXPECTED_TODAY` rather than `PLANNED`, so it can be checked in at once. |
| `PUT`    | `/api/v1/stays/{id}` | Update stay — runs constraint engine. The status is reconciled with the new `dateFrom` in both directions: postponing an `EXPECTED_TODAY` stay returns it to `PLANNED`. |
| `DELETE` | `/api/v1/stays/{id}` | Cancel stay |
| `GET`    | `/api/v1/stays/arrivals` | Expected arrivals for a property |
| `POST`   | `/api/v1/stays/{id}/check-in` | Check in — runs constraint engine. With no `bedId` and no room change the stay keeps its planned bed; only a room change re-assigns. |
| `POST`   | `/api/v1/stays/{id}/check-out` | Check out |
| `POST`   | `/api/v1/stays/{id}/no-show` | Mark no-show (reason stored in a dedicated `no_show_reason` field, doesn't overwrite `notes`) |
| `POST`   | `/api/v1/stays/{id}/move` | Move to another room (atomic) — runs constraint engine. Returns `409` if attempted on the stay's final day (no night left to reassign). |
| `POST`   | `/api/v1/stays/bulk-assign` | Bulk plan multiple stays (max 500) — runs constraint engine per item. Business failures are reported per item in the response body with `200`; a database-level failure rolls the whole batch back. |
| `POST`   | `/api/v1/stays/bulk-checkout` | Bulk checkout multiple stays (max 500) |

### Occupancy

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/properties/{id}/occupancy` | Room-by-room occupants |
| `GET` | `/api/v1/properties/{id}/exceptions` | Data-integrity and attention list: `OVER_CAPACITY`, `BED_CONFLICT` (two workers on one bed), `BED_BLOCKED_OCCUPIED`, `OVERSTAY` (checked in past the planned end date), `PENDING_ARRIVAL` |
| `GET` | `/api/v1/properties/{id}/inspection` | Nightly inspection roster (AGENCY_ADMIN, PROPERTY_ADMIN) |
| `POST` | `/api/v1/properties/{id}/inspection` | Submit discrepancy report (AGENCY_ADMIN, PROPERTY_ADMIN) |
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
| `AGENCY_PLANNER` | Read workers, plan/view/update/cancel stays, bulk-assign, view properties/rooms, `GET /audit` (USER events excluded) |
| `PROPERTY_ADMIN` | Everything above within its assigned properties, plus manage rooms and beds, update the property, check-in/out/move/no-show, inspection roster and reports |
| `FRONT_DESK` | Within its assigned properties: check-in/out/move/no-show, `POST /stays/bulk-checkout`, `GET /properties/{id}/exceptions`, `GET .../arrivals/export`, read-only otherwise |

`AGENCY_ADMIN` has full access, the operational endpoints included: check-in, check-out,
no-show, move and both inspection endpoints all admit it. `AGENCY_PLANNER` plans but does not
operate a front desk, so it is excluded from those and from `bulk-checkout`, which carries the
same role set as single check-out.

`GET /audit` is open to `AGENCY_ADMIN` and `AGENCY_PLANNER`, but `USER` events are filtered out
for anyone but an admin: their state snapshots carry every account's email and role, which is the
same roster `/api/v1/users` is admin-only to protect.

**Property scoping is enforced.** A `PROPERTY_ADMIN`'s or `FRONT_DESK`'s assigned properties
(`assignedPropertyIds` on the JWT) gate every property-bound path: property update, rooms, beds,
every stay operation (including check-in/out/move/no-show, create, bulk-assign and arrivals),
occupancy, exceptions, inspection, and all three CSV exports. Acting outside the list returns
`403 error.property.access_denied`. A token for one of these roles with an empty list therefore
reaches no property at all.

`AGENCY_ADMIN` and `AGENCY_PLANNER` are agency-wide by definition and are not narrowed by the
list. Cross-*agency* ids remain `404`, never `403`, so tenancy is not leaked through error codes.

---

## Constraint Engine

Stay creation, updates, check-in, move, and bulk-assign all run a constraint
engine before persisting:

- **BedOccupancyConstraint** (hard) — the bed is already taken for an overlapping period
  (`BED_OCCUPIED`). Replaced the old room-capacity headcount when V13 dropped `capacity`.
- **DoubleBookingConstraint** (hard) — same worker overlapping stays
- **BlockedRoomConstraint** (hard) — room or property not ACTIVE, or the bed is `BLOCKED`
  (`BED_BLOCKED`)
- **GenderConstraint** (soft) — gender rule on room

Auto-assignment reports `BED_UNAVAILABLE` when a room has no bed that satisfies every hard
constraint for the requested period.

The engine is an application-level check, and under concurrency two requests can both pass it
before either inserts. Since V15 the database carries matching exclusion constraints on
`(bed_id, period)` and `(worker_id, period)`, so that race ends in a `409` rather than a double
booking.

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

<!-- smoke test: intentionally trivial change to trigger the PR Review workflow -->
