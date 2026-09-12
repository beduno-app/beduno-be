# Constraint Engine Hardening — Plan Brief

> Full plan: `context/changes/testing-constraint-engine-hardening/plan.md`
> Research: `context/changes/testing-constraint-engine-hardening/research.md`

## What & Why

Close the concrete, research-confirmed gaps in the bed-conflict
constraint engine's test coverage: three of five write paths that can
assign a bed have zero occupied/blocked-bed rejection tests, the
date-boundary adjacency rule is entirely unverified against the real
query, and no test proves a hard and soft violation surface together.
This is rollout Phase 1 of `context/foundation/test-plan.md`, covering
Risks #1 ("bed double-assignment undetected") and #2 ("constraint engine
mishandles combined rules or boundary cases").

## Starting Point

All 5 bed-assigning write paths (create, update, check-in, move,
bulk-assign) funnel through the same `StayService.resolveBed` →
`runConstraints` chokepoint, and every hard violation maps to HTTP 422 /
`CONSTRAINT_VIOLATION`. Only **create** has end-to-end test coverage of
occupied/blocked-bed rejection. The engine itself
(`ConstraintEngine.evaluate`) has no short-circuit and is unit-tested via
`ConstraintEngineTest`, but that test mocks the repository's overlap
counts directly — the real date-boundary SQL has never been exercised.
Research also surfaced, as a side finding, that bulk-assign structurally
cannot return a 422 at all: per-item failures are swallowed into a 200 OK
response body.

## Desired End State

Update, check-in, and move each reject an occupied or blocked target bed
exactly like create already does. A test proves same-day checkout/checkin
does not conflict while a true overlap does. A unit test proves hard and
soft violations co-occur correctly. Bulk-assign's current behavior is
documented by a passing test rather than left as an untested accident.
`test-plan.md`'s cookbook reflects the real test-layer split this phase
discovered.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Bulk-assign's swallowing behavior | Test current behavior only; do not fix | Keeps this rollout phase test-only, zero production risk | Plan (user decision) |
| Write-path coverage breadth | Occupied + blocked, per path (6 new tests) | Matches the symmetric coverage `create` already has | Plan (user decision) |
| Boundary-case depth | Bed-level only, both directions (checkout==checkin, true overlap) | Directly proves the named risk; worker/room-level use identical operators per research | Plan (user decision) |
| Boundary test's home | New nested class in `BedAssignmentIntegrationTest` | Keeps bed-conflict tests in one place rather than introducing a new repository-test convention | Plan (user decision) |
| Hard+soft combo test layer | Unit, in `ConstraintEngineTest` | Matches the existing composition-test pattern; HTTP-level proof would need an artificial fixture for no added signal | Plan (user decision) |
| Phase priority | Write-path parity → boundary → combo/documentation | Closes the largest, most concrete gap (3 fully untested paths) first | Plan (user decision) |
| 422 vs 409 status mapping | Constraint violations are always 422; 409 is a distinct, already-tested code path (same-bed move) | Research corrected the plan's original hedge between the two | Research |

## Scope

**In scope:**
- Occupied/blocked-bed rejection tests for update, check-in, move (6 tests)
- Checkout==checkin boundary test + true-overlap negative control (2 tests)
- Hard+soft violation co-occurrence unit test (1 test)
- Bulk-assign current-behavior documentation test (1 test)
- `test-plan.md` §6.1/§6.6 correction

**Out of scope:**
- Fixing bulk-assign's error-swallowing behavior (production code change)
- A dedicated repository-level test class
- Worker-level or room-level boundary-adjacency tests
- Validating historical/backfilled (V12) bed data against live constraints
- Investigating whether `countActiveStaysInRoom` is dead code

## Architecture / Approach

Every test lands in an existing, conventional file — no new test
infrastructure. All fixture helpers needed already exist in the five
files this plan touches.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Write-path parity | Occupied/blocked-bed rejection for update, check-in, move | `move`'s same-bed check must not be confused with the constraint-violation path (409 vs 422) |
| 2. Boundary-case coverage | Checkout==checkin non-conflict + true-overlap negative control | Test must actually hit the real query, not a mock, or it proves nothing |
| 3. Combo + documentation + cookbook | Hard+soft co-occurrence test, bulk-assign behavior test, `test-plan.md` correction | None significant — lowest-risk phase |

**Prerequisites:** None — test-only, no dependencies on other in-flight work.
**Estimated effort:** ~11 new test methods + one doc correction, across 3 phases.

## Open Risks & Assumptions

- Bulk-assign's swallowed-error contract remains unfixed after this
  phase; if the team later decides it should return a structured
  per-item error code or a mixed status, that's a separate change.
- The two research open questions not addressed here (dead
  `countActiveStaysInRoom` code, unvalidated V12 backfill data) remain
  open for a future phase if ever prioritized.

## Success Criteria (Summary)

- All 5 bed-assigning write paths have symmetric occupied/blocked-bed
  rejection coverage.
- The date-boundary adjacency rule is proven against the real query, not
  a mock.
- `./gradlew build` stays green throughout (Checkstyle + full suite).
