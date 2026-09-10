# Beduno Backend - Requirements Analysis

> Reconciled against the implementation (entities, enums, constraint classes, `@PreAuthorize` annotations, Flyway V1-V7, i18n bundles, repositories). Concepts described here but never built are marked **Not implemented** inline.

## Domain Model

### Core Entities

All entities extend `BaseEntity` (`id` UUID, `createdAt`, `updatedAt`) and — except `Agency` — carry an `agencyId` tenant discriminator.

#### Worker
`workers` (V3)
- `internalId` (NOT NULL, unique per agency via `uq_workers_agency_internal_id`) - primary identifier used for lookup and check-in
- `firstName`, `lastName` (both NOT NULL)
- `gender` (**NOT NULL**) - `MALE | FEMALE | OTHER`; required, not optional
- `nationality`
- `phone`
- `email`
- `dateOfBirth`
- `tags` - free-form `TEXT[]`, NOT NULL, defaults to `{}`. No controlled vocabulary; see the note under Tag / Reason.
- `notes` - free text
- `status` (NOT NULL, default `ACTIVE`) - `ACTIVE | INACTIVE | DELETED`. **Not implemented: `BLACKLISTED`** - there is no blacklist concept anywhere in the codebase.
- `deletedAt` - set by the soft delete; `WorkerRepository` filters `status <> 'DELETED'` on every read
- Belongs to: Agency (tenant)

#### Property
`properties` (V4)
- `name` (NOT NULL), `address`, `city`
- `status` (NOT NULL, default `ACTIVE`) - `ACTIVE | INACTIVE`. **Not implemented: `blocked` / `maintenance`.**
- `notes`
- Belongs to: Agency (tenant)
- **Not implemented: `type` (internal / partner)** - no such column or enum.
- **Not implemented: property-level `genderRule`** - the gender rule lives on the room only.

#### Room
`rooms` (V4)
- `name` (NOT NULL, unique per property via `uq_rooms_property_name`) - the "room number / label"
- `floor`
- `capacity` (NOT NULL, default 1, `CHECK capacity > 0`)
- `blockedSpots` (NOT NULL, default 0, `CHECK 0 <= blocked_spots <= capacity`) - spots temporarily unavailable
- `availableSpots()` - derived, `capacity - blockedSpots`
- `genderRule` (NOT NULL, default `ANY`) - `ANY | MALE_ONLY | FEMALE_ONLY`. Room-scoped only; there is nothing to inherit from or override, because no property-level rule exists.
- `status` (NOT NULL, default `ACTIVE`) - `ACTIVE | BLOCKED`. **Not implemented: `available` / `maintenance`** (`ACTIVE` plays the role of "available").
- `notes`
- Belongs to: Property (and carries its own `agencyId`)

#### Stay (the core assignment)
`stays` (V5, V7)
- `workerId`, `propertyId`, `roomId` - all NOT NULL FK references
- `dateFrom` (NOT NULL), `dateTo` (nullable = open-ended); `CHECK date_to IS NULL OR date_to > date_from`
- `status` (NOT NULL, default `PLANNED`) - `PLANNED | EXPECTED_TODAY | CHECKED_IN | CHECKED_OUT | CANCELLED | NO_SHOW`
- `overrideReason` - free text; its presence is what permits a soft-constraint override
- `confirmedByUserId` - FK to `users`, set on check-in and on the replacement stay created by a move
- `noShowReason` (V7, `VARCHAR(100)`) - the no-show reason tag. Added so the no-show action stops overwriting `notes`; both now survive.
- `notes` - free text
- `version` - JPA `@Version`, optimistic locking
- Audit fields: `createdAt`, `updatedAt` (from `BaseEntity`)
- **Not implemented: `createdBy`** - the creating user is not recorded on the stay; only the `AuditEvent` row carries `actorUserId`.

### Supporting Entities

#### User / Account
`users` (V2)
- `email` (unique per agency), `passwordHash`, `firstName`, `lastName`
- `language` (NOT NULL, default `PL`)
- `role`: `AGENCY_ADMIN | AGENCY_PLANNER | PROPERTY_ADMIN | FRONT_DESK`
- `assignedPropertyIds` - `UUID[]`, NOT NULL, default `{}`
- `status` (plain string, default `ACTIVE`), `lastLoginAt`
- Scoped to: Agency (tenant) + optionally to specific Properties
- **Not implemented: user management API.** There is no `UserController` and no `AgencyController`. Users and agencies exist only as entities plus `UserRepository`; they must be provisioned directly in the database.

#### AuditEvent
`audit_events` (V6)
- `entityType` - `STAY | WORKER | ROOM | PROPERTY`
- `entityId`
- `action` - `CREATED | UPDATED | DELETED | CHECKED_IN | CHECKED_OUT | NO_SHOW | CANCELLED | MOVED | BULK_ASSIGNED | BULK_CHECKED_OUT`
- `actorUserId` - a raw UUID column, nullable; there is no FK or entity association to `User`
- `createdAt` - the event timestamp (from `BaseEntity`); there is no separate `timestamp` column
- `previousState`, `newState` - `jsonb` snapshots. Partial, not full-row: each service writes a hand-built map of selected fields (for a stay: status, workerId, roomId, propertyId, dateFrom, dateTo, noShowReason).
- `reason` - a **single free-text column**, not a predefined tag

#### Tag / Reason (predefined, localizable)
**Not implemented.** There is no tag or reason entity, table, or enum.
- `workers.tags` is an unconstrained free-form `TEXT[]`
- `stays.overrideReason`, `stays.noShowReason` and `audit_events.reason` are free text
- Nothing is stored with translations; the i18n bundles cover error and export strings only

## Stay Status State Machine

Six states. The transition table lives in `StayStatus.VALID_TRANSITIONS` and is enforced by `StayStatus.canTransitionTo`.

```
PLANNED ──> EXPECTED_TODAY ──> CHECKED_IN ──> CHECKED_OUT (terminal)
   │              │
   │              └──> NO_SHOW (terminal)
   │              │
   └──> CANCELLED └──> CANCELLED (terminal)

Room move (no status of its own):

  CHECKED_IN stay ──> CHECKED_OUT (terminal)
                 └──> new Stay row, CHECKED_IN in the target room,
                      dateFrom = today, audited as AuditAction.MOVED
```

- `PLANNED` -> `{EXPECTED_TODAY, CANCELLED}`
- `EXPECTED_TODAY` -> `{CHECKED_IN, NO_SHOW, CANCELLED}`
- `CHECKED_IN` -> `{CHECKED_OUT}` only
- `CHECKED_OUT`, `CANCELLED`, `NO_SHOW` are terminal - no outgoing transitions

Details:
- `PLANNED` -> `EXPECTED_TODAY`: `StayScheduler` runs daily (default cron `0 0 6 * * *`) and calls `StayService.transitionPlannedToExpectedToday(today)`
- `EXPECTED_TODAY` -> `CHECKED_IN`: front desk confirms arrival; sets `confirmedByUserId`, optionally overrides the room
- `EXPECTED_TODAY` -> `NO_SHOW`: front desk marks no-show with a required reason tag, stored in `noShowReason` (no longer written over `notes`)
- `CHECKED_IN` -> `CHECKED_OUT`: front desk confirms departure, optionally supplying `actualDateTo`
- **A `CHECKED_IN` stay cannot be cancelled.** "Early departure" is a check-out, not a cancellation. Cancellation is reachable only from `PLANNED` and `EXPECTED_TODAY`.
- **Not implemented: a `MOVED` status.** `AuditAction.MOVED` exists, but a move checks the original stay out and creates a brand-new `CHECKED_IN` stay in the target room. `StayService.move` rejects with **409** `error.stay.cannot_move_on_last_day` when `dateTo` is not after today (the replacement stay would violate `chk_stays_dates`), and also rejects a move to the same room or from any status other than `CHECKED_IN`.

## Constraint Engine (v1)

`ConstraintEngine` collects every `StayConstraint` bean and runs them all, accumulating hard and soft violations. It is invoked on stay create, stay update, check-in, move, and each row of bulk-assign.

### Hard Constraints (block the operation)
- **Capacity** (`CapacityConstraint`, type `CAPACITY_EXCEEDED`): overlapping stays in the room must stay below `capacity - blockedSpots`. Occupancy counts **`PLANNED`, `EXPECTED_TODAY` and `CHECKED_IN`** — not just checked-in. Two variants: `constraint.room.capacity.full` when every spot is blocked, `constraint.room.capacity.exceeded` when the room is full for the requested period.
- **Double-booking** (`DoubleBookingConstraint`, type `DOUBLE_BOOKING`): a worker cannot hold two date-overlapping stays. Also counts **`PLANNED`, `EXPECTED_TODAY` and `CHECKED_IN`**.
- **Blocked room** (`BlockedRoomConstraint`, type `ROOM_BLOCKED`): no stays into a room with `status = BLOCKED`.
- **Inactive property** (`BlockedRoomConstraint`, type `PROPERTY_INACTIVE`): no stays into a property with `status = INACTIVE`. Emitted by the same constraint class as `ROOM_BLOCKED`.

Both count queries exclude the stay being edited (`excludeStayId`) on update, check-in and move.

### Soft Constraints (warn but allow override with reason)
- **Gender rule** (`GenderConstraint`, type `GENDER_MISMATCH`): warns when a `MALE_ONLY` room receives a non-`MALE` worker or a `FEMALE_ONLY` room a non-`FEMALE` worker. `ANY` never warns. Note that `Gender.OTHER` violates both restricted rules.

This is the only soft constraint.

- **Not implemented: blacklisted worker.** No blacklist exists (see Worker).
- **Not implemented: "over-plan" as a soft warning.** Because `PLANNED` stays already count toward capacity, planning past a room's capacity for a future date is a **hard block** (`CAPACITY_EXCEEDED`) — the opposite of a warning. There is no separate `OVER_PLANNED` constraint type.

### Constraint Response Model

There is no dedicated constraint-result wire format. `StayService.runConstraints` throws `ConstraintViolationException`, which `GlobalExceptionHandler` renders as **HTTP 422** using the standard `ErrorResponse` envelope. There is no `allowed` flag and no `overridable` flag; `message` is a **message code**, resolved against the i18n bundles.

Hard and soft violations never appear in the same response — hard violations short-circuit. The discriminator is the top-level `message` code:

- `error.constraint.violated` -> `details` holds hard violations; the operation is impossible
- `error.constraint.soft_violations` -> `details` holds soft violations; retry the same request with a non-null `overrideReason` to proceed

```json
{
  "error": "CONSTRAINT_VIOLATION",
  "message": "error.constraint.soft_violations",
  "details": [
    {
      "type": "GENDER_MISMATCH",
      "field": null,
      "message": "constraint.room.gender_mismatch",
      "params": {
        "roomNumber": "12",
        "genderRule": "FEMALE_ONLY",
        "workerGender": "MALE"
      }
    }
  ],
  "timestamp": "2026-08-09T10:15:30Z"
}
```

`field` is always null for constraint violations. `ErrorResponse` also declares a `traceId`, but nothing populates it, so it is omitted (`@JsonInclude(NON_NULL)`).

## Role-Based Access Control

Enforced with `@EnableMethodSecurity` and `@PreAuthorize` on controller methods. The matrix below is transcribed from the annotations.

### Permission Matrix

| Action | Agency Admin | Agency Planner | Property Admin | Front Desk |
|--------|:---:|:---:|:---:|:---:|
| Manage users / agencies | **Not implemented** | **Not implemented** | **Not implemented** | **Not implemented** |
| Read workers (list, get) | Yes | Yes | Yes | Yes |
| Write workers (create, update, delete, CSV import) | Yes | **No** | No | No |
| Read properties (list, get) | Yes | Yes | Yes | Yes |
| Create property | Yes | - | - | - |
| Update property | Yes | - | Assigned only | - |
| Delete property | Yes | - | - | - |
| Read rooms (list, get) | Yes | Yes | Yes | Yes |
| Create / update room | Yes | - | Assigned only | - |
| Delete room | Yes | - | **No** | - |
| Read stays (list, get, arrivals) | Yes | Yes | Yes | Yes |
| Create / update planned stay | Yes | Yes | Yes (agency-wide) | - |
| Cancel stay (`DELETE /stays/{id}`) | Yes | Yes | Yes (agency-wide) | - |
| Check-in / check-out | - | - | Yes | Yes |
| Mark no-show | - | - | Yes | Yes |
| Move worker (room) | - | - | Yes | Yes |
| Bulk assign | Yes | Yes | Yes | - |
| Bulk checkout | Yes | Yes | Yes | Yes |
| Occupancy + exceptions views | Yes | Yes | Yes | Yes |
| Inspection roster + report | - | - | Yes | - |
| Export occupancy / exceptions | Yes | Yes | Yes | - |
| Export arrivals | Yes | Yes | Yes | **Yes** |
| View audit log | Yes | Yes | No | No |
| Override soft constraint | see note | see note | see note | see note |

Notes on the matrix:
- **Override soft constraint is not role-enforced.** `StayService.runConstraints` only checks that `overrideReason` is non-null. Any role that can reach an endpoint accepting an override reason (create, update, check-in, move, bulk-assign) can override a soft constraint — including Agency Planner and Front Desk. **Known gap:** the intended "Admin + Property Admin only" rule has no implementation anywhere.
- **View audit log** is `AGENCY_ADMIN` + `AGENCY_PLANNER` only, and returns the whole agency's trail. There is no per-role, per-property or own-actions scoping. Property Admin and Front Desk have no audit access at all.
- Deletes are guarded, not silently cascading: deleting a property returns **409** (`error.property.has_rooms` / `error.property.has_stays`) while any room or stay references it; deleting a room returns **409** (`error.room.has_stays`) while any stay references it — terminal stays included.

### Scoping Rules

What is actually enforced, as opposed to intended:

- **Tenant isolation** is enforced everywhere: `TenantFilter` puts the JWT `agencyId` into `TenantContext`, and every service resolves it via `TenantContext.requireAgencyId()` and passes it into agency-filtered repository methods.
- **Property-level scoping is enforced in exactly two places**, both via `CurrentUser.hasPropertyAccess`, and both only when `role == PROPERTY_ADMIN`:
  - `PropertyController.update`
  - `RoomController.checkPropertyAccess`, called from room create and room update

  Both throw `ForbiddenException` -> 403 `error.property.access_denied`.
- **Everything else is agency-wide.** Stays (create, update, cancel, check-in, check-out, no-show, move, bulk operations), occupancy, exceptions, inspection, and all three exports accept any `propertyId` within the caller's agency. A Front Desk or Property Admin user can operate on properties they are not assigned to.
- `CurrentUser.hasPropertyAccess` short-circuits to `true` for `AGENCY_ADMIN` and `AGENCY_PLANNER`, so `assignedPropertyIds` is meaningful only for `PROPERTY_ADMIN` and `FRONT_DESK` — and for Front Desk it is never consulted.

## API Requirements

All routes are prefixed `/api/v1`. `/api/v1/auth/login`, `/api/v1/auth/refresh` and `/actuator/health` are public — `/api/v1/auth/me` is not, despite the shared prefix — and the Swagger paths are public only while `beduno.security.public-api-docs` is true, which the `prod` profile turns off. Everything else requires a bearer token. Unauthenticated requests to protected paths return **401** (`RestAuthenticationEntryPoint`, `UNAUTHORIZED` / `error.auth.unauthorized`); role denials return **403**. `POST /auth/login` and `/auth/refresh` are rate limited to 10 requests/minute per client IP (429).

### Auth
- `POST /auth/login` - email + password, returns access + refresh tokens; bad credentials -> **401**
- `POST /auth/refresh` - exchange refresh token; invalid token or unknown subject -> **401**
- `GET /auth/me` - current user profile

### Arrivals Workflow
- `GET /stays/arrivals?propertyId=&date=` - stays in `EXPECTED_TODAY` with `dateFrom = date`
- `POST /stays/{id}/check-in` - confirm arrival; body `{roomId?, overrideReason?}`
- `POST /stays/{id}/no-show` - mark no-show; body `{noShowReason}` (required), stored in `stays.no_show_reason`
- `POST /stays/{id}/check-out` - body `{actualDateTo?}`
- `POST /stays/{id}/move` - body `{targetRoomId, overrideReason?}`; checks the old stay out and returns the new one

### Nightly Occupancy
- `GET /properties/{propertyId}/occupancy?date=` - room-by-room occupancy, counting `CHECKED_IN` only
- `GET /properties/{propertyId}/exceptions?date=` - per room, `OVER_CAPACITY` or `PENDING_ARRIVAL` (an `EXPECTED_TODAY` stay still outstanding). There is no "unassigned worker" or "unknown occupant" exception type.
- `GET /properties/{propertyId}/occupancy/export?language=` - CSV. **CSV is the only format**; there is no `format` parameter. `language` accepts `EN | PL | DE | RU | UA`, defaults to `EN`.
- `GET /properties/{propertyId}/arrivals/export?language=`
- `GET /properties/{propertyId}/exceptions/export?language=`

### Inspection Mode
- `GET /properties/{propertyId}/inspection?date=` - room-by-room roster: expected (`CHECKED_IN` + `EXPECTED_TODAY`) vs actually checked-in
- `POST /properties/{propertyId}/inspection?date=` - submit actual occupants; returns `EXPECTED_NOT_PRESENT` / `UNEXPECTED_PRESENT` discrepancies. Read-only: the report is diffed and returned, never persisted, and no audit event is written.

### Bulk Operations
- `POST /workers/import` - multipart CSV. Columns in order: `internalId, firstName, lastName, gender, nationality, phone, email, dateOfBirth, tags(;-separated), notes`. The first four are required. Duplicate `internalId` rows are skipped, not errored. Returns per-row created/skipped/error counts.
- `POST /stays/bulk-assign` - per-row constraint evaluation; failures are collected, not rolled back
- `POST /stays/bulk-checkout` - same partial-success semantics

### Audit
- `GET /audit` - paginated, filterable by `entityType`, `entityId`, `actorUserId`, `dateFrom`, `dateTo`

### CRUD
- Workers: `/workers`
- Properties: `/properties`
- Rooms: nested under `/properties/{propertyId}/rooms`
- Stays: `/stays` (`DELETE` = cancel, not a row delete)
- Filtering, pagination and sorting on all list endpoints via Spring `Pageable`

## Multi-Tenancy

The system is multi-tenant from day 1. Each agency is a tenant. All data lives in a shared schema with an `agency_id` discriminator column, indexed on every table.

Isolation is **application-level only**. `TenantFilter` reads `agencyId` from the JWT into a ThreadLocal `TenantContext`; services read it back and pass it into repository queries that filter on `agency_id`.

**Not implemented: row-level security.** There is no `ROW LEVEL SECURITY` or `CREATE POLICY` statement in any migration (V1-V7), and the application connects as the table owner. Nothing at the database level prevents a cross-tenant read — a query that omits its `agencyId` predicate will return other tenants' rows, and two currently do so deliberately:

- `StayRepository.findPlannedArrivingOn(date)` - global by design; the nightly scheduler transitions arrivals for every agency in one pass
- `OccupancyService.loadWorkers` -> `WorkerRepository.findAllById(workerIds)` - inherited from `JpaRepository`, no `agencyId` predicate. Safe in practice only because the IDs come from stays already filtered by agency.

## Internationalization (i18n)

- All API error messages, constraint messages and CSV headers use message codes, never hardcoded strings. The API returns the code; resolution happens against the bundles.
- Bundles live in `src/main/resources/i18n`, basename `i18n/messages`, UTF-8, `fallback-to-system-locale: false`.
- Six bundles — `messages.properties` (English default) plus `_pl`, `_en`, `_de`, `_ua`, `_ru` — are **complete and key-identical: 44 keys each**, verified. Coverage spans auth and generic errors, stay errors, constraint messages, worker-import errors, export headers, the six status labels, the two exception-type labels, and the deletion/move guard messages.
- Locale for API responses comes from an `AcceptHeaderLocaleResolver` (default `pl`; supported `pl`, `en`, `de`, `uk`, `ru`).
- Export endpoints accept an explicit `language` parameter (`EN | PL | DE | RU | UA`, default `EN`) that localises CSV headers and status labels.
- **Not implemented: localizable tag/reason vocabulary.** Reasons and tags are free text and are not translated (see Tag / Reason).
- The backend sends structured data and message codes; the frontend handles UI translation.
- CSV is the only backend-generated report format. There is no PDF export.

## Data & Compliance

- **GDPR**: minimum PII is stored (name, gender, nationality, phone, email, date of birth). There is no field-level access control — every role that can read a worker reads the whole record. No retention policy or purge job exists.
- **Audit**: state changes to stays, workers, rooms and properties are logged with actor, timestamp and before/after JSON. Snapshots are partial hand-built maps of selected fields, not full-row diffs. Inspection reports are not audited.
- **Data minimization**: no ID scans in MVP, only references/metadata.
- **Deletion semantics differ per entity** — "workers and stays are soft-deleted" is not accurate:
  - **Workers: soft delete.** `status` -> `DELETED` plus `deletedAt`; every worker query filters `status <> 'DELETED'`.
  - **Stays: never deleted and never soft-deleted.** `DELETE /stays/{id}` transitions the stay to `CANCELLED`. Rows are retained permanently; there is no `deleted_at` on `stays`.
  - **Properties and rooms: hard delete.** The row is physically removed. Because `rooms.property_id`, `stays.property_id` and `stays.room_id` are RESTRICT foreign keys, the services pre-check references and return **409** rather than letting the database fail, so anything referenced in practice cannot be deleted.

## Performance Requirements

**Unverified targets.** No load test, benchmark harness or profiling suite exists in the repository — no Gatling, JMeter, k6 or JMH. The figures below are design intents that have never been measured.

- Answer "where is Worker X tonight?" in < 1 second (API response)
- Arrivals list for a property: < 500ms
- Bulk assign up to 200 workers: < 5 seconds
- CSV import of 1000 workers: < 30 seconds
- Concurrent users per agency: ~20-50

Supporting work that does exist: composite indexes on the hot paths (`idx_stays_occupancy` on `(room_id, status, date_from, date_to)`, plus `agency_id`-leading indexes on every table).
