# Constraint Engine Hardening Implementation Plan

## Overview

This is rollout Phase 1 of `context/foundation/test-plan.md` ("Constraint
engine hardening"), covering test-plan Risks #1 and #2. It closes three
concrete, research-confirmed gaps in the bed-conflict constraint engine's
test coverage — write-path parity, date-boundary adjacency, and
hard+soft violation composition — and documents a fourth finding (bulk-
assign's error-surfacing contract) that research turned up but that is
explicitly out of scope to fix. This is a **test-only** change: no
production code is modified.

## Current State Analysis

All 5 write paths that can assign a worker to a bed (`create`, `update`,
`check-in`, `move`, `bulk-assign`) funnel through the same two
`StayService` helpers — `resolveBed` then `runConstraints`
(`StayService.java:373-389`, `403-446`) — and every hard constraint
violation maps to **HTTP 422, `CONSTRAINT_VIOLATION`**
(`GlobalExceptionHandler.java:58-62`). Despite this shared chokepoint,
HTTP-level bed-occupied/blocked rejection is tested only for **create**
(`BedAssignmentIntegrationTest`, `StayIntegrationTest.Create`). `update`,
`check-in`, and `move` have zero coverage of this kind.

The date-range overlap query (`countActiveStaysInBed`/`...Excluding`,
`StayRepository.java:94-108,110-126`) uses a consistent half-open interval
(`dateFrom < effectiveDateTo AND (dateTo IS NULL OR dateTo > dateFrom)`) —
a stay ending on date X and another starting on date X do **not** conflict
— but this is entirely unverified against the real query: the only
existing direct test of the engine, `ConstraintEngineTest`, mocks the
repository's count methods as `0L`/`1L` and never exercises the actual
SQL.

`ConstraintEngine.evaluate` (`ConstraintEngine.java:15-20`) runs every
registered `StayConstraint` bean unconditionally (no short-circuit), so a
hard violation (e.g. `BED_OCCUPIED`) and a soft violation (e.g.
`GENDER_MISMATCH`) can both fire on the same request and both should
appear in `ConstraintResult`. No existing test constructs this exact
co-occurrence; the closest test,
`MultipleViolationTests.shouldCollectMultipleHardViolations_whenSeveralConstraintsFail`,
only combines multiple *hard* violations.

Separately, research found that `StayService.bulkAssign`
(`StayService.java:299-336`) **cannot surface a 422 at all** — every
per-item failure, including a bed conflict, is caught and swallowed into
the outer `200 OK` response as `AssignmentResult(..., "error",
e.getMessage())` (`StayService.java:333-336`). The existing test
`BulkOperationsIntegrationTest.BulkAssign.shouldReturnPartialSuccess_whenSomeAssignmentsFail`
proves the count fields (`created`/`errors`) but never inspects the
failing item's `status`/`errorCode` fields.

### Key Discoveries:

- All 5 write paths share one exception-to-HTTP mapping
  (`GlobalExceptionHandler.java:58-62`) — a test added for any one path
  generalizes the *pattern*, but each path still needs its own test since
  each has a distinct call chain into `resolveBed`/`runConstraints`
  (`StayService.java:96-259`).
- `move` checks for a same-bed target (`StayService.java:230-232`)
  **before** running the constraint engine — a move test targeting an
  occupied/blocked bed must pick a bed different from the stay's current
  one, or it will hit the existing 409 `error.stay.move_same_room` path
  instead of the intended 422 `CONSTRAINT_VIOLATION` path.
- `checkIn`'s target room defaults to the stay's current room when
  `request.roomId()` is null (`StayService.java:161-162`), so testing a
  bed conflict at check-in does not require a room override — pass
  `roomId: null` and `bedId: <conflicting bed>`.
- `countActiveStaysInBed` only counts stays in `PLANNED`,
  `EXPECTED_TODAY`, or `CHECKED_IN` status
  (`BedOccupancyConstraint.java:25-27`) — a conflicting fixture stay can
  be left in `PLANNED` status; it does not need to be checked in for the
  occupancy check to fire.
- `BedAssignmentIntegrationTest`, `OperationalWorkflowIntegrationTest`,
  `StayIntegrationTest`, `ConstraintEngineTest`, and
  `BulkOperationsIntegrationTest` each already have the helper methods
  (`createWorker`, `createProperty`, `createRoom`, `listBeds`,
  `blockBed`/bed-status-update, `checkedInStay`/`createPlannedStay`,
  `forceExpectedToday`) needed for every test in this plan — no new test
  helpers are required.

## Desired End State

- `update`, `check-in`, and `move` each reject an already-occupied or
  BLOCKED target bed with the same 422/`CONSTRAINT_VIOLATION` shape
  `create` already returns — symmetric coverage across all 5 write paths.
- A test proves a stay ending on date X and another starting on date X in
  the same bed do **not** conflict, with a true-overlap negative control
  proving the same query does reject a real conflict.
- A test proves a hard violation (`BED_OCCUPIED`) and a soft violation
  (`GENDER_MISMATCH`) both surface correctly when they co-occur in one
  `evaluate()` call.
- A test documents bulk-assign's current contract precisely (200 OK, per-item
  `status="error"` and a populated `errorCode`) so the swallowing behavior
  is an intentional, guarded fact rather than an untested accident.
- `context/foundation/test-plan.md` §6.1 and §6.6 reflect what this
  rollout phase actually delivered, correcting the earlier speculative
  hypothesis that boundary-logic testing could live at the unit layer.

### Key Discoveries:

(see Current State Analysis above — carried here per template convention)

## What We're NOT Doing

- **Not fixing bulk-assign's swallowing behavior.** Whether bulk-assign
  should surface a structured per-item error code, a mixed HTTP status, or
  stay as-is is a production-code and API-contract decision explicitly
  deferred (research.md Open Question 1) — this phase only documents the
  current behavior with a test.
- **Not adding a dedicated repository-level test class** (e.g.
  `StayRepositoryIntegrationTest`). The boundary test lives in
  `BedAssignmentIntegrationTest` instead, per the confirmed testing-
  structure decision.
- **Not extending the boundary-adjacency proof to the worker-level query**
  (`countOverlappingStaysForWorker`) or the room-level query. Research
  confirmed identical operators, and this phase scopes to the bed-level
  case named in the risk.
- **Not validating historical/backfilled (V12) bed assignments** against
  the live `BedOccupancyConstraint` (research.md Open Question 3) — a
  data-integrity question for a separate phase, if ever pursued.
- **Not investigating whether `countActiveStaysInRoom`/`...Excluding` are
  dead code** (research.md Open Question 2) — a cleanup candidate, not a
  test target.
- **No production code changes of any kind.** This entire change is
  additive test code plus a documentation correction to `test-plan.md`.

## Implementation Approach

Each phase adds tests to an existing, already-conventional test file
(`StayIntegrationTest`, `OperationalWorkflowIntegrationTest`,
`BedAssignmentIntegrationTest`, `ConstraintEngineTest`,
`BulkOperationsIntegrationTest`) rather than introducing new test
infrastructure — every fixture helper this plan needs already exists in
one of these five files. Phases are ordered by the confirmed priority:
write-path parity first (the largest, most concrete gap), then the named
boundary risk, then the composition/documentation work, closing with the
cookbook correction.

## Critical Implementation Details

- **Stay-status prerequisites differ per write path** and must use the
  existing status-manipulation helpers, not just the public API: `update`
  requires a stay in `PLANNED` or `EXPECTED_TODAY`
  (`StayService.java:120-123`) — a plain `createStay`/`createPlannedStay`
  result already satisfies this. `checkIn` requires a status that
  `canTransitionTo(CHECKED_IN)` allows — use
  `OperationalWorkflowIntegrationTest`'s existing `forceExpectedToday`
  helper after creating a planned stay. `move` requires `CHECKED_IN` — use
  the existing `checkedInStay` helper (which drives status via
  `jdbcTemplate` directly, matching the pattern already in this file).
- **`move`'s same-bed check runs before the constraint engine**
  (`StayService.java:230-232`) — the occupied/blocked-bed move tests in
  Phase 1 must target a bed different from the stay's current bed, or the
  assertion will observe a 409 instead of the intended 422. See Key
  Discoveries above.

## Phase 1: Write-path parity for bed-conflict rejection

### Overview

Add occupied-bed and blocked-bed rejection tests for `update`, `check-in`,
and `move`, mirroring the coverage `create` already has via
`BedAssignmentIntegrationTest`/`StayIntegrationTest.Create`.

### Changes Required:

#### 1. Update path coverage

**File**: `src/test/java/com/beduno/stay/StayIntegrationTest.java`

**Intent**: Prove `PUT /api/v1/stays/{id}` rejects a request whose
`bedId` targets an already-occupied bed, and separately a BLOCKED bed,
with the same 422/`CONSTRAINT_VIOLATION` shape the `Create` nested class
already asserts.

**Contract**: Add two `@Test` methods to the existing `Update` nested
class: `shouldRejectUpdate_whenTargetBedOccupied` and
`shouldRejectUpdate_whenTargetBedBlocked`. Each constructs a room with 2
beds via the existing `createRoom(agencyId, propertyId, bedCount,
blockedBedCount, genderRule)` helper (set `blockedBedCount=1` for the
blocked case), creates a `PLANNED` stay via `createStay(...)`, occupies
(or leaves blocked) the second bed, then sends `UpdateStayRequest(roomId,
bedId, dateFrom, dateTo, overrideReason, notes)` with `bedId` set to the
conflicting bed and the same `roomId`/dates as the existing stay.
Asserts `HttpStatus.UNPROCESSABLE_ENTITY` and
`ErrorResponse.error()=="CONSTRAINT_VIOLATION"`, matching the existing
`Create` tests' assertion shape exactly.

#### 2. Check-in path coverage

**File**: `src/test/java/com/beduno/stay/OperationalWorkflowIntegrationTest.java`

**Intent**: Prove `POST /api/v1/stays/{id}/check-in` rejects a request
whose `bedId` targets an already-occupied bed, and separately a BLOCKED
bed.

**Contract**: Add two `@Test` methods to the existing `CheckInWorkflow`
nested class: `shouldRejectCheckIn_whenTargetBedOccupied` and
`shouldRejectCheckIn_whenTargetBedBlocked`. Use `createRoom(propertyId,
bedCount, blockedBedCount)` (the file's existing 3-arg overload) to get a
2-bed room with the second bed blocked for the blocked case, or occupy
the second bed with a separate stay for the occupied case. Create the
subject stay via `createPlannedStay(...)` then `forceExpectedToday(...)`
(both already present in this file). Send `CheckInRequest(null,
<conflictingBedId>, null)` — `roomId` stays null so the target room
defaults to the stay's current room. Assert `HttpStatus.UNPROCESSABLE_ENTITY`
via an `ErrorResponse` response type (new import, matching
`StayIntegrationTest`'s existing usage of the same class).

#### 3. Move path coverage

**File**: `src/test/java/com/beduno/stay/OperationalWorkflowIntegrationTest.java`

**Intent**: Prove `POST /api/v1/stays/{id}/move` rejects a request whose
`targetBedId` targets an already-occupied bed, and separately a BLOCKED
bed, in the target room — distinct from the existing
`shouldRejectMove_whenSameBed` test, which covers the same-bed case via a
different (409) code path.

**Contract**: Add two `@Test` methods to the existing `RoomMoveWorkflow`
nested class: `shouldRejectMove_whenTargetBedOccupied` and
`shouldRejectMove_whenTargetBedBlocked`. Build a `checkedInStay` in a
source room, a separate 2-bed target room (second bed occupied by
another worker's stay, or blocked), then send `MoveRequest(targetRoom.id(),
<conflictingBedId>, null)` where `<conflictingBedId>` is **not** the
subject stay's current bed (see Critical Implementation Details). Assert
`HttpStatus.UNPROCESSABLE_ENTITY`, using `ErrorResponse` as the response
type.

### Success Criteria:

#### Automated Verification:

- New `StayIntegrationTest` tests pass: `./gradlew test --tests "com.beduno.stay.StayIntegrationTest"`
- New `OperationalWorkflowIntegrationTest` tests pass: `./gradlew test --tests "com.beduno.stay.OperationalWorkflowIntegrationTest"`
- Full suite still green: `./gradlew test`
- Full build (incl. Checkstyle) passes: `./gradlew build`

#### Manual Verification:

- Review each of the 6 new tests to confirm its expected status/error
  code is derived from the constraint's documented behavior
  (`research.md` §1 — `BED_OCCUPIED`/`BED_BLOCKED` → 422
  `CONSTRAINT_VIOLATION`), not copied from whatever the test happened to
  observe on a first run.

---

## Phase 2: Boundary-case coverage (date adjacency)

### Overview

Prove the checkout-day == checkin-day adjacency rule at the bed level,
with a true-overlap negative control, against the real
`countActiveStaysInBed` query (not mocked).

### Changes Required:

#### 1. Boundary conditions for bed occupancy

**File**: `src/test/java/com/beduno/stay/BedAssignmentIntegrationTest.java`

**Intent**: Prove that a stay ending on date X and a new stay starting on
date X, in the same bed, do **not** conflict (201 Created), and that a
stay starting even one day earlier — a true overlap — does conflict (422
`CONSTRAINT_VIOLATION`/`BED_OCCUPIED`), against the real repository
query rather than a mock.

**Contract**: Add a new nested class `BoundaryConditions` with two
`@Test` methods:
- `shouldAllowNewStay_whenCheckoutDateEqualsCheckinDate`: create `stay1`
  explicitly assigned to `bed` via `createStay(...)` with
  `dateFrom=D`, `dateTo=D+7`; create `stay2` for a different worker,
  explicit same `bed`, with `dateFrom=D+7` (equal to `stay1`'s `dateTo`),
  `dateTo=D+14`. Assert the second create returns `HttpStatus.CREATED`.
- `shouldRejectNewStay_whenDatesTrulyOverlap`: same setup, but `stay2`'s
  `dateFrom=D+6` (one day before `stay1`'s `dateTo`) — assert
  `HttpStatus.UNPROCESSABLE_ENTITY` with `ErrorResponse.error()=="CONSTRAINT_VIOLATION"`.

Reuse the existing `createWorker`, `createProperty`, `createRoom`,
`listBeds`, `createStay(agencyId, workerId, propertyId, roomId, bedId,
dateFrom, dateTo)` helpers already in this file — no new helpers needed.

### Success Criteria:

#### Automated Verification:

- New `BedAssignmentIntegrationTest` tests pass: `./gradlew test --tests "com.beduno.stay.BedAssignmentIntegrationTest"`
- Full suite still green: `./gradlew test`
- Full build (incl. Checkstyle) passes: `./gradlew build`

#### Manual Verification:

- Confirm by inspection that `shouldAllowNewStay_whenCheckoutDateEqualsCheckinDate`
  would fail if `countActiveStaysInBed`'s boundary operator were changed
  from `dateTo > dateFrom` to `dateTo >= dateFrom` — i.e., the test is a
  real regression guard for this exact operator, not an incidental pass.

---

## Phase 3: Combo test, bulk-assign documentation, and cookbook close-out

### Overview

Prove hard and soft violations both surface when they co-occur in one
`ConstraintEngine.evaluate()` call; add a test documenting bulk-assign's
current 200-with-per-item-error contract; correct
`context/foundation/test-plan.md`'s cookbook to reflect what this rollout
phase actually delivered.

### Changes Required:

#### 1. Hard + soft co-occurrence

**File**: `src/test/java/com/beduno/stay/constraint/ConstraintEngineTest.java`

**Intent**: Prove that when a bed-occupied hard violation and a
gender-mismatch soft violation both apply to the same evaluation, both
appear in `ConstraintResult` — distinct from the existing
`shouldCollectMultipleHardViolations_whenSeveralConstraintsFail`, which
only combines multiple *hard* violations.

**Contract**: Add `shouldCollectHardAndSoftViolationsTogether_whenBothFire`
to the existing `MultipleViolationTests` nested class. Build a room with
`GenderRule.MALE_ONLY`, a `FEMALE` worker, and a bed; mock
`countActiveStaysInBed(...)` to return `1L` (occupied) and
`countOverlappingStaysForWorker(...)` to return `0L`, following the exact
mocking pattern already used in `BedOccupancyConstraintTests.shouldBlockOperation_whenBedIsAlreadyOccupied`
and `GenderConstraintTests.shouldAddSoftViolation_whenFemaleWorkerInMaleOnlyRoom`.
Assert `result.hardViolations()` contains type `"BED_OCCUPIED"` **and**
`result.softViolations()` contains type `"GENDER_MISMATCH"` in the same
`evaluate()` call.

#### 2. Bulk-assign current-behavior documentation

**File**: `src/test/java/com/beduno/stay/BulkOperationsIntegrationTest.java`

**Intent**: Document bulk-assign's current error-surfacing contract as an
explicit, intentional test — the outer HTTP response is always 200 OK
even when a per-item bed conflict occurs, and the conflict is reported
only in that item's `status`/`errorCode` fields.

**Contract**: Add `shouldReportBedConflictInResultBody_notHttpStatus` to
the existing `BulkAssign` nested class. Reuse the same single-bed-room /
occupied-then-conflicting-assignment setup as the existing
`shouldReturnPartialSuccess_whenSomeAssignmentsFail`, but assert
precisely on the failing item: `response.getStatusCode()` is
`HttpStatus.OK` (not 422), and
`result.results().stream().filter(r -> "error".equals(r.status())).findFirst()`
is present with a non-null, non-blank `errorCode()`.

#### 3. Cookbook correction

**File**: `context/foundation/test-plan.md`

**Intent**: Replace §6.1's placeholder (which incorrectly hypothesized
that boundary-logic testing could live at the unit layer) with what this
phase actually proved, and append a §6.6 note capturing the transferable
lesson for future rollout phases.

**Contract**: §6.1 ("Adding a unit test") changes from `TBD — see §3
Phase 2 (boundary logic in ConstraintEngine, if research confirms it
needs no DB dependency)` to a filled-in recipe: location
`src/test/java/com/beduno/stay/constraint/`, mocking policy (mock
`StayRepository`'s count methods directly, per `ConstraintEngineTest`'s
`@ExtendWith(MockitoExtension.class)` pattern), reference test
`ConstraintEngineTest.MultipleViolationTests`, and an explicit note that
this layer is for constraint **composition** only — boundary/adjacency
semantics against the real query require the integration layer instead
(cross-reference `BedAssignmentIntegrationTest.BoundaryConditions` from
Phase 2). §6.6 gets a new bullet: *"Phase 1 (constraint engine hardening)
found that engine composition and query-boundary semantics need different
test layers — the existing unit test mocks past the real SQL, so a new
integration test was needed for the boundary case. Bulk-assign was also
found to structurally swallow per-item errors into a 200 OK; see
`context/changes/testing-constraint-engine-hardening/research.md` Open
Question 1 if that contract is ever revisited."*

### Success Criteria:

#### Automated Verification:

- New `ConstraintEngineTest` test passes: `./gradlew test --tests "com.beduno.stay.constraint.ConstraintEngineTest"`
- New `BulkOperationsIntegrationTest` test passes: `./gradlew test --tests "com.beduno.stay.BulkOperationsIntegrationTest"`
- Full suite still green: `./gradlew test`
- Full build (incl. Checkstyle) passes: `./gradlew build`
- `context/foundation/test-plan.md` §6.1 no longer reads "TBD" and §6.6 contains the new bullet (file diff inspection)

#### Manual Verification:

- Confirm the §6.6 note reads as genuinely useful guidance for someone
  opening this file cold before writing their next test, not a restatement
  of the phase's own title.

---

## Testing Strategy

### Unit Tests:

- Hard+soft co-occurrence in `ConstraintEngineTest` (Phase 3) — the only
  new unit-level test in this plan; everything else is integration-level
  by design (see Current State Analysis).

### Integration Tests:

- Occupied/blocked-bed rejection for update, check-in, move (Phase 1).
- Checkout==checkin adjacency and true-overlap negative control (Phase 2).
- Bulk-assign's 200-with-per-item-error contract (Phase 3).

### Manual Testing Steps:

1. After Phase 1, spot-check that each new test's expected error shape
   traces to `research.md` §1, not to an observed first-run result.
2. After Phase 2, confirm the boundary test would fail under a flipped
   comparison operator (mental/manual check, not a mutation-testing tool).
3. After Phase 3, read the new `test-plan.md` §6.6 bullet as a future
   contributor would.

## Performance Considerations

None — this phase adds ~11 test methods to an already-Testcontainers-backed
suite; no new infrastructure, no production code paths change.

## Migration Notes

Not applicable — no schema or data changes.

## References

- Research: `context/changes/testing-constraint-engine-hardening/research.md`
- Test-plan risk source: `context/foundation/test-plan.md` §2 (Risks #1, #2) and Risk Response Guidance
- Existing coverage pattern: `src/test/java/com/beduno/stay/BedAssignmentIntegrationTest.java`
- Existing coverage pattern: `src/test/java/com/beduno/stay/constraint/ConstraintEngineTest.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Write-path parity for bed-conflict rejection

#### Automated

- [x] 1.1 New StayIntegrationTest tests pass: `./gradlew test --tests "com.beduno.stay.StayIntegrationTest"` — ebe5e5c
- [x] 1.2 New OperationalWorkflowIntegrationTest tests pass: `./gradlew test --tests "com.beduno.stay.OperationalWorkflowIntegrationTest"` — ebe5e5c
- [x] 1.3 Full suite still green: `./gradlew test` — ebe5e5c
- [x] 1.4 Full build (incl. Checkstyle) passes: `./gradlew build` — ebe5e5c

#### Manual

- [x] 1.5 Review each of the 6 new tests for a research-derived (not observed) expected status/error code — ebe5e5c

### Phase 2: Boundary-case coverage (date adjacency)

#### Automated

- [x] 2.1 New BedAssignmentIntegrationTest tests pass: `./gradlew test --tests "com.beduno.stay.BedAssignmentIntegrationTest"`
- [x] 2.2 Full suite still green: `./gradlew test`
- [x] 2.3 Full build (incl. Checkstyle) passes: `./gradlew build`

#### Manual

- [x] 2.4 Confirm the boundary test would fail under a flipped comparison operator

### Phase 3: Combo test, bulk-assign documentation, and cookbook close-out

#### Automated

- [ ] 3.1 New ConstraintEngineTest test passes: `./gradlew test --tests "com.beduno.stay.constraint.ConstraintEngineTest"`
- [ ] 3.2 New BulkOperationsIntegrationTest test passes: `./gradlew test --tests "com.beduno.stay.BulkOperationsIntegrationTest"`
- [ ] 3.3 Full suite still green: `./gradlew test`
- [ ] 3.4 Full build (incl. Checkstyle) passes: `./gradlew build`
- [ ] 3.5 test-plan.md §6.1 and §6.6 updated

#### Manual

- [ ] 3.6 Confirm the §6.6 note reads as genuinely useful guidance, not a restatement of the phase title
