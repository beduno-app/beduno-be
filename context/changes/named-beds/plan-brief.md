# Named Beds, End-to-End — Plan Brief

> Full plan: `context/changes/named-beds/plan.md`

## What & Why

Beduno records a worker's placement as "this room," never "this bed" — `Room` carries only a `capacity` int and a `blockedSpots` int. This plan introduces a `Bed` entity so a planner can allocate a wave of workers to individually identified beds, with the system auto-assigning a bed unless the planner overrides the choice. It's the roadmap's north star (`S-01`) — the smallest end-to-end slice that tests whether the domain model actually matches how the agency works — and implements PRD FR-004, FR-005, and FR-018.

## Starting Point

No bed concept exists anywhere in the codebase. `Room.availableSpots()` is a static `capacity - blockedSpots` int; `Stay.roomId` is the sole placement pointer, set directly by five write paths in `StayService`. The constraint engine's `CapacityConstraint` is a room-level headcount, hardcoded hard (never overridable). There is no auto-assignment or override precedent anywhere in the code. Also surfaced during planning: a live frontend (SPA) is already built against the current Room/Stay JSON contract in `docs/api-specification.md` — migration `V9`, which landed the same day as this plan, exists purely to match it, contradicting the PRD's "no integrated consumers" claim.

## Desired End State

A planner creates a room, bulk-generates its beds, and allocates a wave of workers to a property — the system picks a free, unblocked bed for each worker unless one is named explicitly, and every stay records which bed and whether the choice was automatic. `Room.capacity`/`blockedSpots` are gone; bed inventory is the only source of truth. Occupancy views, exports, and the audit trail all speak in beds. Existing rooms and stays are backfilled, not reset.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Bed creation | Bulk-generate with sequential numbering, renameable after | Matches today's one-call room setup; individual add-only would make onboarding a property's inventory a real chore |
| Room.capacity/blockedSpots | Removed entirely, replaced by bed-derived counts | One source of truth — keeping both recreates the exact drift problem this change exists to eliminate |
| GenderRule scope | Stays room-level | Matches how the agency actually thinks about it; no PRD signal suggests per-bed segregation |
| Blocked spots | Become a per-bed `BedStatus.BLOCKED` | Directly answers the PRD's own gap analysis — you can now name *which* bed is out of service |
| CapacityConstraint | Replaced by a per-bed occupancy check | Capacity becomes a counting problem no longer meaningful once beds exist; a bed-occupied check is structurally identical to the existing double-booking check |
| Auto-assign heuristic | First available bed, ordered by label | Simplest correct behavior; "smart" allocation is explicitly `S-07`'s job, not this slice's |
| Auto vs. override tracking | New `bedAutoAssigned` boolean on Stay | The existing free-text `overrideReason` means something different (justifying a soft-constraint warning) — conflating the two loses a real, queryable signal |
| API contract | Broken deliberately, documented like `V9` | Honors what the PRD actually asked for; the frontend session gets a coordinated follow-up, not a silent shim |
| Data migration | Backfill existing rooms/stays, don't reset | Preserves in-flight dev/demo data other sessions (notably the frontend) are actively exercising |
| No-free-bed failure | Hard 422, same shape as today's capacity rejection | Suggesting alternatives is allocation-proposal territory (`S-07`, still blocked) — building a slice of it here blurs a boundary the roadmap deliberately drew |
| Same-room bed move | `move` extended to allow it (only same-*bed* is rejected) | Matches how a planner actually thinks about "change where this worker sleeps" — whether the new bed is in the same room is incidental |

## Scope

**In scope:** `Bed` entity + CRUD, bed backfill migration, `Room.capacity`/`blockedSpots` retirement, constraint-engine bed-awareness, `Stay` bed integration (auto-assign + override + move), occupancy/export bed-awareness, test and doc reconciliation.

**Out of scope:** external hotels (`S-02`), crew rule (`S-05`), room-sharing rules (`S-06`), bed-night cost (`S-11`), allocation proposals (`S-07`), per-bed gender rules, "smart" auto-assignment, alternate-room suggestions, a `FINANCE` role.

## Architecture / Approach

`Bed` is a new flat module (`com.beduno.bed`, alongside `room/`), additive and disconnected from `Stay` in Phase 1. `Stay` gains a `bedId` (the real placement pointer) plus denormalized `roomId`/`propertyId` (unchanged existing convention). The constraint engine's `CapacityConstraint` is replaced by a `BedOccupancyConstraint` keyed on bed instead of room; `BlockedRoomConstraint` gains a bed-status check. A single `resolveBed(...)` helper in `StayService` handles both auto-assign (iterate free beds by label, re-running the full constraint engine per candidate) and explicit override, used by every write path.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Bed foundation | `Bed` entity, CRUD, audit, bulk-generate — fully additive | None — nothing else depends on it yet |
| 2. Schema migration, backfill, capacity retirement, bed-occupancy constraint | Every room/stay backfilled onto beds; `capacity`/`blockedSpots` gone; `CapacityConstraint` replaced by a bed-occupancy check; `OccupancyService`/`ExportService` rewired in the same phase | Three real consumers (`CapacityConstraint`, `OccupancyService`, `ExportService`+DTOs) call the fields this phase removes — plan review caught that leaving them for later phases breaks `./gradlew build` at this phase's own boundary; now fixed by updating them here too |
| 3. Blocked-bed constraint | `BlockedRoomConstraint` extended with a bed-status check | Low — the last, self-contained piece of constraint-engine bed-awareness |
| 4. Stay write-path integration | Auto-assign + override across create/update/check-in/move/bulk-assign; `bed_id` finally made `NOT NULL` (`V14`) | Five write paths to update consistently; incidental audit-gap fix included; plan review caught that `NOT NULL` can't land any earlier than this phase without breaking every existing Stay test in between |
| 5. Occupancy & export bed-level detail | Per-occupant `bedId`/`bedLabel` on top of the aggregate counts Phase 2 already introduced | Low — mostly additive DTO fields |
| 6. Test & doc reconciliation | Existing suites updated, new bed coverage, docs reconciled | Inverting the same-room-move test is a visible, deliberate behavior change |

**Prerequisites:** none — `S-01` has no roadmap prerequisites; `F-01`, `F-02`, `S-04`, `S-08` can run in parallel with this.

**Plan-reviewed:** `/10x-plan-review` found two critical phase-sequencing gaps (Room-field consumers and a `ConstraintContext` record change both outliving the phase that broke them) plus a related `NOT NULL`-timing issue surfaced during triage, and one warning (two new failure paths with no defined message code). All four are fixed in this version — see `context/changes/named-beds/reviews/plan-review.md`.

## Open Risks & Assumptions

- The frontend (SPA) will need a coordinated update once this ships — flagged explicitly in `change.md`, not silently absorbed.
- The backfill's stay-to-bed matching is deterministic but synthetic for historical/non-overlapping stays; acceptable because it's demo/dev data, not production history (PRD FR-018 explicitly permits discarding pre-migration data — this plan chooses to preserve it anyway rather than reset).
- US-01's acceptance criteria (Open Question 3) remain unresolved by the product owner; this plan builds against FR-004/FR-005 and validates against those, not a signed-off acceptance test.

## Success Criteria (Summary)

- A planner can allocate a wave of workers to a property via the API with beds auto-assigned or explicitly overridden, and no two active stays ever share a bed.
- Every pre-existing room and stay survives the migration with a valid bed — no worker is left without one.
- The occupancy export, inspection roster, and audit trail all report at bed granularity.
