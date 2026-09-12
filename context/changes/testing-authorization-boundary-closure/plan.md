# Authorization Boundary Closure Implementation Plan

## Overview

This is rollout Phase 2 of `context/foundation/test-plan.md` ("Authorization
boundary closure"), covering test-plan Risks #3 and #5. It closes the
concrete cross-agency test-coverage gaps research confirmed for
bed-touching write paths, closes an easy property-scoping test asymmetry
between `BedIntegrationTest` and its sibling `RoomIntegrationTest`, and
adds two passing "trip-wire" tests that document — without fixing —
a real, severe property-scoping authorization gap that a separate,
already-`ready` roadmap slice (S-08 `enforce-property-scoping`) owns
fixing. This is a **test-only** change: no production code is modified.

## Current State Analysis

`CurrentUser.hasPropertyAccess` (`common/security/CurrentUser.java:15-28`)
is called at exactly **3 places** in the entire codebase: `PUT
/properties/{id}`, `POST`/`PUT` on rooms, and `POST`/`bulk-generate`/`PUT`
on beds. Every other endpoint reachable by PROPERTY_ADMIN/FRONT_DESK —
all of Stay (including check-in, check-out, move, no-show, bulk-assign,
bulk-checkout), all of Occupancy/Exceptions/Inspection/Arrivals-export,
and Worker reads — performs no property check at all. This is not
speculative: the existing suite already proves it as a side effect —
`OperationalWorkflowIntegrationTest` and `StayGuardIntegrationTest` call
check-in/check-out/move/no-show using the 2-arg `authHeaders(Role,
agencyId)` overload, which sets `assignedPropertyIds = new UUID[0]`
(zero assigned properties), and these calls succeed with 200 OK today.

Separately, every bed-touching write path filters by `agencyId`
correctly — `BedService`'s create/bulkGenerate/update/delete all gate
through agency-filtered `getRoomOrThrow`/`getBedOrThrow` calls, and
`StayService.resolveBed`'s auto-assign branch calls
`bedRepository.findAllByAgencyIdAndRoomId(room.getAgencyId(), room.getId())`
where `room.getAgencyId()` is already tenant-verified. None of these 5
paths has a cross-agency test today, though — `BedIntegrationTest.TenantIsolation`
only covers read/list, and `BedAssignmentIntegrationTest.TenantIsolation`
only covers the explicit-bedId path.

Finally, `IntegrationTestBase` already has a 3-arg `authHeaders(Role,
UUID agencyId, UUID[] propertyIds)` overload (`IntegrationTestBase.java:60`)
used only by `RoomIntegrationTest` and `PropertyIntegrationTest` today —
`BedIntegrationTest`, despite `BedController` carrying the identical
`checkPropertyAccess` guard `RoomController` has, has zero tests using it.

### Key Discoveries:

- The property-scoping gap and cross-agency isolation are structurally
  independent mechanisms — `hasPropertyAccess` is an in-memory JWT-claim
  check; agency isolation is enforced entirely at the repository-query
  level. A resource can pass every tenant check and still be reachable
  outside a user's assigned properties.
- Test infrastructure for property-scoping already exists
  (`IntegrationTestBase.java:60`'s 3-arg `authHeaders` overload) — no new
  test helpers are required for any phase in this plan.
- `resolveBed`'s auto-assign branch (`StayService.java:414-446`, line 423)
  is already safe; the gap here is coverage, not code.
- `RoomIntegrationTest.Create` (`RoomIntegrationTest.java:49-71`) is the
  exact pattern to mirror onto `BedIntegrationTest` — two tests,
  `assignedPropertyIds` including vs. excluding the target property.
- All standard cross-agency isolation tests in this codebase use the
  same shape: an `OTHER_AGENCY_ID` token attempting the operation on a
  `DEFAULT_AGENCY_ID` resource, asserting `404 NOT_FOUND` (e.g.
  `BedIntegrationTest.TenantIsolation.shouldNotAccessBedFromOtherAgency`).

## Desired End State

- Two passing tests exist that document today's actual property-scoping
  behavior on Stay (check-in) and Occupancy (read) — intentionally
  written as **trip-wires**: they pass now and will start failing the
  moment roadmap slice S-08 adds the missing check, at which point
  whoever implements S-08 updates or removes them.
- `BedService`'s create, bulkGenerate, update, and delete, plus
  `resolveBed`'s auto-assign branch, each have a cross-agency isolation
  test proving they reject/404 a cross-tenant room or bed.
- `BedIntegrationTest` has the same property-scoping test pair
  `RoomIntegrationTest` already has, closing the asymmetry.

### Key Discoveries:

(see Current State Analysis above — carried here per template convention)

## What We're NOT Doing

- **Not fixing the property-scoping gap.** Adding `hasPropertyAccess`
  checks to Stay/Occupancy/Worker controllers is roadmap slice S-08
  (`enforce-property-scoping`, already `ready`)'s job, not this test-only
  rollout phase's. This plan documents the gap; it does not close it.
- **Not testing every unchecked endpoint individually.** The trip-wire
  tests cover one representative Stay write path (check-in) and one
  Occupancy read — proving the pattern once per resource type rather
  than repeating the same missing-check assertion ~15 times.
- **Not editing `AGENTS.md`.** The two undocumented, caller-dependent
  tenant-scope patterns research found (`BedRepository.existsByRoomIdAndLabel`,
  `ExportService`'s bed-id lookup) are safe today and are left as an open
  note (see Open Questions in `research.md`) for a future change, not
  addressed here.
- **Not fixing `WorkerIntegrationTest.shouldNotReturnWorkersFromOtherAgency`'s
  weak assertion** (a pre-existing test that only checks 200 OK, found
  incidentally by research) — not one of this phase's named risks.
- **No production code changes of any kind.** This entire change is
  additive test code.

## Implementation Approach

Each phase adds tests to an existing, conventional test file
(`OperationalWorkflowIntegrationTest`, `BedIntegrationTest`,
`BedAssignmentIntegrationTest`) using patterns already proven elsewhere
in the codebase (`RoomIntegrationTest`'s property-scoping pattern, the
standard cross-agency 404 shape). Phases are ordered by the confirmed
priority: the property-scoping trip-wire first (the more severe,
PRD/roadmap-flagged finding), then cross-agency bed-path coverage, then
the `BedIntegrationTest` parity closure.

## Critical Implementation Details

- **The trip-wire tests must read as intentional documentation, not
  accidental gaps.** Each one needs an inline comment explaining that it
  asserts today's actual (insecure) behavior on purpose, naming the
  research open question and the roadmap slice (S-08) that will
  eventually make the test fail — without that comment, a future reader
  could mistake a passing "PROPERTY_ADMIN can check in to an unassigned
  property" test for an endorsed design decision rather than a
  documented gap.
- **The auto-assign cross-agency test's purpose is subtle**: it is not
  testing a currently-broken path (research confirmed `resolveBed`'s
  auto-assign branch is already safe). It exists to prove that omitting
  `bedId` (auto-assign) does not create a code path that skips the room's
  agency check — the same 404 the explicit-bedId sibling test already
  gets, reached through a different branch.

## Phase 1: Property-scoping trip-wire

### Overview

Add two passing tests documenting today's actual property-scoping gap on
Stay (check-in) and Occupancy (read) — one representative endpoint per
resource type, matching the pattern already established for
bulk-assign's documented-not-fixed contract in rollout Phase 1.

### Changes Required:

#### 1. Stay check-in trip-wire

**File**: `src/test/java/com/beduno/stay/OperationalWorkflowIntegrationTest.java`

**Intent**: Prove that a PROPERTY_ADMIN whose `assignedPropertyIds` does
NOT include the stay's property can still check it in today — an
intentional, commented trip-wire for a known gap, not a design
endorsement.

**Contract**: Add `shouldAllowCheckIn_whenPropertyAdminNotAssignedToStaysProperty`
to the existing `CheckInWorkflow` nested class. Build a stay via the
existing `createPlannedStay` + `forceExpectedToday` helpers, then call
check-in using `authHeaders(Role.PROPERTY_ADMIN, DEFAULT_AGENCY_ID, new
UUID[]{UUID.randomUUID()})` (a random, non-matching assigned-property
array) instead of the file's default. Assert `HttpStatus.OK`. Include the
inline comment described in Critical Implementation Details.

#### 2. Occupancy read trip-wire

**File**: `src/test/java/com/beduno/stay/OperationalWorkflowIntegrationTest.java`

**Intent**: Prove that a PROPERTY_ADMIN whose `assignedPropertyIds` does
NOT include the property can still read its occupancy today.

**Contract**: Add `shouldAllowOccupancyRead_whenPropertyAdminNotAssignedToProperty`
to the existing `OccupancyEndpoints` nested class. Build a checked-in
stay via the existing `checkedInStay` helper, then call `GET
/api/v1/properties/{propertyId}/occupancy?date=...` using the same
non-matching `authHeaders(Role.PROPERTY_ADMIN, DEFAULT_AGENCY_ID, new
UUID[]{UUID.randomUUID()})` pattern. Assert `HttpStatus.OK`. Same inline
comment style as test 1, cross-referencing it as the sibling case.

### Success Criteria:

#### Automated Verification:

- New OperationalWorkflowIntegrationTest tests pass: `./gradlew test --tests "com.beduno.stay.OperationalWorkflowIntegrationTest"`
- Full suite still green: `./gradlew test`
- Full build (incl. Checkstyle) passes: `./gradlew build`

#### Manual Verification:

- Read both trip-wire tests' inline comments as a future contributor
  would — confirm they clearly read as documented, intentional gaps tied
  to roadmap slice S-08, not silent endorsements of current behavior.

---

## Phase 2: Cross-agency bed-path tests

### Overview

Add cross-agency isolation coverage for the 5 bed-touching write paths
research found untested: `BedService`'s create, bulkGenerate, update,
delete, and `StayService.resolveBed`'s auto-assign branch.

### Changes Required:

#### 1. BedService write-path cross-agency tests

**File**: `src/test/java/com/beduno/bed/BedIntegrationTest.java`

**Intent**: Prove create, bulkGenerate, update, and delete each reject a
request from a different agency targeting a room/bed that belongs to
`DEFAULT_AGENCY_ID`, matching the standard cross-agency 404 shape this
file's existing `TenantIsolation` tests already use.

**Contract**: Add four `@Test` methods to the existing `TenantIsolation`
nested class: `shouldNotCreateBedInOtherAgencyRoom`,
`shouldNotBulkGenerateBedsInOtherAgencyRoom`,
`shouldNotUpdateBedInOtherAgencyRoom`, `shouldNotDeleteBedInOtherAgencyRoom`.
Each builds a `DEFAULT_AGENCY_ID` room (and, for update/delete, a bed via
the existing `createBed` helper), then calls the corresponding endpoint
using `authHeaders(Role.AGENCY_ADMIN, OTHER_AGENCY_ID)`, asserting
`HttpStatus.NOT_FOUND` — identical shape to the file's existing
`shouldNotAccessBedFromOtherAgency`.

#### 2. Auto-assign cross-agency test

**File**: `src/test/java/com/beduno/stay/BedAssignmentIntegrationTest.java`

**Intent**: Prove that omitting `bedId` (auto-assign) does not create a
code path that skips the target room's agency check — see Critical
Implementation Details for why this test's value is about branch
coverage, not fixing a live bug.

**Contract**: Add `shouldRejectAutoAssign_whenRoomBelongsToOtherAgency`
to the existing `TenantIsolation` nested class. Create a worker in
`OTHER_AGENCY_ID` and a room in `DEFAULT_AGENCY_ID`, then `POST
/api/v1/stays` with `bedId: null` using an `OTHER_AGENCY_ID` admin token.
Assert `HttpStatus.NOT_FOUND` (the room lookup fails before `resolveBed`
runs).

### Success Criteria:

#### Automated Verification:

- New BedIntegrationTest tests pass: `./gradlew test --tests "com.beduno.bed.BedIntegrationTest"`
- New BedAssignmentIntegrationTest test passes: `./gradlew test --tests "com.beduno.stay.BedAssignmentIntegrationTest"`
- Full suite still green: `./gradlew test`
- Full build (incl. Checkstyle) passes: `./gradlew build`

#### Manual Verification:

- Confirm each of the 5 new tests would actually fail if its
  corresponding `BedService`/`StayService` method dropped its
  agency-filtering call — i.e., these are real regression guards, not
  tautological passes.

---

## Phase 3: BedIntegrationTest property-scoping parity

### Overview

Close the asymmetry research found: `BedController` carries the same
`checkPropertyAccess` guard `RoomController` does, but `BedIntegrationTest`
has zero tests proving it — while `RoomIntegrationTest` already does.

### Changes Required:

#### 1. Bed create property-scoping tests

**File**: `src/test/java/com/beduno/bed/BedIntegrationTest.java`

**Intent**: Mirror `RoomIntegrationTest.Create`'s
`shouldCreateRoom_whenPropertyAdminWithAccess` /
`shouldRejectCreate_whenPropertyAdminWithoutAccess` pair onto
`BedIntegrationTest`'s `Create` nested class — no production code change
needed, the guard already exists on `BedController.create`.

**Contract**: Add `shouldCreateBed_whenPropertyAdminWithAccess` and
`shouldRejectCreate_whenPropertyAdminWithoutAccess` to the existing
`Create` nested class. Use `authHeaders(Role.PROPERTY_ADMIN,
DEFAULT_AGENCY_ID, new UUID[]{room.propertyId()})` for the access-granted
case and `new UUID[]{UUID.randomUUID()}` for the denied case, asserting
`HttpStatus.CREATED` and `HttpStatus.FORBIDDEN` respectively — identical
shape to `RoomIntegrationTest.java:49-71`.

### Success Criteria:

#### Automated Verification:

- New BedIntegrationTest tests pass: `./gradlew test --tests "com.beduno.bed.BedIntegrationTest"`
- Full suite still green: `./gradlew test`
- Full build (incl. Checkstyle) passes: `./gradlew build`

#### Manual Verification:

- Confirm the two new tests are a faithful mirror of
  `RoomIntegrationTest.Create`'s pattern (same access-granted/denied
  shape), not a divergent variant.

---

## Testing Strategy

### Unit Tests:

None — every test in this plan is integration-level, consistent with
how property-scoping and cross-agency isolation are exercised elsewhere
in this codebase (real HTTP + Testcontainers).

### Integration Tests:

- Property-scoping trip-wire for Stay check-in and Occupancy read (Phase 1).
- Cross-agency isolation for BedService's 4 write paths and resolveBed's auto-assign branch (Phase 2).
- BedIntegrationTest property-scoping parity with RoomIntegrationTest (Phase 3).

### Manual Testing Steps:

1. After Phase 1, read both trip-wire comments as a future contributor
   would, confirming they read as documented gaps, not endorsements.
2. After Phase 2, spot-check that each new cross-agency test would
   actually fail if its corresponding agency-filter call were removed.
3. After Phase 3, confirm the new bed tests faithfully mirror
   `RoomIntegrationTest.Create`'s existing pattern.

## Performance Considerations

None — this phase adds 9 test methods to an already-Testcontainers-backed
suite; no new infrastructure, no production code paths change.

## Migration Notes

Not applicable — no schema or data changes.

## References

- Research: `context/changes/testing-authorization-boundary-closure/research.md`
- Test-plan risk source: `context/foundation/test-plan.md` §2 (Risks #3, #5) and Risk Response Guidance
- Property-scoping pattern to mirror: `src/test/java/com/beduno/room/RoomIntegrationTest.java:49-71`
- Cross-agency 404 pattern to mirror: `src/test/java/com/beduno/bed/BedIntegrationTest.java` (existing `TenantIsolation`)
- Prior-phase precedent for documenting-not-fixing: `context/changes/testing-constraint-engine-hardening/plan.md` Phase 3 (bulk-assign)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Property-scoping trip-wire

#### Automated

- [x] 1.1 New OperationalWorkflowIntegrationTest tests pass: `./gradlew test --tests "com.beduno.stay.OperationalWorkflowIntegrationTest"`
- [x] 1.2 Full suite still green: `./gradlew test`
- [x] 1.3 Full build (incl. Checkstyle) passes: `./gradlew build`

#### Manual

- [x] 1.4 Read both trip-wire tests' inline comments for clarity as documented, intentional gaps

### Phase 2: Cross-agency bed-path tests

#### Automated

- [ ] 2.1 New BedIntegrationTest tests pass: `./gradlew test --tests "com.beduno.bed.BedIntegrationTest"`
- [ ] 2.2 New BedAssignmentIntegrationTest test passes: `./gradlew test --tests "com.beduno.stay.BedAssignmentIntegrationTest"`
- [ ] 2.3 Full suite still green: `./gradlew test`
- [ ] 2.4 Full build (incl. Checkstyle) passes: `./gradlew build`

#### Manual

- [ ] 2.5 Confirm each of the 5 new tests would fail if its agency-filtering call were removed

### Phase 3: BedIntegrationTest property-scoping parity

#### Automated

- [ ] 3.1 New BedIntegrationTest tests pass: `./gradlew test --tests "com.beduno.bed.BedIntegrationTest"`
- [ ] 3.2 Full suite still green: `./gradlew test`
- [ ] 3.3 Full build (incl. Checkstyle) passes: `./gradlew build`

#### Manual

- [ ] 3.4 Confirm the new bed tests faithfully mirror RoomIntegrationTest.Create's pattern
