<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Named Beds, End-to-End Implementation Plan

- **Plan**: `context/changes/named-beds/plan.md`
- **Mode**: Deep
- **Date**: 2026-09-10
- **Verdict**: REVISE (all findings fixed during triage — plan is now SOUND)
- **Findings**: 2 critical, 1 warning, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | FAIL (2 findings) |
| Plan Completeness | WARNING (1 finding) |

## Grounding

15/15 paths ✓, 12/12 symbols ✓, brief↔plan ✓

## Findings

### F1 — Phase 2 removes Room fields three consumers still need

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Blind Spots
- **Location**: Phase 2 — Room module updates
- **Detail**: Phase 2 (as originally written) deleted `Room.capacity`/`blockedSpots`/`availableSpots()`, but `CapacityConstraint` (deleted only in the original Phase 3) and `OccupancyService`/`ExportService`/`RoomOccupancyResponse`/`OccupancyExceptionResponse` (untouched until the original Phase 5) all called those exact members directly (`OccupancyService.java:60-61,87,90,96`, `ExportService.java:32-33,42-43,86-87,96-97`). Phase 2's own "Full build green: `./gradlew build`" criterion was unachievable as sequenced.
- **Fix A ⭐ Recommended (applied)**: Pull the minimal compile-preserving update into Phase 2 itself — stand up `BedOccupancyConstraint` there (replacing `CapacityConstraint`), and swap `OccupancyService`/`ExportService`/the two DTOs onto the same `bedCount`/`availableBedCount` values `RoomService` gains in Phase 2, deferring only per-occupant `bedId`/`bedLabel` enrichment to Phase 5.
  - Strength: keeps every phase's own build-green criterion true, matching the plan's stated intent.
  - Tradeoff: Phase 2 grows to touch `stay/constraint/` and `occupancy/` in addition to `room/`.
  - Confidence: HIGH — grounded in direct grep evidence of every call site.
  - Blind spot: no other indirect consumer found (Swagger/OpenAPI generation derives from the DTO shape, already covered).
- **Fix B**: Merge Phases 2/3/5's Room+Occupancy work into one larger phase. Not chosen.
- **Decision**: FIXED (Fix A) — plan.md restructured; Phase 2 title and scope now cover schema migration, backfill, capacity retirement, and the bed-occupancy constraint; Phase 3 shrank to the blocked-bed check; Phase 5 shrank to per-occupant enrichment only.

### F2 — Phase 3 adds a context field StayService can't pass

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Blind Spots
- **Location**: Phase 3 — Constraint context (original numbering)
- **Detail**: Adding a `Bed bed` field to the `ConstraintContext` record broke `StayService`'s five production call sites (`create` `:95`, `update` `:121`, `checkIn` `:154`, `move` `:211`, `bulkAssign` `:288`) plus 3 test sites in `ConstraintEngineTest`, none of which were updated until the original Phase 4 — Java records have no optional positional constructor, so this was a guaranteed compile break at the end of the constraint-engine phase.
- **Fix ⭐ Recommended (applied)**: Add `ConstraintContext.bed` in the same phase that now owns `BedOccupancyConstraint` (Phase 2, post-F1-fix); update all 8 existing call sites to pass `null` for `bed`; have `BedOccupancyConstraint` and the bed-blocked check in `BlockedRoomConstraint` (Phase 3) treat `ctx.bed() == null` as a no-op. Phase 4 replaces `null` with `resolveBed`'s real output.
- **Decision**: FIXED — folded into Phase 2's item 5/6; documented as a deliberate transitional state in Critical Implementation Details.

### F3 — New failure paths lack defined i18n message codes

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 4 — Bed resolution
- **Detail**: `resolveBed`'s "room has no free bed" and "requested bedId not in target room" failure paths had no message code defined anywhere in the plan's i18n sections, which `MessageBundleTest`/`AGENTS.md` require.
- **Fix ⭐ Recommended (applied)**: Add `BED_UNAVAILABLE` / `constraint.bed.unavailable` (zero-candidate case) and `error.bed.not_in_room` (mismatched-room validation case).
- **Decision**: FIXED — added to Phase 4's i18n item (placed there rather than the now-shrunk Phase 3, since that's where they're actually used).

## Additional discovery during triage (not a separately numbered finding)

While applying F1/F2's fixes, a related runtime issue surfaced: the original Phase 2 set `stays.bed_id NOT NULL` (`V13`) in the same migration that dropped `rooms.capacity`/`blocked_spots`, but `StayService` doesn't set `bedId` on any write until Phase 4 — every existing Stay-creating integration test would hit a `NOT NULL` constraint violation at runtime in between, which `./gradlew build` (Phase 2's own criterion) would catch via its `test` task.

- **Resolution (applied, user-selected)**: Split the migration — `V13` (Phase 2) now only drops `rooms.capacity`/`blocked_spots`; a new `V14` (Phase 4) sets `stays.bed_id NOT NULL`, applied once `resolveBed` guarantees a value on every write. Documented in Critical Implementation Details and Migration Notes.

## Outcome

All three findings plus the triage-surfaced NOT NULL-timing issue are fixed directly in `plan.md` (phases renumbered/rescoped, Progress section rebuilt to match) and reflected in `plan-brief.md`. Re-reviewing the restructured plan against the same three failure modes: no phase's own stated automated success criteria remain unachievable as sequenced. Verdict after fixes: **SOUND**.
