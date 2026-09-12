---
change_id: testing-constraint-engine-hardening
title: Constraint engine hardening — bed-conflict and boundary-case coverage
status: implemented
created: 2026-09-11
updated: 2026-09-12
archived_at: null
---

## Notes

Open a change folder for rollout Phase 1 of context/foundation/test-plan.md: "Constraint engine hardening".
Risks covered: #1 (a worker ends up assigned to a bed that is already occupied or blocked, undetected until move-in), #2 (the constraint engine mishandles combined rules or date-range boundary cases). Test types planned: integration.
Risk response intent:
- #1: prove attempting to place two active stays in the same bed (or into a BLOCKED bed) is rejected end-to-end via every public write path (create/move/bulk-assign/check-in), and that an overlapping-but-not-identical date range is rejected too, not just an exact-same-day one. Challenge the assumption that a constraint-engine violation automatically means the HTTP layer stops the write.
- #2: prove overlapping stays whose date ranges touch at a boundary resolve per the actual adjacency rule (not off-by-one), and that multiple constraints that should both fire (e.g. gender rule + occupancy) are both evaluated and surfaced, not short-circuited by the first hard violation.
After creating the folder, follow the downstream continuation rule.
