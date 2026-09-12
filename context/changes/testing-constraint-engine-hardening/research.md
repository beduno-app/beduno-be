---
date: 2026-09-11T17:52:55Z
researcher: Claude Sonnet 5
git_commit: 071231500aa49df787960b7c3a36c68eca6bff55
branch: main
repository: beduno-be
topic: "Constraint engine hardening — bed-conflict and boundary-case coverage (test-plan Phase 1, Risks #1 and #2)"
tags: [research, codebase, stay, constraint-engine, bed-occupancy, testing]
status: complete
last_updated: 2026-09-11
last_updated_by: Claude Sonnet 5
---

# Research: Constraint engine hardening — bed-conflict and boundary-case coverage

**Date**: 2026-09-11T17:52:55Z
**Researcher**: Claude Sonnet 5
**Git Commit**: 071231500aa49df787960b7c3a36c68eca6bff55
**Branch**: main
**Repository**: beduno-be

> Note on permalinks: `main` is 5 commits ahead of `origin/main` and the
> current HEAD is not pushed, so GitHub permalinks would 404. All
> references below use local `path:line` citations instead.

## Research Question

Ground rollout Phase 1 of `context/foundation/test-plan.md` ("Constraint
engine hardening") for two risks:

- **Risk #1**: A worker ends up assigned to a bed that is already occupied
  or blocked, undetected until move-in.
- **Risk #2**: The constraint engine mishandles combined rules or
  date-range boundary cases (checkout-day == checkin-day, multiple
  constraints firing together).

Specifically: trace every write path that can assign a bed to a worker,
confirm how a constraint violation becomes an HTTP response, ground the
engine's fan-out/short-circuit behavior and date-boundary semantics,
inventory existing test coverage, and surface any relevant prior design
decisions from the `named-beds` change that shipped this engine.

## Summary

The constraint engine (`ConstraintEngine` + 4 `StayConstraint` beans) has
**no short-circuit** — every registered constraint always runs, and hard
and soft violations both surface in the same `ConstraintResult` when they
co-occur. Date-range overlap uses a consistent half-open-interval
(`dateFrom < effectiveDateTo AND (dateTo IS NULL OR dateTo > dateFrom)`)
across bed-, worker-, and (unused) room-level queries — a checkout date
equal to another stay's check-in date is **not** a conflict; same-day
turnover is allowed everywhere consistently. All 5 write paths that can
assign a bed (create, update, check-in, move, bulk-assign) funnel through
the same two `StayService` helpers, `resolveBed` then `runConstraints`,
and every hard-violation constraint failure surfaces as **HTTP 422,
`CONSTRAINT_VIOLATION`** via `GlobalExceptionHandler`.

The gaps are concrete, not speculative:

1. Bed-occupied/blocked rejection has HTTP-level test coverage only for
   **create** (`BedAssignmentIntegrationTest`, `StayIntegrationTest`).
   **update, check-in, and move have zero tests** asserting a 422 for an
   occupied/blocked target bed.
2. **Bulk-assign structurally cannot surface a 422 at all** — per-item
   constraint failures are swallowed into a `200 OK` response body with an
   `"error"` status string (`StayService.java:333-336`). This is a
   genuinely new finding, not one of the plan's original two risks — see
   Open Questions.
3. **No test exercises the exact checkout-day==checkin-day boundary**
   against the real JPQL query. `ConstraintEngineTest` (the only direct
   unit test of the engine) mocks the repository's overlap-count methods
   as `0`/`1` — the boundary operators themselves are untested at any
   layer.
4. **No test combines a hard violation with a soft violation** in the same
   `evaluate()` call to confirm both surface correctly (the closest test,
   `MultipleViolationTests.shouldCollectMultipleHardViolations...`, only
   combines multiple *hard* violations with a loose `hasSizeGreaterThanOrEqualTo(2)` assertion).

None of the two original risks turned out to be speculative — research
confirms concrete, currently-uncovered failure surfaces for both.

## Detailed Findings

### 1. Write paths → constraint engine → HTTP mapping (Risk #1)

All 5 write paths that can assign a bed funnel through the same two
private `StayService` helpers — no controller or public method calls
`ConstraintEngine` directly:

| Path | Controller | HTTP | Service method | `resolveBed` call | `runConstraints` call |
|---|---|---|---|---|---|
| Create | `StayController.create` (StayController.java:116-118) | `POST /api/v1/stays` | `StayService.create` (StayService.java:96-118) | 103-104 | 107 |
| Update | `StayController.update` (StayController.java:124-127) | `PUT /api/v1/stays/{id}` | `StayService.update` (StayService.java:121-147) | 133-134 | 137 |
| Check-in | `StayController.checkIn` (StayController.java:81-84) | `POST /api/v1/stays/{id}/check-in` | `StayService.checkIn` (StayService.java:157-188) | 170-171 | 174 |
| Move | `StayController.move` (StayController.java:106-109) | `POST /api/v1/stays/{id}/move` | `StayService.move` (StayService.java:206-259) | 228-229 | 236 |
| Bulk assign (per item) | `StayController.bulkAssign` (StayController.java:141-143) | `POST /api/v1/stays/bulk-assign` | `StayService.bulkAssign` (StayService.java:300-339) | 313 | 315 |

`resolveBed` (StayService.java:414-446) calls `constraintEngine.evaluate(ctx)`
directly at line 437, but **only** during auto-assign candidate iteration.
An explicit `bedId` from the caller is *not* validated by `resolveBed`
itself (StayService.java:403-412 javadoc) — enforcement for an explicit
bed happens entirely in the subsequent `runConstraints` call.

**Exception → HTTP mapping** (`runConstraints`, StayService.java:373-389):

- Any hard violation → `ConstraintViolationException("error.constraint.violated", ...)` (377-380).
- Soft violation with no `overrideReason` → same exception,
  `"error.constraint.soft_violations"` (383-388). Passing a non-null
  `overrideReason` (from the request DTOs) lets the write proceed past a
  soft violation; hard violations have **no override mechanism**.
- `resolveBed`'s no-candidate-beds case → `ConstraintViolationException`
  with detail type `BED_UNAVAILABLE` (428-432); all-candidates-failed case
  → the same, using the first candidate's hard violations (445).

`GlobalExceptionHandler.handleConstraintViolation`
(GlobalExceptionHandler.java:58-62) maps every `ConstraintViolationException`
to **HTTP 422 (`UNPROCESSABLE_ENTITY`)**, body `error="CONSTRAINT_VIOLATION"`,
`message=<message code>`, `details=<violation detail>`. Concretely:

- Bed-occupied: `BedOccupancyConstraint` (impl/BedOccupancyConstraint.java:47-53) → `HardViolation("BED_OCCUPIED", "constraint.bed.occupied", ...)` → 422 / `CONSTRAINT_VIOLATION` / detail `BED_OCCUPIED`.
- Blocked bed: `BlockedRoomConstraint` (impl/BlockedRoomConstraint.java:38-44) → `HardViolation("BED_BLOCKED", "constraint.bed.blocked", ...)` → 422 / `CONSTRAINT_VIOLATION` / detail `BED_BLOCKED`.
- No free bed in room: `resolveBed` (StayService.java:428-432) → 422 / `CONSTRAINT_VIOLATION` / detail `BED_UNAVAILABLE`.

Note: `BedOccupancyConstraint`/`BlockedRoomConstraint` are no-ops when
`ctx.bed()` is null (`ConstraintContext.java:11-15`), but every current
write path resolves a real bed before building the context, so this is
currently moot — flagged in Historical Context as a past transitional
state, not a live gap.

**Overlap definition** (`countActiveStaysInBed` / `...Excluding`,
StayRepository.java:94-108, 110-126):

```
s.dateFrom < :effectiveDateTo AND (s.dateTo IS NULL OR s.dateTo > :dateFrom)
```

where `effectiveDateTo = ctx.dateTo() != null ? ctx.dateTo() : LocalDate.MAX`
(BedOccupancyConstraint.java:38). A stay with `dateTo == null` (open-ended)
conflicts with everything starting after it. Only `PLANNED`,
`EXPECTED_TODAY`, `CHECKED_IN` stays count as occupying
(BedOccupancyConstraint.java:25-27).

**Coverage gaps found** (HTTP-level, occupied/blocked-bed rejection):

- Covered: **create** only — `BedAssignmentIntegrationTest.ExplicitAssign.shouldRejectBlockedBed`/`.shouldRejectOccupiedBed`, `NoFreeBed.shouldFailHard_whenRoomHasNoBeds`/`.shouldFailHard_whenAllBedsOccupied`, `StayIntegrationTest.shouldReturnHardViolation_whenNoFreeBedInRoom`.
- **Not covered**: `update` (`PUT /{id}`), `checkIn` (`POST /{id}/check-in`), `move` (`POST /{id}/move` — only same-bed 409 and success are tested, not occupied/blocked-*target*-bed 422).
- **Structurally cannot be covered as a 422** today: `bulkAssign` —
  `BulkOperationsIntegrationTest.shouldReturnPartialSuccess_whenSomeAssignmentsFail`
  (BulkOperationsIntegrationTest.java:170-199) only asserts the outer
  `errors()==1` count; the endpoint always returns outer `200 OK` by
  design, with the per-item constraint failure swallowed into
  `AssignmentResult.status="error"` / `e.getMessage()`
  (StayService.java:333-336). See Open Questions.

### 2. Constraint engine composition & boundary semantics (Risk #2)

**Fan-out, no short-circuit** — `ConstraintEngine.evaluate`
(ConstraintEngine.java:15-20) runs every registered `StayConstraint` bean
unconditionally via `forEach`:

```java
public ConstraintResult evaluate(ConstraintContext ctx) {
    var hard = new ArrayList<HardViolation>();
    var soft = new ArrayList<SoftViolation>();
    constraints.forEach(c -> c.evaluate(ctx, hard, soft));
    return new ConstraintResult(List.copyOf(hard), List.copyOf(soft));
}
```

If a gender-rule violation (always soft, `GenderConstraint.java:29-39`)
and an occupancy violation (always hard,
`BedOccupancyConstraint.java:47-53`) would independently fire, **both
appear** in the result — in their respective `hard`/`soft` lists.
`List<StayConstraint>` is constructor-injected (`ConstraintEngine.java:13`)
with no `@Order`; bean iteration order is implicit classpath-scan order,
but since each constraint only appends to shared lists and never reads
another's output, **order does not affect correctness** — only the
ordering of violations within the result lists, which nothing downstream
depends on.

**Boundary semantics are consistent across levels.** All overlap queries
— `countActiveStaysInBed`/`...Excluding` (StayRepository.java:94-108,
110-126), `countActiveStaysInRoom`/`...Excluding` (65-66, 81-82, currently
**unused by any constraint** — dead relative to the bed model, see Open
Questions), `countOverlappingStaysForWorker`/`...Excluding` (164-165,
180-181) — use the identical half-open pattern. A stay ending (checkout)
on date X and another starting (check-in) on date X do **not** overlap —
same-day turnover is allowed, consistently at bed, worker, and (unused)
room level. `findActiveStaysForPropertyOnDate` (136-137) uses a different,
point-in-time inclusion check (`dateFrom <= :date AND (dateTo IS NULL OR
dateTo > :date)`) rather than a range overlap — also treats checkout day
as vacated, consistent in spirit.

**Hard vs. soft**: `sealed interface Violation permits HardViolation,
SoftViolation` (Violation.java:5); `ConstraintResult.isAllowed()` returns
`hardViolations.isEmpty()` (ConstraintResult.java:7-9) — a hard violation
always blocks the write with no override. A soft violation blocks only
without a caller-supplied `overrideReason` (StayService.java:383-388).

**Coverage gaps found**:

- `ConstraintEngineTest` (the only test exercising the engine directly) is
  a unit test that **mocks `StayRepository`'s overlap-count methods**
  directly as `0L`/`1L` — it verifies constraint *composition* (which
  constraint fires under which mocked condition) but never exercises the
  real JPQL boundary predicate at all.
- **No repository-level or SQL-level test exists** for `StayRepository`'s
  overlap queries — the `<`/`>` boundary logic is entirely unverified
  against a real database.
- **No test constructs an exact checkout-day==checkin-day scenario** —
  every date arithmetic example in the integration suites uses
  `plusDays(n)`/`minusDays(n)` offsets that avoid exact adjacency.
- `MultipleViolationTests.shouldCollectMultipleHardViolations_whenSeveralConstraintsFail`
  (ConstraintEngineTest.java:353-365) is the only test combining multiple
  constraints firing at once, and it combines three *hard* violations with
  a loose `hasSizeGreaterThanOrEqualTo(2)` assertion — no test combines a
  hard **and** a soft violation in the same evaluation to confirm both
  buckets populate correctly.

### 3. Existing test inventory (full list)

Dedicated engine unit test: `src/test/java/com/beduno/stay/constraint/ConstraintEngineTest.java`
— real `ConstraintEngine` wired with real constraint beans, only
`StayRepository` mocked; no HTTP/Spring context.

- **Bed occupancy/blocked/double-booking**: `ConstraintEngineTest.BedOccupancyConstraintTests` (`shouldBeNoOp_whenBedIsNull`, `shouldBlockOperation_whenBedIsAlreadyOccupied`, `shouldAllow_whenBedIsFree`, `shouldExcludeCurrentStay_whenUpdating`); `.BlockedRoomConstraintTests` (`shouldBlockOperation_whenBedIsBlocked`, `shouldAllow_whenBedIsActive`); `.DoubleBookingConstraintTests` (`shouldBlockOperation_whenWorkerAlreadyHasOverlappingStay`, `shouldAllow_whenWorkerHasNoOverlappingStays`, `shouldExcludeCurrentStay_whenUpdating`).
- **HTTP-level bed conflict**: `BedAssignmentIntegrationTest` (`ExplicitAssign.shouldRejectBlockedBed`, `.shouldRejectOccupiedBed`, `AutoAssign.shouldSkipOccupiedAndBlockedBeds`, `NoFreeBed.shouldFailHard_whenRoomHasNoBeds`, `.shouldFailHard_whenAllBedsOccupied`); `StayIntegrationTest` (`Create.shouldReturnHardViolation_whenNoFreeBedInRoom`, `.shouldReturnHardViolation_whenWorkerAlreadyBooked`); `BulkOperationsIntegrationTest.BulkAssign.shouldReturnPartialSuccess_whenSomeAssignmentsFail`; `OperationalWorkflowIntegrationTest.RoomMoveWorkflow.shouldRejectMove_whenSameBed` (409, distinct `ConflictException` path — see Open Questions).
- **Combination**: `ConstraintEngineTest.MultipleViolationTests` (`shouldCollectMultipleHardViolations_whenSeveralConstraintsFail`, `shouldReturnAllowed_whenNoViolations`).
- **Boundary/date-adjacency**: none exercising the real overlap query. `StayGuardIntegrationTest.MoveOnFinalDay` (`shouldReturnConflict_whenStayEndsToday`, `shouldMove_whenStayStillHasNightsRemaining`) tests a same-stay `dateFrom==dateTo` DB-constraint edge case, not cross-stay boundary overlap.
- **Gender rule**: `ConstraintEngineTest.GenderConstraintTests` (5 methods covering MALE_ONLY/FEMALE_ONLY/MIXED/OTHER); `StayIntegrationTest.Create.shouldReturnSoftViolation_whenGenderMismatch`, `.shouldSucceed_whenGenderMismatchOverridden`.
- **Auto-assignment (`resolveBed`)**: `BedAssignmentIntegrationTest.AutoAssign.shouldPickLowestLabelFreeBed`, `.shouldSkipOccupiedAndBlockedBeds`; `ExplicitAssign.shouldSetAutoAssignedFalse_whenBedExplicit`.

### 4. Prior design decisions (`named-beds` change history)

From `context/changes/named-beds/plan.md`, `plan-brief.md`, and
`reviews/plan-review.md`:

- The engine's fan-out design was intentional and documented up front:
  "four hardcoded `@Component` beans fanned out by `ConstraintEngine`;
  hard-vs-soft is which list each one appends to, not declarative data"
  (plan.md:9). Bed-occupied and bed-blocked were explicitly decided to
  stay hard/never-overridable; only `GenderConstraint` stays soft
  (plan.md:20) — matches what research found live in the code.
- `resolveBed`'s auto-assign strategy was explicitly decided to be
  simple-by-design: "first available bed, ordered by label" (plan-brief.md:26),
  with a specific call-out that each candidate must be re-evaluated through
  the **full** engine, not just an occupancy check (plan.md:52) — matches
  the live `evaluate()`-per-candidate behavior.
- **Test enrichment was explicitly deferred to Phase 6** of that plan
  (plan.md:346, 431) and Phase 6 has since shipped exactly the coverage
  named there (auto-assign ordering, explicit override, blocked/occupied
  rejection, no-free-bed, cross-agency isolation) — but **combination and
  boundary-case testing was never named as in scope for any phase**, which
  is consistent with what this research found missing.
- **No boundary/adjacency decision is documented anywhere** in the three
  named-beds artifacts — the overlap logic is only justified by analogy
  ("structurally identical to how `DoubleBookingConstraint` already checks
  worker overlap," plan.md:9, 186), with no explicit statement of
  inclusive/exclusive semantics. This rollout phase is the first place
  that semantic gets pinned down and tested.
- Plan review's final verdict was **SOUND**, no outstanding architectural
  concerns (plan-review.md:69) — nothing blocks a hardening phase.
- A historical transitional-state note: during `named-beds`' own rollout,
  "room/bed-level occupancy and blocked-bed enforcement are genuinely
  absent from the running system during this window ... only worker-level
  `DoubleBookingConstraint` holds" (plan.md:50) — this confirms the
  `ctx.bed()==null` no-op path in `BedOccupancyConstraint`/`BlockedRoomConstraint`
  was deliberately exercised historically; it is not reachable from any
  current write path (all 5 resolve a real bed first), so a regression
  test guarding against its reappearance is a nice-to-have, not a gap.
- Backfilled data may not satisfy live constraints: "historical/non-overlapping
  stays don't need to satisfy the live occupancy constraint the way a
  fresh assignment does" (plan.md:142) — pre-existing bed assignments from
  the V12 backfill were never validated against `BedOccupancyConstraint`.
  Whether to validate historical data retroactively is a scope decision
  for `/10x-plan`, not something this research resolves.

## Code References

- `src/main/java/com/beduno/stay/StayController.java:81-84,106-109,116-118,124-127,141-143` — the 5 write-path endpoints
- `src/main/java/com/beduno/stay/StayService.java:96-118,121-147,157-188,206-259,300-339` — the 5 service methods
- `src/main/java/com/beduno/stay/StayService.java:373-389` — `runConstraints`, the exception-throwing chokepoint
- `src/main/java/com/beduno/stay/StayService.java:403-446` — `resolveBed`, including the auto-assign loop (437) and no-candidate/all-failed paths (428-432, 445)
- `src/main/java/com/beduno/stay/StayService.java:333-336` — bulk-assign per-item error swallowing (never surfaces HTTP status)
- `src/main/java/com/beduno/common/exception/GlobalExceptionHandler.java:58-62` — `ConstraintViolationException` → 422 `CONSTRAINT_VIOLATION`
- `src/main/java/com/beduno/stay/constraint/ConstraintEngine.java:13,15-20` — constructor-injected bean list, full-fanout `evaluate`
- `src/main/java/com/beduno/stay/constraint/ConstraintContext.java:11-15` — null-bed no-op guard
- `src/main/java/com/beduno/stay/constraint/impl/BedOccupancyConstraint.java:25-27,38,47-53` — status filter, effective-date-to, hard violation
- `src/main/java/com/beduno/stay/constraint/impl/BlockedRoomConstraint.java:38-44` — blocked-bed hard violation
- `src/main/java/com/beduno/stay/constraint/impl/GenderConstraint.java:29-39` — soft violation
- `src/main/java/com/beduno/stay/constraint/Violation.java:5`, `HardViolation.java:5`, `SoftViolation.java:5`, `ConstraintResult.java:5,7-9` — violation type hierarchy
- `src/main/java/com/beduno/stay/StayRepository.java:65-66,81-82` — `countActiveStaysInRoom`/`Excluding` (unused by constraints)
- `src/main/java/com/beduno/stay/StayRepository.java:94-108,110-126` — `countActiveStaysInBed`/`Excluding`, the live overlap predicate
- `src/main/java/com/beduno/stay/StayRepository.java:136-137` — `findActiveStaysForPropertyOnDate`, point-in-time check
- `src/main/java/com/beduno/stay/StayRepository.java:164-165,180-181` — `countOverlappingStaysForWorker`/`Excluding`
- `src/test/java/com/beduno/stay/constraint/ConstraintEngineTest.java` — dedicated engine unit test (all `*Tests` nested classes)
- `src/test/java/com/beduno/stay/constraint/ConstraintEngineTest.java:353-365` — `MultipleViolationTests.shouldCollectMultipleHardViolations_whenSeveralConstraintsFail`
- `src/test/java/com/beduno/stay/BedAssignmentIntegrationTest.java` — auto-assign, explicit-assign, no-free-bed HTTP-level tests
- `src/test/java/com/beduno/stay/StayIntegrationTest.java:92-115` — `shouldReturnHardViolation_whenNoFreeBedInRoom`
- `src/test/java/com/beduno/stay/BulkOperationsIntegrationTest.java:170-199` — `shouldReturnPartialSuccess_whenSomeAssignmentsFail`
- `src/test/java/com/beduno/stay/OperationalWorkflowIntegrationTest.java` — `RoomMoveWorkflow.shouldRejectMove_whenSameBed` (409, distinct code path)
- `src/test/java/com/beduno/stay/StayGuardIntegrationTest.java:41-58,47-48,65-66` — `MoveOnFinalDay` same-stay boundary tests (different from cross-stay overlap)

## Architecture Insights

- **Full fan-out, no short-circuit** is a deliberate, load-bearing design
  choice (confirmed both live and in the original plan) — any test
  strategy for Risk #2 can safely assume every constraint always runs, so
  "does constraint B still get evaluated after constraint A fails hard"
  is not a live risk; what's untested is whether the *results* compose
  correctly when a hard and a soft violation co-occur.
- **Boundary semantics are uniform** across bed/worker/(unused room)
  levels — there is no divergence to test between levels, simplifying the
  Phase 1 test design to one boundary-case pattern reused across bed and
  worker overlap checks.
- **Two natural test layers already exist and should be extended, not
  replaced**: `ConstraintEngineTest` (unit, mocked repository, tests
  composition) and the `*IntegrationTest` family (real HTTP + Testcontainers,
  tests end-to-end rejection). The boundary-semantics gap specifically
  requires the integration layer, since the unit layer mocks past the real
  query.
- **`resolveBed`'s two-phase design** (unchecked explicit bed → engine
  validates in `runConstraints`; auto-assign evaluates the engine inline
  per candidate) means an explicit-bed test and an auto-assign test are
  exercising genuinely different code paths through the same engine, not
  redundant coverage.

## Historical Context (from prior changes)

See "4. Prior design decisions" above — no separate archive entries exist
(`context/archive/` has no slices yet); all historical context comes from
the still-active `context/changes/named-beds/` folder.

## Related Research

None — this is the first research document for this change, and no other
`context/changes/**/research.md` exists yet in the repository.

## Open Questions

1. **Bulk-assign cannot surface a 422 at all.** This is a genuinely new
   finding beyond the plan's original two risks: `StayService.bulkAssign`
   swallows every per-item constraint failure into a `200 OK` response
   with `AssignmentResult.status="error"` (StayService.java:333-336). A
   test asserting "bed conflict is rejected" for this path cannot use the
   same HTTP-status assertion shape as the other 4 paths — it must assert
   on the response body's per-item status/message instead. `/10x-plan`
   should decide whether this belongs in this rollout phase (as a
   response-shape nuance on Risk #1) or as a separately tracked risk.
2. **`countActiveStaysInRoom`/`...Excluding` appear unused** by any live
   constraint (only bed- and worker-level counts are called from
   `BedOccupancyConstraint`/`DoubleBookingConstraint`). Confirm whether
   this is intentional dead code left over from the pre-named-beds
   room-capacity model, or used elsewhere not covered by this research's
   scope — if genuinely dead, it's a cleanup candidate, not a test target.
3. **Backfilled (V12) bed assignments were never validated against
   `BedOccupancyConstraint`.** Whether Phase 1 should add a data-integrity
   check for pre-existing rows, or explicitly scope that out as "new
   writes only," is a planning decision, not something this research
   resolves.
4. **Response-guidance corrections for `test-plan.md` §2** (surfaced by
   this research, not yet backported):
   - The original guidance for Risk #1 hedged "409/422" for constraint
     rejections — research confirms constraint violations are *always*
     422/`CONSTRAINT_VIOLATION`; the existing 409 (`ConflictException`,
     same-bed move) is a structurally different, already-tested code path
     and should not be conflated with constraint-engine rejections.
   - The original guidance for Risk #2 hedged between unit and
     integration as the "likely cheapest layer" — research confirms
     **both are needed and already exist as separate layers**:
     `ConstraintEngineTest` (unit, composition) is the right place to add
     a hard+soft co-occurrence test; a *new* integration-level test
     against the real repository query is required for the boundary
     semantics, since the existing unit test mocks past the real SQL.
   - These are refinements, not reversals — `/10x-plan` can consume this
     research document directly without needing `test-plan.md` §2 edited
     first, but the plan owner may want the backport for future rollout
     phases that reference §2 evidence.
