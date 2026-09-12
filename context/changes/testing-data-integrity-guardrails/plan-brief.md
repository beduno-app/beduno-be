# Data-Integrity Guardrails — Plan Brief

> Full plan: `context/changes/testing-data-integrity-guardrails/plan.md`
> Research: `context/changes/testing-data-integrity-guardrails/research.md`

## What & Why

Rollout Phase 3 of `context/foundation/test-plan.md`: prove RESTRICT-FK delete guards are fully
covered, and build the first migration-testing harness capable of proving a Flyway migration
transforms populated data correctly — not just an empty schema. Research found a real,
previously-unknown correctness gap in the already-shipped V12 backfill; this plan proves and
documents it rather than fixing it.

## Starting Point

Property/Room/Bed delete already guard their RESTRICT FKs with `countBy...` → `ConflictException`
→ 409, but one branch (`error.room.has_beds`) has zero test coverage. Every migration test today
only ever runs against an empty Testcontainers schema — `V12__backfill_beds.sql`'s real backfill
logic (re-pointing every stay at a bed by creation-order rank modulo bed count) has never been
exercised against non-trivial data.

## Desired End State

Delete-guard coverage is complete for every entity that currently has a guard. A reusable
migration-test harness exists and is proven against the real V10→V14 sequence. The V12 gap
(rank-modulo assignment ignores date-range overlap, so it can put two overlapping stays on the
same bed) is proven by an executable test and documented — deliberately not fixed in this phase.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Risk #4 "next entity" scope | Close existing Property/Room/Bed gaps only | No `ready` roadmap slice concretely needs a new delete guard today | Research + Plan |
| Risk #6 harness shape | Reusable harness, proven on V12 | Directly reusable once S-02 lands; this is exactly the infra investment a test-hardening rollout should make | Plan |
| V12/BedOccupancyConstraint gap | Close it now (prove, don't fix) | Already a named, unresolved item from Phase 1; cheapest time to prove it is while building the harness anyway | Plan |
| Discovered V12 bug (rank-modulo ignores overlap) | Document via a passing test that asserts the violation exists; do not modify V12 or add a forward-fix migration | Never modify existing migrations; a test-only rollout shouldn't turn into a production data-migration change | Plan |
| Generic `DataIntegrityViolationException` handler | Out of scope, document only | Production-code decision, not a test-coverage one; matches Phase 1/2 precedent of documenting rather than fixing | Plan |

## Scope

**In scope:**
- `error.room.has_beds` delete-guard test
- Documenting `error.property.has_stays` as unreachable dead code (comment only, no prod change)
- A reusable Flyway-driving test harness (`MigrationTestSupport`) independent of `IntegrationTestBase`
- Two migration tests against the real V10→V14 sequence, including the adversarial scenario that
  proves the V12/BedOccupancyConstraint gap
- `test-plan.md` §3/§6.5/§6.6 reconciliation

**Out of scope:**
- Any change to an existing migration file
- A forward-fix migration repairing real data
- A generic FK-violation → 409 exception handler
- A delete-guard test for a "next entity" beyond Property/Room/Bed
- Backfilling the pre-existing §6.3 cookbook gap left over from rollout Phase 2
- CI wiring

## Architecture / Approach

Two independent tracks: (1) a small delete-guard test addition on the existing integration-test
base, and (2) a new, self-contained migration-test package (`com.beduno.migration`) that drives
Flyway's Java API directly against its own dedicated Testcontainers Postgres instance — bypassing
`IntegrationTestBase`'s shared, already-fully-migrated container entirely, since that one cannot
be rewound to an intermediate schema version without breaking every other integration test.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Delete-guard coverage completion | `has_beds` test + documented `has_stays` finding | Low — follows an established, well-tested pattern |
| 2. Migration-testing harness | Reusable harness + proof that V12 has a real, undiscovered gap | Medium — new infra pattern; test intentionally asserts a bug exists rather than its absence |
| 3. Documentation close-out | `test-plan.md` reconciled | Low — pure documentation |

**Prerequisites:** none beyond what's already in `main`.
**Estimated effort:** ~1-2 sessions across 3 phases.

## Open Risks & Assumptions

- The discovered V12 gap is proven only against constructed test data, not real production rows
  — if production data happens to contain the same interleaved-stay shape, the same violation
  may already exist there today. This plan deliberately does not investigate or fix that; it's
  flagged here for visibility.
- The dedicated migration-test container adds real wall-clock time to `./gradlew test`; Phase 2's
  manual verification checks this is acceptable but doesn't set a hard budget.

## Success Criteria (Summary)

- Every Property/Room/Bed delete-guard branch has a test that fails when its guard is removed.
- A migration test proves, against the real V10→V14 migrations, both a safe case and the one
  known unsafe case — the unsafe case is a permanent, documented, non-blocking regression proof.
- `test-plan.md` accurately reflects what shipped.
