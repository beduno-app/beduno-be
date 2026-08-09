# Beduno Backend - Project Overview

## What is Beduno?

Beduno is an operational system for temporary work agencies to manage worker housing. It replaces spreadsheets and WhatsApp with a reliable, auditable source of truth for **"who sleeps where tonight"**.

## Problem Statement

Owners of temporary work agencies relocate large groups of workers between distant locations. Workers have different constraints (gender, skills, bans), properties have limited capacity, and changes happen daily. Today this is managed via Excel/Google Sheets + WhatsApp, leading to overbookings, missing people, and zero accountability.

## MVP Scope (Phase 1 - Housing Source of Truth)

The MVP proves Beduno can be the reliable system of record for bed occupancy across multiple properties for one agency.

### Three Must-Not-Fail Moments

1. **Arrival Day** - expected arrivals list is accurate, check-in is fast, no-shows are tracked same day
2. **Nightly List** - "currently in-house" count and roster matches reality
3. **Inspection Day** - room-by-room roster with expected-vs-present discrepancy computation (not yet persisted as an audit record — see Core Capability #6)

### Core Capabilities

| # | Capability | Description |
|---|-----------|-------------|
| 1 | Room capacity inventory | Properties > Rooms > Capacity (spots), gender rule toggle, blocked rooms/spots |
| 2 | Worker directory | Internal ID, name, phone, gender, notes/tags |
| 3 | Stay assignment (date-based) | Worker <> Property/Room with dates + status (planned/checked-in/checked-out/no-show) |
| 4 | Arrivals workflow | "Expected today" list + one-tap check-in + no-show tracking |
| 5 | Nightly occupancy report | "Currently in-house" per property + exceptions + export |
| 6 | Inspection mode | Room-by-room roster + expected vs present. Discrepancies are computed and returned in the response, but currently **not persisted**: `POST /properties/{id}/inspection` is read-only and writes no report row or audit event. |
| 7 | Roles + audit trail | Role-based permissions, immutable change log. Roles are enforced by JWT role claim; per-property scoping for `PROPERTY_ADMIN` is only enforced on property update and room create/update, not on stays, occupancy, or exports (see README) |

### Explicit Non-Goals (MVP)

- Job/site assignment, transport routing
- Payroll, time tracking, ATS integrations
- Full document storage (references only)
- Public marketplace
- Dynamic pricing/payments

## Operational Model

### Propose-Confirm Workflow

Reality is owned by the property side. Agency plans, property confirms.

- **Agency Planner** creates planned stays (who should arrive, dates, preferred property/room)
- **Property Admin** manages room inventory + capacity rules
- **Front Desk / Shift Lead** performs operational actions (check-in/out, room move, mark no-show)
- **Agency Admin** reviews audit log; full agency-wide access. User and permission management is **not yet implemented** — there is no user-management API (no `UserController` or `AgencyController`); users can currently only be created directly via SQL/seed data.

### Key Design Principle

If agencies can overwrite actual occupancy, property partners will stop trusting the tool. The system enforces that only property-side roles can confirm check-in/out and room changes.

## Target Users

- **Primary ICP**: PL temporary work agencies with 200-800 workers and 10-50 properties
- **Daily users**: front desk staff, shift leads (mobile-first, multilingual)
- **Planning users**: agency ops/planners
- **Admin users**: agency owners, property managers

## Multi-Language Support

Required from day 1: PL, EN, DE, UA, RU. UI language is per-user. Reports/exports have selectable language. UI strings are kept short and structured (statuses, predefined reasons) to minimize translation burden.

## Geography & Scale

- Start: Poland
- Next: CEE (Central-Eastern Europe)
- Later: Global
- Scale: hundreds of workers, tens of properties per agency

## Roadmap (Beyond MVP)

| Phase | Focus |
|-------|-------|
| 1 (MVP) | Internal housing ops - occupancy + moves + audit + reporting |
| 2 | Job-site linkage (lightweight) |
| 3 | Transport ("bus list per pickup point", not route optimization) |
| 4 | External partner supply (B2B, verified) |
| 5 | Public marketplace |
