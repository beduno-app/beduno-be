---
change_id: testing-data-integrity-guardrails
title: Data-integrity guardrails — RESTRICT-FK delete guards and migration/backfill correctness
status: implementing
created: 2026-09-12
updated: 2026-09-12
archived_at: null
---

## Notes

Open a change folder for rollout Phase 3 of context/foundation/test-plan.md: "Data-integrity guardrails".
Risks covered: #4 (a deletable entity with a RESTRICT foreign key has no application-level guard, surfacing an unhandled 500 instead of a clean 409), #6 (a future migration/backfill behaves correctly against a fresh test database but corrupts or mis-sets data shaped like production). Test types planned: integration.
Risk response intent:
- #4: prove every entity with a RESTRICT foreign key referenced by an active row (property, room, bed, and the next entity the roadmap introduces) returns a clean 409 on delete, never an unhandled 500. Challenge the assumption that fixing the guard for one entity makes the pattern safe everywhere -- each entity needs its own explicit guard and test, grounded in every RESTRICT FK actually present in the schema.
- #6: prove a migration that alters or backfills a table with pre-existing rows (not a fresh empty DB) produces the exact expected end state, verified against the next roadmap migration in sequence. Challenge the assumption that running clean against Testcontainers' fresh schema implies safety against populated data -- that gap is exactly what caused a past incident.
After creating the folder, follow the downstream continuation rule.
