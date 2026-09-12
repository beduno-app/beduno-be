# Authorization Boundary Closure — Plan Brief

> Full plan: `context/changes/testing-authorization-boundary-closure/plan.md`
> Research: `context/changes/testing-authorization-boundary-closure/research.md`

## What & Why

Close the test-coverage gaps rollout Phase 2 targets: cross-agency
isolation on bed-touching write paths (Risk #5), and a property-scoping
test asymmetry (`BedIntegrationTest` has zero scoping tests despite
sharing `RoomIntegrationTest`'s guard). It also documents — without
fixing — a real, severe property-scoping gap (Risk #3): PROPERTY_ADMIN/
FRONT_DESK users can act on Stay/Occupancy resources at any property in
their agency today, with only 3 endpoints in the whole codebase actually
checking `assignedPropertyIds`.

## Starting Point

`CurrentUser.hasPropertyAccess` is called at exactly 3 places (property
update, room create/update, bed create/bulk-generate/update). Everything
else PROPERTY_ADMIN/FRONT_DESK can reach — all of Stay, all of
Occupancy/Inspection/Arrivals — has no property check, and the existing
test suite already demonstrates this as an unintended side effect. Every
bed-touching write path correctly filters by `agencyId`, but none of the
5 paths (`BedService` create/bulkGenerate/update/delete,
`resolveBed`'s auto-assign branch) has a cross-agency test today.

## Desired End State

Two passing trip-wire tests document today's property-scoping gap on
Stay and Occupancy — they'll fail once roadmap slice S-08 fixes it,
flagging that work for whoever implements it. All 5 bed-touching write
paths have cross-agency test coverage. `BedIntegrationTest` has the same
property-scoping tests `RoomIntegrationTest` already has.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Property-scoping gap (Risk #3) handling | Document as a passing trip-wire, don't fix | Fixing ~15+ endpoints is roadmap slice S-08's job, already `ready`; this rollout stays test-only per its own charter and Phase 1's precedent | Plan (user decision) |
| Trip-wire coverage breadth | One representative endpoint per resource type (Stay check-in, Occupancy read) | Proves the pattern without repeating the same missing-check assertion ~15 times | Plan (user decision) |
| BedIntegrationTest parity | Close it in this phase | Zero-risk, direct copy of RoomIntegrationTest's proven pattern, no code change needed | Plan (user decision) |
| Cross-agency bed-path coverage | All 5 write paths | Matches AGENTS.md's per-module-per-path mandate; research found all 5 genuinely uncovered | Plan (user decision) |
| Undocumented tenant-scope patterns (existsByRoomIdAndLabel, ExportService) | Out of scope, left as an open note | AGENTS.md edits are a different kind of change than test additions; not one of this phase's named risks | Plan (user decision) |
| Phase priority | Trip-wire first, then cross-agency, then parity | Risk #3 is the more severe, PRD/roadmap-flagged finding | Plan (user decision) |

## Scope

**In scope:**
- Property-scoping trip-wire tests for Stay check-in and Occupancy read (2 tests)
- Cross-agency isolation tests for BedService's 4 write paths + auto-assign (5 tests)
- BedIntegrationTest property-scoping parity with RoomIntegrationTest (2 tests)

**Out of scope:**
- Actually fixing the property-scoping gap (roadmap slice S-08's job)
- Testing every unchecked endpoint individually (~15+)
- Editing AGENTS.md for the two undocumented tenant-scope patterns
- Fixing WorkerIntegrationTest's weak cross-agency assertion (incidental finding)

## Architecture / Approach

Every test lands in an existing, conventional file, reusing patterns
already proven elsewhere (`RoomIntegrationTest`'s property-scoping shape,
the standard cross-agency 404 shape). No new test infrastructure — the
3-arg `authHeaders` overload already exists.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Property-scoping trip-wire | 2 passing tests documenting the Risk #3 gap on Stay + Occupancy | Must read clearly as intentional documentation, not an endorsed design — needs a strong inline comment |
| 2. Cross-agency bed-path tests | 5 tests closing Risk #5's coverage gap | None significant — code is already correct, tests just prove it |
| 3. BedIntegrationTest parity | 2 tests closing a pure test-coverage asymmetry | None significant — direct pattern copy |

**Prerequisites:** None — test-only, no dependencies on other in-flight work.
**Estimated effort:** ~9 new test methods across 3 phases.

## Open Risks & Assumptions

- The trip-wire tests will need to be updated or removed once roadmap
  slice S-08 ships — this is intentional but does carry the same
  bit-rot risk this codebase has already seen once (forgotten `@Disabled`
  tests from an earlier phase). The inline comments are the mitigation.
- The two undocumented tenant-scope patterns (research Open Question 2)
  remain undocumented after this phase.

## Success Criteria (Summary)

- Two trip-wire tests pass today and clearly document the property-scoping gap.
- All 5 bed-touching write paths have cross-agency test coverage.
- `BedIntegrationTest` matches `RoomIntegrationTest`'s property-scoping pattern.
- `./gradlew build` stays green throughout (Checkstyle + full suite).
