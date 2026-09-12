---
change_id: testing-authorization-boundary-closure
title: Authorization boundary closure — property-scoping and cross-agency isolation
status: implemented
created: 2026-09-12
updated: 2026-09-12
archived_at: null
---

## Notes

Open a change folder for rollout Phase 2 of context/foundation/test-plan.md: "Authorization boundary closure".
Risks covered: #3 (a FRONT_DESK/PROPERTY_ADMIN user acts on stays/rooms/beds at a property they are not assigned to, within their own agency), #5 (a bed-touching write path or new non-agency identity leaks or accepts another agency's data). Test types planned: integration.
Risk response intent:
- #3: prove a FRONT_DESK/PROPERTY_ADMIN token scoped to property A cannot read or write stays/rooms/beds at property B in the same agency, across every endpoint accepting a property-scoped resource -- not only the 3 currently enforced. Challenge the assumption that cross-agency isolation tests already cover this; property-scoping is a different, within-tenant authorization gap.
- #5: prove every bed-touching write path rejects or 404s a request whose bed/room/property id belongs to a different agency, even when the id is syntactically valid. Challenge the assumption that explicit-bedId validation is the only place tenant injection could happen -- the auto-assign path (no client-supplied id) needs the same proof.
After creating the folder, follow the downstream continuation rule.
