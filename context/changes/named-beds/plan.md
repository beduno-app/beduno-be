# Named Beds, End-to-End Implementation Plan

## Overview

Beduno's `Room` entity carries only a `capacity` int and a `blockedSpots` int — a placement is recorded as "this worker is in this room," never "this worker is in this specific bed." This plan introduces a `Bed` entity so a planner can allocate a wave of workers to individually identified beds, with the system auto-assigning a bed unless the planner overrides the choice. It implements the roadmap's north star (`S-01` in `context/foundation/roadmap.md`) and PRD requirements FR-004, FR-005, and FR-018, and is the acceptance vehicle for US-01's bed half.

## Current State Analysis

The codebase has no bed concept anywhere. `Room.capacity`/`blockedSpots`/`availableSpots()` (`Room.java:32-51`) are room-level counts; `Stay.roomId` (`Stay.java:31`) is the sole placement pointer, set directly by five write paths in `StayService` (`create`, `update`, `checkIn`, `move`, `bulkAssign`). The constraint engine (`stay/constraint/`) is four hardcoded `@Component` beans fanned out by `ConstraintEngine`; hard-vs-soft is which list each one appends to, not declarative data. `CapacityConstraint` is the rule most reshaped by this change — today it's a room-level headcount against `room.availableSpots()`, backed by `StayRepository.countActiveStaysInRoom[Excluding]` (`StayRepository.java:65,82`); at bed granularity the same question becomes "is this specific bed free for these dates," structurally identical to how `DoubleBookingConstraint` already checks worker overlap. No auto-assignment or override precedent exists anywhere in the codebase (`grep` for auto-assign/pick-room patterns returns nothing); the closest analogue is `CheckInRequest.roomId`, an existing per-operation room override.

`Room.capacity`/`blockedSpots`/`availableSpots()` are not only read inside the `room` module — `CapacityConstraint.java`, `OccupancyService.java:60-61,87,90,96`, `ExportService.java:32-33,42-43,86-87,96-97`, and the `RoomOccupancyResponse`/`OccupancyExceptionResponse` DTOs all call them directly. Any phase that removes these members must account for every one of these consumers in the same phase, or the build breaks at that phase's own boundary (see Critical Implementation Details).

Critically, there is a **live frontend (SPA)** built against the current Room/Stay JSON contract documented in `docs/api-specification.md` ("for the frontend team"). Migration `V9__room_contract_alignment.sql`, which landed the same day as this plan, exists purely to match what that SPA already sends — contradicting the PRD's "no integrated consumers, reshape freely" claim (`context/foundation/prd.md` §Constraints & Compatibility). This plan breaks the contract deliberately anyway (per the confirmed decision below), with the same dated-changelog discipline `V9` used, and flags the coordinated frontend follow-up explicitly rather than silently.

### Key Discoveries

- `Room.availableSpots()` (`Room.java:49-51`) is a static inventory number (`capacity - blockedSpots`); the *live* vacancy the frontend actually reads is recomputed in `RoomResponse.withOccupancy` (`RoomResponse.java:38-42`) — two different "available" numbers exist today for the same field name, a confusion this change removes by deriving both from real bed data.
- `blockedSpots` is a bare integer with no link to which physical bed is blocked (`RoomService.java:86-87,105-106` only validates it against `capacity`) — exactly the ambiguity FR-004 exists to resolve.
- `ConstraintContext` (`stay/constraint/ConstraintContext.java:10-17`) is a record carrying `Worker, Room, Property, dateFrom, dateTo, excludeStayId` — every constraint reasons off `Room`, never a specific spot within it. It is constructed positionally at 5 sites in `StayService.java` (`:95,121,154,211,288`) and 3 in `ConstraintEngineTest.java` (`:52,165,220`) — adding a field to this record requires touching all 8 sites in the same change, since Java records have no optional positional constructor.
- `StayService.runConstraints` (`StayService.java:345-361`) is the central override semantic: a hard violation is always fatal; a soft violation is fatal only without an `overrideReason`. This must not change — bed-occupied and bed-blocked stay hard (never overridable); only `GenderConstraint` stays soft.
- Migrations run through `V9`; the next is `V10`. Convention: `snake_case`, named constraints (`uq_<table>_<cols>`, `chk_<table>_<what>`), explicit `idx_<table>_<cols>` indexes, bare `REFERENCES` (RESTRICT by default, no cascades), a rationale comment on any migration that alters or narrows existing data (`V8`, `V9` both do this).
- Audit is manual, not AOP: ~20 explicit `auditService.log(...)` call sites in `StayService`/`WorkerService`/`RoomService`/`PropertyService` (`docs/architecture.md:459-461`). `StayService.create` (`:103-104`) and `bulkAssign` (`:301-302`) currently pass `null` for the audit `reason` argument instead of the caller-supplied `overrideReason` — `update`/`checkIn`/`move` already pass it correctly. This plan touches those exact call sites to add bed fields, so the sibling bug is fixed for free.
- i18n convention: `Violation.type()` is a flat `SCREAMING_SNAKE_CASE` machine code (`ROOM_BLOCKED`, `CAPACITY_EXCEEDED`); `Violation.message()` is a dotted i18n key (`constraint.room.blocked`). Every new key must land in all six bundles under `src/main/resources/i18n/` — `MessageBundleTest` fails the build on a missing key.
- `AGENTS.md`/`CLAUDE.md` hard rules that bound this plan: every repository query filters by `agencyId` (three sanctioned exceptions, none of which this plan adds to); never modify an existing migration; every module's test suite includes a cross-agency isolation case; DTOs are records, entities extend `BaseEntity` and carry `agencyId`, constructor injection only.

## Desired End State

A planner can create a room, generate a set of individually identified beds for it, and allocate a wave of workers to that property — the system picks a free, unblocked bed for each worker unless the planner names one explicitly, and every resulting stay records which bed and whether the choice was automatic or overridden. `Room.capacity`/`blockedSpots` are gone; a room's bed inventory is the only source of truth for how many spots it has and which are blocked. Existing rooms and stays are backfilled onto beds rather than reset. The occupancy views, exports, and audit trail all speak in beds. `docs/api-specification.md` documents the new contract with the same dated rigor `V9` used.

**Verification:** create a room, bulk-generate beds, allocate several workers to it via `POST /stays` without specifying a bed (auto-assign) and via one explicit `bedId` (override); confirm no two active stays ever share a bed; confirm a blocked bed is unassignable even with an override reason; confirm moving a checked-in worker to a different bed in the same room succeeds while moving them to their current bed is rejected; confirm the audit trail and occupancy export both show bed-level detail; confirm Flyway backfills every pre-existing room/stay cleanly with no stay left without a bed.

## What We're NOT Doing

- Hotel/external accommodation (`S-02`), the crew-together rule (`S-05`), room-sharing rules (`S-06`), bed-night cost (`S-11`), or allocation proposals / FR-020 (`S-07`) — separate roadmap slices, several of which depend on this one.
- Any "smart" auto-assignment (load-balancing, fairness, crew-awareness) — first free bed by label order only, per the confirmed decision below.
- Suggesting alternate rooms/properties when no bed is free — that belongs to `S-07`'s proposal mode; this plan fails hard, the same shape as today's capacity rejection.
- A per-bed `GenderRule` — gender segregation stays a room-level policy.
- A `FINANCE` role, cost, or rate modeling of any kind.
- Reconciling every stale line in `docs/api-specification.md`/`docs/architecture.md` — only the sections this change actually touches (Room, Stay, Occupancy, Constraint Engine, ERD/schema).
- Formal sign-off on US-01's acceptance criteria (Open Question 3, still owned by the product owner) — this plan builds against FR-004/FR-005's concrete requirements; acceptance-criteria validation happens once the agency uses this.

## Implementation Approach

Land the `Bed` entity and its CRUD surface first, fully additive and disconnected from `Stay` — this is safe to ship on its own and gives every later phase something real to point at. Only then migrate `Stay` and retire `Room.capacity`/`blockedSpots`, backfilling existing data rather than resetting it (the codebase's own precedent — `V9` narrowed a live column only after verifying the data was safe to narrow). Because three real consumers (`CapacityConstraint`, `OccupancyService`, `ExportService` + its DTOs) call the fields being retired, their compile-preserving updates land in the same phase as the retirement — richer bed-level enrichment for occupancy/export follows in its own later phase, but the mechanical swap can't wait. Stay write paths land once the constraint engine can judge a specific bed; occupancy enrichment and test/doc reconciliation close it out. Every phase keeps `./gradlew build` green rather than leaving a known-red state across a phase boundary — this is a stronger bar than "will compile," since `./gradlew build` runs the full test suite, including every existing Stay-creating integration test.

## Critical Implementation Details

**Migration ordering.** The beds table (`V10`) and the nullable `stays.bed_id`/`bed_auto_assigned` columns (`V11`) must exist before the data backfill (`V12`) can populate them. `V13` (Phase 2) drops `rooms.capacity`/`blocked_spots` once their consumers are updated in the same phase — but it does **not** make `stays.bed_id` `NOT NULL`. That waits for `V14` (Phase 4): until `StayService` actually resolves and sets a bed on every write, an unmodified `create`/`update`/`checkIn`/`bulkAssign` would insert `bed_id = NULL`, and enforcing `NOT NULL` any earlier would fail every existing Stay-creating integration test the moment `./gradlew build` runs them. Each of `V10`-`V14` is a separate file per the "never modify an existing migration" rule.

**A transitional null-bed window exists between Phase 2 and Phase 4, by design.** Phase 2 adds `ConstraintContext.bed` and replaces `CapacityConstraint` with `BedOccupancyConstraint`, but no write path resolves a real bed until Phase 4 — all 8 existing `ConstraintContext` construction sites pass `null` for `bed` in the interim, and `BedOccupancyConstraint` (Phase 2) and the bed-blocked check in `BlockedRoomConstraint` (Phase 3) both treat `ctx.bed() == null` as a no-op. Room/bed-level occupancy and blocked-bed enforcement are genuinely absent from the running system during this window — only worker-level `DoubleBookingConstraint` holds. This is acceptable only because nothing ships mid-sequence: the "no double-booking, over-capacity, or assignment into a blocked room" guardrail is evaluated against the plan's final state (end of Phase 4 onward), not each intermediate phase boundary.

**Auto-assign must re-run the full constraint engine per candidate bed**, not just check bed occupancy. A candidate bed can still fail on `BED_BLOCKED` (bed-level) or `GENDER_MISMATCH` (room-level, soft); picking "first free bed" without re-evaluating every constraint would let a blocked or gender-mismatched bed slip through unassigned-but-unchecked.

## Phase 1: Bed foundation

### Overview

Introduce `Bed` as a first-class, tenant-scoped entity with its own CRUD surface, fully additive — no existing entity changes yet. This is deployable on its own: a room can grow a bed inventory without anything else in the system knowing about it.

### Changes Required:

#### 1. Beds table

**File**: `src/main/resources/db/migration/V10__create_beds.sql`

**Intent**: A new tenant-scoped table, one row per physical bed, scoped to a room — the codebase's flat-module convention (`room/` is top-level, not nested under `property/`) extends naturally to `beds` sitting alongside `rooms`.

**Contract**: `beds(id UUID PK DEFAULT gen_random_uuid(), agency_id UUID NOT NULL REFERENCES agencies(id), room_id UUID NOT NULL REFERENCES rooms(id), label VARCHAR(50) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now())`, constraint `uq_beds_room_label UNIQUE (room_id, label)`, indexes `idx_beds_agency_id`, `idx_beds_room_id`, `idx_beds_agency_room` — matching `V4__create_properties_rooms.sql`'s naming exactly. Bare `REFERENCES` (RESTRICT), no cascade, consistent with every existing FK in the schema.

#### 2. Bed module

**File**: `src/main/java/com/beduno/bed/Bed.java`, `BedStatus.java`, `BedRepository.java`, `BedService.java`, `BedController.java`, `BedMapper.java`, `dto/{CreateBedRequest, BulkGenerateBedsRequest, UpdateBedRequest, BedResponse}.java`

**Intent**: A new domain module shaped exactly like `room/` — entity extends `BaseEntity` and carries `agencyId`; thin controller; service does validation + persistence + audit; MapStruct mapping; DTOs as records.

**Contract**:
- `Bed`: `agencyId`, `roomId`, `label` (String), `status` (`BedStatus`, default `ACTIVE`).
- `BedStatus`: `ACTIVE, BLOCKED`.
- `BedRepository`: `findAllByAgencyIdAndRoomId`, `findByIdAndAgencyId`, `findByIdAndAgencyIdAndRoomId`, `existsByRoomIdAndLabel`, `countByAgencyIdAndRoomId` — all agency-filtered per the hard tenant-isolation rule.
- `BedController`: nested under `/api/v1/properties/{propertyId}/rooms/{roomId}/beds` — `GET` (list), `GET /{bedId}`, `POST` (create one), `POST /bulk-generate` (`BulkGenerateBedsRequest{count}` — see below), `PUT /{bedId}` (rename/status), `DELETE /{bedId}`. Role gating mirrors `RoomController`: reads open to all four roles; create/bulk-generate/update require `AGENCY_ADMIN`/`PROPERTY_ADMIN` with `checkPropertyAccess`; delete requires `AGENCY_ADMIN` only.
- `BedService.bulkGenerate(roomId, count)`: labels the new beds as the next `count` sequential integers after the room's current highest *numeric* label (falls back to starting at `"1"` if the room has no beds yet, or if existing labels aren't purely numeric — a renamed bed like `"top bunk"` doesn't block numbering the rest). Delete guarded by `stayRepository.countByAgencyIdAndBedId(...) > 0` → 409 `error.bed.has_stays`, the same shape as `RoomService.delete`'s guard (`RoomService.java:123-136`). Every write calls `auditService.log(...)` with `AuditEntityType.BED`, following `RoomService`'s pattern exactly.

#### 3. Audit entity type

**File**: `src/main/java/com/beduno/audit/AuditEntityType.java`

**Intent**: `Bed` joins `Stay, Worker, Room, Property` as a first-class audited entity.

**Contract**: add `BED` to the enum.

#### 4. i18n

**File**: all six bundles under `src/main/resources/i18n/`

**Intent/Contract**: add `error.bed.not_found`, `error.bed.has_stays`, `error.bed.label_exists`.

#### 5. Tests

**File**: `src/test/java/com/beduno/bed/BedIntegrationTest.java`

**Intent**: CRUD, bulk-generate numbering (including the renamed-bed-doesn't-block-numbering case), duplicate-label 409, delete-guard 409, and — per `AGENTS.md`'s "every module's suite must include a cross-agency isolation case" — a test asserting agency A cannot read/write agency B's beds.

### Success Criteria:

#### Automated Verification:

- Compiles: `./gradlew compileJava`
- Bed module tests pass: `./gradlew test --tests 'com.beduno.bed.*'`
- Full build green: `./gradlew build`
- Message bundles complete: the build's `MessageBundleTest` passes (all six bundles carry the new keys)

#### Manual Verification:

- Bulk-generate beds on a fresh room via the API and confirm sequential labels
- Rename a bed and confirm the next bulk-generate doesn't collide with the renamed label
- Block a bed via the API and confirm its status is visible on read

---

## Phase 2: Stay schema migration, backfill, capacity retirement, and bed-occupancy constraint

### Overview

Give `Stay` a real (initially nullable) bed pointer, backfill every existing room and stay onto the new model, and retire `Room.capacity`/`blockedSpots` — together with every direct consumer of those fields, in the same phase, so `./gradlew build` (which runs the full test suite) stays green at this phase's own boundary. That includes standing up `ConstraintContext.bed` and a bed-occupancy constraint to replace `CapacityConstraint`, since `CapacityConstraint` cannot survive the field removal either. Every existing call site that doesn't yet have a bed to offer passes `null` — see Critical Implementation Details for why that's a deliberate, temporary state rather than a gap.

### Changes Required:

#### 1. Stay bed columns (nullable)

**File**: `src/main/resources/db/migration/V11__add_stay_bed.sql`

**Intent**: Add the columns before any data migration touches them.

**Contract**: `ALTER TABLE stays ADD COLUMN bed_id UUID REFERENCES beds(id)` (nullable — stays nullable through Phase 3; see Critical Implementation Details for why `NOT NULL` waits for `V14`), `ADD COLUMN bed_auto_assigned BOOLEAN NOT NULL DEFAULT true`. New index `idx_stays_bed_occupancy ON stays (bed_id, status, date_from, date_to)`, the bed-level sibling of the existing `idx_stays_occupancy` on `room_id`.

#### 2. Data backfill

**File**: `src/main/resources/db/migration/V12__backfill_beds.sql`

**Intent**: Every existing room gets a bed inventory sized to its current capacity (if it doesn't have one already from Phase 1's optional API), and every existing stay is deterministically re-pointed at a bed in its current room, so no pre-existing stay is left without a bed.

**Contract**: two data-migration statements: generate beds for rooms with none, sized to `capacity`; then match each room's stays to its beds by rank (stay rank by `created_at`/`id` matched against bed rank by `label`), wrapping around with modulo if a room has more stay rows than beds — safe because historical/non-overlapping stays don't need to satisfy the live occupancy constraint the way a fresh assignment does. Sketch:

```sql
INSERT INTO beds (id, agency_id, room_id, label, status, created_at, updated_at)
SELECT gen_random_uuid(), r.agency_id, r.id, gs::text, 'ACTIVE', now(), now()
FROM rooms r, generate_series(1, r.capacity) gs
WHERE NOT EXISTS (SELECT 1 FROM beds b WHERE b.room_id = r.id);

WITH ranked_stays AS (
  SELECT id, room_id, row_number() OVER (PARTITION BY room_id ORDER BY created_at, id) - 1 AS rn
  FROM stays
), room_bed_counts AS (
  SELECT room_id, count(*) AS bed_count FROM beds GROUP BY room_id
), ranked_beds AS (
  SELECT id, room_id, row_number() OVER (PARTITION BY room_id ORDER BY label) - 1 AS rn
  FROM beds
)
UPDATE stays s SET bed_id = rb.id
FROM ranked_stays rs
JOIN room_bed_counts c ON c.room_id = rs.room_id
JOIN ranked_beds rb ON rb.room_id = rs.room_id AND rb.rn = rs.rn % c.bed_count
WHERE s.id = rs.id;
```

#### 3. Drop room capacity columns

**File**: `src/main/resources/db/migration/V13__drop_room_capacity.sql`

**Intent**: Retire the model this change replaces, now that `V12` has backfilled every stay. Deliberately does **not** touch `stays.bed_id`'s nullability — that's `V14`'s job in Phase 4, once `StayService` guarantees a value on every write (see Critical Implementation Details).

**Contract**: `ALTER TABLE rooms DROP CONSTRAINT chk_rooms_capacity, DROP CONSTRAINT chk_rooms_blocked_spots, DROP COLUMN capacity, DROP COLUMN blocked_spots`. Rationale comment citing this plan and `V9`'s precedent.

#### 4. Room module updates

**File**: `src/main/java/com/beduno/room/{Room, RoomService, RoomMapper}.java`, `dto/{CreateRoomRequest, UpdateRoomRequest, RoomResponse}.java`

**Intent**: Room no longer owns a capacity model — it reports bed-derived counts instead.

**Contract**: `Room.capacity`, `blockedSpots`, `availableSpots()` are removed. `CreateRoomRequest`/`UpdateRoomRequest` lose `capacity`/`blockedSpots`. `RoomResponse` replaces `capacity`/`blockedSpots`/`availableSpots` with `bedCount` (total beds) and `availableBedCount` (beds that are `ACTIVE` and not currently occupied) — computed in `RoomService`, batch-loaded once per property per request (matching the existing `occupantsByRoom` batching pattern, `RoomService.java:157-179`, rather than querying per room). `RoomService.SORTABLE` drops its `capacity`/`blockedSpots` sort keys.

#### 5. Constraint context and bed-occupancy constraint

**File**: `src/main/java/com/beduno/stay/constraint/ConstraintContext.java`; delete `stay/constraint/impl/CapacityConstraint.java`, add `stay/constraint/impl/BedOccupancyConstraint.java`; `src/main/java/com/beduno/stay/StayRepository.java`

**Intent**: `CapacityConstraint` calls `room.availableSpots()`/`getCapacity()`/`getBlockedSpots()` (item 4 above removes all three), so it cannot survive to a later phase — its replacement lands here instead of waiting. A room-level headcount no longer means anything once capacity is bed-derived; the question becomes binary: is this specific bed already taken for this date range, structurally identical to how `DoubleBookingConstraint` checks worker overlap.

**Contract**: `ConstraintContext` gains a `Bed bed` field alongside the existing `Worker, Room, Property, dateFrom, dateTo, excludeStayId`. `BedOccupancyConstraint.evaluate(...)`: if `ctx.bed() == null`, return with no violations (no write path resolves a real bed until Phase 4 — see Critical Implementation Details); otherwise hard violation `BED_OCCUPIED` / `constraint.bed.occupied` (params `bedLabel, roomNumber`), driven by new `StayRepository.countActiveStaysInBed(bedId, agencyId, dateFrom, dateTo)` / `countActiveStaysInBedExcluding(...)` — same JPQL shape as `countActiveStaysInRoom[Excluding]` (`StayRepository.java:65,82`), keyed on `bedId`.

#### 6. Existing ConstraintContext call sites

**File**: `src/main/java/com/beduno/stay/StayService.java` (5 sites: `create` `:95`, `update` `:121`, `checkIn` `:154`, `move` `:211`, `bulkAssign` `:288`), `src/test/java/com/beduno/stay/constraint/ConstraintEngineTest.java` (3 sites: `:52,165,220`)

**Intent**: `ConstraintContext`'s new field is mandatory the moment it exists (it's a record) — every construction site needs a value even though real bed resolution isn't wired until Phase 4.

**Contract**: pass `null` for `bed` at all 8 sites. A one-line touch per site — `StayService`'s actual write-path logic is otherwise untouched until Phase 4.

#### 7. Occupancy consumers of the retired Room fields

**File**: `src/main/java/com/beduno/occupancy/OccupancyService.java`, `ExportService.java`, `dto/{RoomOccupancyResponse, OccupancyExceptionResponse}.java`

**Intent**: These call `room.getCapacity()`/`getBlockedSpots()`/`availableSpots()` directly — the same reason `CapacityConstraint` can't wait, these can't either. Only the compile-preserving swap lands here; per-occupant `bedId`/`bedLabel` enrichment is Phase 5's job.

**Contract**: `OccupancyService`/`ExportService` and the two DTOs replace `capacity`/`blockedSpots` with the same `bedCount`/`availableBedCount` values `RoomService` now computes (item 4). `OVER_CAPACITY` detection (`OccupancyService.java:87`, today `checkedIn.size() > room.availableSpots()`) compares against `availableBedCount` instead — kept as a data-integrity safety net over historical/backfilled data, since going forward `BedOccupancyConstraint` prevents new violations once Phase 4 lands.

#### 8. i18n

**File**: all six bundles

**Intent/Contract**: add `constraint.bed.occupied`.

#### 9. API contract

**File**: `docs/api-specification.md`

**Intent**: Reconcile the Room contract section with the same dated-changelog discipline `V9`'s note used.

**Contract**: update the documented `RoomResponse`/request JSON shapes; add a changelog line dated today citing this change and `V10`-`V13`.

### Success Criteria:

#### Automated Verification:

- Compiles: `./gradlew compileJava`
- Room module tests pass: `./gradlew test --tests 'com.beduno.room.*'`
- Constraint engine tests pass (bed-occupied case, null-bed no-op case): `./gradlew test --tests 'com.beduno.stay.constraint.*'`
- Occupancy module tests pass: `./gradlew test --tests 'com.beduno.occupancy.*'`
- Full build green, migrations `V10`-`V13` apply cleanly against Testcontainers Postgres: `./gradlew build`
- No remaining `capacity`/`blockedSpots` usage outside historical migrations, across `room`, `occupancy`, and `stay/constraint` source (`grep -rn "capacity\|blockedSpots" src/main/java/com/beduno/{room,occupancy,stay/constraint}` returns nothing outside comments)

#### Manual Verification:

- Boot against the local dev database (`docker compose -f docker/docker-compose.yml up -d` + `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun`) and confirm `V10`-`V13` apply cleanly against whatever seed/demo data exists, backfilling beds for every pre-existing room
- `GET` an existing room via Swagger UI and confirm the response reports `bedCount`/`availableBedCount` instead of `capacity`/`blockedSpots`/`availableSpots`
- Confirm the existing Stay API (create/check-in/move) still behaves exactly as before — bed resolution is inert (always `null`) at this point, by design

---

## Phase 3: Blocked-bed constraint

### Overview

Extend `BlockedRoomConstraint` with a bed-level check now that beds carry their own status — the last piece of constraint-engine bed-awareness. Kept separate from Phase 2 because, unlike `CapacityConstraint`, `BlockedRoomConstraint` doesn't call any member `Room.capacity`'s retirement removes, so nothing forces it into the same phase.

### Changes Required:

#### 1. Extend BlockedRoomConstraint

**File**: `src/main/java/com/beduno/stay/constraint/impl/BlockedRoomConstraint.java`

**Intent**: A blocked bed rejects a placement the same way a blocked room or inactive property does today.

**Contract**: add a third check, `ctx.bed() != null && ctx.bed().getStatus() == BedStatus.BLOCKED` → hard violation `BED_BLOCKED` / `constraint.bed.blocked` with a `bedLabel` param, alongside the existing `ROOM_BLOCKED`/`PROPERTY_INACTIVE` checks (`BlockedRoomConstraint.java:19-33`). The null-guard matches `BedOccupancyConstraint`'s Phase 2 no-op behavior — no bed is resolved yet.

#### 2. GenderConstraint

**File**: `src/main/java/com/beduno/stay/constraint/impl/GenderConstraint.java`

**Intent**: No behavior change — gender rule stays room-level, and `ctx.room()` remains populated exactly as today.

**Contract**: none; confirm it compiles unchanged against the `ConstraintContext` shape Phase 2 already finalized.

#### 3. i18n

**File**: all six bundles

**Intent/Contract**: add `constraint.bed.blocked`.

#### 4. Tests

**File**: `src/test/java/com/beduno/stay/constraint/ConstraintEngineTest.java`

**Intent**: Extend with the bed-blocked case, following the existing `shouldBlockOperation_whenRoomIsBlocked`-style naming (the bed-occupied case was already added in Phase 2).

### Success Criteria:

#### Automated Verification:

- Constraint engine tests pass: `./gradlew test --tests 'com.beduno.stay.constraint.*'`
- Full build green: `./gradlew build`

---

## Phase 4: Stay write-path bed integration

### Overview

Every write path that places a worker now resolves a bed — the planner's explicit choice or the system's auto-assigned pick — and records which happened. This is also where `stays.bed_id` finally becomes mandatory at the database level, once every write path guarantees a value, and where `BED_OCCUPIED`/`BED_BLOCKED` become observable through the running API for the first time.

### Changes Required:

#### 1. Stay entity

**File**: `src/main/java/com/beduno/stay/Stay.java`

**Intent**: The bed becomes the actual placement pointer; `propertyId`/`roomId` remain as denormalized fields, following the existing convention where `propertyId` is already kept alongside `roomId` rather than derived from it (`Stay.java:28-32`) — this keeps every existing property/room-scoped query and index working unchanged.

**Contract**: `bedId` (already added as a nullable column in Phase 2) becomes Java-level `nullable = false` from this phase onward, since `resolveBed` (below) guarantees a value on every write path; add `bedAutoAssigned` (boolean, not null, default `true`).

#### 2. Bed resolution and every write path

**File**: `src/main/java/com/beduno/stay/StayService.java`

**Intent**: Centralize "given a room (and optionally a planner-chosen bed), resolve the bed to place this worker in" into one path used by every operation, rather than duplicating auto-assign logic five times.

**Contract**:
- New private `resolveBed(room, worker, property, dateFrom, dateTo, requestedBedId, excludeStayId) -> BedAssignment(Bed bed, boolean autoAssigned)`: if `requestedBedId` is present, load it (must belong to `room`, else `ValidationException` — `error.bed.not_in_room`), run the full constraint engine against it, and mark `autoAssigned=false`. If absent, iterate the room's `ACTIVE` beds ordered by label, running the full constraint engine per candidate (see Critical Implementation Details), returning the first that passes with `autoAssigned=true`; if none pass, throw with the first candidate's hard violations, or — if the room has zero beds at all — a `BED_UNAVAILABLE` / `constraint.bed.unavailable` violation, so the resulting 422 has the same shape as today's capacity rejection.
- `create` (`:87-106`), `update` (`:108-131`), `checkIn` (`:140-168`), `bulkAssign` (`:274-311`) each call `resolveBed` (replacing their `null` placeholder from Phase 2) instead of resolving only a room, and persist the resulting `bedId`/`bedAutoAssigned`.
- `move` (`:185-234`): replace the same-room rejection (`:193-195`) with a same-*bed* rejection evaluated after the target bed is resolved — a move to a different bed within the same room now succeeds. `MoveRequest` gains an optional `targetBedId`; when absent, auto-assign within `targetRoomId`.
- `snapshot(stay)` (`:402-416`) adds `bedId`, `bedAutoAssigned` to the audit map.
- Incidental fix: `create` (`:103-104`) and `bulkAssign` (`:301-302`) currently pass `null` for the audit `reason` instead of the caller-supplied `overrideReason`, unlike `update`/`checkIn`/`move`. Since these exact lines are already being rewritten for the bed fields, pass the real reason through — closes a pre-existing audit gap at no extra cost.

#### 3. Stay bed column becomes mandatory

**File**: `src/main/resources/db/migration/V14__require_stay_bed.sql`

**Intent**: Once `resolveBed` guarantees every write path sets a bed, make that guarantee structural — applied at the end of this phase, once item 2 lands.

**Contract**: `ALTER TABLE stays ALTER COLUMN bed_id SET NOT NULL`. Rationale comment noting this completes the split from `V13` and citing the reason it waited (see Critical Implementation Details).

#### 4. Stay DTOs

**File**: `src/main/java/com/beduno/stay/dto/{CreateStayRequest, UpdateStayRequest, CheckInRequest, MoveRequest, BulkAssignRequest, StayResponse, StaySummary, BulkAssignResult}.java`, `StayMapper.java`

**Intent**: Expose the planner-override field on writes and the resolved bed on reads.

**Contract**: `CreateStayRequest`/`UpdateStayRequest`/`BulkAssignRequest.Assignment` gain an optional `bedId` (`roomId`/`propertyId` remain required as the auto-assign scope). `CheckInRequest` gains optional `bedId` alongside its existing `roomId` override. `MoveRequest` gains optional `targetBedId`. `StayResponse`/`StaySummary` gain `bedId`, `bedAutoAssigned`. `BulkAssignResult.AssignmentResult` gains `bedId`, so a bulk caller sees which bed each row landed on without a follow-up `GET`.

#### 5. i18n

**File**: all six bundles

**Intent/Contract**: add `error.bed.not_in_room` (requested `bedId` doesn't belong to the target room) and `BED_UNAVAILABLE` / `constraint.bed.unavailable` (the room has no eligible bed at all — `resolveBed`'s empty-candidate case).

#### 6. API contract

**File**: `docs/api-specification.md`

**Intent/Contract**: reconcile the Stay contract section (request/response shapes, the move/check-in override description) with a dated changelog line citing `V11`-`V14` and this plan, matching `V9`'s precedent.

### Success Criteria:

#### Automated Verification:

- Compiles: `./gradlew compileJava`
- Stay module tests pass: `./gradlew test --tests 'com.beduno.stay.*'` (existing fixtures updated minimally to supply a bed so the suite stays green — full test enrichment is Phase 6)
- Full build green, migration `V14` applies cleanly: `./gradlew build`
- Message bundles complete for the two new codes (`MessageBundleTest` passes)

#### Manual Verification:

- Create a stay without `bedId` via the API and confirm the system auto-assigns the lowest-label free bed, with `bedAutoAssigned=true` on read
- Create a stay with an explicit `bedId` and confirm `bedAutoAssigned=false`
- Attempt to move a checked-in stay to its own current bed and confirm rejection; move it to a different bed in the same room and confirm success
- Check the audit log for a stay created with an `overrideReason` and confirm the reason is now recorded
- Attempt to assign two overlapping stays to the same bed via the API and confirm a 422 with `BED_OCCUPIED`
- Block a bed and confirm assignment to it via the API is rejected with `BED_BLOCKED` even when an `overrideReason` is supplied — hard violations stay non-overridable

---

## Phase 5: Occupancy and export bed-level detail

### Overview

Occupancy views, the inspection roster, and CSV exports already report bed-derived aggregate counts as of Phase 2 (`bedCount`/`availableBedCount` in place of `capacity`/`blockedSpots`). This phase adds the finer-grained detail: which specific bed each occupant is in, so two workers in the same room can be told apart.

### Changes Required:

#### 1. OccupancyService

**File**: `src/main/java/com/beduno/occupancy/OccupancyService.java`

**Intent**: Report per-bed occupancy, not just per room.

**Contract**: `getOccupancy`/`getExceptions`/`getInspectionRoster` (`:44-128`) group stays by `Stay::getBedId` in addition to room; each occupant entry carries `bedId`/`bedLabel`.

#### 2. Occupancy DTOs

**File**: `src/main/java/com/beduno/occupancy/dto/{OccupantSummary, RoomOccupancyResponse, OccupancyExceptionResponse, InspectionRoomEntry, RoomActualOccupancy, InspectionReportRequest}.java`

**Intent/Contract**: add `bedId`/`bedLabel` wherever an occupant is listed.

#### 3. Export CSVs

**File**: `src/main/java/com/beduno/occupancy/ExportService.java`

**Intent**: Exports gain a per-occupant bed column, alongside the `BedCount`/`AvailableBedCount` aggregate columns Phase 2 already introduced in place of `Capacity`/`Blocked`.

**Contract**: occupancy/arrivals/exception CSV headers gain a `Bed` column carrying the occupant's bed label.

#### 4. API contract

**File**: `docs/api-specification.md`

**Intent/Contract**: reconcile the Occupancy/Inspection and CSV export sections.

### Success Criteria:

#### Automated Verification:

- Occupancy module tests pass: `./gradlew test --tests 'com.beduno.occupancy.*'`
- Full build green: `./gradlew build`

#### Manual Verification:

- Pull an occupancy CSV export and confirm each occupant row names a bed
- Pull the inspection roster for a property and confirm two workers in the same room are distinguished by bed

---

## Phase 6: Test suite reconciliation and documentation close-out

### Overview

Bring every existing fixture and assertion in line with the bed model, add the coverage the bed-specific behavior still lacks, and reconcile the architecture documentation.

### Changes Required:

#### 1. Existing integration tests

**File**: `StayIntegrationTest`, `OperationalWorkflowIntegrationTest`, `BulkOperationsIntegrationTest`, `StayGuardIntegrationTest`, `RoomIntegrationTest`, `RoomOccupantsIntegrationTest`, `RoomSortIntegrationTest`, `ExportIntegrationTest` (`src/test/java/com/beduno/{stay,room,occupancy}/`)

**Intent**: Fixture helpers create beds alongside rooms; sort-key and response-shape assertions follow the retired `capacity`/`blockedSpots` fields; `OperationalWorkflowIntegrationTest.shouldRejectMove_whenSameRoom` (`:223`) inverts to a same-*bed* rejection now that a same-room, different-bed move succeeds.

**Contract**: no new endpoints — assertion and fixture updates only, following the existing `should{Behavior}_when{Condition}` naming and `@Nested` class structure.

#### 2. New coverage

**File**: `src/test/java/com/beduno/stay/BedAssignmentIntegrationTest.java`

**Intent**: Coverage the bed-specific behavior doesn't yet have: auto-assign picks the lowest-label free bed; an explicit override sets `bedAutoAssigned=false`; a blocked or occupied bed is rejected; a room with no free bed fails hard; a cross-agency isolation case for the bed-touching write paths (per `AGENTS.md`).

#### 3. Architecture documentation

**File**: `docs/architecture.md`

**Intent**: Reconcile the ERD, Key Tables, and Constraint Engine sections with the shipped schema, following the file's own "reconciled against the implementation on `<date>`" convention (line 3).

**Contract**: ERD shows `Bed` between `Room` and `Stay`; `rooms`/`stays` table blocks drop `capacity`/`blocked_spots`/add `bed_id`/`bed_auto_assigned`; a `beds` table block is added; the Constraint Engine diagram replaces `CapacityConstraint` with `BedOccupancyConstraint`.

### Success Criteria:

#### Automated Verification:

- Full build green, including Checkstyle: `./gradlew build`
- Full test suite passes: `./gradlew test`
- No remaining `capacity`/`blockedSpots` references outside `V1`-`V9` migration files: `grep -rn "capacity\|blockedSpots" src/main/java src/main/resources/db/migration/V1[0-4]*.sql` returns nothing unexpected
- Cross-agency isolation test passes for the new bed-touching write paths

#### Manual Verification:

- Walk through US-01's scenario end-to-end by hand: plan a small wave across a property's rooms, let some workers auto-assign and override one explicitly, confirm the occupancy export and audit log both show bed-level detail
- Hand the updated `docs/api-specification.md` to whoever owns the frontend session — the coordinated contract-break follow-up noted in `change.md`

---

## Testing Strategy

### Unit Tests:

- `ConstraintEngineTest`: bed-occupied (Phase 2), bed-blocked (Phase 3), and null-bed no-op cases alongside the existing four constraints.

### Integration Tests:

- `BedIntegrationTest`: CRUD, bulk-generate numbering, duplicate-label conflict, delete guard, cross-agency isolation.
- `BedAssignmentIntegrationTest`: auto-assign, override, blocked/occupied rejection, no-free-bed hard failure, cross-agency isolation.
- Existing Stay/Room/Occupancy suites updated for bed fixtures rather than replaced.

### Manual Testing Steps:

1. Boot against the local dev database and confirm the full `V10`-`V14` migration chain applies cleanly.
2. Plan a small wave via the API: some workers auto-assigned, one explicitly overridden to a specific bed.
3. Confirm the occupancy export, inspection roster, and audit log all show bed-level detail for that wave.
4. Confirm a same-room bed move succeeds and a same-bed move is rejected.

## Performance Considerations

Room list/read endpoints now need a bed-count aggregate instead of reading two ints off the room row. Batch-load bed and occupancy counts once per property per request — the same pattern `RoomService.occupantsByRoom` already uses for worker loads (`RoomService.java:157-179`, one query per property rather than per room) — rather than querying beds per room in a loop.

## Migration Notes

`V10`-`V14` backfill existing rooms and stays rather than resetting them, per the confirmed decision. `stays.bed_id` becomes `NOT NULL` in two steps across `V13`/`V14` rather than one, deliberately: `V13` (Phase 2) only drops `rooms.capacity`/`blocked_spots`, and `V14` (Phase 4) enforces `bed_id NOT NULL` only once `StayService` guarantees a value on every write — see Critical Implementation Details. There is no rollback migration in this codebase's convention (migrations are never modified or reversed; a problem found after `V14` ships is fixed by a new forward migration, same as every prior migration here).

## References

- Roadmap: `context/foundation/roadmap.md` — `S-01`, the north star.
- PRD: `context/foundation/prd.md` — FR-004, FR-005, FR-018, US-01.
- Precedent for a deliberate, documented contract break: `src/main/resources/db/migration/V9__room_contract_alignment.sql`, `docs/api-specification.md` §4 changelog note.
- Precedent for CRUD + audit pattern to follow: `src/main/java/com/beduno/room/RoomService.java`.
- Closest existing analogue for bed-occupied overlap checking: `src/main/java/com/beduno/stay/constraint/impl/DoubleBookingConstraint.java`.
- Existing per-operation override precedent: `CheckInRequest.roomId` (`src/main/java/com/beduno/stay/dto/CheckInRequest.java`).
- Plan review: `context/changes/named-beds/reviews/plan-review.md` — resolved 2 critical phase-sequencing findings (F1, F2) plus a related NOT NULL-timing issue found during triage, and 1 warning (F3, missing message codes). All three are folded into this plan.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Bed foundation

#### Automated

- [x] 1.1 Compiles: `./gradlew compileJava` — 17f7dfb
- [x] 1.2 Bed module tests pass: `./gradlew test --tests 'com.beduno.bed.*'` — 17f7dfb
- [x] 1.3 Full build green: `./gradlew build` — 17f7dfb
- [x] 1.4 Message bundles complete (MessageBundleTest passes) — 17f7dfb

#### Manual

- [x] 1.5 Bulk-generate beds on a fresh room via the API and confirm sequential labels — 17f7dfb
- [x] 1.6 Rename a bed and confirm the next bulk-generate doesn't collide with the renamed label — 17f7dfb
- [x] 1.7 Block a bed via the API and confirm its status is visible on read — 17f7dfb

### Phase 2: Stay schema migration, backfill, capacity retirement, and bed-occupancy constraint

#### Automated

- [x] 2.1 Compiles: `./gradlew compileJava` — ce21d1b
- [x] 2.2 Room module tests pass: `./gradlew test --tests 'com.beduno.room.*'` — ce21d1b
- [x] 2.3 Constraint engine tests pass (bed-occupied + null-bed no-op cases) — ce21d1b
- [x] 2.4 Occupancy module tests pass: `./gradlew test --tests 'com.beduno.occupancy.*'` — ce21d1b
- [x] 2.5 Full build green, migrations V10-V13 apply cleanly: `./gradlew build` — ce21d1b
- [x] 2.6 No remaining capacity/blockedSpots usage across room/occupancy/stay-constraint source — ce21d1b

#### Manual

- [x] 2.7 Boot against local dev DB and confirm V10-V13 backfill cleanly against seeded data — ce21d1b
- [x] 2.8 GET an existing room via Swagger and confirm bedCount/availableBedCount replace capacity/blockedSpots/availableSpots — ce21d1b
- [x] 2.9 Existing Stay API (create/check-in/move) behaves unchanged — bed resolution is inert (null) at this point — ce21d1b

### Phase 3: Blocked-bed constraint

#### Automated

- [x] 3.1 Constraint engine tests pass (bed-blocked case): `./gradlew test --tests 'com.beduno.stay.constraint.*'`
- [x] 3.2 Full build green: `./gradlew build`

### Phase 4: Stay write-path bed integration

#### Automated

- [ ] 4.1 Compiles: `./gradlew compileJava`
- [ ] 4.2 Stay module tests pass: `./gradlew test --tests 'com.beduno.stay.*'`
- [ ] 4.3 Full build green, migration V14 applies cleanly: `./gradlew build`
- [ ] 4.4 Message bundles complete for the two new codes (MessageBundleTest passes)

#### Manual

- [ ] 4.5 Stay created without bedId auto-assigns the lowest-label free bed, bedAutoAssigned=true
- [ ] 4.6 Stay created with explicit bedId has bedAutoAssigned=false
- [ ] 4.7 Move to current bed rejected; move to a different bed in the same room succeeds
- [ ] 4.8 Audit log records the overrideReason on a created stay (closes the pre-existing create/bulkAssign gap)
- [ ] 4.9 Two overlapping stays assigned to the same bed via the API return 422 BED_OCCUPIED
- [ ] 4.10 Assignment to a blocked bed via the API is rejected with BED_BLOCKED even with an overrideReason

### Phase 5: Occupancy and export bed-level detail

#### Automated

- [ ] 5.1 Occupancy module tests pass: `./gradlew test --tests 'com.beduno.occupancy.*'`
- [ ] 5.2 Full build green: `./gradlew build`

#### Manual

- [ ] 5.3 Occupancy CSV export names a bed per occupant row
- [ ] 5.4 Inspection roster distinguishes same-room workers by bed

### Phase 6: Test suite reconciliation and documentation close-out

#### Automated

- [ ] 6.1 Full build green including Checkstyle: `./gradlew build`
- [ ] 6.2 Full test suite passes: `./gradlew test`
- [ ] 6.3 No remaining capacity/blockedSpots references outside V1-V9 migrations
- [ ] 6.4 Cross-agency isolation test passes for bed-touching write paths

#### Manual

- [ ] 6.5 US-01 wave walkthrough end-to-end, bed-level detail confirmed in export and audit log
- [ ] 6.6 Updated docs/api-specification.md handed to the frontend session
