# Beduno Backend - Implementation Plan

## Phases

The implementation is split into 6 sequential phases. Each phase produces a working, testable increment.

---

## Phase 1: Project Scaffold & Infrastructure

**Goal**: Bootable Spring Boot app with database, auth skeleton, and CI-ready test setup.

### Tasks
1. Initialize Spring Boot 3.4 project with Gradle (Kotlin DSL)
   - Dependencies: Spring Web, Spring Data JPA, Spring Security, Spring Validation, Flyway, PostgreSQL driver, Lombok, MapStruct, SpringDoc OpenAPI
2. Configure `application.yml` with profiles (dev, prod)
3. Set up Docker Compose with PostgreSQL 16
4. Create `BaseEntity` (id UUID, createdAt, updatedAt)
5. Create Flyway migration `V1__create_agencies.sql`
   - `agencies` table with id, name, status, settings (JSONB), timestamps
6. Create Flyway migration `V2__create_users.sql`
   - `users` table with id, agency_id, email, password_hash, first_name, last_name, role, language, assigned_property_ids, status, timestamps
7. Implement JWT authentication
   - `JwtTokenProvider`: generate, validate, parse tokens
   - `SecurityConfig`: stateless session, JWT filter, public endpoints (/auth/**)
   - `AuthController`: POST /auth/login, POST /auth/refresh
   - `AuthService`: authenticate, issue tokens
8. Implement multi-tenancy
   - `TenantContext` (ThreadLocal holding agencyId)
   - `TenantFilter` (extracts agencyId from JWT, sets TenantContext)
9. Set up `GlobalExceptionHandler` with standard error response format
10. Set up Testcontainers base class for integration tests
11. Write smoke test: app starts, login works, JWT is valid

### Deliverable
App boots, connects to Postgres, user can log in and receive a JWT. Integration test suite runs against Testcontainers.

---

## Phase 2: Core Entities (Workers, Properties, Rooms)

**Goal**: CRUD for the three core entities with tenant isolation and role-based access.

### Tasks
1. Flyway migration `V3__create_workers.sql`
   - workers table with all fields, unique constraint on (agency_id, internal_id), index on agency_id
2. Worker module
   - Entity, Repository, Service (CRUD + search/filter), Controller
   - DTOs: CreateWorkerRequest, UpdateWorkerRequest, WorkerResponse, WorkerSummary
   - Endpoints: GET/POST/PUT/DELETE /api/v1/workers, GET /api/v1/workers/{id}
   - Filtering: by status, gender, tag, name search
   - Soft delete
3. Flyway migration `V4__create_properties_rooms.sql`
   - properties table, rooms table (with FK to property), indexes
4. Property module
   - Entity, Repository, Service, Controller
   - DTOs for create/update/response
   - Endpoints: CRUD for properties
5. Room module (nested under property)
   - Entity, Repository, Service, Controller
   - Endpoints: CRUD at /api/v1/properties/{propertyId}/rooms
   - Capacity management, blocked spots, gender rule
6. Role-based access on all endpoints
   - Agency Admin: full CRUD
   - Agency Planner: read workers, read properties/rooms
   - Property Admin: read/write own properties and rooms
   - Front Desk: read-only
7. Tenant isolation verification
   - All repository queries filter by agencyId
   - Integration tests: cross-tenant data is never returned
8. Pagination and sorting on list endpoints

### Deliverable
Full CRUD for workers, properties, rooms. Role-based access enforced. Tenant isolation tested.

---

## Phase 3: Stay Management & Constraint Engine

**Goal**: Create, update, and manage stays with full constraint validation.

### Tasks
1. Flyway migration `V5__create_stays.sql`
   - stays table with all fields, indexes on (agency_id, worker_id), (agency_id, property_id), (agency_id, status)
   - Composite index for occupancy queries: (room_id, status, date_from, date_to)
2. StayStatus enum with valid transition map
3. Constraint engine
   - `ConstraintEngine` orchestrator
   - `CapacityConstraint`: check room occupancy vs capacity - blockedSpots
   - `DoubleBookingConstraint`: check overlapping checked_in stays for same worker
   - `BlockedRoomConstraint`: check room/property status
   - `GenderConstraint`: check gender rule compliance (soft)
   - `ConstraintResult` with hard/soft violations
   - Unit tests for every constraint type and edge case
4. Stay module
   - Entity (with `@Version` for optimistic locking), Repository, Service, Controller
   - Create stay (planned) - runs constraint engine
   - Update stay (change dates, room) - runs constraint engine
   - Cancel stay
   - Status transitions with validation
5. Stay endpoints
   - POST /api/v1/stays (create planned stay)
   - PUT /api/v1/stays/{id} (update)
   - DELETE /api/v1/stays/{id} (cancel)
   - GET /api/v1/stays?workerId=&propertyId=&status=&dateFrom=&dateTo=
   - GET /api/v1/stays/{id}
6. Soft constraint override flow
   - If soft violations present, return 422 with violation details
   - Client resends with `overrideReason` field -> operation proceeds
7. Role-based access
   - Agency Planner/Admin: create/update/cancel planned stays
   - Property Admin: create/update for own properties
   - Front Desk: read-only on stays (operational actions in Phase 4)

### Deliverable
Stays can be created and managed with full constraint validation. Constraint engine has comprehensive unit tests.

---

## Phase 4: Operational Workflows (Arrivals, Check-in/out, Inspection)

**Goal**: The three must-not-fail workflows are fully functional.

### Tasks
1. Automatic status transition: planned -> expected_today
   - Scheduled task (`@Scheduled`) runs daily at configurable time
   - Finds all stays with status=planned and dateFrom=today, transitions to expected_today
2. Arrivals workflow
   - GET /api/v1/stays/arrivals?propertyId=&date= (expected_today stays)
   - POST /api/v1/stays/{id}/check-in (with optional room override)
     - Runs constraint engine before confirming
     - Sets confirmedByUserId
     - Transitions status to checked_in
   - POST /api/v1/stays/{id}/no-show (with reason tag)
3. Check-out workflow
   - POST /api/v1/stays/{id}/check-out
   - Sets actual dateTo if different from planned
4. Room move workflow
   - POST /api/v1/stays/{id}/move (with target roomId)
   - Checks out from current room, creates new stay in target room
   - Atomic operation (single transaction)
5. Occupancy service
   - GET /api/v1/properties/{id}/occupancy?date= (rooms with current occupants)
   - GET /api/v1/properties/{id}/exceptions?date= (over-capacity, unassigned)
   - Aggregation query: room capacity vs checked_in count
6. Inspection mode
   - GET /api/v1/properties/{id}/inspection?date= (room-by-room roster)
   - POST /api/v1/properties/{id}/inspection (submit discrepancy report)
   - Discrepancy: expected worker not present, unexpected worker present
7. Role enforcement
   - Check-in/out/move/no-show: Property Admin + Front Desk only
   - Inspection: Property Admin only
8. Integration tests for complete workflows
   - Create property -> rooms -> workers -> plan stays -> arrival day -> check-in -> nightly occupancy -> inspection

### Deliverable
All three must-not-fail workflows operational. End-to-end integration test covers the full lifecycle.

---

## Phase 5: Audit Trail, Bulk Operations & Export

**Goal**: Full auditability, bulk workflows, and exportable reports.

### Tasks
1. Flyway migration `V6__create_audit_events.sql`
2. Audit module
   - `AuditService.log(entityType, entityId, action, previousState, newState, reason)`
   - Integrate into Stay, Worker, Room, Property services
   - Store before/after state as JSONB
3. Audit endpoints
   - GET /api/v1/audit?entityType=&entityId=&userId=&dateFrom=&dateTo=
   - Pagination, filtering by entity, actor, date range, action type
4. Bulk operations
   - POST /api/v1/workers/import (CSV upload)
     - Parse CSV, validate rows, create workers, return summary (created/skipped/errors)
   - POST /api/v1/stays/bulk-assign (list of worker+room+dates)
     - Run constraint engine per assignment, return per-item results
   - POST /api/v1/stays/bulk-checkout (list of stay IDs)
5. Export service
   - GET /api/v1/properties/{id}/occupancy/export?format=csv&language=pl
   - Nightly occupancy list with localized headers and status labels
   - Arrivals list export
   - Exception report export
6. i18n for exports
   - Message bundles for status labels, column headers, reason tags
   - Language parameter on all export endpoints
7. Integration tests for bulk operations (happy path + partial failures)

### Deliverable
Full audit trail on all changes. Bulk import/assign/checkout working. CSV exports with language support.

---

## Phase 6: Polish, Security Hardening & Documentation

**Goal**: Production-ready hardening, API documentation, and operational readiness.

### Tasks
1. Rate limiting on auth endpoints
2. Input sanitization review (XSS, injection)
3. CORS configuration for frontend origin
4. Spring Actuator: health, info, metrics endpoints (secured)
5. Structured logging with MDC (agencyId, userId, traceId)
6. OpenAPI documentation
   - All endpoints documented with request/response schemas
   - Example values for common operations
   - Error response documentation
7. Dockerfile for production build (multi-stage)
8. Database indexes review and optimization
9. Load test the critical queries (occupancy, arrivals) with realistic data volumes
10. README with setup instructions, environment variables, API overview

### Deliverable
Production-ready backend with complete API docs, containerized build, and security hardening.

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

Each phase depends on the previous one. Within each phase, tasks can be parallelized where noted.

## Key Implementation Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Build tool | Gradle (Kotlin DSL) | Faster than Maven, type-safe build scripts |
| IDs | UUID v7 (time-ordered) | No sequence contention, safe for distributed systems, sortable |
| Timestamps | UTC everywhere, LocalDate for stay dates | Avoid timezone confusion; stay dates are calendar dates, not instants |
| Soft delete | `deleted_at` column | Required for audit trail and data retention |
| Optimistic locking | `@Version` on Stay | Prevent concurrent check-in race conditions |
| Multi-tenancy | Shared schema + agency_id filter | Simplest for MVP, sufficient for expected scale |
| Constraint override | Re-submit with overrideReason | Clean UX flow, audit-friendly |
