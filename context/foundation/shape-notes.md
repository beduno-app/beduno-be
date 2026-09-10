---
project: "Beduno"
context_type: brownfield
created: 2026-08-19
updated: 2026-08-19
product_type: api
target_scale:
  users: large
  qps: low
  data_volume: small
timeline_budget:
  delivery_weeks: 4
  hard_deadline: null
  after_hours_only: false
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  gray_areas_resolved:
    - topic: "driving gap"
      decision: "validate the domain model against the partner agency before building further"
    - topic: "primary persona"
      decision: "all four roles affected; agency admin is primary"
    - topic: "must preserve"
      decision: "multi-tenant isolation, constraint engine correctness, audit trail"
    - topic: "validation source"
      decision: "one specific partner agency acts as the arbiter of correctness"
    - topic: "API stability"
      decision: "free to change — no consumers integrated yet"
    - topic: "suspected model divergences"
      decision: "external hotels, named beds, constraint set breadth, cost/invoicing"
    - topic: "auth change"
      decision: "enforce property scoping; keep JWT and the four existing roles"
    - topic: "additional constraints"
      decision: "keep crews together, separate by end client, shift-pattern compatibility"
    - topic: "hotel representation"
      decision: "both shapes \u2014 block-booked hotels as properties, ad-hoc hotels as per-worker bookings"
    - topic: "billing model"
      decision: "recharge bed-nights to the end client"
    - topic: "preserved capabilities"
      decision: "stay lifecycle, localised exports, soft-constraint override with reason, existing stay history"
    - topic: "business logic change"
      decision: "widens the allocation rule and adds a commercial recharge rule"
    - topic: "data migration"
      decision: "reset development data; FR-018 reframed to bind from first production data"
    - topic: "persona detail"
      decision: "owner-operator wearing every hat; opens it every morning to check last night"
    - topic: "success thresholds"
      decision: "primary = one full wave with zero side-channel decisions; secondary = agency asks for the next feature"
    - topic: "non-goals"
      decision: "not accounting software, no UI in this repo, no self-service onboarding; automatic allocation deliberately left open"
    - topic: "product framing"
      decision: "unchanged product type (backend API); hundreds of users including workers; no hard deadline"
    - topic: "first increment"
      decision: "full MVP \u2014 no incremental slice; scope-cost surfaced and accepted"
    - topic: "new audiences"
      decision: "hotel partner, worker-facing and finance/billing added \u2014 all four taken together despite the surfaced risk"
    - topic: "automatic allocation (post-shape)"
      decision: "resolved into scope — proposal mode added as FR-020; cost accepted"
    - topic: "new-audience identity (post-shape)"
      decision: "hybrid — accounts for hotel partners; scoped links for workers with optional magic-link sign-in"
    - topic: "persona name (post-shape)"
      decision: "kept role-based deliberately; no name recorded"
  frs_drafted: 20
  quality_check_status: accepted
---

# Shape Notes — Beduno

Seed idea, verbatim:

> a brownfield mvp, that we need to adjust, iron out and finalize; app meant for
> temporary work agencies that are relocating a lot of people and need to manage
> they sleepovers, hotels etc

## Current System

> Drafted from the codebase and `docs/`, not from user dictation. Confirm or correct
> before this flows into the PRD.

**System purpose.** Beduno manages accommodation for temporary work agencies that relocate
workers between sites and need to know where each person sleeps.

**Key architecture.** Modular monolith exposing a REST API. Organised by domain module —
`auth`, `user`, `agency`, `worker`, `property`, `room`, `stay`, `occupancy`, `audit` — with
shared code in `common/`.

**Tech stack.** Java 21, Spring Boot 3.4, PostgreSQL 16, Flyway migrations, Gradle. JWT
stateless auth. MapStruct for DTO mapping. Testcontainers-backed integration tests.

**Multi-tenancy.** Shared schema with an `agency_id` discriminator; `TenantContext`
(ThreadLocal) populated by `TenantFilter` from JWT claims. Every repository query filters by
agency, with two documented cross-tenant exceptions.

**Current user base.** No production users. One partner agency acts as design partner and
arbiter of correctness.

**Core functionality today.**
- Worker, property and room management scoped to an agency
- Stay lifecycle with status transitions (planned → expected today → checked in → …), plus
  no-show, move and cancel operations
- A constraint engine evaluated before stay changes: hard rules (capacity exceeded, double
  booking, blocked room, inactive property) and one soft rule (gender mismatch) that a user
  may override with a recorded reason
- Occupancy views and exports, localised across English, Polish, German, Russian and Ukrainian
- An audit log recording changes to stays
- Four roles: `AGENCY_ADMIN`, `AGENCY_PLANNER`, `PROPERTY_ADMIN`, `FRONT_DESK`

## Vision & Problem Statement

The system was built from a written specification and reasoning about the domain rather than
from observing the partner agency at work. It was then parked for a period; the work is now
being picked back up and needs to move quickly. The gap driving this change is that the domain
model is unvalidated — it may not describe how the agency actually operates.

Four areas are suspected of diverging from reality, all four flagged by the product owner:

1. **External hotels are not modelled.** The system represents only properties and rooms the
   agency controls. Placing a worker in a third-party hotel — named explicitly in the seed
   idea — has nowhere to live in the current model.
2. **Room capacity versus named beds.** Rooms carry a capacity count and a blocked-spots
   count rather than individually identified beds, so an assignment to a specific bed cannot
   be expressed.
3. **The constraint set may be too thin.** Allocation today turns on capacity, double
   booking, blocked rooms, inactive properties and gender. Real allocation may also depend on
   factors the model does not carry.
4. **Cost and invoicing are absent.** Nothing models what a bed-night costs, who is billed,
   or how accommodation is recharged.

**Current workaround and its cost.** The agency coordinates accommodation over messaging and
phone calls between planner, front desk and property. Nothing is recorded, so no one can
reconstruct what was agreed after the fact.

**Why now.** The project was parked and is being resumed under time pressure.

## User & Persona

All four existing roles are affected by this change. The **agency admin** is the primary
persona: an **owner-operator wearing every hat** — they run the agency, and accommodation is one
of several things they personally handle. They care about cost and about not being called at
night.

**The moment they reach for it.** Every morning, checking last night: who arrived, who did not
show, what is free tonight. The trigger is habitual rather than a crisis — which means the
product has to be worth opening on an ordinary day, not only when something is on fire.

### Secondary personas

Agency planner, property admin and front desk are all in scope for this change; the product
owner named them as affected rather than excluded.

> Resolved post-shape: the persona stays role-based deliberately; no name is recorded.

## Access Control

**Current model.** JWT-based stateless authentication. Access tokens carry `userId`
(subject), `agencyId`, `role`, `assignedPropertyIds` (the `properties` claim) and `lang`.
Refresh tokens carry only `userId`. Four roles exist: `AGENCY_ADMIN`, `AGENCY_PLANNER`,
`PROPERTY_ADMIN`, `FRONT_DESK`.

**Known gap in the current model.** The `properties` claim is issued but never enforced. A
`FRONT_DESK` or `PROPERTY_ADMIN` user can currently act on stays at any property within their
agency, not only their assigned ones.

**Planned changes.**

1. **Enforce property scoping.** Keep JWT and the four existing roles, but make
   `assignedPropertyIds` binding so `PROPERTY_ADMIN` and `FRONT_DESK` are limited to their own
   properties.
2. **External hotel / partner access.** Limited visibility for third-party accommodation
   providers — e.g. arrivals expected tonight — without exposing the agency's full worker list.
3. **Worker-facing access.** Workers can see where they are sleeping.
4. **Finance / billing role.** Reconciles bed-nights and invoices without operational stay
   control.

**Identity for the new audiences (post-shape).** Hybrid: hotel partners get real accounts;
workers reach their stay through a scoped access link or code, with the possibility to sign in
using a magic link where the worker has an email address. Delivery for email-less workers
remains open.

**Socrates — smallest useful access change.** Asked whether scoping alone would suffice, with
the three new audiences deferred. The product owner chose to take all four together, having
seen the risk. Recorded as a deliberate decision, not an oversight.

> Risk noted, carried forward to `## Constraints & Preserved Behavior` and to the timeline
> gate: external hotel access introduces the first non-agency identity and the first data
> exposure outside the tenant boundary, in a system whose primary guardrail is multi-tenant
> isolation. Worker-facing access introduces a second new audience with no existing identity
> path. Both widen the blast radius of a change whose stated purpose is to *validate* the
> domain model.

## Success Criteria

### Primary

- One complete relocation wave is planned and executed with every placement decision recorded
  in the system and none made over chat or phone. Binary and observable: zero side-channel
  decisions across a full wave.

### Secondary

- The agency comes back asking for the next feature rather than for fixes — the signal that
  what shipped works and they want more of it. (Product owner's original phrasing: "happy
  agency".)

### Guardrails

- **Multi-tenant isolation.** No agency can see another agency's workers, properties or stays.
- **Constraint engine correctness.** No double-booking, over-capacity, or assignment into a
  blocked room.
- **Audit trail.** Every change to a stay remains attributable and reconstructable.
- **Hotel partners see only their own arrivals.** The new external audience must never see the
  agency's wider worker or property data.
- **No worker left without a bed.** No change may produce a state where someone arrives and the
  system cannot say where they sleep.

## Timeline acknowledgment

Acknowledged on 2026-08-19: 4-week delivery covering model validation, external hotels, named
beds, a widened constraint set, cost/invoicing, enforced property scoping and three new
audiences. The scope-cost surface was presented — expensive pieces named, scope-down moves
offered, and the tension between "validate the model" and "build the full MVP first" stated.
The product owner accepted the cost and elected to proceed with the full scope.

## Functional Requirements

### Accommodation model

- FR-001: Agency admin can register an external hotel as accommodation the agency does not own. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: registering hotels the agency does not own implies inventory it cannot see, and may be admin overhead for one-off stays. Resolution: stands as written — external accommodation is core to the product's purpose.
- FR-002: Agency planner can place a worker into an external hotel as a per-worker booking of N nights, without the hotel's internal room structure being modelled. The booking is still checked for double-booking — a worker cannot be in two places on the same night. Priority: must-have. Change: new
  > Socrates: Counter-argument accepted: a booking with no room structure bypasses the constraint engine, colliding with the constraint-correctness guardrail. Resolution: FR amended — room-level rules do not apply, but the double-booking check still does.
- FR-003: Agency admin can manage a block-booked hotel as a property with rooms, the same way an owned property is managed. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: supporting two hotel shapes doubles the surface the engine, exports and audit must cover inside a four-week window. Resolution: stands as written — block-booked hotels behave like owned properties operationally.
- FR-004: Agency admin can define individually identified beds within a room. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: bed identity records fiction if workers self-select, and the migration rewrites every historical stay for an unconfirmed benefit. Resolution: stands as written — the capacity count is genuinely too coarse.
- FR-005: Agency planner can allocate workers to a property or room and have the system assign beds automatically, overriding individual beds where it matters. Priority: must-have. Change: modified
  > Socrates: Counter-argument accepted: choosing individual beds for a large wave is far more work than allocating headcount. Resolution: FR amended — the system assigns beds, the planner overrides only where it matters.

### Allocation constraints

- FR-006: The system evaluates whether an assignment keeps a crew together and surfaces the result before the change is committed. Priority: must-have. Change: new
  > Socrates: Counter-argument accepted: no concept groups workers into crews today, so this FR hid a new entity. Resolution: crew becomes a first-class group that planners maintain (see FR-019).
- FR-007: The system evaluates whether an assignment shares a room between workers placed with different end clients. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: client segregation fragments occupancy and needs placement data the worker model may not carry. Resolution: stands as written — client separation is a real operating constraint.
- FR-008: The system evaluates whether an assignment shares a room between incompatible shift patterns. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: shift data goes stale, is set by the end client, and stacking this with the crew and client rules could reject most workable allocations. Resolution: stands as written — day and night shift sharing a room is a genuine problem.

> Open: whether FR-006, FR-007 and FR-008 are hard rules (blocking) or soft rules
> (overridable with a recorded reason) has not been decided. Carried to `## Open Questions`.

- FR-019: Agency planner can group workers into a crew, and maintain that grouping over time. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: crew membership changes every wave and may go stale, could be derived from the arrival wave instead, and the entity emerged mid-session rather than from the agency. Resolution: stands as written — planners think in crews.
- FR-020: Agency planner can request a proposed allocation for a set of workers — the system suggests assignments that satisfy the allocation rules, and the planner reviews, adjusts and approves before anything is committed. Priority: must-have. Change: new
  > Added post-shape: the propose-vs-judge open question was resolved in favour of proposal mode; the scope growth inside the four-week window was surfaced and accepted. Depends on the hard-vs-soft resolution.

### Cost and invoicing

- FR-009: Finance user can see what a stay costs in bed-nights. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: bed-night rates vary by property, contract and season, and the agency already runs finance software. Resolution: stands as written — per-stay cost visibility is what the agency lacks.
- FR-010: Finance user can produce what is needed to recharge accommodation cost to the end client the worker is placed with. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: recharge terms vary per client contract, the agency already runs finance software, and the required placement data may not exist. Resolution: stands as written — recharging accommodation is the commercial point of the system.

### Access control

- FR-011: Property admin and front desk can act only on properties assigned to them. Priority: must-have. Change: modified
  > Socrates: Counter-argument considered: staff cover multiple sites, assignment data may never have been maintained, and existing users lose reach they have today. Resolution: stands as written — unenforced scoping is a real security gap.
- FR-012: Hotel partner can see expected arrivals for their own accommodation, and nothing else about the agency. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: this is the first identity outside the tenant boundary and breaks the premise that every user belongs to exactly one agency; hotels may not adopt an account at all. Resolution: stands as written, with the isolation risk carried to Constraints.
- FR-013: Worker can see where they are sleeping. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: workers have no identity path, a worker-facing surface is arguably its own product, and showing a bed may reveal who else is housed there. Resolution: stands as written.
- FR-014: Finance user can reach cost data without operational control over stays. Priority: must-have. Change: new
  > Socrates: Counter-argument considered: separation of duties may be over-engineering at this size, and per-stay cost access necessarily exposes who stayed where. Resolution: stands as written.

### Preserved behaviour

- FR-015: Agency planner and front desk can run the stay lifecycle — planned, expected today, checked in, plus no-show, move and cancel. The lifecycle is explicitly under review in this change rather than frozen: it goes on the validation agenda alongside beds, hotels and constraints. Priority: must-have. Change: modified
  > Socrates: Counter-argument accepted: freezing the lifecycle before the agency has confirmed it may lock in the very error this change exists to find, and an ad-hoc hotel booking may have no check-in event at all. Resolution: reclassified from preserved to under review — the lifecycle joins the validation agenda.
- FR-016: Any role with occupancy access can export occupancy in all five supported languages, extended to cover hotels and beds rather than replaced. Priority: must-have. Change: preserved
  > Socrates: Counter-argument considered: "extended" understates a rewrite, five locales is cost against a feature nobody has used in production, and the export shape may not survive beds and hotels. Resolution: stands as written.
- FR-017: Agency planner can override a soft constraint by recording a reason, and that override remains auditable. Priority: must-have. Change: preserved
  > Socrates: Counter-argument considered: three new soft rules could make overriding routine, diluting the audit signal and masking a wrong model; free-text reasons cannot be aggregated. Resolution: stands as written.
- FR-018: Any role with stay access can read and reconstruct stay history from the first real agency data onward. Development data predating the model migration is explicitly out of scope and may be discarded. Priority: must-have. Change: preserved
  > Socrates: Counter-argument considered: there are no production users, so the protected history is development data; reinterpreting capacity-era stays under a bed model invents detail never captured. Resolution: stands, but reframed in Phase 5 — the guarantee binds from the first production data onward, not to development data, which the migration may discard.

## User Stories

### US-01: Planner places a relocation wave across own properties and hotels

- **Given** an agency planner with a wave of workers to relocate, some to agency properties and some to external hotels
- **When** they assign each worker to accommodation
- **Then** each worker has a named bed or a hotel booking, the constraint results are visible before committing, and no coordination happens over phone or chat

**Was different before:** external hotels had nowhere to live in the model, assignment was to a
room's capacity count rather than an identified bed, and crew, client and shift compatibility
were not evaluated at all.

> Draft. Confirm the Given/When/Then before it flows into the PRD; acceptance criteria are not
> yet captured.

## Business Logic

The system currently decides, given a proposed accommodation assignment, whether that
assignment is permissible — blocking hard violations and flagging soft ones for override with a
recorded reason.

This change widens that rule and adds a second one alongside it.

**Widened allocation rule.** The permissibility decision takes on more inputs than capacity,
double booking, blocked rooms, inactive properties and gender: it also considers the identified
bed, whether accommodation is an owned property or an external hotel, whether a crew is kept
together, whether workers placed with different end clients share a room, and whether shift
patterns are compatible.

**New commercial rule.** Stays become recoverable cost: the system turns accommodation
consumed into what is needed to recharge that cost to the end client the worker is placed with.

> Open: whether the crew, end-client and shift rules are hard (blocking) or soft (overridable
> with a recorded reason) is undecided. Propose-vs-judge was resolved post-shape: proposal mode
> is in scope as FR-020.

## Non-Functional Requirements

- Personal data about relocated workers is retained only as long as there is a lawful basis for
  holding it, and a subject access request can be answered from the system.
- Check-in remains usable at the moment a worker arrives — including late at night, on a phone,
  by staff standing at the door.
- Planning a full relocation wave stays fast enough that planners do not revert to spreadsheets
  and messaging.
- All five supported languages remain covered wherever a person reads output, including the new
  worker-facing surface.

> Open: none of the four carries a threshold yet. "Fast enough", "usable at the door" and the
> retention window need numbers before the PRD can claim them as measurable. Carried to
> `## Open Questions`.

## Constraints & Preserved Behavior

**Backward compatibility.** None required. The API has no integrated consumers, so endpoints,
payloads and error codes may be reshaped freely. This was confirmed explicitly.

**Data migration.** Development data predating the model migration may be discarded — the
system is reset and the new model defines the schema from scratch. The history guarantee in
FR-018 binds from the first real agency data onward, not to today's synthetic data.

**Preserved behaviour.**
- Multi-tenant isolation: no agency sees another agency's workers, properties or stays.
- Constraint engine correctness: no double-booking, over-capacity, or assignment into a blocked
  room.
- Audit trail: every change to a stay stays attributable and reconstructable.
- Localised occupancy exports keep working across all five languages (FR-016).
- Soft-constraint override with a recorded, auditable reason (FR-017).

**Explicitly not preserved.** The stay lifecycle. It was reclassified during the Socrates round
from preserved to under review, and joins the validation agenda rather than being frozen ahead
of it (FR-015).

**Known risk to the isolation guardrail.** External hotel partner access (FR-012) introduces the
first identity that does not belong to exactly one agency — the premise every tenant-isolation
assumption in the current codebase rests on. Worker-facing access (FR-013) introduces a second
audience with no existing identity path. Both were accepted deliberately after the risk was
surfaced.

## Non-Goals

- **Not becoming accounting software.** The change stops at producing what is needed to
  recharge accommodation cost. No ledgers, no payment handling, no tax treatment — the agency's
  existing finance system keeps that job.
- **No user interface in this repository.** This stays a backend API. The worker-facing and
  hotel-partner experiences are separate client applications, outside this change and outside
  its four-week estimate.
- **No self-service agency onboarding.** Built for the one partner agency. No signup flow, no
  tenant provisioning, no subscription billing.

> Automatic allocation, deliberately not ruled out during shaping, was resolved post-shape
> into scope: proposal mode is FR-020.

## Product framing

- **Product type:** unchanged — a backend REST API. New audiences arrive as endpoints and
  authentication paths, not screens.
- **Scale:** hundreds of users, workers included. Worker-facing access makes the worker
  population, not agency staff, the dominant user count.
- **Deadline:** none anchored to a date; four weeks is the working estimate under urgency.

## Open Questions

1. Are the crew, end-client and shift rules hard (blocking) or soft (overridable with a
   recorded reason)? Now also gates FR-020's proposals. — owner: product owner, resolve during
   agency validation.
2. None of the four non-functional requirements carries a number: "fast enough", "usable at the
   door" and the data retention window all need thresholds. — owner: product owner.
3. US-01's acceptance criteria are not yet written. — owner: product owner.
4. How does a worker without an email address receive their access? Identity model decided
   post-shape (hybrid: accounts for hotel partners, scoped links plus optional magic-link
   sign-in for workers); the delivery channel is the remaining gap. — owner: product owner,
   resolve before FR-013 is built.
