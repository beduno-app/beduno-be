# Idea #17 - Bedok

**Created:** Apr 13 at 9:00 PM
**Updated:** Apr 14 at 1:31 PM
**Messages:** 16

---

## User - Apr 13 at 9:03 PM

#idea 17: bedok (name and domain reserved, final)

the problem statement is: owners of temporary work agencies need to move a lot of people at once between far locations, people might have different skillsets, companies have different needs, but those workers always have to sleep somewhere, some of the agency owners own also worker hotels but still need to keep track of who is living where

we want a system for managing those issues, tracking workers, etc., in the later roadmap we have selling this solution to different agencies owners and even later opening this to the public for everyone who wants to offer beds, not necessarily whole rooms or flats

## Assistant - Apr 13 at 9:03 PM

**Restate (1 sentence)**  
Bedok = an ops system for temp-work agencies to allocate workers to jobs + transport + beds, and keep a live “who is where tonight” roster across multiple locations and skill constraints.

---

## ICP + JTBD
**Primary ICP (now)**  
- Owners/ops managers at **temporary work agencies** that relocate groups (50–5,000 workers).  
- Often manage: job assignments + buses/vans + **agency-owned or partner accommodations**.

**JTBD**  
- “When clients change headcount/skills and projects move, I need to **reassign workers and beds fast** without chaos, so I avoid no-shows, overbooked rooms, and angry clients/workers.”

---

## What you’re really building (scope framing)
This is **not** just “bed management.” It’s a **constraints + roster system**:
- Worker identity + documents + skills (who can do what)
- Job/site assignment (who works where)
- Accommodation assignment (who sleeps where)
- Transport assignment (how they move between sites/housing)
- Changes happen daily → you’re selling **operational reliability**

If you try to do everything at once, you’ll drown in edge cases. So pick the **single source of truth** first: *bed occupancy + worker location*.

---

## Alternatives (what they do today)
- Excel/Google Sheets + WhatsApp/phone calls (most common)
- Generic HR/ATS or workforce management tools (don’t model beds well)
- Property/hotel PMS (doesn’t model agency worker flows)
- “Do nothing” and accept losses (overtime admin, empty beds, penalties)

---

## Assumptions (tagged + confidence)
**Demand**
- Assumption (Med): Agencies feel enough pain to pay monthly for “beds + rostering” vs spreadsheets.
- Assumption (Low–Med): The pain is concentrated in a repeatable workflow you can standardize.

**Distribution**
- Assumption (Med): You can sell via owner networks and referrals (industry is relationship-driven).
- Assumption (Low): You can later sell self-serve to small landlords (different ICP, different sales motion).

**Retention**
- Assumption (Med): Once they trust it as “truth,” switching costs become high (process + data).

**Unit economics**
- Assumption (Low–Med): Support burden won’t explode (night shifts, emergencies, language barriers).

**Feasibility**
- Assumption (High): You can build bed occupancy + assignment + audit trail.
- Assumption (Low–Med): You can reliably handle identity/docs + compliance across countries without heavy customization.

**Risk**
- Fact (High): You’ll handle **PII** (IDs, addresses, employment status) → GDPR/security expectations are high.

---

## Pushback (top risks to kill fast)
1) **Scope trap**: jobs + transport + beds + skills = ERP-level complexity.  
2) **Data integrity**: if the system is ever wrong, ops will revert to WhatsApp/Excel. Trust is everything.  
3) **24/7 operations**: changes at 22:00; someone needs access; “who can edit what” matters.  
4) **Marketplace later is a different business**: selling to agencies ≠ opening beds to the public. Two-sided supply, verification, payments, dispute handling. Don’t assume smooth roadmap.  
5) **Compliance & reputation risk**: worker housing is sensitive; you must avoid looking like you enable unsafe/illegal housing (even if you don’t).

---

## RAA (riskiest assumptions) → hypotheses
Pick 3–5 to test first:

1) **They will pay**  
- Hypothesis: Agencies will pay for “live occupancy + allocation” if it saves X hours/week and reduces incidents.

2) **You can replace the spreadsheet**  
- Hypothesis: A minimal workflow (check-in/out + assignment + bed capacity) is enough to become the single source of truth.

3) **Operational trust**  
- Hypothesis: With audit log + role permissions + simple mobile check-in, accuracy can be kept above a threshold.

4) **Integration is not mandatory day 1**  
- Hypothesis: You can start without payroll/ATS integrations and still deliver value.

---

## Experiments (fast, low-build)
| Hypothesis | Why | Experiment | Audience | Success metric | Duration | Effort | Decision rule | If fails, learn |
|---|---|---|---|---|---|---|---|---|
| Agencies will pay | viability | 10 founder calls + price test (3 tiers) | 10–15 agency owners | 3+ say “I’d pay €X now” + ask for pilot | 7–10 days | Low | If <2 strong yes → reposition | Pain not acute / wrong buyer |
| Minimal workflow works | scope | “Concierge MVP” in Airtable/Notion + WhatsApp intake | 1 agency pilot | Used daily for 2 weeks; spreadsheet abandoned | 14 days | Low–Med | If they keep parallel sheet → trust/UX issue | Needed features / data flow |
| Trust via audit & roles | retention | Prototype: check-in/out + audit log + role-based edit | 1–2 ops teams | <2 “where is X?” incidents/week | 2–3 weeks | Med | If confusion persists → need different UX | Real operational failure modes |
| No integrations needed | feasibility | Manual import/export (CSV) + nightly roster report | Pilot | Still used after week 3 | 3–4 weeks | Low | If they demand integration to continue → prioritize 1 system | Identify must-integrate system |

---

## MVP scope (what to build first)
### MVP goal (what we prove)
**Prove Bedok can be the reliable source of truth for “who sleeps where tonight” for one agency across 1–3 locations.**

### Must-have (≤7)
1) **Entities**: Worker, Bed/Room, Property/Location, Assignment (date-based)  
2) **Capacity rules**: beds per room, gender/constraints (if needed), blocked beds  
3) **Check-in / check-out** (mobile-friendly) + “currently staying” view  
4) **Roster views**: by property, by worker, by date, conflicts/overbookings  
5) **Bulk actions**: import workers, bulk assign/move, bulk checkout  
6) **Roles + audit log**: who changed what, when  
7) **Exports/reports**: nightly occupancy list + client/site list (CSV/PDF)

### Non-goals (explicit)
- Full ATS/HR system  
- Payroll/time tracking  
- Transport routing optimization  
- Public marketplace for beds  
- Dynamic pricing/payments to landlords

### Funnel + events to track
- Created property  
- Imported workers  
- Created assignments  
- Check-in/out events  
- Conflict detected → resolved  
- Nightly roster generated  
- Weekly active operators (WAO)

### Acceptance criteria (pilot-ready)
- Can answer in <30 seconds: “Where is Worker X sleeping tonight?”  
- No double-booking without a visible warning  
- Every change is attributable (audit)  
- Operates on phone during night shift

---

## Roadmap suggestion (de-risked sequencing)
**Phase 1: Internal housing ops (agency-owned/controlled beds)**  
- Occupancy + moves + audit + reporting.

**Phase 2: Add job-site linkage (lightweight)**  
- Assign worker to site + shift schedule, but keep it minimal.

**Phase 3: Transport (only if demanded)**  
- “Bus list per pickup point” not route optimization.

**Phase 4: External supply (partners, not public)**  
- Verified partner properties first (B2B supply). Contracts + SLAs.

**Phase 5: Public marketplace (only if Phase 4 works)**  
- Identity verification, payments, disputes, compliance, reviews.

---

## 6 questions (answer these to lock MVP)
1) Geography: where first (PL only, EU, UK)? Any cross-border worker movement?  
2) Typical scale: workers per agency + number of properties + move frequency/week?  
3) Who is the daily user: owner, dispatcher, hotel manager, shift lead? (and how many)  
4) Current workflow: what tools + what exact “nightmare moment” happens weekly?  
5) What constraints matter: gender separation, max per room, document verification, curfew, bans/blacklist?  
6) Commercial: who pays (agency vs property) and what pricing anchor feels natural (per worker/month, per bed/month, per property/month)?

---

**Next decision:** Are you committing to **Phase 1 only (housing source-of-truth)** as MVP, or trying to include job-site assignment in v1?  

**Next actions (24–72h):**
1) Answer the 6 questions above (short bullets).  
2) Line up 10 owner/operator calls; I’ll give you a tight call script + pricing test once you confirm geography + scale.  
3) Choose 1 pilot agency and run a 14-day concierge MVP to map the real workflow and edge cases.

## User - Apr 13 at 11:05 PM

1. starting PL, then quickly CEE, then maybe global
2. hundreds workers, tens of properties, 2-3 per quarter
3. all of them
4. a lot spreadsheets, whatsapp communication, private reminders etc
5. all of these
6. agency per worker/month, property small fee per bed

MVP is Phase 1 only

## Assistant - Apr 13 at 11:05 PM

**Restate (1 sentence)**  
Bedok MVP = **housing source-of-truth** for PL temp-work agencies: allocate workers to properties/rooms/beds with constraints + audit trail, replacing “Sheets + WhatsApp” for *who sleeps where tonight*.

---

## What your answers imply (and where I’ll push back)
- PL → CEE fast. Good. But workflows vary by country and worker nationality.  
  - **Assumption (Med):** You can standardize enough to scale across CEE without heavy customization.
- Hundreds of workers, tens of properties, but only **2–3 big moves per quarter**.  
  - Pushback: Your pain might be **daily micro-changes** (late arrivals, swaps, bans, damaged rooms), not “big moves”. If not, willingness-to-pay may be weaker.
- “All of them” are users (owner + dispatcher + property manager).  
  - Pushback: Multi-role products fail if you don’t pick **one operational owner of truth**. You need a clear “who confirms check-in/out”.

---

## ICP + JTBD (tight)
**ICP (MVP)**  
- PL temporary work agency with 200–800 workers and 10–50 properties (owned + partner “worker hotels”).

**JTBD**  
- “When workers move and constraints apply, I need to assign beds fast and keep a live, trusted occupancy list so we avoid overbooking, missing people, and property conflicts.”

---

## The riskiest assumption (RAT)
**RAT:** *Trust + data integrity beats WhatsApp.*  
If operators don’t trust it at 22:00, you lose.

So MVP must optimize for:
- **Speed** (mobile, bulk actions)
- **Accuracy** (hard conflict warnings)
- **Accountability** (audit log + roles)
- **One truth owner** (agency-led vs property-led check-in)

---

## MVP scope (Phase 1 only) — tightened for “all constraints”
You said constraints matter (gender, max/room, docs, bans, etc.). If you implement “all” literally, you’ll overbuild.

### MVP goal
Become the **system of record for bed occupancy** across multiple properties for one agency.

### Must-have (≤7)
1) **Core entities**: Worker, Property, Room, Bed, Stay (date-based assignment)  
2) **Constraint engine v1** (minimal but extensible):  
   - Hard: capacity per bed/room, “blocked bed”, gender rule (if used)  
   - Soft: tags/notes for everything else + warnings (not hard blocks yet)
3) **Check-in / check-out** (mobile-first) with “Currently staying” list  
4) **Bulk operations**: import workers, bulk assign/move, bulk checkout  
5) **Conflict detection**: double-booking, over-capacity, rule violations (hard/soft)  
6) **Roles + audit log**: who changed what + when + rollback pattern  
7) **Exports**: nightly occupancy list per property + exception report (who has no bed)

### Non-goals (explicit)
- Job/site assignment, transport, payroll, ATS integrations
- Public marketplace
- Full document compliance workflows (store references/metadata only in MVP)

**Assumption (Med):** Soft constraints + warnings are acceptable in MVP.  
If agencies demand hard enforcement for 10+ constraint types, you’re building a vertical ERP.

---

## Key product decision you must make now
### “Who confirms occupancy?”
Pick one as the *primary editor*:
- **Option A (recommended): Agency-led truth**  
  - Property managers can “suggest/confirm”, but agency ops owns assignments.
- **Option B: Property-led truth**  
  - More accurate at the bed level, but harder to sell and coordinate; more users, more support.

**Assumption (High):** Without a clear truth owner, data becomes stale → churn.

---

## Pricing model (structure, not numbers)
You want: agency per worker/month + property small fee per bed. That can work, but watch incentives.

**Recommended structure**
- Agency: charge per **active housed worker/month** (not total in database).  
  - Prevents “I imported 1,000 people once” pricing fights.
- Property: fee per **occupied bed/month** (or per property/month) only if you truly deliver value to property ops.

Pushback:
- **Assumption (Low–Med):** Properties will pay. Many will treat this as the agency’s problem unless you give them a standalone benefit (damage tracking, maintenance, invoices, access control, etc.). Don’t bet MVP on property revenue.

---

## Experiments (next 2–4 weeks, minimal build)
| Hypothesis | Why | Experiment | Audience | Success metric | Duration | Effort | Decision rule |
|---|---|---|---|---|---|---|---|
| Agency will pay for “bed truth” | viability | 12 calls + pricing page + ask for paid pilot LOI | Agency owners/ops | 3 paid pilots or signed LOIs | 10 days | Low | If no paid intent → reposition/value prop |
| Soft constraints are enough | scope | Concierge MVP (Airtable/Notion) with warnings + nightly report | 1 agency | Used daily; no parallel sheet after week 2 | 14 days | Low–Med | If they keep Sheets → identify missing “hard constraint” set |
| One-truth-owner works | retention | Define RACI + role permissions in pilot | 1 agency + 2 properties | <2 disputes/week about “who changed what” | 2–3 weeks | Low | If disputes persist → change permission model |

---

## Data + compliance (don’t ignore)
You will handle PII (IDs, addresses, possibly immigration/work eligibility data).
- **Fact (High):** GDPR applies in PL/EU.  
- MVP approach: store minimum, add field-level access control, audit, retention policy.  
(No legal advice—get a GDPR/security pro when you start handling ID scans.)

---

## 5 clarifying questions (answer in bullets)
1) Who is the **truth owner** for check-in/out: agency ops or property manager?  
2) Are beds **assigned** (Bed 12) or just **capacity per room** (Room 3 has 4 spots)?  
3) Do you need **gender separation** as a hard rule from day 1 (yes/no)?  
4) Do workers have **unique IDs** today (internal ID) or only names/phone numbers?  
5) What is the “must-not-fail” moment: **nightly list**, **arrival day**, or **inspection day**?

---

**Next decision:** Pick the **truth owner (A or B)** + decide “bed-level vs room-capacity” for MVP.  

**Next actions (24–72h):**
1) Answer the 5 questions above.  
2) I’ll produce a **one-page PRD + pilot RACI + data model** and a **call script** to close 1 paid pilot.  
3) Start concierge MVP with 1 agency: nightly occupancy report + change log from day 1.

## User - Apr 14 at 1:18 PM

1. property manager
2. capacity per room
3. as an option, yes
4. internal id
5. all three

## Assistant - Apr 14 at 1:18 PM

## Your choices → immediate implications
- **Truth owner = property manager**  
  - Pushback: this makes sales + rollout harder. You now have *two buyers*: agency (pays) + property ops (must use daily).  
  - Assumption (Med): property managers will actually do check-ins/outs in the tool, not WhatsApp.
- **Capacity per room (not per bed)**  
  - Good. Cuts complexity ~5–10x.  
  - Assumption (High): “spot in room” is acceptable for audits/inspections (vs exact bed).
- **Gender separation optional**  
  - Good. Implement as a rule toggle per property/room type.
- **Internal worker ID exists**  
  - Great. Avoids name collisions and speeds bulk ops.
- **Must-not-fail = nightly list + arrival day + inspection day**  
  - This means your MVP is basically **3 workflows**, not 1 screen.

---

## MVP re-scope (Phase 1) around the 3 must-not-fail moments
### MVP goal
Property managers can run arrivals, keep occupancy accurate, and pass inspections—without spreadsheets.

### Must-have (7)
1) **Room capacity inventory**: properties → rooms → capacity (spots), gender rule toggle, blocked rooms/spots  
2) **Worker directory**: internal ID, name, phone, gender (if needed), notes/tags  
3) **Stay assignment (date-based)**: worker ↔ property/room with from/to dates + status (planned/checked-in/checked-out/no-show)  
4) **Arrivals workflow (arrival day)**: “Expected today” list + one-tap check-in + no-show tracking  
5) **Nightly occupancy report (nightly list)**: “Currently in-house” per property + exceptions (over-capacity, unknown, unassigned) export  
6) **Inspection mode (inspection day)**: room-by-room roster + “who is supposed to be here” + quick discrepancy capture  
7) **Roles + audit trail**: property manager edits check-in/out; agency can propose assignments; everything logged

**Non-goals**
- Transport, job-site assignment, payroll/ATS integrations
- Full document storage (store references only in MVP)
- Marketplace

---

## The key design decision (you need this or it breaks)
### Use a “propose → confirm” model (hybrid truth)
Because **property manager is truth owner**, but agencies still plan moves.

**RACI (simple)**
- **Agency ops:** creates/updates *planned* assignments (“Worker X should arrive Fri”)  
- **Property manager:** confirms reality (check-in/out, room changes, blocks capacity)  
- **Owner/admin:** resolves disputes + manages permissions

**Why this matters**
- If agency can overwrite actual occupancy, property managers will stop trusting the tool.  
- If property managers must also create all planned assignments, agencies won’t get planning value and won’t pay.

Assumption (High): this hybrid model reduces conflict and increases adoption.

---

## What “correct” means (define your success metric)
You said all three moments must not fail. Define correctness like this:

- **Nightly list correctness:** “in-house count + who is where” matches reality (target threshold you define)  
- **Arrival correctness:** expected arrivals list is up to date; no-show is tracked same day  
- **Inspection correctness:** discrepancies are captured with timestamp + who approved changes

If you don’t quantify this, you won’t know if you’re winning vs Sheets.

---

## Biggest risks now (so you don’t waste 3 months)
1) **Adoption risk (property managers)**  
   - They’re busy, often mobile-only, sometimes multilingual.  
   - Mitigation: mobile-first, 3 taps max for check-in, offline-tolerant if signal is bad.  
   - Assumption (Med): UX can beat WhatsApp speed.
2) **Responsibility fights** (“Agency says he’s there; property says no”)  
   - Mitigation: immutable audit log + “reported by” + status model (planned vs confirmed).
3) **Support load**  
   - Property-driven truth tends to create “emergency” support expectations.  
   - Mitigation: strict permissioning + self-serve change log + exportable proof.

---

## Experiments to run (fastest path to truth)
| Hypothesis | Experiment | Audience | Success metric | Duration | Effort | Decision rule |
|---|---|---|---|---|---|---|
| Property managers will adopt | Pilot with 1 property manager as daily user (mobile) | 1 property | ≥80% check-ins done in tool (not WhatsApp) | 14 days | Low–Med | If <50% → UX/workflow redesign |
| Hybrid truth reduces conflict | Run propose→confirm with audit in pilot | agency + property | <2 disputes/week + disputes resolved via log | 2–3 weeks | Med | If disputes persist → tighten permissions/statuses |
| Room-capacity is enough | Use only room capacity (no beds) | 1–2 properties | Inspection day succeeds without bed-level detail | 3–4 weeks | Low | If fail → add “spot number” only where needed |

---

## 3 questions (only what’s needed next)
1) Is the **property manager employed by the agency** (agency-owned hotel) or an **external partner**?  
2) On arrival day, who physically checks them in: **front desk**, **shift lead**, or **security**?  
3) Do you need **multi-language UI** in MVP (PL/UA/RU/EN), or can it start PL+EN?

---

**Next decision:** Commit to the **propose → confirm** model (agency plans, property confirms) and lock the 3 core screens: *Arrivals / In-house / Inspection*.  

**Next actions (24–72h):**
1) Answer the 3 questions above.  
2) Pick 1 pilot property + 1 pilot agency and define: one “go-live” date + who is on duty.  
3) I’ll draft: (a) the exact data model (entities + statuses), (b) the permission matrix, and (c) a clickable MVP wireframe outline for the 3 screens.

## User - Apr 14 at 1:24 PM

1. might be both, at first probably internal, when public mostly external partners
2. front desk or shift lead
3. yes, multilanguage is crucial, pl, en, de, ua, ru and later other languages

## Assistant - Apr 14 at 1:24 PM

## What changed with your answers
- **Properties can be internal or external** (later mostly external).  
  - Assumption (Med): your workflow can handle *partner properties* without constant disputes.
- **Check-in actor is front desk or shift lead** (not always “property manager”).  
  - This forces **role granularity** and **very fast UX** (front desk won’t do admin work).
- **Multi-language is crucial from day 1** (PL/EN/DE/UA/RU).  
  - Pushback: this is doable, but only if you **aggressively minimize text** and avoid free-form instructions. Otherwise translation + support becomes a tax.

---

## MVP organizational model (so it works for internal + external)
### Roles (MVP)
- **Agency Planner**: creates *planned* stays (who should arrive, dates, preferred property/room type).
- **Property Admin (internal manager / partner manager)**: manages room inventory + capacity rules.
- **Front Desk / Shift Lead**: does *operational actions* only (check-in/out, room move, mark no-show).
- **Agency Admin**: user management, permissions, audit review.

### Permission rule (critical)
- **Reality is owned by the property side**: only property roles can confirm check-in/out and room moves.  
- Agency can propose and request changes.

**Fact (High):** if agencies can overwrite “who is in-house”, external partners won’t trust the tool.

---

## Multi-language without killing speed (design constraints)
You need 5 languages early. Fine—do it with tight constraints:

### Approach
- **UI language per user** (not per property).  
- **Reports/export language** selectable (PL/EN/DE/UA/RU).
- Keep the UI to **short, reusable strings** (buttons, statuses, warnings).
- Prefer **structured statuses** over free text:
  - Planned / Expected today / Checked-in / No-show / Checked-out / Moved
- Use **tags + predefined reasons** (localized) instead of long notes:
  - “Arrived late”, “Docs missing”, “Room conflict”, “Sent to other property”, etc.

**Assumption (High):** if you allow lots of free-form text early, translation becomes inconsistent and support load spikes.

### Translation workflow (MVP)
- Store strings in a proper i18n system from day 1.  
- Start with professional translation for the “core 200–400 strings”, not the whole product.

---

## MVP screens (locked to your 3 must-not-fail moments)
1) **Arrivals (Today)**
   - Expected today list (filter by property)
   - Quick check-in (search by internal ID + optional QR)
   - Mark no-show / arrived late / redirected
2) **In-house (Nightly truth)**
   - Current occupancy per room + capacity
   - Exceptions: over capacity, unknown person, unassigned worker
   - One-tap export
3) **Inspection Mode**
   - Room-by-room roster
   - “Expected vs present” discrepancy capture
   - Timestamp + “confirmed by” (audit)

Support screens:
- Room inventory + capacity rules (property admin)
- Worker directory (agency admin/planner)
- Audit log (for disputes)

---

## Pushback: “DE” in PL temp-work housing
You said PL → CEE → global, but want **German** in MVP.

- Assumption (Med): German is needed because stakeholders/users are German-speaking (owners/clients/partners), not workers.
- If German is “nice to have”, defer it. If German is required for adoption (e.g., DE-based agency owner operating in PL), keep it.

I won’t decide this for you—confirm *who* needs DE.

---

## Experiments (now include language + partner readiness)
| Hypothesis | Why | Experiment | Success metric | Duration |
|---|---|---|---|---|
| Shift leads/front desk will use it | adoption | 2-week pilot where shift leads do 80% of check-ins | ≥80% check-ins in tool; <10% WhatsApp fallbacks | 14 days |
| 5-language UI does not slow ops | usability | Time 20 check-ins per role/language | Median check-in <15 seconds | 1–3 days |
| External partner won’t fight the “truth model” | roadmap risk | Run one partner property as “read-only agency, property confirms reality” | Disputes resolved via audit; no partner churn in 30 days | 30 days |

---

## Security / GDPR quick note (because partner properties = more exposure)
- **Fact (High):** You’ll share worker PII with multiple entities (agency + property).  
- MVP must include:
  - role-based access
  - audit trail
  - data minimization (don’t store ID scans in v1 unless unavoidable)

(Not legal advice—get GDPR/security review before scaling to external partners.)

---

## 4 clarifying questions (to finalize v1 spec)
1) **Who needs German**: workers, property staff, or agency owners/clients?  
2) Do you need **QR codes / badges** for internal IDs at check-in (yes/no)?  
3) Will shift leads/front desk have **company devices**, or BYOD phones (Android/iOS mix)?  
4) Do you need **offline mode** (poor reception) for check-in/out (yes/no)?

---

**Next decision:** Confirm language priority by user type (especially **DE**) + decide whether v1 check-in uses **ID search only** or **ID + QR**.  

**Next actions (24–72h):**
1) Answer the 4 questions above (bullets).  
2) Choose 1 internal property for pilot and name the exact roles/users who will operate it (front desk vs shift leads).  
3) I’ll produce: MVP PRD (entities + statuses), permissions matrix, and the exact localized string list for the 3 core screens (so translation stays bounded).

## User - Apr 14 at 1:25 PM

1. it should be possible to change language no matter the role, everything must be localized
2. that would speed things up, so yes
3. higher ranked company devices, lower own devices with some app, both os
4. yes

## Assistant - Apr 14 at 1:25 PM

## Locked decisions (good)
- **Truth owner = property side** (front desk / shift lead executes).  
- **Room capacity** (not bed-level).  
- **Multi-language for everything** (PL/EN/DE/UA/RU) from day 1.  
- **QR check-in** (speed).  
- **Mixed devices + offline** (company devices + BYOD; Android/iOS; poor reception).

These 5 together basically force a specific product shape: **Web admin + mobile ops app with offline-first + strict RBAC**.

---

## MVP v1 (Phase 1) — exact deliverables
### Apps
1) **Web Admin (agency + property admin)**
- Properties, rooms, capacity, gender rule toggle
- Worker directory (internal ID is primary key)
- Planned stays (agency proposes)
- Users/roles, permissions
- Audit log + exports

2) **Mobile Ops (front desk / shift lead)**
- Arrivals (today) → scan QR → check-in in 1–2 taps
- In-house list → room view + occupancy
- Inspection mode → room-by-room roster + discrepancy capture
- Offline mode + sync

### Minimal data model (MVP)
- Worker {internalId, name, phone?, gender?, tags, status}
- Property {name, address, rules}
- Room {propertyId, label/number, capacity, genderRule?}
- Stay {workerId, propertyId, roomId, startDate, endDate, status, source(planned/confirmed), timestamps}
- Event/Audit {actor, action, before/after, deviceId, timestamp, offlineFlag}

**Fact (High):** if you don’t store actions as events/audit, partner disputes will kill trust.

---

## The “propose → confirm” workflow (must be enforced)
- Agency creates **Planned** stays.
- Property roles can:
  - confirm check-in (turns Planned → Checked-in)
  - move rooms (Checked-in stays)
  - check-out
  - mark no-show / redirected

Agency cannot directly edit “checked-in reality”.

**Assumption (High):** this reduces conflict vs letting both sides edit everything.

---

## QR design (fast + privacy-safe)
Goal: scan should identify a worker with **zero PII** in the QR.

- QR contains: `workerInternalId + short checksum` (or signed token)
- No name, no phone, no nationality inside QR
- App resolves ID → displays worker card (localized)

**Assumption (Med):** you can issue/print badges or generate PDFs for agencies fast enough.  
If not, fallback is manual ID search.

---

## Offline-first (because you said “yes”)
Offline changes will happen. Plan for it explicitly.

### Offline rules (simple, MVP-safe)
- Mobile app keeps a **local queue of actions** (check-in/out/move).
- On sync:
  - server applies actions in order
  - if conflict (room capacity exceeded, worker already checked-in elsewhere):
    - keep action as “Needs review”
    - show a conflict inbox to property admin

### What you should NOT do in MVP
- Don’t attempt “smart auto-merge” of conflicting offline edits.
- Don’t allow offline edits to room inventory/capacity (admin-only online).

**Fact (High):** offline + multi-editor + capacity constraints creates conflicts. You need a clear conflict UX.

---

## Localization (5 languages) without exploding cost
You said “everything must be localized no matter the role”. Good—do it, but bounded.

### Non-negotiables
- Per-user language setting (in both apps)
- Localized:
  - statuses
  - action buttons
  - reasons (predefined)
  - exports/reports
  - error/conflict messages

### Strong constraint (to keep it shippable)
- In MVP, allow free-text notes, but **do not rely on them operationally**.
- Use **localized predefined reasons** for 90% of flows.

**Assumption (High):** predefined reasons will cover most real cases and keep reports readable across languages.

---

## BYOD + company devices (security stance for MVP)
You’ll have:
- “Higher ranked” staff on company devices (good)
- Shift leads on BYOD (risk)

MVP must include:
- Role-based access (shift lead sees only their property)
- Session timeouts + PIN/biometric if available
- Ability to revoke a device/session instantly
- Minimal PII visible on shift lead screens (only what’s needed to check in)

**Fact (High):** partner properties + BYOD increases breach risk. You need revocation and least-privilege from day 1.

---

## Your top risks now (the ones that can kill the business)
1) **Adoption risk**: shift lead won’t use it at speed.  
2) **Offline conflict risk**: “system says full but we checked in anyway” moments.  
3) **Multi-language support load**: unclear translations cause operational mistakes.  
4) **Two-sided rollout**: agency pays, property must comply.

---

## Experiments (do these before building too much)
| Hypothesis | Experiment | Success metric | Duration | Effort |
|---|---|---:|---:|---:|
| QR check-in is fast enough | Prototype scanner + dummy worker DB | median check-in < 10s | 2 days | Low |
| Offline flow is acceptable | Pilot in a low-reception area | <5% actions end in “Needs review” | 14 days | Med |
| Shift leads adopt on BYOD | BYOD pilot with 3–5 shift leads | ≥80% check-ins via app | 14 days | Med |
| Localization is “clear enough” | Test with UA/RU speakers using the app | <2 misunderstandings per shift | 3–5 days | Low–Med |

---

## 5 final questions (so I can write the PRD + screen flows)
1) QR carrier: **badge**, **paper list**, or **phone wallet** (Apple/Google) in v1?  
2) Do workers rotate properties often, or is it mostly stable with occasional moves?  
3) Inspection output: do you need **PDF** or is **CSV** enough in v1?  
4) Do you need “gender separation” at **property level** or **room level** (or both)?  
5) Any requirement for photo capture on inspection (room damage evidence) in v1?

---

**Next decision:** Choose the **QR carrier for v1** (badge vs paper vs wallet) and confirm inspection export format (PDF vs CSV).  

**Next actions (24–72h):**
1) Answer the 5 questions (bullets).  
2) Pick 1 internal property for a 14-day pilot and commit specific users (names/roles) + one low-reception area for offline testing.  
3) I’ll deliver: PRD + permission matrix + offline conflict rules + localized string list (bounded) + MVP milestone plan.

## User - Apr 14 at 1:26 PM

1. phone
2. 1-3 times per quarter usually
3. both
4. both
5. plan that for v2

## Assistant - Apr 14 at 1:26 PM

## Locked MVP spec (Phase 1) — based on your answers
**Bedok v1** = property-side, offline-capable, multi-language mobile ops app + web admin to manage **room-capacity housing**, with **agency proposes → property confirms**.

### Key decisions (and implications)
- **QR on phone** (worker presents QR on their phone).  
  - Pushback: not all workers will have a working smartphone/battery/data.  
  - **Must-have fallback**: search by internal ID + optional “agency printed roster QR” for group arrivals.  
  - Assumption (Med): smartphone QR works for the majority.
- Moves are **1–3 times/quarter** (mostly stable).  
  - Great: optimize for daily correctness, not optimization algorithms.
- Exports: **CSV + PDF** in v1.  
- **Gender separation rules at property + room level** (both).  
- **Inspection photo evidence** is v2.

---

## MVP scope (tight, buildable)
### Web Admin (Agency + Property Admin)
Must-have
1) Properties, rooms, capacities (spots), blocked rooms/spots  
2) Rules: gender separation toggles (property-level default, room-level override)  
3) Worker directory (internal ID primary), minimal PII  
4) Planned stays (agency proposes) + assignment suggestions (property/room)  
5) Users/roles/permissions + device/session revoke  
6) Audit log (immutable) + exports (CSV/PDF)

Non-goals
- Document scans storage, payroll/ATS, transport, marketplace

### Mobile Ops (Front desk / Shift lead)
Must-have (3 core screens)
1) **Arrivals (Today)**: scan QR → check-in; mark no-show/redirected  
2) **In-house**: current occupancy by room + exceptions  
3) **Inspection mode**: room-by-room roster, discrepancy capture (no photos v1)

Offline-first
- Check-in/out/move actions work offline, queued and synced later.

---

## Workflow rules (this is where trust is won)
### Status model (simple, enforceable)
- **Planned** (agency) → **Expected Today** (system) → **Checked-in** (property) → **Checked-out** (property)  
Side states: **No-show**, **Redirected**, **Needs review** (sync conflicts)

### Permission rule (non-negotiable)
- **Only property roles can create “Checked-in reality.”**  
- Agency can propose/plan only.

**Fact (High):** external partners will not adopt if agencies can overwrite occupancy.

---

## Offline conflict handling (MVP-grade)
When syncing queued offline actions:
- If action breaks capacity / gender rule / worker already checked-in elsewhere:
  - set to **Needs review**
  - do **not** silently override
  - notify Property Admin (web) + show conflict list (mobile)

**Assumption (High):** “explicit conflict inbox” beats smart auto-merge for v1.

---

## Localization plan (5 languages) that won’t blow up
Languages v1: **PL, EN, DE, UA, RU** everywhere (mobile + web + exports).

Constraints to keep it shippable:
- Use **predefined, localized reasons** for 90% of operations (late arrival, redirected, room conflict, etc.)
- Allow free-text notes, but don’t depend on them for core flows.
- Exports: template-based PDF per language; CSV language-agnostic columns + localized headers.

---

## QR on phone — implementation choice
### Recommended v1 mechanism
- QR contains a **signed token** referencing `workerInternalId` (no PII in QR).
- Token can expire (e.g., rotates daily/weekly) to reduce misuse.

Fallbacks (must-have)
- Manual search by internal ID
- “Group arrival list” on agency side that can be scanned (one QR → opens list of expected arrivals)

**Assumption (Med):** these fallbacks prevent ops failure when phones are dead/missing.

---

## Pilot acceptance criteria (what “done” means)
Run with 1 internal property first.

You pass v1 if, over 14 days:
- **Adoption:** ≥80% of check-ins happen in the app (not WhatsApp) (Assumption, Med)
- **Speed:** median check-in < 10–15 seconds including language switching (Assumption, Med)
- **Correctness:** nightly in-house report matches reality with only minor exceptions (define threshold with pilot) (Assumption, Med)
- **Disputes:** disputes are resolved using audit log, not arguments (Assumption, Med)

---

## v2 (already implied by your needs)
- Inspection **photo evidence** + incident/damage workflow
- External partner onboarding pack (SLA, access boundaries, billing hooks)
- More constraint types as *hard rules* (after you see real demand)

---

## 4 remaining decisions to finalize the PRD
1) Do you require **worker self-service** app/page in v1 to show QR, or will agency generate QR and distribute (e.g., SMS/WhatsApp link)?  
2) Should QR be **always available** or only when “Expected today / Checked-in” (reduces misuse)?  
3) How do you identify property users: email login, phone OTP, or both? (BYOD reality)  
4) For PDF export: do you need a **signature line / stamp** for inspections (common in ops)?

---

**Next decision:** Choose the **QR distribution method** (worker self-serve vs agency-issued link) + whether QR is always-on or state-based.  
**Next actions (24–72h):**
1) Answer the 4 decisions above (bullets).  
2) Name the pilot property and list exact roles/users (front desk, 2–3 shift leads, 1 property admin, 1 agency planner).  
3) I’ll produce the v1 PRD: screens + statuses + permissions + offline conflict rules + i18n string budget + pilot instrumentation events.

## User - Apr 14 at 1:28 PM

1. worker app by default, agency distrubition orchestrated by shift lead as a fallback
2. reduce misuse, allow overwrite by shift lead
3. both
4. let’s say yes

## Assistant - Apr 14 at 1:28 PM

## Updated MVP spec (locked from your last answers)
**Bedok v1** = **property-truth** housing ops with **worker QR**, offline-first mobile ops, and full localization (PL/EN/DE/UA/RU).

### Decisions you just made
- **Worker app by default** to present QR.  
  - Fallback: shift lead can orchestrate distribution (e.g., send link / show roster) when worker can’t.  
- **QR should be misuse-resistant** (state-based), but **shift lead can override**.  
- Login: **email + phone OTP**.  
- Inspection PDF: **signature/stamp line = yes**.

---

## Pushback (so you don’t get surprised)
- **Worker “app” is a trap if it requires install.**  
  - Assumption (Med): many workers won’t install or keep it updated.  
  - Recommendation: ship worker “app” as **PWA / web page** (add-to-home optional). (Assumption, High: reduces friction.)
- **Override is where fraud and disputes happen.**  
  - You must make override auditable and “expensive” (reason + actor + timestamp). (Fact, High: otherwise partners won’t trust it.)

---

## Worker QR design (state-based + override)
### Default rule (reduce misuse)
- Worker QR is **valid only** when:
  - Worker is **Expected Today** for that property OR already **Checked-in** there.
- QR encodes **no PII**. It’s a signed token referencing `workerInternalId`. (Fact, High)

### Override (shift lead)
Shift lead can override in 2 cases:
1) **Force check-in** (worker arrived but not expected)
2) **Reassign on arrival** (wrong property/room)

Override requirements (MVP must-have)
- Select a **localized reason** (predefined list)
- Optional note
- Generates an **audit event** (“override_checkin”) and flags the stay for review

**Assumption (High):** this balances speed with partner trust.

---

## Product surfaces (what you will actually ship)
### 1) Worker app (PWA)
Must-have
- Language selector (PL/EN/DE/UA/RU)
- “My QR” (time-limited / state-based)
- Basic identity display: internal ID + name (optional, configurable)
- Optional: “Where to go today” (property name/address) — only if you’re comfortable sharing this

Non-goals v1
- Messaging, document uploads, payments

### 2) Mobile Ops app (front desk / shift lead)
Must-have
- **Arrivals**: scan QR → check-in in 1–2 taps
- **Search by internal ID** (fallback)
- **In-house**: room occupancy + exceptions
- **Inspection mode**: room roster + discrepancy capture
- **Offline queue + sync**
- Conflict inbox: “Needs review”

### 3) Web Admin (agency + property admin)
Must-have
- Properties/rooms/capacity + gender rules (property default, room override)
- Worker directory + planned stays (agency proposes)
- Permissions + device/session revoke
- Audit log
- Exports: **CSV + PDF** (localized) + inspection PDF with signature line

---

## Permission matrix (v1)
- **Agency planner**: create/modify *Planned* stays, view occupancy, cannot change reality.
- **Property admin**: room inventory/rules, approve moves, resolve conflicts.
- **Front desk**: check-in/out for expected workers, limited move rights.
- **Shift lead**: front desk rights + **override** capability.

**Fact (High):** this is mandatory for external partners later, so bake it in now.

---

## Offline-first rules (keep it simple)
- Offline allowed actions: check-in/out, move room, mark no-show.
- Offline not allowed: edit capacity/rules/users.
- Sync conflicts go to **Needs review** (never silent auto-merge).

---

## Instrumentation (so pilots generate truth, not vibes)
Track events (must-have):
- qr_scan_success / qr_scan_fail_reason
- checkin_success (normal vs override)
- offline_action_queued / offline_sync_success / offline_conflict
- export_pdf_generated / export_csv_generated
- discrepancy_logged (inspection)

**Assumption (Med):** these metrics will show whether you’re beating Sheets/WhatsApp on speed + correctness.

---

## Pilot plan (2-stage, because worker app adds risk)
### Stage A (7–10 days): property ops only
- Use Mobile Ops + Web Admin.
- Worker identification via internal ID search (and optionally a shift-lead roster).
- Goal: prove the **property truth workflow** works.

### Stage B (next 7–14 days): add worker PWA QR
- Introduce worker QR gradually (one property / one shift).
- Goal: prove QR reduces check-in time without increasing overrides.

**Assumption (High):** staging reduces failure risk vs launching everything at once.

---

## 4 final spec questions (last ones before PRD/wireframes)
1) Worker PWA auth: **SMS OTP**, **magic link**, or **no-login QR issued by agency**? (Pick one for v1.)  
2) Should worker QR show **property name only** or also **address + map link**? (Privacy vs convenience.)  
3) Override limit: do you want a rule like “max X overrides per shift before requiring property admin approval”? (yes/no)  
4) Inspection PDF: per **property** or per **room** (one PDF per room is slower but cleaner)?

---

**Next decision:** Choose **worker PWA authentication method** (Q1) and the **QR content** (Q2).  
**Next actions (24–72h):**
1) Answer the 4 questions above (bullets).  
2) Pick 1 internal property and confirm: number of rooms, typical nightly occupancy, and who will act as shift lead(s).  
3) I’ll deliver: v1 PRD + screen flows (Arrivals/In-house/Inspection/Worker QR) + permissions + offline conflict UX + i18n string list (bounded) + pilot success thresholds.

## User - Apr 14 at 1:30 PM

1. sms
2. convenience
3. yes
4. per property, but it’s possible to select rooms to include in the report

## Assistant - Apr 14 at 1:30 PM

## Locked v1 decisions (now stable)
- **Worker “app” = PWA** with **SMS OTP** login. *(Assumption, High: avoids install friction.)*
- Worker QR includes **convenience info**: property + **address + map link**. *(Assumption, Med: speeds arrivals; increases privacy exposure.)*
- **Shift lead overrides allowed**, but **rate-limited**. *(Fact, High: needed to control misuse.)*
- **Inspection PDF per property**, with ability to **include selected rooms**.

---

## v1 PRD outline (buildable, Phase 1 only)

### 1) Core workflow: arrivals (front desk / shift lead)
**Happy path**
1. Open **Arrivals (Today)** for Property A.
2. Scan worker QR (from worker PWA).
3. App resolves worker → checks state:
   - Expected today for this property → **Check-in**
   - Not expected → show **Override** action (shift lead only)
4. Assign room (auto-suggest or pick) respecting:
   - capacity
   - gender rules (property default, room override)
5. Save → audit event + offline-safe.

**Fallbacks**
- Search by **internal ID**
- “No-phone” handling: manual ID search + check-in (with reason)

**Correctness definition**
- Every check-in produces an **immutable audit event**. *(Fact, High)*

---

### 2) Nightly truth: in-house + exceptions
Screens
- **In-house by room**: occupancy / capacity + worker list
- **Exceptions**:
  - over capacity
  - gender rule violation (if enforced as hard)
  - worker checked-in but not assigned to a room
  - offline actions pending sync
- Export **Nightly Occupancy**: CSV + PDF (localized)

---

### 3) Inspection mode (no photos v1)
- Start “Inspection mode” → select **rooms to include**
- Room-by-room roster: expected vs present
- Quick actions:
  - mark discrepancy (predefined reason + note)
  - move / checkout (permissioned)
- Generate **Property Inspection PDF**:
  - selected rooms
  - signature/stamp lines
  - generated timestamp + generated-by user

---

## Permissions + override policy (must be explicit)
Roles
- Agency Planner (web): plan stays, view reports
- Property Admin (web): inventory/rules/users, resolves conflicts
- Front Desk (mobile): check-in/out expected workers
- Shift Lead (mobile): front desk + **override**

**Override requirements (v1)**
- Mandatory: predefined localized reason
- Optional: note
- Always: audit event + “flagged” record

**Override rate limiting**
- Rule: “If overrides per shift exceed threshold → require Property Admin approval.” *(Assumption, Med)*
- Implementation: shift has a time window (e.g., 8–12h) + per-property counter.

> **Guess (Low):** start with threshold = 5 overrides / shift / property, then tune in pilot.

---

## Worker PWA (SMS OTP) — minimum viable spec
Must-have
- SMS OTP login (phone number = primary identity for worker access)
- “My QR” (time-limited token; no PII inside QR)
- Language switch (PL/EN/DE/UA/RU)
- “Today’s location” card: property name + address + map link

Risks
- **SMS cost + deliverability** in CEE. *(Assumption, Med)*
- Shared phones / number changes. *(Assumption, Med)*  
Mitigation: allow agency to rebind worker phone after admin verification + audit.

---

## Offline-first constraints (v1)
- Offline allowed: check-in/out/move/discrepancy mark
- Offline not allowed: inventory/rules/users
- Conflicts on sync → “Needs review” inbox (never silent merge). *(Fact, High)*

---

## MVP instrumentation (so you can validate fast)
Track:
- checkin_success (normal vs override)
- checkin_time_seconds (scan→confirmed)
- override_count_per_shift
- offline_queue_depth + sync_conflicts
- nightly_export_generated
- inspection_pdf_generated + rooms_included
- discrepancy_logged

Success targets to set in pilot:
- **Adoption:** ≥80% check-ins done in-app *(Assumption, Med)*
- **Speed:** median scan check-in < 15s *(Assumption, Med)*
- **Control:** overrides < X% of check-ins; otherwise onboarding/data is broken *(Assumption, Med)*
- **Offline:** sync conflicts < 5% of actions *(Assumption, Med)*

---

## 5 open questions (last to finalize build tickets)
1) **Map link** provider: Google Maps, Apple Maps, or both (based on OS)?  
2) SMS OTP: do you need **WhatsApp fallback** for OTP when SMS fails (yes/no)?  
3) Worker PWA access: should workers see only **today**, or also next 7 days (planning vs privacy)?  
4) Gender rule: do you want it **hard-blocking** or “warn + allow override” (v1)?  
5) Override threshold: define what a “shift” means in your ops (fixed hours vs manual start/end).

---

**Next decision:**  
- Pick (a) map provider behavior (both vs OS-specific) and (b) gender rule enforcement mode (hard block vs warn+override).

**Next actions (24–72h):**  
1) Answer the 5 open questions in bullets.  
2) Share pilot parameters: #workers, #rooms, avg daily arrivals, and how many shift leads/front desk users.  
3) I’ll convert this into: **epics + user stories + acceptance criteria** for Web Admin / Mobile Ops / Worker PWA, plus a **2-stage pilot plan** with exact success thresholds.

