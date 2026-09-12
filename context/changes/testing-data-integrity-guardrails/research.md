---
date: 2026-09-12T16:42:42+02:00
researcher: claude-sonnet-5
git_commit: 2b01fd8bbcfe356277bee070b051eecd8e11b04e
branch: main
repository: beduno-be
topic: "Data-integrity guardrails — RESTRICT-FK delete guards and migration/backfill correctness (test-plan.md rollout Phase 3, risks #4 and #6)"
tags: [research, codebase, delete-guards, foreign-keys, flyway, migrations, backfill]
status: complete
last_updated: 2026-09-12
last_updated_by: claude-sonnet-5
---

# Research: Data-integrity guardrails — RESTRICT-FK delete guards and migration/backfill correctness

**Date**: 2026-09-12T16:42:42+02:00
**Researcher**: claude-sonnet-5
**Git Commit**: 2b01fd8bbcfe356277bee070b051eecd8e11b04e
**Branch**: main
**Repository**: beduno-be

## Research Question

For rollout Phase 3 of `context/foundation/test-plan.md` ("Data-integrity guardrails", risks #4 and #6):

- **#4**: Does every entity with a RESTRICT foreign key referenced by an active row return a clean 409 on delete, never an unhandled 500 — across current entities (property, room, bed) and the next one the roadmap introduces? Challenge the assumption that fixing the guard for one entity makes the pattern safe everywhere.
- **#6**: Does a migration that alters or backfills a table with pre-existing rows (not a fresh empty DB) produce the exact expected end state, verified against the next roadmap migration in sequence? Challenge the assumption that running clean against Testcontainers' fresh schema implies safety against populated data.

## Summary

**Risk #4 — delete guards.** All 14 foreign keys in the schema are implicit `NO ACTION` (no migration ever writes `ON DELETE`), which behaves as RESTRICT absent deferred constraints. Of the six tables that are FK-referenced (`agencies`, `users`, `workers`, `properties`, `rooms`, `beds`), only three have a real hard-delete code path at all: **Property, Room, Bed** — and all three are already guarded with an explicit `countBy...` pre-check that throws `ConflictException` → clean 409, following a consistent, well-commented pattern. `Worker.delete` is soft-delete only (never touches the FK). `Agency` and `User` have no delete code path in the app whatsoever — the RESTRICT FKs pointing at them are real but structurally unreachable through the API today.

There is **no generic safety net**: `GlobalExceptionHandler` has zero handling for `DataIntegrityViolationException`/Postgres `23503`. Every existing guard is a hand-written pre-check; nothing catches a forgotten or removed guard except the DB itself surfacing as an unhandled 500. Two concrete, currently-untested guard branches exist today: `RoomService`'s `error.room.has_beds` path (zero test coverage) and `PropertyService`'s `error.property.has_stays` path (structurally unreachable in current flow — stays always imply a room, so `has_rooms` fires first — making this dead code worth flagging, not testing as a live path).

**The "next entity" for risk #4 is ambiguous and needs a plan-time decision**, not a research-time one: roadmap slice S-04 (`worker-crews`, `ready`) introduces a new `crews` entity but the roadmap text gives no indication of a RESTRICT FK requiring a guard (looks additive). There is no other concretely `ready` entity-introducing slice with an obvious RESTRICT-FK delete-guard need. This should be surfaced to `/10x-plan` as an open question rather than assumed.

**Risk #6 — migration/backfill correctness.** No migration-testing harness exists. `IntegrationTestBase` boots one shared Testcontainers Postgres and lets Spring Boot's `FlywayAutoConfiguration` run the full V1→V14 chain once, against an always-empty database, before any test method executes. No test anywhere seeds rows before a migration and asserts the migration transformed them — meaning `V12__backfill_beds.sql`'s actual backfill logic (re-pointing every existing stay at a bed by rank/modulo) has never been exercised against non-trivial data in an automated test, only manually in dev/prod per `named-beds/plan.md:233`.

There is a real, already-lived incident that validates this risk: `context/changes/named-beds/reviews/plan-review.md:63-65` caught, during plan review (not testing), that the original migration ordering would have made `stays.bed_id NOT NULL` before `StayService` guaranteed setting it — every Stay-creating test would have hit a constraint violation. It was caught by re-reading the plan, not by a migration test, because no such test exists.

**The next concrete migration is roadmap slice S-02 (`external-hotel-bookings`, status `proposed`, blocked on `S-01`)**, whose own risk note in `roadmap.md:163` explicitly states it will alter the `stays` table again — the same table V11–V14 already altered and backfilled — and is deliberately sequenced right after S-01 "because both reshape what a stay points at." This is the strongest, most concretely-grounded target for a migration-testing harness pattern, though S-02 itself is not yet planned or built (status `proposed`, prerequisite `S-01` = the already-completed `named-beds` change). A harness built now should be validated against the **existing** V10→V14 backfill (already shipped, real production-shaped data available to reason about) rather than waiting on S-02 to exist.

**Also directly relevant**: `context/changes/testing-constraint-engine-hardening/research.md` already flagged, and left open, that V12's backfilled bed assignments were never validated against `BedOccupancyConstraint` — i.e., the backfill could in principle have produced two overlapping stays sharing a bed. This is a live, named, unresolved gap from the already-completed Phase 1 rollout and is square in scope for this phase.

## Detailed Findings

### Risk #4 — RESTRICT foreign keys and delete guards

#### Schema: every RESTRICT-behaving FK (all implicit `NO ACTION`, none explicit, no `ON DELETE CASCADE`/`SET NULL` anywhere in the schema)

| Constraining table.column | Referenced table.column | Migration:line |
|---|---|---|
| `users.agency_id` | `agencies.id` | `V2__create_users.sql:3` |
| `workers.agency_id` | `agencies.id` | `V3__create_workers.sql:3` |
| `properties.agency_id` | `agencies.id` | `V4__create_properties_rooms.sql:3` |
| `rooms.agency_id` | `agencies.id` | `V4__create_properties_rooms.sql:18` |
| `rooms.property_id` | `properties.id` | `V4__create_properties_rooms.sql:19` |
| `stays.agency_id` | `agencies.id` | `V5__create_stays.sql:3` |
| `stays.worker_id` | `workers.id` | `V5__create_stays.sql:4` |
| `stays.property_id` | `properties.id` | `V5__create_stays.sql:5` |
| `stays.room_id` | `rooms.id` | `V5__create_stays.sql:6` |
| `stays.confirmed_by_user_id` | `users.id` | `V5__create_stays.sql:11` (nullable column, but no `ON DELETE SET NULL` written — still NO ACTION) |
| `audit_events.agency_id` | `agencies.id` | `V6__create_audit_events.sql:3` |
| `beds.agency_id` | `agencies.id` | `V10__create_beds.sql:5` |
| `beds.room_id` | `rooms.id` | `V10__create_beds.sql:6` |
| `stays.bed_id` | `beds.id` | `V11__add_stay_bed.sql:5` |

#### Which referenced entities have an app-level guard

| Entity | Hard-delete code path exists? | Guard | Citation |
|---|---|---|---|
| Property | Yes | `roomRepository.countByAgencyIdAndPropertyId` + `stayRepository.countByAgencyIdAndPropertyId` pre-checks → `ConflictException` (`error.property.has_rooms` / `error.property.has_stays`) before `propertyRepository.delete` | `property/PropertyService.java:87-105` (checks 94-99, delete 102) |
| Room | Yes | `stayRepository.countByAgencyIdAndRoomId` + `bedRepository.countByAgencyIdAndRoomId` pre-checks → `ConflictException` (`error.room.has_stays` / `error.room.has_beds`) before `roomRepository.delete` | `room/RoomService.java:123-139` (checks 128-133, delete 136) |
| Bed | Yes | `stayRepository.countByAgencyIdAndBedId` pre-check → `ConflictException` (`error.bed.has_stays`) before `bedRepository.delete` | `bed/BedService.java:128-143` (check 135, delete 140) |
| Worker | Soft delete only | Sets `status=DELETED` + `deletedAt`; never issues a SQL `DELETE`, so `stays.worker_id` FK is never exercised | `worker/WorkerService.java:113-122` |
| Agency | No delete path at all | No `AgencyService`, no delete endpoint | confirmed via repo-wide grep — no hits |
| User | No delete path at all | No `UserService`, no delete endpoint | confirmed via repo-wide grep — no hits |
| Stay | N/A (leaf table, not referenced by others) | `StayService` has no delete/remove method at all — only status transitions | confirmed via repo-wide grep — no hits |

Every existing guard is hand-written and explicitly comments the RESTRICT FK it mirrors, e.g. `room/RoomService.java:127`: `// stays.room_id and beds.room_id are both RESTRICT foreign keys — see PropertyService.delete.` This is a deliberate, consistently-applied pattern, not an accident — but it is **per-entity and per-developer-remembered**, with no structural enforcement (no shared base guard, no annotation, no generic exception translator) that would catch a fourth entity's developer forgetting to add the same pre-check.

#### Generic safety net: none

`GlobalExceptionHandler` (`common/exception/GlobalExceptionHandler.java`) has handlers for `AccessDeniedException`, `UnauthorizedException`, `NotFoundException`, `ConflictException` (→409), `ConstraintViolationException` (→422), `ForbiddenException`, `ValidationException`, plus `handleMethodArgumentNotValid`/`handleExceptionInternal` overrides and a catch-all `handleGeneral(Exception.class)` (`GlobalExceptionHandler.java:123-128`) that logs "Unhandled exception" and returns bare `500 INTERNAL_ERROR`/`error.internal`. Repo-wide grep for `DataIntegrityViolationException`, `23503`, `foreign_key_violation` returns **zero matches**. Any future delete path that omits the pre-check guard — or an existing guard accidentally removed in a refactor — falls straight through to that generic 500.

#### Exception/message-code convention already established

- `BusinessException` (abstract, `common/exception/BusinessException.java:6-19`) carries `messageCode`; all business exceptions extend it.
- `ConflictException extends BusinessException` → mapped to 409 in `GlobalExceptionHandler.java:52-56`. This is the exact type used for "cannot delete, has dependents."
- Message-code convention: `error.<entity>.<reason>` (snake_case reason) — `error.property.has_rooms`, `error.property.has_stays`, `error.room.has_stays`, `error.room.has_beds`, `error.bed.has_stays` — defined per-locale in `src/main/resources/i18n/messages{,_en,_pl,_de,_uk,_ru}.properties:63-67` (all six bundles).
- Other exception types for contrast: `NotFoundException`→404, `ForbiddenException`→403, `UnauthorizedException`→401, `ValidationException`→400, `ConstraintViolationException`→422 (structured `ViolationDetail` list — a different concern, the stay-placement constraint engine, not FK dependents).

#### Existing test coverage

- `src/test/java/com/beduno/property/DeletionGuardIntegrationTest.java` — dedicated delete-guard suite:
  - `PropertyDeletion.shouldReturnConflict_whenPropertyStillHasRooms` (line 44) ✓
  - `PropertyDeletion.shouldDeleteProperty_whenNothingReferencesIt` (line 55) ✓ (happy path)
  - `RoomDeletion.shouldReturnConflict_whenStaysReferenceRoom` (line 68) ✓
  - `RoomDeletion.shouldReturnConflict_whenOnlyTerminalStaysReferenceRoom` (line 80) ✓ — confirms a CANCELLED stay still blocks (FK survives status change)
  - `RoomDeletion.shouldDeleteRoom_whenNoStaysReferenceIt` (line 94) ✓ (happy path)
  - **`error.room.has_beds` — zero coverage.** No test creates a room with beds (but no stays) and asserts the room-delete 409. Grepped `has_beds`/`HasBeds`/`StillHasBeds` across `src/test/java` — no hits.
  - **`error.property.has_stays` — untested and likely unreachable in practice.** A stay always requires a room, and a property with any room triggers `has_rooms` first (`PropertyService.java:94-99` order), so the `has_stays` branch (checked second) may be dead code under current business rules. Worth confirming during planning rather than writing a test that can never actually reach that branch through normal API use.
- `src/test/java/com/beduno/bed/BedIntegrationTest.java` `Delete` nested class: `shouldDeleteBed` (line 156, happy path) and `shouldRejectDelete_whenStayReferencesBed` (line 171-180, covers `error.bed.has_stays`) ✓
- `src/test/java/com/beduno/worker/WorkerIntegrationTest.java`: `shouldSoftDeleteWorker` (line 167) ✓ confirms soft-delete, no FK risk.
- `src/test/java/com/beduno/common/exception/ErrorContractIntegrationTest.java` covers general 4xx-vs-500 contract (wrong verb, bad JSON, wrong content-type, bad UUID path var, UTF-8 on 401) but has no test proving an FK violation would currently surface as a 500 — that's an inferred gap from the missing handler, not something asserted anywhere today.

#### "Next entity" for risk #4 — open question, not settled by research

Roadmap slice **S-04 (`worker-crews`, `ready`, `roadmap.md:48,131-137`)** is the most likely candidate the test-plan's phrasing ("the next entity the roadmap introduces") points at, but its roadmap description gives no indication of a RESTRICT FK requiring an application-level delete guard — it reads as additive (new `crews` table + join/FK), and its own risk note only flags "highest chance of being modelled wrong," not a delete-guard concern. No other `ready` roadmap slice concretely introduces a new FK-referenced entity. **This should go to `/10x-plan` as an explicit scope question**: either (a) treat S-04/`worker-crews` as the target once it exists and write a delete-guard test the moment it ships (deferring this phase's #4 scope to "current three entities only"), or (b) scope #4 purely to closing the two existing coverage gaps (`has_beds`, and confirming `has_stays` reachability) on Property/Room/Bed, with no forward-looking "next entity" component at all, since none concretely exists yet.

### Risk #6 — migration/backfill correctness

#### Current migration test setup

- `src/test/java/com/beduno/IntegrationTestBase.java:23-28` — a single `PostgreSQLContainer<>("postgres:16-alpine")` started once in a static block, shared across the whole test JVM run (not per test class).
- `IntegrationTestBase.java:30-45` — `@DynamicPropertySource` wires the container's JDBC URL/credentials; explicitly sets `spring.flyway.enabled=true` (line 36) and `spring.jpa.hibernate.ddl-auto=validate` (line 35) — Hibernate never creates schema; Flyway is the only schema authority.
- `src/main/resources/application.yml:12-17` — baseline `flyway.enabled: true`, `locations: classpath:db/migration`, inherited by every profile.
- Flyway runs via Spring Boot's `FlywayAutoConfiguration`, triggered automatically the first time the `@SpringBootTest` context initializes against the container — not an explicit call anywhere in test code. Repo-wide grep for `Flyway`, `.migrate(`, `baselineVersion`, `.target(` across `src/test/java/com/beduno/` returns zero matches outside the one property line.
- **No test seeds rows before a migration runs and asserts the migration transformed them.** Every integration test inserts fixtures via `TestRestTemplate`/`jdbcTemplate` after the full V1→V14 chain has already applied to an empty schema — meaning `V12__backfill_beds.sql`'s actual backfill logic runs as a no-op in every test today (nothing to backfill against zero rows).

#### Migration sequence (14 total, in order)

| File | What it does |
|---|---|
| V1__create_agencies.sql | Creates `agencies` |
| V2__create_users.sql | Creates `users` |
| V3__create_workers.sql | Creates `workers` |
| V4__create_properties_rooms.sql | Creates `properties`, `rooms` (with `capacity`/`blocked_spots`) |
| V5__create_stays.sql | Creates `stays` |
| V6__create_audit_events.sql | Creates `audit_events` |
| V7__add_stay_no_show_reason.sql | Adds `stays.no_show_reason` |
| V8__unique_user_email.sql | Adds global `UNIQUE(email)` on `users` |
| V9__room_contract_alignment.sql | Renames `rooms.name`→`room_number`, narrows `floor` type, remaps `gender_rule` values — a data transformation on a live column, verified empty in prod before shipping |
| V10__create_beds.sql | Creates `beds` (fully additive) |
| V11__add_stay_bed.sql | Adds nullable `stays.bed_id`/`bed_auto_assigned` |
| **V12__backfill_beds.sql** | **The only true data backfill**: generates bed inventory per room's old `capacity`, re-points every existing stay at a bed by rank/modulo |
| V13__drop_room_capacity.sql | Drops `rooms.capacity`/`blocked_spots` + check constraints |
| V14__require_stay_bed.sql | Sets `stays.bed_id NOT NULL` |

V9 and V11–V14 are the ALTER/backfill-bearing migrations; V12 is the only genuine backfill against pre-existing data so far.

#### Roadmap sequencing (S-02, S-04, F-02)

- **F-02 (`constraint-classification`, `ready`, `roadmap.md:44,102-109`)** — "each constraint rule declares whether it blocks or is overridable as data rather than by which list it appends to." Reads as a refactor of the existing four rules' classification into a small config/lookup table — closer to seeding reference rows than an ALTER-and-backfill of a populated business table. Not a strong risk-#6 anchor.
- **S-04 (`worker-crews`, `ready`, `roadmap.md:48,131-137`)** — new `crews` entity, additive. No roadmap text indicating an ALTER against existing populated rows or a backfill.
- **S-02 (`external-hotel-bookings`, `proposed`, prerequisite `S-01`, `roadmap.md:50,157-163`)** — **the strong candidate**. Its own risk note (`roadmap.md:163`) states: *"Introduces a second accommodation shape with no room structure... Sequenced immediately after S-01 rather than parallel to it because both reshape what a stay points at; doing them concurrently means merging two migrations of the same table."* This is an explicit, textual commitment to another populated-table ALTER on `stays` (the same table V11–V14 already altered/backfilled), once S-01 (`named-beds`, already shipped) has real rows in it. S-02 is not yet planned or built — status `proposed`, blocked on nothing structurally missing but not next-in-queue on the roadmap.

**Practical implication for this phase**: since S-02 doesn't exist yet, a migration-testing harness built now should validate against **V10→V14 — the already-shipped, real migration+backfill** — rather than waiting on S-02. This both grounds the test in real (already-merged) code and directly exercises the exact incident risk #6 describes, without needing to speculate about S-02's not-yet-designed schema.

#### Historical incident — validates risk #6 directly

- `context/changes/named-beds/plan.md:48` — documents the correct migration ordering: V10 (beds table) + V11 (nullable `stays.bed_id`/`bed_auto_assigned`) must exist before V12 (backfill) can populate them; V13 does not set `NOT NULL` — that waits for V14.
- `context/changes/named-beds/reviews/plan-review.md:63-65` — **a real, already-lived incident**, caught during plan review rather than by any automated test: the original plan set `stays.bed_id NOT NULL` in the same migration that dropped `rooms.capacity` (originally V13), but `StayService` didn't set `bedId` on any write path until Phase 4 of that plan — meaning every Stay-creating integration test would have hit a `NOT NULL` violation at runtime in the interim. Resolution: split into V13 (drop capacity only) + separate V14 (`NOT NULL`, later, once `resolveBed` guarantees a value).
- `context/changes/named-beds/plan.md:480-488` ("Migration Notes") — "There is no rollback migration in this codebase's convention... a problem found after V14 ships is fixed by a new forward migration."
- `context/changes/named-beds/plan-brief.md:29,61,67` — explicit "Backfill existing rooms/stays, don't reset" decision; backfill's stay-to-bed matching is "deterministic but synthetic for historical/non-overlapping stays; acceptable because it's demo/dev data, not production history" — an assumption that has never been tested against data shaped like actual non-overlapping-but-populated rows.
- **Directly in scope, already flagged and left open**: `context/changes/testing-constraint-engine-hardening/research.md` (Open Question, cross-referenced at `plan.md:119`, `plan-brief.md:66,92`) — V12's backfilled bed assignments were **never validated against `BedOccupancyConstraint`**. In principle the backfill's rank/modulo logic could have produced two overlapping stays sharing a bed, and nothing in the codebase checks that today. This is a live, named, unresolved gap from the completed Phase 1 rollout and sits squarely inside this phase's scope.

No hits under `context/archive/**/` for migration/backfill/Flyway — `named-beds` (still `in-progress`, not archived) is the only prior precedent.

## Code References

- `src/main/resources/db/migration/V1__create_agencies.sql` through `V14__require_stay_bed.sql` — full FK and migration inventory (see tables above for line-level citations)
- `src/main/java/com/beduno/property/PropertyService.java:87-105` — guarded Property delete
- `src/main/java/com/beduno/room/RoomService.java:123-139` — guarded Room delete
- `src/main/java/com/beduno/bed/BedService.java:128-143` — guarded Bed delete
- `src/main/java/com/beduno/worker/WorkerService.java:113-122` — soft-delete only
- `src/main/java/com/beduno/common/exception/GlobalExceptionHandler.java:52-56,123-128` — `ConflictException`→409 mapping; catch-all→500 with no FK-specific handler
- `src/main/java/com/beduno/common/exception/BusinessException.java:6-19` — exception base
- `src/main/resources/i18n/messages{,_en,_pl,_de,_uk,_ru}.properties:63-67` — `error.<entity>.<reason>` message codes for existing delete guards
- `src/test/java/com/beduno/property/DeletionGuardIntegrationTest.java` — existing delete-guard test suite (lines 44, 55, 68, 80, 94 per entity/scenario above)
- `src/test/java/com/beduno/bed/BedIntegrationTest.java:156,171-180` — Bed delete-guard tests
- `src/test/java/com/beduno/IntegrationTestBase.java:23-45` — Testcontainers + Flyway bootstrap (no migration-specific harness)
- `src/main/resources/application.yml:12-17` — Flyway baseline config

## Architecture Insights

- **Delete-guard pattern is deliberate and consistent but structurally unenforced.** Every guarded entity (Property, Room, Bed) follows the same shape: count referencing rows via a repository method scoped by `agencyId` + the FK column, throw `ConflictException` with an `error.<entity>.<reason>` code if non-zero, else delete. The pattern is documented in code comments cross-referencing the specific RESTRICT FK, but nothing (no shared abstract guard, no annotation-driven check, no exception translator) would catch a new entity's developer forgetting to replicate it — the safety net is developer memory plus code review, backed by no generic 500→409 translation layer.
- **Flyway is validated only as "applies to empty schema," never as "correctly transforms populated data."** This is a structural gap in the test harness itself, not a one-off oversight in any single migration — every current and future migration with a data-shape change (renames, backfills, NOT NULL tightenings) is exercised only against zero rows in CI/test.
- **The team already has direct, painful evidence that migration ordering + populated-data assumptions can break silently**: the named-beds V13/V14 split was caught by human plan review, not by any test. A migration-testing harness that seeds realistic pre-migration rows would have caught that exact class of bug automatically.
- **Risk #6's cheapest grounding is retrospective, not prospective**: rather than waiting for S-02 (not yet planned) to test "does the next migration handle populated data correctly," the existing V10→V14 backfill is already shipped, already has a known open validation gap (`BedOccupancyConstraint` never checked against the backfill's output), and is directly testable today by seeding realistic pre-V12 rows into a fresh Testcontainers instance and running only V12 forward, or by asserting the shipped backfill's actual invariants hold when reproduced against realistic data.

## Historical Context (from prior changes)

- `context/changes/named-beds/plan.md` — migration ordering rationale (V10-V14), "no rollback convention, forward-fix only."
- `context/changes/named-beds/plan-brief.md:29,61,67` — "backfill, don't reset" decision; backfill assumptions about historical/synthetic data.
- `context/changes/named-beds/reviews/plan-review.md:63-65` — the NOT-NULL-too-early incident, caught by review not by test — direct precedent motivating this phase.
- `context/changes/testing-constraint-engine-hardening/research.md` (Open Question) — V12 backfill never validated against `BedOccupancyConstraint`; cross-referenced at `plan.md:119`, `plan-brief.md:66,92`.
- `context/foundation/roadmap.md:44,48,50,102-109,131-137,157-163` — F-02, S-04, S-02 slice definitions and sequencing rationale.

## Related Research

- `context/changes/testing-constraint-engine-hardening/research.md` — prior rollout phase; the open V12/`BedOccupancyConstraint` question originates there and is directly relevant to this phase's risk #6 scope.
- `context/changes/testing-authorization-boundary-closure/research.md` — prior rollout phase (risks #3, #5); no direct overlap with this phase's risks, but establishes the same integration-test-matrix pattern this phase can reuse for delete-guard coverage.

## Open Questions

1. **Which entity counts as "the next entity" for risk #4?** No `ready` roadmap slice concretely introduces a new RESTRICT-FK-referenced entity today. Recommend `/10x-plan` scope this as: close the two existing coverage gaps (Property/Room/Bed) now, and treat "next entity" as aspirational/deferred rather than inventing a synthetic test target.
2. **Should this phase build a general migration-testing harness pattern, or a one-off test against the already-shipped V12 backfill?** A reusable pattern (seed pre-migration rows → run target migration → assert end state) would generalize to S-02 once it exists, but nothing forces that generality now. Recommend grounding this in the existing V10→V14 sequence for a concrete first proof, with the harness shape designed to be reusable.
3. **Should the V12 backfill validation against `BedOccupancyConstraint` (flagged open in Phase 1) be closed in this phase?** It fits risk #6's scope precisely (verifying an already-shipped migration's real output is correct against a business invariant) and is already a named, unresolved item — worth an explicit scope decision in `/10x-plan` rather than silently picking it up or silently deferring it again.
4. **Should a generic `DataIntegrityViolationException` → 409 handler be added to `GlobalExceptionHandler` as a safety net**, or is per-entity guarding (the current pattern) considered sufficient and intentional? This is a production-code decision, not just a test-coverage one — raising it here since research surfaced it, but the actual fix (if any) is out of this test-only rollout's scope per the Phase 1/2 precedent of documenting rather than fixing gaps.
