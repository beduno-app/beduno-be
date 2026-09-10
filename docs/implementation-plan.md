# Beduno Backend - Implementation Plan (delivered)

> **Status: all six phases are implemented.** This document was written before the
> build as a forward-looking plan. It has been restated to record what was actually
> delivered. Tasks that were planned but not built are marked inline with **[not
> delivered]** and collected in [Deferred / not delivered](#deferred--not-delivered).
> Decisions the implementation made *differently* from this plan are the review
> agenda in [`open-questions.md`](open-questions.md).

## Phases

Six sequential phases, each producing a working, testable increment. All are
complete; the codebase is 59+ commits, 124 Java files and 7 Flyway migrations.

---

## Phase 1: Project Scaffold & Infrastructure — delivered

**Goal**: Bootable Spring Boot app with database, auth skeleton, and CI-ready test setup.

### Tasks
1. ✅ Spring Boot 3.4 project with Gradle (Kotlin DSL) — Spring Web, Data JPA, Security, Validation, Flyway, PostgreSQL driver, Lombok, MapStruct, SpringDoc OpenAPI
2. ✅ `application.yml` with dev and prod profiles
3. ✅ Docker Compose with PostgreSQL 16
4. ✅ `BaseEntity` (id UUID, createdAt, updatedAt)
5. ✅ `V1__create_agencies.sql`
6. ✅ `V2__create_users.sql`
7. ✅ JWT authentication — `JwtTokenProvider`, `SecurityConfig`, `AuthController` (login, refresh, me), `AuthService`
8. ✅ Multi-tenancy — `TenantContext` (ThreadLocal), `TenantFilter` reading JWT claims
9. ✅ `GlobalExceptionHandler` with a standard error response format
10. ✅ Testcontainers base class (`IntegrationTestBase`, singleton container)
11. ✅ Smoke test: app starts, JWT is valid

**Delivered.** App boots, connects to Postgres, issues JWTs. Integration suite runs
against Testcontainers.

---

## Phase 2: Core Entities (Workers, Properties, Rooms) — delivered

**Goal**: CRUD for the three core entities with tenant isolation and role-based access.

### Tasks
1. ✅ `V3__create_workers.sql` — unique on (agency_id, internal_id)
2. ✅ Worker module — CRUD, filtering by status/gender/tag/name, soft delete via `deleted_at`
3. ✅ `V4__create_properties_rooms.sql`
4. ✅ Property module — CRUD
5. ✅ Room module — **shipped as its own top-level module**, not nested inside `property/` as the plan's structure implied. Endpoints stayed nested at `/api/v1/properties/{propertyId}/rooms`
6. ⚠️ Role-based access — enforced, but **not as specified**: Agency Planner is read-only on workers (the plan gave it write access), and Property Admin cannot delete rooms
7. ⚠️ Tenant isolation — every query filters by `agencyId` except the deliberate cross-tenant scheduler sweep. Integration tests exist but two of them do not actually assert exclusion (see `open-questions.md` §5)
8. ✅ Pagination and sorting on list endpoints — though sort keys are inconsistent between endpoints (see Q9)

---

## Phase 3: Stay Management & Constraint Engine — delivered

**Goal**: Create, update, and manage stays with full constraint validation.

### Tasks
1. ✅ `V5__create_stays.sql` with the planned indexes and a `chk_stays_dates` CHECK
2. ✅ `StayStatus` enum with a valid-transition map — **six states, no `MOVED`**
3. ⚠️ Constraint engine — `ConstraintEngine`, `CapacityConstraint`, `DoubleBookingConstraint`, `BlockedRoomConstraint` (which also covers property-inactive), `GenderConstraint` (soft), `ConstraintResult`, unit tests. **Capacity and double-booking count PLANNED and EXPECTED_TODAY as well as CHECKED_IN**, which the plan did not specify and which makes over-planning a hard block (see Q1)
4. ✅ Stay module with `@Version` optimistic locking
5. ✅ Stay endpoints — create, update, cancel, list with filters, get by id
6. ✅ Soft-constraint override — 422 with details, resubmit with `overrideReason`
7. ⚠️ Role-based access — as planned, except Front Desk can also call `bulk-checkout`, and the override itself is not role-restricted (see S2)

---

## Phase 4: Operational Workflows (Arrivals, Check-in/out, Inspection) — delivered

**Goal**: The three must-not-fail workflows are fully functional.

### Tasks
1. ✅ Scheduled `planned -> expected_today` transition — `StayScheduler`, cron `beduno.scheduler.arrival-transition-cron` (default 06:00). Intentionally cross-tenant
2. ✅ Arrivals workflow — `GET /stays/arrivals` (propertyId **required**), check-in with optional room override, no-show with reason tag
3. ✅ Check-out workflow — sets `dateTo` only when `actualDateTo` is supplied
4. ⚠️ Room move — atomic, single transaction, but modelled as check-out plus a new stay rather than a `MOVED` status, and **same-property only** (the plan allowed a different property). Refuses with 409 on the stay's final day
5. ✅ Occupancy service — occupancy and exceptions endpoints with the aggregation query
6. ⚠️ Inspection mode — roster and discrepancy computation both work, but **the submit endpoint persists nothing** and writes no audit event (see Q5)
7. ✅ Role enforcement on check-in/out/move/no-show (Property Admin + Front Desk) and inspection (Property Admin)
8. ✅ End-to-end integration test covering the full lifecycle

---

## Phase 5: Audit Trail, Bulk Operations & Export — delivered

**Goal**: Full auditability, bulk workflows, and exportable reports.

### Tasks
1. ✅ `V6__create_audit_events.sql`
2. ✅ Audit module — `AuditService.log(...)` with before/after JSONB snapshots, integrated into Stay, Worker, Room and Property services at ~20 call sites. **Explicit calls, not AOP**
3. ⚠️ Audit endpoint — filters by entityType, entityId, actorUserId and date range. **No `action` filter**, though the plan called for one
4. ✅ Bulk operations — CSV worker import, bulk-assign, bulk-checkout, all returning per-item results. Bulk-assign flattens constraint details to a bare message code (see Q8)
5. ✅ Export service — occupancy, arrivals and exceptions as CSV with a `language` parameter
6. ✅ i18n for exports — all five languages plus the default bundle are complete and key-identical. (This was only true for EN and PL until the reconciliation pass; DE/RU/UA exports previously emitted raw message keys.)
7. ✅ Integration tests for bulk operations, happy path and partial failures

---

## Phase 6: Polish, Security Hardening & Documentation — delivered

**Goal**: Production-ready hardening, API documentation, and operational readiness.

### Tasks
1. ✅ Rate limiting on auth endpoints — Bucket4j, 10 req/min per IP. Bucket map is never evicted (see S4)
2. ⚠️ Input sanitization review — no artifact was produced; nothing to verify against
3. ✅ CORS configured — originally wide open with credentials allowed; narrowed to an env-driven allowlist in `fc14245` (S3 closed)
4. ⚠️ Spring Actuator — `health` and `info` exposed; **`metrics` was planned and is not exposed**
5. ✅ Structured logging with MDC (requestId, userId, agencyId) and JSON output under the prod profile
6. ✅ OpenAPI documentation — bearer scheme, `@Operation` on all controllers
7. ✅ Multi-stage production Dockerfile, non-root, with a healthcheck
8. ⚠️ Database index review — indexes exist and match the plan, but no review record was produced
9. ❌ **[not delivered]** Load test the critical queries — no harness, no results
10. ✅ README with setup, environment variables and API overview

### Unplanned additions

- **Checkstyle** (`config/checkstyle/checkstyle.xml`, `isIgnoreFailures = false`) — wired into `./gradlew build`. Never mentioned in this plan.
- **`V7__add_stay_no_show_reason.sql`** — added during reconciliation so the no-show reason stops overwriting `stays.notes`.
- **401 handling** — `UnauthorizedException`, `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`.
- **Deletion guards** — 409 on property/room delete while referenced.

---

## Deferred / not delivered

Carried forward. Details and the reasoning behind each sit in
[`open-questions.md`](open-questions.md).

| Item | Planned in | Status |
|---|---|---|
| UUID v7 (time-ordered ids) | Key decisions | Not implemented — `GenerationType.UUID` and `gen_random_uuid()` are both random v4 |
| Soft delete across entities | Key decisions | Workers only. Stays are cancelled by status; properties and rooms are hard-deleted behind a 409 guard |
| Load test critical queries | Phase 6.9 | Not done |
| Actuator `metrics` endpoint | Phase 6.4 | Not exposed |
| Audit `action` filter | Phase 5.3 | Not supported |
| Input sanitization review | Phase 6.2 | No artifact |
| DB index review record | Phase 6.8 | No artifact |
| Per-property scoping for operational roles | Phase 4.7 | Enforced for property/room writes only — see S1 |
| Role-restricted soft-constraint override | Phase 3.7 | Not enforced — see S2 |
| Persisted inspection reports | Phase 4.6 | Computed and returned, never stored — see Q5 |
| User and agency management API | Implied throughout | No controller exists — see Q6 |

---

## Dependency Graph

```
Phase 1 (Scaffold)
    │
    v
Phase 2 (Entities)
    │
    v
Phase 3 (Stays + Constraints)
    │
    v
Phase 4 (Workflows)
    │
    v
Phase 5 (Audit + Bulk + Export)
    │
    v
Phase 6 (Polish)
```

## Key Implementation Decisions

Corrected to record what was actually built. Rows where the plan and the code
disagree are called out.

| Decision | Planned | As built |
|----------|---------|----------|
| Build tool | Gradle (Kotlin DSL) | ✅ As planned |
| IDs | UUID v7 (time-ordered), for sortability and index locality | ❌ **Random UUID v4** — `GenerationType.UUID` + `gen_random_uuid()`. The stated benefit was never realised |
| Timestamps | UTC everywhere, `LocalDate` for stay dates | ✅ As planned — `TIMESTAMPTZ` + `LocalDate` |
| Soft delete | `deleted_at` column | ⚠️ **Workers only.** Stays use a `CANCELLED` status; properties and rooms are hard-deleted, guarded by a 409 conflict check |
| Optimistic locking | `@Version` on Stay | ✅ As planned |
| Multi-tenancy | Shared schema + `agency_id` filter | ✅ As planned, with one sanctioned cross-tenant query (the scheduler sweep) |
| Constraint override | Re-submit with `overrideReason` | ✅ As planned, but ⚠️ not restricted by role |
| Static analysis | *(not planned)* | ➕ Checkstyle, failing the build on violations |
| Authentication errors | *(not specified)* | ➕ 401 with the standard error envelope; 403 reserved for role denials |
