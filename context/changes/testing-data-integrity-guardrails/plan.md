# Data-Integrity Guardrails Implementation Plan

## Overview

Rollout Phase 3 of `context/foundation/test-plan.md`. Closes two concrete, already-identified
gaps: an untested delete-guard branch (`error.room.has_beds`), and the total absence of any
harness capable of proving a Flyway migration/backfill transforms *populated* data correctly
(today every migration only ever runs against an empty Testcontainers schema).

## Current State Analysis

- All 14 FKs in the schema are implicit `NO ACTION` (RESTRICT behavior). Only three referenced
  entities have a hard-delete code path at all — Property, Room, Bed — and all three already
  guard with a `countBy...` pre-check → `ConflictException` → 409
  (`property/PropertyService.java:87-105`, `room/RoomService.java:123-139`,
  `bed/BedService.java:128-143`). No generic safety net exists:
  `GlobalExceptionHandler` has no handler for `DataIntegrityViolationException`/23503
  (`common/exception/GlobalExceptionHandler.java:123-128`), so a forgotten guard falls straight
  through to a bare 500.
- `RoomService.delete`'s second guard branch, `error.room.has_beds`, has zero test coverage
  today (`room/RoomService.java:132-133`). `PropertyService.delete`'s `error.property.has_stays`
  branch (`property/PropertyService.java:98-99`) is structurally unreachable under current
  business rules: a stay always requires a room, and `has_rooms` is checked first
  (`property/PropertyService.java:94-96`) — a property with any active stay necessarily still
  has a room, so `has_rooms` always fires before `has_stays` could.
- `IntegrationTestBase` (`src/test/java/com/beduno/IntegrationTestBase.java:23-45`) boots one
  shared Testcontainers Postgres and lets Spring Boot's `FlywayAutoConfiguration` run the full
  V1→V14 chain once against an always-empty schema, before any test method executes. No test
  anywhere seeds rows before a migration and asserts the migration transformed them —
  `V12__backfill_beds.sql`'s real backfill logic has never been exercised against non-trivial
  data in an automated test.
- **`V12__backfill_beds.sql:13-26` has a real, previously undiscovered correctness gap.** It
  assigns each stay to a bed via `row_number() OVER (PARTITION BY room_id ORDER BY created_at,
  id) % bed_count` — creation-order rank modulo bed count, with zero awareness of date-range
  overlap. A room with `capacity` 2 (→ 2 beds) and three historical stays — A (days 1–30, rank
  0), B (days 5–10, rank 1), C (days 15–20, rank 2) — is entirely valid under the old
  room-capacity model (at most 2 concurrently active at any point), but the backfill assigns A
  and C to the *same* bed (`2 % 2 == 0`, same as A's `0 % 2`) despite their date ranges
  overlapping (days 15–20). This is a structural property of the algorithm whenever a room
  historically had more distinct stays than beds with ranks congruent mod `bed_count` — not a
  one-off data quirk. Confirmed by direct SQL trace this session (see plan question log); not
  previously known.
- `context/changes/testing-constraint-engine-hardening/research.md` already flagged, and left
  open, this exact gap (V12 backfill never validated against `BedOccupancyConstraint`) — this
  phase closes that specific open item.
- Real precedent that this class of bug is easy to miss: `context/changes/named-beds/reviews/plan-review.md:63-65`
  documents a migration-ordering bug (premature `NOT NULL`) caught only by human plan review,
  not by any test, because no migration-testing harness existed then either.

## Desired End State

- Every entity with a currently-guarded RESTRICT FK (Property, Room, Bed) has full test coverage
  of its guard branches, or an explicit documented reason a branch is unreachable.
- A reusable migration-testing harness exists (seed pre-migration rows → migrate to an
  intermediate version → seed more if needed → migrate to latest → assert end state), proven
  against the real, already-shipped V10→V14 sequence, independent of `IntegrationTestBase`'s
  shared container.
- The V12/`BedOccupancyConstraint` gap is no longer *unknown* — it is proven, via an executable
  test, to exist for a specific realistic scenario, with the finding documented in code and in
  `test-plan.md`. The gap itself is **not** fixed (no new migration, no `V12` edit) — that is a
  deliberate scope boundary, not an oversight.
- `test-plan.md` §3 row for Phase 3 reads `complete`; §6.5 (migration test cookbook) and §6.6
  (per-rollout-phase notes) are filled in.

### Key Discoveries

- `room/RoomService.java:127`: existing guards are hand-commented with the exact RESTRICT FK
  they mirror — a deliberate, consistent, but structurally unenforced pattern.
- `build.gradle.kts:37-38,63-64`: `flyway-core`, `flyway-database-postgresql`, and
  `testcontainers:junit-jupiter`/`postgresql` are all already on the test classpath — no new
  dependency needed to drive Flyway's Java API directly against a dedicated container.
- `IntegrationTestBase`'s shared container is started via a raw static block (not
  `@Testcontainers`/`@Container`) and is shared across the entire test JVM run with the full
  schema already migrated — it cannot be rewound to an intermediate version without breaking
  every other integration test. The harness needs its own, independent container.

## What We're NOT Doing

- Not modifying `V12__backfill_beds.sql` or any other existing migration (standing project
  rule: never modify existing migrations).
- Not writing a forward-fix migration (e.g. a new `V15`) to repair the discovered
  bed-assignment gap in real data — that would turn this test-only rollout phase into a
  production data-migration change, which is out of scope here. The gap is documented, not
  fixed, by explicit decision.
- Not adding a generic `DataIntegrityViolationException` → 409 handler to
  `GlobalExceptionHandler` — that is a production-code safety-net decision, not a test-coverage
  one, and this rollout's Phase 1/2 precedent is to document such gaps rather than fix them.
- Not writing a delete-guard test for a "next entity" beyond Property/Room/Bed — no `ready`
  roadmap slice concretely introduces a new RESTRICT-FK-referenced entity today (S-04
  `worker-crews` reads as additive). Deferred, not invented.
- Not backfilling the pre-existing `test-plan.md` §6.3 cookbook gap left over from rollout
  Phase 2 (cross-agency isolation test pattern) — out of this phase's risk scope (#4, #6), even
  though it is touched in the same file.
- Not wiring CI — consistent with §5's existing note that no rollout phase in this test-plan
  wires CI.

## Implementation Approach

Two independent tracks, sequenced so the harder/riskier one (the migration harness) comes
second once the simpler delete-guard work is done and committed. A third, small phase reconciles
documentation, mirroring how Phases 1 and 2 closed out.

## Critical Implementation Details

- **The migration harness must not reuse `IntegrationTestBase`.** It needs its own
  `@Testcontainers`-managed Postgres instance and must drive Flyway directly via its Java API
  (`Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration").target(version).load().migrate()`),
  calling `.migrate()` a second time with no target (or `MigrationVersion.LATEST`) to resume
  from wherever Flyway's own `schema_history` table left off. No `@SpringBootTest` context is
  booted at all — this is a plain JUnit 5 test against raw JDBC/Flyway.
- **Row ordering for the adversarial scenario must be deterministic.** `V12`'s ranking is
  `ORDER BY created_at, id` — the seed inserts must set explicit, distinctly-spaced `created_at`
  timestamps (not rely on `now()` defaults) so the rank-and-modulo assignment is reproducible
  across runs.
- **The overlap/violation check cannot use the real `BedOccupancyConstraint` class directly** —
  that class needs a full `ConstraintContext` (bed entity, room entity, etc.) that only exists
  inside a booted Spring context, which this dedicated-container test intentionally does not
  boot. Implement the same semantics as a raw SQL self-join instead: two rows in `stays` sharing
  a `bed_id`, both with `status IN ('PLANNED','EXPECTED_TODAY','CHECKED_IN')`, whose
  `[date_from, COALESCE(date_to, 'infinity'))` ranges overlap.
- **Test B is expected to assert a violation exists, not assert it doesn't.** Write the
  assertion as `assertThat(violationCount).isGreaterThan(0)` with a comment explaining this
  documents a known, already-shipped gap this rollout deliberately does not fix — this keeps CI
  green while making the finding permanent and visible, rather than silently passing or
  red-blocking the build on a pre-existing production-code issue outside this phase's scope.

## Phase 1: Delete-guard coverage completion

### Overview

Close the one real, currently-untested delete-guard branch, and document the one branch that
looks untested but is actually unreachable.

### Changes Required:

#### 1. Room delete-guard test for `has_beds`

**File**: `src/test/java/com/beduno/property/DeletionGuardIntegrationTest.java`

**Intent**: Add a test proving `RoomService.delete` returns 409 with `error.room.has_beds` when
a room has beds (from the bulk-generate endpoint) but no stays — the one existing guard branch
with zero coverage.

**Contract**: New `@Test` inside the existing `RoomDeletion` nested class, following the exact
shape of the sibling `shouldReturnConflict_whenStaysReferenceRoom` test: create a property and
room, call the bulk-generate-beds endpoint (`BulkGenerateBedsRequest`, already imported and used
elsewhere in this file for stay creation) to give the room a bed with no stay attached, delete
the room, assert `409 CONFLICT` and body contains `error.room.has_beds`.

#### 2. Document the unreachable `has_stays` branch

**File**: `src/test/java/com/beduno/property/DeletionGuardIntegrationTest.java`

**Intent**: Record, next to the existing `PropertyDeletion` tests, why no test exists for
`error.property.has_stays` — so a future reader doesn't mistake the absence for a coverage gap.

**Contract**: A short comment on the `PropertyDeletion` nested class (not a new test, not a
production-code change) stating: a stay always requires a room; `has_rooms` is checked before
`has_stays` in `PropertyService.delete`; therefore a property with any active stay still has a
room and `has_rooms` always fires first — `has_stays` is unreachable through the current API,
confirmed by `research.md`.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests "com.beduno.property.DeletionGuardIntegrationTest"` passes
- `./gradlew build` passes (Checkstyle + full compile)

#### Manual Verification:

- Temporarily comment out the `has_beds` guard check in `room/RoomService.java`, rerun the new
  test, confirm it now fails (falls through to an unhandled 500) — proves the test is not
  vacuous. Restore the guard afterward.

---

## Phase 2: Migration-testing harness

### Overview

Build a reusable seed → partial-migrate → continue-migrate → assert pattern against a dedicated
Testcontainers Postgres instance, and prove it against the real, already-shipped V10→V14
sequence — including the newly-discovered V12/`BedOccupancyConstraint` gap.

### Changes Required:

#### 1. Reusable migration-driving helper

**File**: `src/test/java/com/beduno/migration/MigrationTestSupport.java`

**Intent**: Give this test (and any future migration test, e.g. once roadmap slice S-02 lands)
a shared, tested way to apply the project's Flyway migrations up to an arbitrary version against
a caller-supplied JDBC URL, so tests can seed rows mid-sequence.

**Contract**: A plain Java helper (no Spring dependency) exposing `migrateTo(String version)`
and `migrateToLatest()`, both operating on the same `org.flywaydb.core.Flyway` instance
configured with `.locations("classpath:db/migration")` against the constructor-supplied
JDBC URL/username/password:

```java
Flyway.configure()
    .dataSource(jdbcUrl, username, password)
    .locations("classpath:db/migration")
    .target(version) // omit/target(MigrationVersion.LATEST) for migrateToLatest()
    .load()
    .migrate();
```

Each call reuses Flyway's own `schema_history` table, so a second `.migrate()` call resumes
from wherever the first left off rather than re-running anything.

#### 2. Migration test class

**File**: `src/test/java/com/beduno/migration/BedBackfillMigrationTest.java`

**Intent**: Seed realistic pre-V12 rows against a dedicated `@Testcontainers`-managed Postgres,
migrate through the real V10→V14 sequence via `MigrationTestSupport`, and assert the resulting
`stays`/`beds` state — both the case where the backfill is safe, and the constructed case where
it is not.

**Contract**:

- Own `@Container static PostgreSQLContainer<?>` (independent of `IntegrationTestBase`'s shared
  instance) plus `@Testcontainers` lifecycle management.
- `Test A` — "typical data" / harness sanity: seed one agency, one property/room (capacity 1,
  pre-V13 schema still has the column at this point in the sequence), one worker, and three
  sequential non-overlapping historical stays with explicit spaced `created_at` values. Migrate
  V1→V11, insert the seed rows, migrate to latest. Assert: every stay has a non-null `bed_id`
  referencing a bed whose `room_id` matches the stay's `room_id`, and the raw-SQL overlap check
  (see Critical Implementation Details) finds zero violations.
- `Test B` — the documented gap: same setup, but capacity 2 (→ 2 beds) with stays A/B/C exactly
  as constructed in Current State Analysis (A: days 1–30, B: days 5–10, C: days 15–20, ranked in
  that creation order). Migrate the same way. Assert the raw-SQL overlap check finds **at least
  one** violation (A and C sharing a bed while their date ranges overlap) — with a comment
  cross-referencing `research.md` Open Question 3 and this plan's Current State Analysis,
  explaining this is a real, discovered, intentionally-unfixed gap in already-shipped
  `V12__backfill_beds.sql`.

#### 3. Fill in the migration-test cookbook entry

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the §6.5 "TBD" placeholder now that the pattern exists.

**Contract**: Location (`src/test/java/com/beduno/migration/`), the seed→partial-migrate→
continue-migrate→assert pattern shape, reference test (`BedBackfillMigrationTest`), and the
run-locally command — matching the style of the existing §6.1/§6.2 entries.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests "com.beduno.migration.*"` passes (Test A green because no violation
  exists; Test B green because it correctly asserts a violation count > 0)
- `./gradlew build` passes (Checkstyle + full compile)
- Full suite still passes: `./gradlew test`

#### Manual Verification:

- Temporarily change Test B's overlap predicate to always return zero rows, rerun, confirm Test
  B now fails — proves the assertion is real and not vacuously true. Restore afterward.
- Run `./gradlew test` once for the full suite and note the wall-clock delta from the new
  dedicated container's startup — confirm it's not prohibitively slow (a second Postgres
  container spinning up once per run is expected and acceptable).

---

## Phase 3: Documentation close-out

### Overview

Reconcile `test-plan.md` to reflect Phase 3's completion, mirroring how Phases 1 and 2 closed
out.

### Changes Required:

#### 1. Reconcile the rollout table and cookbook

**File**: `context/foundation/test-plan.md`

**Intent**: Mark rollout Phase 3 complete and record what this phase found, for future
contributors reading §6.6.

**Contract**: §3 row for Phase 3: `Status` → `complete`. §6.6: append a "Phase 3 (data-integrity
guardrails)" entry recording — the `has_beds` gap closure, the `has_stays` dead-code finding,
the migration-harness pattern now available for reuse (e.g. by S-02 later), and the discovered
(documented, deliberately unfixed) `V12`/`BedOccupancyConstraint` violation risk with a pointer
to `BedBackfillMigrationTest`.

### Success Criteria:

#### Automated Verification:

- `./gradlew build` passes (no code change in this phase, but confirms nothing else broke)

#### Manual Verification:

- Read the updated §3/§6.5/§6.6 sections and confirm they accurately describe what Phases 1-2
  of this change actually shipped.

---

## Testing Strategy

### Unit Tests:

- None new — this phase is entirely integration-level (delete guards need a real DB to prove
  the FK actually restricts; migration correctness is meaningless without a real Flyway run).

### Integration Tests:

- `DeletionGuardIntegrationTest.RoomDeletion.shouldReturnConflict_whenRoomStillHasBeds` (Phase 1)
- `BedBackfillMigrationTest` Test A and Test B (Phase 2)

### Manual Testing Steps:

1. Confirm the new `has_beds` test fails when the guard is temporarily removed (Phase 1).
2. Confirm Test B's violation assertion fails when its overlap predicate is temporarily
   neutered (Phase 2).
3. Confirm full-suite wall-clock time is acceptable with the second Testcontainers instance
   (Phase 2).
4. Read the final `test-plan.md` §3/§6.5/§6.6 for accuracy (Phase 3).

## Performance Considerations

A second Postgres Testcontainers instance adds one more container startup to the full test run
(on top of the one `IntegrationTestBase` already starts). This is a one-time cost per test JVM
run, not per test method, and is an accepted tradeoff for genuine migration-correctness signal.

## Migration Notes

No new migrations are added or modified by this phase. The discovered `V12` gap is documented,
not remediated — see "What We're NOT Doing."

## References

- Related research: `context/changes/testing-data-integrity-guardrails/research.md`
- Guard pattern precedent: `src/main/java/com/beduno/bed/BedService.java:128-143`
- Prior migration-ordering incident: `context/changes/named-beds/reviews/plan-review.md:63-65`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not
> rename step titles. See `references/progress-format.md`.

### Phase 1: Delete-guard coverage completion

#### Automated

- [x] 1.1 `./gradlew test --tests "com.beduno.property.DeletionGuardIntegrationTest"` passes
- [x] 1.2 `./gradlew build` passes (Checkstyle + full compile)

#### Manual

- [x] 1.3 Confirm the new `has_beds` test fails when the guard is temporarily removed, then restore it

### Phase 2: Migration-testing harness

#### Automated

- [ ] 2.1 `./gradlew test --tests "com.beduno.migration.*"` passes (Test A and Test B both green)
- [ ] 2.2 `./gradlew build` passes (Checkstyle + full compile)
- [ ] 2.3 Full suite passes: `./gradlew test`

#### Manual

- [ ] 2.4 Confirm Test B's violation assertion fails when its overlap predicate is temporarily neutered, then restore it
- [ ] 2.5 Confirm full-suite wall-clock delta from the new container is acceptable

### Phase 3: Documentation close-out

#### Automated

- [ ] 3.1 `./gradlew build` passes

#### Manual

- [ ] 3.2 Read the updated test-plan.md §3/§6.5/§6.6 sections for accuracy
