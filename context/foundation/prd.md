---
project: "Beduno"
version: 1
status: draft
created: 2026-08-19
context_type: brownfield
product_type: api
target_scale:
  users: large
  qps: low
  data_volume: small
timeline_budget:
  delivery_weeks: 4
  hard_deadline: null
  after_hours_only: false
---

# PRD — Beduno

## Current System Overview

**System purpose.** Beduno manages accommodation for temporary work agencies that relocate
workers between sites and need to know where each person sleeps.

**Key architecture.** Modular monolith exposing a REST API. Organised by domain module —
`auth`, `user`, `agency`, `worker`, `property`, `room`, `stay`, `occupancy`, `audit` — with
shared code in `common/`.

**Tech stack.** Java 21, Spring Boot 3.4, PostgreSQL 16, Flyway migrations, Gradle. JWT
stateless auth — access tokens carry the user, agency, role, assigned properties and language;
refresh tokens carry only the user. MapStruct for DTO mapping. Testcontainers-backed
integration tests.

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

## Problem Statement & Motivation

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
persona: an **owner-operator wearing every hat** — they run the agency, and accommodation is
one of several things they personally handle. They care about cost and about not being called
at night.

**The moment they reach for it.** Every morning, checking last night: who arrived, who did not
show, what is free tonight. The trigger is habitual rather than a crisis — which means the
product has to be worth opening on an ordinary day, not only when something is on fire.

### Secondary personas

Agency planner, property admin and front desk are all in scope for this change; the product
owner named them as affected rather than excluded. The change also enables three audiences the
system has never served: hotel partners, workers themselves, and a finance/billing user.

The persona is deliberately kept role-based; no name is recorded — a decision, not a gap.

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

## User Stories

### US-01: Planner places a relocation wave across own properties and hotels

- **Given** an agency planner with a wave of workers to relocate, some to agency properties and some to external hotels
- **When** they assign each worker to accommodation
- **Then** each worker has a named bed or a hotel booking, the constraint results are visible before committing, and no coordination happens over phone or chat

**Was different before:** external hotels had nowhere to live in the model, assignment was to a
room's capacity count rather than an identified bed, and crew, client and shift compatibility
were not evaluated at all.

#### Acceptance Criteria

# TODO: acceptance criteria for US-01 — see Open Questions

## Scope of Change

Twenty functional requirements, grouped thematically. Each carries its delta category
(`[new]`, `[modified]`, `[preserved]`), its priority, and the Socrates challenge recorded
during shaping (FR-020 arrived afterwards, when the propose-vs-judge question was resolved).
Nothing is categorised `[removed]`.

### Accommodation model

- [new] FR-001: Agency admin can register an external hotel as accommodation the agency does not own. Priority: must-have.
  > Socrates: Counter-argument considered: registering hotels the agency does not own implies inventory it cannot see, and may be admin overhead for one-off stays. Resolution: stands as written — external accommodation is core to the product's purpose.
- [new] FR-002: Agency planner can place a worker into an external hotel as a per-worker booking of N nights, without the hotel's internal room structure being modelled. The booking is still checked for double-booking — a worker cannot be in two places on the same night. Priority: must-have.
  > Socrates: Counter-argument accepted: a booking with no room structure bypasses the constraint engine, colliding with the constraint-correctness guardrail. Resolution: FR amended — room-level rules do not apply, but the double-booking check still does.
- [new] FR-003: Agency admin can manage a block-booked hotel as a property with rooms, the same way an owned property is managed. Priority: must-have.
  > Socrates: Counter-argument considered: supporting two hotel shapes doubles the surface the engine, exports and audit must cover inside a four-week window. Resolution: stands as written — block-booked hotels behave like owned properties operationally.
- [new] FR-004: Agency admin can define individually identified beds within a room. Priority: must-have.
  > Socrates: Counter-argument considered: bed identity records fiction if workers self-select, and the migration rewrites every historical stay for an unconfirmed benefit. Resolution: stands as written — the capacity count is genuinely too coarse.
- [modified] FR-005: Agency planner can allocate workers to a property or room and have the system assign beds automatically, overriding individual beds where it matters. Priority: must-have.
  > Socrates: Counter-argument accepted: choosing individual beds for a large wave is far more work than allocating headcount. Resolution: FR amended — the system assigns beds, the planner overrides only where it matters.

### Allocation constraints

- [new] FR-006: The system evaluates whether an assignment keeps a crew together and surfaces the result before the change is committed. Priority: must-have.
  > Socrates: Counter-argument accepted: no concept groups workers into crews today, so this FR hid a new entity. Resolution: crew becomes a first-class group that planners maintain (see FR-019).
- [new] FR-007: The system evaluates whether an assignment shares a room between workers placed with different end clients. Priority: must-have.
  > Socrates: Counter-argument considered: client segregation fragments occupancy and needs placement data the worker model may not carry. Resolution: stands as written — client separation is a real operating constraint.
- [new] FR-008: The system evaluates whether an assignment shares a room between incompatible shift patterns. Priority: must-have.
  > Socrates: Counter-argument considered: shift data goes stale, is set by the end client, and stacking this with the crew and client rules could reject most workable allocations. Resolution: stands as written — day and night shift sharing a room is a genuine problem.
- [new] FR-019: Agency planner can group workers into a crew, and maintain that grouping over time. Priority: must-have.
  > Socrates: Counter-argument considered: crew membership changes every wave and may go stale, could be derived from the arrival wave instead, and the entity emerged mid-session rather than from the agency. Resolution: stands as written — planners think in crews.

# TODO: hard-vs-soft classification for FR-006, FR-007 and FR-008 — see Open Questions

### Allocation proposals

- [new] FR-020: Agency planner can request a proposed allocation for a set of workers — the system suggests assignments that satisfy the allocation rules, and the planner reviews, adjusts and approves before anything is committed. Priority: must-have.
  > Added post-shaping, when the propose-vs-judge open question was resolved in favour of proposal mode. The cost was surfaced and accepted: this materially grows the four-week scope, and the proposals depend on the hard-vs-soft resolution of FR-006–FR-008 (Open Question 1).

### Cost and invoicing

- [new] FR-009: Finance user can see what a stay costs in bed-nights. Priority: must-have.
  > Socrates: Counter-argument considered: bed-night rates vary by property, contract and season, and the agency already runs finance software. Resolution: stands as written — per-stay cost visibility is what the agency lacks.
- [new] FR-010: Finance user can produce what is needed to recharge accommodation cost to the end client the worker is placed with. Priority: must-have.
  > Socrates: Counter-argument considered: recharge terms vary per client contract, the agency already runs finance software, and the required placement data may not exist. Resolution: stands as written — recharging accommodation is the commercial point of the system.

### Access control

- [modified] FR-011: Property admin and front desk can act only on properties assigned to them. Priority: must-have.
  > Socrates: Counter-argument considered: staff cover multiple sites, assignment data may never have been maintained, and existing users lose reach they have today. Resolution: stands as written — unenforced scoping is a real security gap.
- [new] FR-012: Hotel partner can see expected arrivals for their own accommodation, and nothing else about the agency. Priority: must-have.
  > Socrates: Counter-argument considered: this is the first identity outside the tenant boundary and breaks the premise that every user belongs to exactly one agency; hotels may not adopt an account at all. Resolution: stands as written, with the isolation risk carried to Constraints.
- [new] FR-013: Worker can see where they are sleeping. Priority: must-have.
  > Socrates: Counter-argument considered: workers have no identity path, a worker-facing surface is arguably its own product, and showing a bed may reveal who else is housed there. Resolution: stands as written.
- [new] FR-014: Finance user can reach cost data without operational control over stays. Priority: must-have.
  > Socrates: Counter-argument considered: separation of duties may be over-engineering at this size, and per-stay cost access necessarily exposes who stayed where. Resolution: stands as written.

### Preserved behaviour

- [modified] FR-015: Agency planner and front desk can run the stay lifecycle — planned, expected today, checked in, plus no-show, move and cancel. The lifecycle is explicitly under review in this change rather than frozen: it goes on the validation agenda alongside beds, hotels and constraints. Priority: must-have.
  > Socrates: Counter-argument accepted: freezing the lifecycle before the agency has confirmed it may lock in the very error this change exists to find, and an ad-hoc hotel booking may have no check-in event at all. Resolution: reclassified from preserved to under review — the lifecycle joins the validation agenda.
- [preserved] FR-016: Any role with occupancy access can export occupancy in all five supported languages, extended to cover hotels and beds rather than replaced. Priority: must-have.
  > Socrates: Counter-argument considered: "extended" understates a rewrite, five locales is cost against a feature nobody has used in production, and the export shape may not survive beds and hotels. Resolution: stands as written.
- [preserved] FR-017: Agency planner can override a soft constraint by recording a reason, and that override remains auditable. Priority: must-have.
  > Socrates: Counter-argument considered: three new soft rules could make overriding routine, diluting the audit signal and masking a wrong model; free-text reasons cannot be aggregated. Resolution: stands as written.
- [preserved] FR-018: Any role with stay access can read and reconstruct stay history from the first real agency data onward. Development data predating the model migration is explicitly out of scope and may be discarded. Priority: must-have.
  > Socrates: Counter-argument considered: there are no production users, so the protected history is development data; reinterpreting capacity-era stays under a bed model invents detail never captured. Resolution: stands, but reframed — the guarantee binds from the first production data onward, not to development data, which the migration may discard.

## Constraints & Compatibility

**Backward compatibility.** None required. The API has no integrated consumers, so endpoints,
payloads and error codes may be reshaped freely. This was confirmed explicitly.

**Data migration.** Development data predating the model migration may be discarded — the
system is reset and the new model is defined from scratch. The history guarantee in FR-018
binds from the first real agency data onward, not to today's synthetic data.

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

**Non-functional constraints.** Four properties captured during shaping bound how the changed
system behaves at its outer boundary:
- Personal data about relocated workers is retained only as long as there is a lawful basis for
  holding it, and a subject access request can be answered from the system.
- Check-in remains usable at the moment a worker arrives — including late at night, on a phone,
  by staff standing at the door.
- Planning a full relocation wave stays fast enough that planners do not revert to spreadsheets
  and messaging.
- All five supported languages remain covered wherever a person reads output, including the new
  worker-facing surface.

# TODO: thresholds for the four non-functional constraints — see Open Questions

## Business Logic Changes

The system currently decides, given a proposed accommodation assignment, whether that
assignment is permissible — blocking hard violations and flagging soft ones for override with a
recorded reason.

This change widens that rule, gives it a proposing direction, and adds a second rule
alongside it.

**Widened allocation rule.** The permissibility decision takes on more inputs than capacity,
double booking, blocked rooms, inactive properties and gender: it also considers the identified
bed, whether accommodation is an owned property or an external hotel, whether a crew is kept
together, whether workers placed with different end clients share a room, and whether shift
patterns are compatible.

**Proposal mode.** The widened rule also runs in the proposing direction: rather than only
judging an assignment a planner proposed, the system can propose an allocation that satisfies
the rules, for the planner to review, adjust and approve (FR-020).

**New commercial rule.** Stays become recoverable cost: the system turns accommodation
consumed into what is needed to recharge that cost to the end client the worker is placed with.

> Open: whether the crew, end-client and shift rules are hard (blocking) or soft (overridable
> with a recorded reason) is undecided — see Open Question 1. Propose-vs-judge is resolved:
> proposal mode is in scope as FR-020.

## Access Control Changes

**Current model.** Sign-in is unchanged by this work: stateless authentication with four roles
— agency admin, agency planner, property admin and front desk. Property admin and front desk
users each carry a set of assigned properties. Mechanics are described in Current System
Overview.

**Known gap in the current model.** The assigned-properties list is issued but never enforced.
A front desk or property admin user can currently act on stays at any property within their
agency, not only their assigned ones.

**Planned changes.**

1. **Enforce property scoping.** Keep the sign-in model and the four existing roles, but make
   assigned properties binding so property admin and front desk are limited to their own
   properties.
2. **External hotel / partner access.** Limited visibility for third-party accommodation
   providers — e.g. arrivals expected tonight — without exposing the agency's full worker list.
3. **Worker-facing access.** Workers can see where they are sleeping.
4. **Finance / billing role.** Reconciles bed-nights and invoices without operational stay
   control.

**Identity for the new audiences.** Hybrid, decided post-shaping: hotel partners get real
accounts; workers reach their stay through a scoped access link or code, with the possibility
to sign in using a magic link where the worker has an email address. How a worker without
email receives their access remains open.

**Socrates — smallest useful access change.** Asked whether scoping alone would suffice, with
the three new audiences deferred. The product owner chose to take all four together, having
seen the risk. Recorded as a deliberate decision, not an oversight.

> Risk, carried also under Constraints & Compatibility: external hotel access introduces the
> first non-agency identity and the first data exposure outside the tenant boundary, in a
> system whose primary guardrail is multi-tenant isolation. Worker-facing access introduces a
> second new audience with no existing identity path. Both widen the blast radius of a change
> whose stated purpose is to *validate* the domain model.

## Non-Goals

- **Not becoming accounting software.** The change stops at producing what is needed to
  recharge accommodation cost. No ledgers, no payment handling, no tax treatment — the agency's
  existing finance system keeps that job.
- **No user interface in this repository.** This stays a backend API. The worker-facing and
  hotel-partner experiences are separate client applications, outside this change and outside
  its four-week estimate.
- **No self-service agency onboarding.** Built for the one partner agency. No signup flow, no
  tenant provisioning, no subscription billing.

> Automatic allocation, twice declined as a non-goal, was subsequently resolved **into scope**:
> FR-020 adds proposal mode, with the system suggesting allocations the planner approves. It is
> no longer an open question.

## Open Questions

1. **Are the crew, end-client and shift rules hard (blocking) or soft (overridable with a
   recorded reason)?** Affects FR-006, FR-007, FR-008, the widened allocation rule, and the
   proposals FR-020 must satisfy. — Owner: product owner. Resolve during agency validation.
2. **What are the thresholds for the four non-functional constraints?** "Fast enough", "usable
   at the door" and the data retention window all need numbers before they are measurable. —
   Owner: product owner.
3. **What are US-01's acceptance criteria?** The Given/When/Then is a draft awaiting
   confirmation. — Owner: product owner.
4. **How does a worker without an email address receive their access?** The identity model is
   decided — accounts for hotel partners, scoped links plus optional magic-link sign-in for
   workers — but the delivery channel for email-less workers is the remaining gap. — Owner:
   product owner. Resolve before FR-013 is built.
