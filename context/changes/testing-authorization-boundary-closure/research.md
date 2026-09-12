---
date: 2026-09-12T13:54:34Z
researcher: Claude Sonnet 5
git_commit: 2b521dffdbffd29229e426699051662fc05d1976
branch: main
repository: beduno-be
topic: "Authorization boundary closure — property-scoping and cross-agency isolation (test-plan Phase 2, Risks #3 and #5)"
tags: [research, codebase, authorization, property-scoping, tenant-isolation, testing]
status: complete
last_updated: 2026-09-12
last_updated_by: Claude Sonnet 5
---

# Research: Authorization boundary closure — property-scoping and cross-agency isolation

**Date**: 2026-09-12T13:54:34Z
**Researcher**: Claude Sonnet 5
**Git Commit**: 2b521dffdbffd29229e426699051662fc05d1976
**Branch**: main
**Repository**: beduno-be

> Note on permalinks: `main` is 11 commits ahead of `origin/main` and the
> current HEAD is not pushed, so GitHub permalinks would 404. All
> references below use local `path:line` citations instead.

## Research Question

Ground rollout Phase 2 of `context/foundation/test-plan.md` ("Authorization
boundary closure") for two risks:

- **Risk #3**: A FRONT_DESK/PROPERTY_ADMIN user acts on stays/rooms/beds at
  a property they are not assigned to, within their own agency.
- **Risk #5**: A bed-touching write path, or a new non-agency identity,
  leaks or accepts another agency's data.

Specifically: inventory every place `hasPropertyAccess` is (or isn't)
enforced across all FRONT_DESK/PROPERTY_ADMIN-reachable endpoints; verify
every bed-touching write path (including `resolveBed`'s auto-assign
branch) filters by `agencyId`; and inventory existing test coverage for
both concerns.

## Summary

**Risk #3 is a real, large, already-documented gap — not speculative.**
`CurrentUser.hasPropertyAccess` (`common/security/CurrentUser.java:15-28`)
is called at exactly **3 call sites** in the entire codebase: `PUT
/properties/{id}`, `POST`/`PUT` on rooms, and `POST`/`bulk-generate`/`PUT`
on beds. Every other FRONT_DESK/PROPERTY_ADMIN-reachable endpoint —
**all of Stay** (create, read, update, cancel, check-in, check-out,
move, no-show, bulk-assign, bulk-checkout, arrivals), **all of
Occupancy/Exceptions/Inspection/Arrivals-export**, and Worker reads —
performs **no property check at all**. A PROPERTY_ADMIN or FRONT_DESK
user can act on any resource in their own agency regardless of
`assignedPropertyIds`. This isn't hypothetical: the existing test suite
already proves it as a side effect — `OperationalWorkflowIntegrationTest`
and `StayGuardIntegrationTest` call check-in/check-out/move/no-show using
`authHeaders(Role.PROPERTY_ADMIN)` (2-arg overload, which sets
`assignedPropertyIds = new UUID[0]` — zero assigned properties) and these
calls succeed with 200 OK today.

**Risk #5 is NOT a live vulnerability in the code researched — it is a
test-coverage gap.** Every bed-touching write path (`BedService`
create/bulkGenerate/update/delete, and `StayService.resolveBed`'s
auto-assign branch specifically) correctly filters by `agencyId` at
every repository call. The gap is that **none of this is tested against
another agency** — `BedIntegrationTest.TenantIsolation` only covers
read/list; `BedAssignmentIntegrationTest.TenantIsolation` only covers the
explicit-bedId path, not auto-assign. Two related, previously-undocumented
findings surfaced along the way (see Open Questions): two repository
queries structurally rely on a caller having pre-validated tenant scope
rather than filtering by `agencyId` themselves, in the same shape as the
sanctioned exceptions in `AGENTS.md` but not recorded there.

## Detailed Findings

### 1. Property-scoping enforcement (Risk #3)

`CurrentUser.hasPropertyAccess` (`CurrentUser.java:15-28`): AGENCY_ADMIN
and AGENCY_PLANNER always pass; for PROPERTY_ADMIN/FRONT_DESK, a `null`
`assignedPropertyIds` fails closed (not "all properties"), and access
requires an exact id match in the array. The method itself does not
special-case FRONT_DESK — every call site restricts the check to
`role == PROPERTY_ADMIN`.

**All 3 call sites** (exhaustive grep, confirmed against `docs/api-specification.md:135-142`'s existing claim, which is accurate):

- `PropertyController.java:72` — `update()`, `PUT /properties/{id}`.
- `RoomController.java:93` (`checkPropertyAccess` helper) — called from `create()` (`RoomController.java:65`) and `update()` (`:76`).
- `BedController.java:108` (`checkPropertyAccess` helper) — called from `create()` (`:64`), `bulkGenerate()` (`:78`), `update()` (`:91`).

**Everything else reachable by PROPERTY_ADMIN/FRONT_DESK is unchecked**:
`StayController` — every endpoint (`GET/POST/PUT/DELETE /stays*`,
including `check-in`, `check-out`, `move`, `no-show`, `bulk-assign`,
`bulk-checkout`, `arrivals`); `OccupancyController` — `GET /occupancy`,
`GET /exceptions`, `GET/POST /inspection`, `GET /arrivals/export`,
`GET /occupancy/export`, `GET /exceptions/export`; `WorkerController`'s
read endpoints. `BedController`'s `DELETE` is intentionally
AGENCY_ADMIN-only (no property check applicable). `AuditController` is
AGENCY_ADMIN/AGENCY_PLANNER-only, out of scope for these two roles.

**What it takes to exploit the gap today**: on `StayController`, the
`id` path param resolves via agency-filtered lookup only — no property
filter — so a PROPERTY_ADMIN/FRONT_DESK user who knows any stay id in
their own agency can act on it regardless of property. On
`OccupancyController`, `propertyId` is a raw path param with only an
`(agencyId, propertyId)`-scoped query — any property in the same agency
works, including the inspection roster endpoint, which returns
room-by-room occupant detail.

### 2. Cross-agency filtering on bed-touching write paths (Risk #5)

**`StayService.resolveBed`'s auto-assign branch**
(`StayService.java:414-446`, specifically line 423) makes exactly one
repository call: `bedRepository.findAllByAgencyIdAndRoomId(room.getAgencyId(),
room.getId())`. `room.getAgencyId()` comes from `getRoomOrThrow` (467-470),
itself gated on `roomRepository.findByIdAndAgencyId(roomId, agencyId)`
with `agencyId = TenantContext.requireAgencyId()`. Properly filtered end
to end — **no leak found**.

**`BedService`'s 4 write paths** (`BedService.java`): create (55-72),
bulkGenerate (80-98), update (110-126), delete (129-143) all gate through
`getRoomOrThrow`/`getBedOrThrow` first, both of which are agency-filtered
(`RoomRepository.findByIdAndAgencyIdAndPropertyId`,
`BedRepository.findByIdAndAgencyIdAndRoomId`). Once gated, all subsequent
queries and the entity being mutated are already tenant-verified.

**Two structurally-fragile-but-currently-safe patterns found, neither
documented in `AGENTS.md`**:

- `BedRepository.existsByRoomIdAndLabel(UUID roomId, String label)`
  (`BedRepository.java:20`), called from `BedService.java:60` (create)
  and `:116` (update) — takes no `agencyId`. Safe today only because both
  callers already validated `roomId` via `getRoomOrThrow` moments before.
  Not one of `AGENTS.md`'s 3 named sanctioned exceptions.
- `ExportService.java:90` calls the inherited, unfiltered
  `BedRepository.findAllById(...)` to resolve bed labels
  (`ExportService.java:69` sources the ids from
  `stayService.getArrivals(...)`, which is itself agency-filtered via
  `StayRepository.findArrivals`). Same shape as the sanctioned
  `OccupancyService.loadWorkers` exception, but for beds, and not
  recorded in `AGENTS.md`.

### 3. Existing test coverage — property-scoping

Only **2 files** exercise `assignedPropertyIds` scoping (the 3-arg
`authHeaders(Role, UUID agencyId, UUID[] propertyIds)` overload defined
at `IntegrationTestBase.java:60`, which threads through to
`User.setAssignedPropertyIds`):

- `RoomIntegrationTest.Create` — `shouldCreateRoom_whenPropertyAdminWithAccess`,
  `shouldRejectCreate_whenPropertyAdminWithoutAccess` (403 when the
  assigned-properties array excludes the target).
- `PropertyIntegrationTest.Update` — `shouldAllowPropertyAdminToUpdateOwnProperty`,
  `shouldRejectPropertyAdminUpdatingOtherProperty`.

**`BedIntegrationTest` has zero property-scoping tests** despite
`BedController` carrying the identical `checkPropertyAccess` guard as
`RoomController` — a striking, easily-fixed asymmetry (same pattern,
just never copied over). No stay, worker, or occupancy test uses the
3-arg overload at all — every PROPERTY_ADMIN/FRONT_DESK call in
`OperationalWorkflowIntegrationTest`, `StayGuardIntegrationTest`,
`BulkOperationsIntegrationTest`, etc. uses the 2-arg overload (empty
`assignedPropertyIds`), which is why those tests currently pass despite
exercising exactly the gap Risk #3 describes.

### 4. Existing test coverage — cross-agency isolation on bed paths

- `BedIntegrationTest.TenantIsolation` (`:164-188`) — read-only:
  `shouldNotAccessBedFromOtherAgency`, `shouldNotListBedsFromOtherAgencyRoom`.
  **No create/bulkGenerate/update/delete cross-agency test.**
- `BedAssignmentIntegrationTest.TenantIsolation` (`:240-262`) — only
  `shouldRejectBedFromOtherAgencyRoom`, which targets the **explicit**
  bedId path. **No test drives auto-assign (`bedId: null`) against a
  cross-agency room.**
- `StayIntegrationTest.TenantIsolation` (`:336-368`) — only
  `shouldNotAccessStayFromOtherAgency` (GET) and
  `shouldNotCancelStayFromOtherAgency` (cancel). **No cross-agency test
  for `StayService.create` (beyond the explicit-bedId case already
  covered by `BedAssignmentIntegrationTest`), `update`, `checkIn`,
  `move`, or `bulkAssign`.**

**Write paths with zero cross-agency test today**: `BedService.create`,
`.bulkGenerate`, `.update`, `.delete`; `StayService.resolveBed`'s
auto-assign branch; `StayService.update`, `.checkIn`, `.move`,
`.bulkAssign`.

## Code References

- `src/main/java/com/beduno/common/security/CurrentUser.java:15-28` — `hasPropertyAccess`
- `src/main/java/com/beduno/property/PropertyController.java:67-72` — the only property-write scope check
- `src/main/java/com/beduno/room/RoomController.java:65,76,93` — room create/update scope checks
- `src/main/java/com/beduno/bed/BedController.java:64,78,91,108` — bed create/bulkGenerate/update scope checks
- `src/main/java/com/beduno/stay/StayController.java` — every endpoint, all unchecked for property scope
- `src/main/java/com/beduno/occupancy/OccupancyController.java:40,49,58,67,89` — occupancy/exceptions/inspection/arrivals-export, all unchecked
- `src/main/java/com/beduno/stay/StayService.java:414-446` (esp. 423) — `resolveBed` auto-assign, properly agency-filtered
- `src/main/java/com/beduno/stay/StayService.java:467-470` — `getRoomOrThrow`, the tenant gate
- `src/main/java/com/beduno/bed/BedService.java:55-143` — create/bulkGenerate/update/delete, all gated
- `src/main/java/com/beduno/bed/BedRepository.java:20` — `existsByRoomIdAndLabel`, no `agencyId` param
- `src/main/java/com/beduno/occupancy/ExportService.java:69,90` — unfiltered `findAllById` on bed ids sourced from an agency-filtered stay query
- `src/test/java/com/beduno/IntegrationTestBase.java:56,60,68,97,101` — the 2-arg vs. 3-arg `authHeaders` overloads and `assignedPropertyIds` wiring
- `src/test/java/com/beduno/room/RoomIntegrationTest.java:50-71` — the only property-scoping test pattern to copy from
- `src/test/java/com/beduno/property/PropertyIntegrationTest.java:147-169` — same pattern, property-update
- `src/test/java/com/beduno/bed/BedIntegrationTest.java` — has the guard, zero scoping tests
- `src/test/java/com/beduno/stay/BedAssignmentIntegrationTest.java:240-262` — explicit-bedId cross-agency only, no auto-assign cross-agency test

## Architecture Insights

- Property-scoping and cross-agency isolation are **structurally
  independent** mechanisms in this codebase: `hasPropertyAccess` is a
  service/controller-level in-memory check against a JWT claim; agency
  isolation is enforced entirely at the repository-query level
  (`agencyId` predicates). A resource can pass every repository-level
  tenant check and still be reachable by a PROPERTY_ADMIN/FRONT_DESK user
  outside their assigned properties — the two concerns must be tested
  separately, exactly as the test-plan's response guidance anticipated.
- The test infrastructure for property-scoping already exists
  (`IntegrationTestBase`'s 3-arg `authHeaders` overload) — this phase
  does not need new test infrastructure, only broader use of an existing,
  already-proven helper.
- The property-scoping gap is not evenly distributed: it is total for
  Stay/Occupancy/Worker and non-existent (well, present) for
  Property/Room/Bed writes. A test plan should treat "prove the gap
  exists" (Stay/Occupancy) and "prove `BedIntegrationTest` matches the
  pattern its sibling `RoomIntegrationTest` already has" as two different
  shaped tasks — the first documents a real, current authorization hole;
  the second closes a test-coverage asymmetry with no code change needed
  (the guard already exists on `BedController`).

## Historical Context (from prior changes)

No `context/archive/` entries exist yet. The `docs/api-specification.md`
"Property scoping — read this" section (`docs/api-specification.md:135-142`)
was itself corrected during the `named-beds` change's Phase 6 (this
session, commit `3397c45`) specifically because it undercounted
`hasPropertyAccess` call sites before `BedController` existed — this
research confirms that corrected text is now accurate.

## Related Research

- `context/changes/testing-constraint-engine-hardening/research.md` —
  rollout Phase 1's research; established the `resolveBed`/write-path
  call-chain patterns this research builds on for the auto-assign
  cross-agency check.

## Open Questions

1. **Is fixing the property-scoping gap in scope for this test rollout
   phase, or documentation-only?** Per the test-plan's own principle
   (tests document risk, they don't fix production code), the most
   direct reading is: write tests that currently **fail** (proving the
   gap) or, if the team wants the code fixed too, that decision expands
   this phase's scope beyond "testing." `/10x-plan` needs an explicit
   scope call here — this is the single biggest decision left.
2. **`BedRepository.existsByRoomIdAndLabel` and `ExportService`'s
   `findAllById` usage are undocumented, caller-dependent tenant
   boundaries**, structurally identical to `AGENTS.md`'s 3 sanctioned
   exceptions but not recorded there. Not exploitable today (both callers
   already validate the scoping id first), but worth a decision: add
   them to `AGENTS.md`'s exception list, or refactor to agency-filtered
   queries. Out of this research's scope to decide.
3. **`WorkerIntegrationTest.shouldNotReturnWorkersFromOtherAgency`**
   (found incidentally) only asserts 200 OK and a non-null body — no real
   cross-agency filtering assertion. Not one of this phase's named risks,
   but a pre-existing weak test worth flagging to `/10x-plan` as a
   candidate for tightening if it fits the phase's scope.
