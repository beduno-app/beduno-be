# Beduno Backend - Architecture

> Reconciled against the implementation on 2026-08-09. Everything below was verified against
> `src/main/java/com/beduno/**`, `src/main/resources/db/migration/V1..V7`, `application*.yml`,
> `logback-spring.xml`, `build.gradle.kts`, and `docker/`.

## Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Language | Java 21 | LTS, virtual threads, pattern matching, records |
| Framework | Spring Boot 3.4 | Mature ecosystem, security, i18n, actuator |
| Build | Gradle (Kotlin DSL) | Faster builds, better dependency management |
| Database | PostgreSQL 16 | JSONB for flexible fields, row-level security, mature |
| Migrations | Flyway | Version-controlled schema migrations |
| Auth | Spring Security + JWT | Stateless API auth, role-based access |
| JWT library | jjwt 0.12.6 | HS256 signing/parsing, no Spring OAuth2 dependency needed |
| API | REST (JSON) | Simple, well-understood, sufficient for MVP |
| Validation | Jakarta Validation | Declarative constraint validation |
| Mapping | MapStruct 1.6.3 | Compile-time DTO mapping, no reflection overhead |
| Boilerplate | Lombok (Spring Boot managed) | Getters/setters/constructors on entities and services |
| Rate limiting | Bucket4j 8.10.1 (core, in-memory) | Token-bucket throttling of the auth endpoints |
| Monitoring | Spring Boot Actuator | Health/info endpoints for container and platform probes |
| Static analysis | Checkstyle 10.21.4 | Enforced at build time (`isIgnoreFailures = false`) |
| Testing | JUnit 5 + Testcontainers | Real DB in tests, no mocking the database |
| API Docs | SpringDoc OpenAPI 2.8.4 | Auto-generated Swagger UI |
| Containerization | Docker + Docker Compose | Local dev and deployment |

## Project Structure

```
beduno-be/
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew / gradlew.bat
├── gradle/
│   └── wrapper/
├── config/
│   └── checkstyle/
│       ├── checkstyle.xml
│       └── suppressions.xml
├── docker/
│   ├── Dockerfile
│   └── docker-compose.yml
├── docs/
├── src/
│   ├── main/
│   │   ├── java/com/beduno/
│   │   │   ├── BedunoApplication.java        # @SpringBootApplication @EnableScheduling
│   │   │   ├── config/
│   │   │   │   ├── AuditConfig.java          # @EnableJpaAuditing
│   │   │   │   ├── JwtConfig.java            # @ConfigurationProperties("beduno.jwt")
│   │   │   │   ├── OpenApiConfig.java
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   └── WebConfig.java            # CORS + AcceptHeaderLocaleResolver
│   │   │   ├── common/
│   │   │   │   ├── exception/
│   │   │   │   │   ├── BusinessException.java
│   │   │   │   │   ├── ConflictException.java
│   │   │   │   │   ├── ConstraintViolationException.java
│   │   │   │   │   ├── ErrorResponse.java
│   │   │   │   │   ├── ForbiddenException.java
│   │   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   │   ├── NotFoundException.java
│   │   │   │   │   ├── UnauthorizedException.java
│   │   │   │   │   └── ValidationException.java
│   │   │   │   ├── model/
│   │   │   │   │   ├── BaseEntity.java
│   │   │   │   │   └── PageResponse.java
│   │   │   │   └── security/
│   │   │   │       ├── CurrentUser.java
│   │   │   │       ├── RateLimitFilter.java
│   │   │   │       ├── RestAccessDeniedHandler.java
│   │   │   │       ├── RestAuthenticationEntryPoint.java
│   │   │   │       ├── TenantContext.java
│   │   │   │       └── TenantFilter.java
│   │   │   ├── agency/
│   │   │   │   └── Agency.java               # entity only; no controller/service/repository
│   │   │   ├── auth/
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── AuthService.java
│   │   │   │   ├── JwtTokenProvider.java
│   │   │   │   └── dto/
│   │   │   │       ├── AuthResponse.java
│   │   │   │       ├── LoginRequest.java
│   │   │   │       └── RefreshRequest.java
│   │   │   ├── user/
│   │   │   │   ├── Role.java                 # entity + repo only; no user CRUD API yet
│   │   │   │   ├── User.java
│   │   │   │   └── UserRepository.java
│   │   │   ├── worker/
│   │   │   │   ├── Gender.java
│   │   │   │   ├── Worker.java
│   │   │   │   ├── WorkerController.java
│   │   │   │   ├── WorkerMapper.java
│   │   │   │   ├── WorkerRepository.java
│   │   │   │   ├── WorkerService.java        # CRUD + importCsv()
│   │   │   │   ├── WorkerStatus.java
│   │   │   │   └── dto/
│   │   │   │       ├── CreateWorkerRequest.java
│   │   │   │       ├── UpdateWorkerRequest.java
│   │   │   │       ├── WorkerImportResult.java
│   │   │   │       ├── WorkerResponse.java
│   │   │   │       └── WorkerSummary.java
│   │   │   ├── property/
│   │   │   │   ├── Property.java
│   │   │   │   ├── PropertyController.java
│   │   │   │   ├── PropertyMapper.java
│   │   │   │   ├── PropertyRepository.java
│   │   │   │   ├── PropertyService.java
│   │   │   │   ├── PropertyStatus.java
│   │   │   │   └── dto/
│   │   │   │       ├── CreatePropertyRequest.java
│   │   │   │       ├── PropertyResponse.java
│   │   │   │       └── UpdatePropertyRequest.java
│   │   │   ├── room/                          # top-level module, not nested under property/
│   │   │   │   ├── GenderRule.java
│   │   │   │   ├── Room.java
│   │   │   │   ├── RoomController.java
│   │   │   │   ├── RoomMapper.java
│   │   │   │   ├── RoomRepository.java
│   │   │   │   ├── RoomService.java
│   │   │   │   ├── RoomStatus.java
│   │   │   │   └── dto/
│   │   │   │       ├── CreateRoomRequest.java
│   │   │   │       ├── RoomResponse.java
│   │   │   │       └── UpdateRoomRequest.java
│   │   │   ├── stay/
│   │   │   │   ├── Stay.java
│   │   │   │   ├── StayController.java
│   │   │   │   ├── StayMapper.java
│   │   │   │   ├── StayRepository.java
│   │   │   │   ├── StayScheduler.java        # nightly PLANNED -> EXPECTED_TODAY sweep
│   │   │   │   ├── StayService.java          # lifecycle + bulkAssign()/bulkCheckout()
│   │   │   │   ├── StayStatus.java           # holds the transition table
│   │   │   │   ├── constraint/
│   │   │   │   │   ├── ConstraintContext.java
│   │   │   │   │   ├── ConstraintEngine.java
│   │   │   │   │   ├── ConstraintResult.java
│   │   │   │   │   ├── HardViolation.java
│   │   │   │   │   ├── SoftViolation.java
│   │   │   │   │   ├── StayConstraint.java
│   │   │   │   │   ├── Violation.java        # sealed, permits Hard/SoftViolation
│   │   │   │   │   └── impl/
│   │   │   │   │       ├── BlockedRoomConstraint.java
│   │   │   │   │       ├── CapacityConstraint.java
│   │   │   │   │       ├── DoubleBookingConstraint.java
│   │   │   │   │       └── GenderConstraint.java
│   │   │   │   └── dto/
│   │   │   │       ├── BulkAssignRequest.java
│   │   │   │       ├── BulkAssignResult.java
│   │   │   │       ├── BulkCheckoutRequest.java
│   │   │   │       ├── BulkCheckoutResult.java
│   │   │   │       ├── CheckInRequest.java
│   │   │   │       ├── CheckOutRequest.java
│   │   │   │       ├── CreateStayRequest.java
│   │   │   │       ├── MoveRequest.java
│   │   │   │       ├── NoShowRequest.java
│   │   │   │       ├── StayResponse.java
│   │   │   │       ├── StaySummary.java
│   │   │   │       └── UpdateStayRequest.java
│   │   │   ├── occupancy/
│   │   │   │   ├── ExportService.java
│   │   │   │   ├── OccupancyController.java
│   │   │   │   ├── OccupancyService.java     # occupancy, exceptions, inspection roster/report
│   │   │   │   └── dto/
│   │   │   │       ├── InspectionDiscrepancyResponse.java
│   │   │   │       ├── InspectionReportRequest.java
│   │   │   │       ├── InspectionRoomEntry.java
│   │   │   │       ├── OccupancyExceptionResponse.java
│   │   │   │       ├── OccupantSummary.java
│   │   │   │       ├── RoomActualOccupancy.java
│   │   │   │       ├── RoomDiscrepancy.java
│   │   │   │       ├── RoomOccupancyResponse.java
│   │   │   │       └── WorkerDiscrepancy.java
│   │   │   └── audit/
│   │   │       ├── AuditAction.java
│   │   │       ├── AuditController.java
│   │   │       ├── AuditEntityType.java
│   │   │       ├── AuditEvent.java
│   │   │       ├── AuditMapper.java
│   │   │       ├── AuditRepository.java
│   │   │       ├── AuditService.java
│   │   │       ├── JsonbConverter.java
│   │   │       └── dto/
│   │   │           └── AuditEventResponse.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       ├── logback-spring.xml            # MDC rid/uid/aid; JSON-ish lines under prod
│   │       ├── db/migration/
│   │       │   ├── V1__create_agencies.sql
│   │       │   ├── V2__create_users.sql
│   │       │   ├── V3__create_workers.sql
│   │       │   ├── V4__create_properties_rooms.sql
│   │       │   ├── V5__create_stays.sql
│   │       │   ├── V6__create_audit_events.sql
│   │       │   ├── V7__add_stay_no_show_reason.sql
│   │       │   └── V8__unique_user_email.sql
│   │       └── i18n/
│   │           ├── messages.properties
│   │           ├── messages_pl.properties
│   │           ├── messages_en.properties
│   │           ├── messages_de.properties
│   │           ├── messages_uk.properties
│   │           └── messages_ru.properties
│   └── test/
│       └── java/com/beduno/
│           ├── BedunoApplicationSmokeTest.java
│           ├── IntegrationTestBase.java      # shared Testcontainers PostgreSQL 16
│           ├── TestBuilders.java
│           ├── auth/
│           │   ├── AuthIntegrationTest.java
│           │   └── JwtTokenProviderTest.java
│           ├── property/
│           │   ├── DeletionGuardIntegrationTest.java
│           │   └── PropertyIntegrationTest.java
│           ├── room/
│           │   └── RoomIntegrationTest.java
│           ├── stay/
│           │   ├── BulkOperationsIntegrationTest.java
│           │   ├── OperationalWorkflowIntegrationTest.java
│           │   ├── StayGuardIntegrationTest.java
│           │   ├── StayIntegrationTest.java
│           │   └── constraint/
│           │       └── ConstraintEngineTest.java
│           └── worker/
│               └── WorkerIntegrationTest.java
```

## Database Schema

### ERD (simplified)

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│  Agency  │────<│   User   │     │  Worker  │>─── agency_id
└──────────┘     └──────────┘     └──────────┘
     │                                  │
     │           ┌──────────┐           │ worker_id
     ├──────────<│ Property │           │
     │           └──────────┘           │
     │                │ property_id     │
     │           ┌──────────┐     ┌──────────┐
     ├──────────<│   Room   │<────│   Stay   │>──── property_id
     │           └──────────┘     └──────────┘
     │
     │           ┌─────────────┐
     └──────────<│ AuditEvent  │   (polymorphic: entity_type + entity_id,
                 └─────────────┘    no FK to the audited row)
```

Rooms carry both `property_id` and their own `agency_id`. Stays carry `agency_id`,
`worker_id`, `property_id`, and `room_id`. Audit events reference the audited entity
loosely by `entity_type` + `entity_id` — there is no foreign key back to it.

### Key Tables

```sql
-- All tables have: id UUID PK (gen_random_uuid), created_at, updated_at (TIMESTAMPTZ).
-- Every table except agencies carries agency_id (tenant discriminator, FK -> agencies).

agencies (
    id, name, status, settings JSONB NOT NULL DEFAULT '{}'
)

users (
    id, agency_id, email, password_hash, first_name, last_name,
    role, language DEFAULT 'PL', assigned_property_ids UUID[] NOT NULL DEFAULT '{}',
    status DEFAULT 'ACTIVE', last_login_at,
    UNIQUE (agency_id, email),  -- uq_users_email_agency
    UNIQUE (email)              -- uq_users_email (V8); login resolves by email alone
)

workers (
    id, agency_id, internal_id, first_name, last_name,
    gender NOT NULL, nationality, phone, email, date_of_birth,
    tags TEXT[] NOT NULL DEFAULT '{}', notes,
    status DEFAULT 'ACTIVE', deleted_at,      -- soft delete: status=DELETED + deleted_at
    UNIQUE (agency_id, internal_id)           -- uq_workers_agency_internal_id
)

properties (
    id, agency_id, name, address, city,
    status DEFAULT 'ACTIVE', notes
)   -- no `type`, no property-level gender rule

rooms (
    id, agency_id, property_id, name, floor,
    capacity INT NOT NULL DEFAULT 1,
    blocked_spots INT NOT NULL DEFAULT 0,
    gender_rule VARCHAR(20) NOT NULL DEFAULT 'ANY',   -- ANY | MALE_ONLY | FEMALE_ONLY
    status DEFAULT 'ACTIVE', notes,
    UNIQUE (property_id, name),                        -- uq_rooms_property_name
    CHECK (capacity > 0),                              -- chk_rooms_capacity
    CHECK (blocked_spots >= 0 AND blocked_spots <= capacity)  -- chk_rooms_blocked_spots
)

stays (
    id, agency_id, worker_id, property_id, room_id,
    date_from DATE NOT NULL, date_to DATE,
    status DEFAULT 'PLANNED',
    override_reason,          -- set when a soft constraint was overridden
    confirmed_by_user_id,     -- FK -> users, set at check-in
    no_show_reason VARCHAR(100),   -- V7; previously overwrote notes
    notes,
    version BIGINT NOT NULL DEFAULT 0,   -- JPA @Version optimistic lock
    CHECK (date_to IS NULL OR date_to > date_from)   -- chk_stays_dates
)   -- NOT soft-deleted: cancelling sets status = CANCELLED

audit_events (
    id, agency_id, entity_type, entity_id,
    action, actor_user_id,
    previous_state JSONB, new_state JSONB,
    reason,
    created_at, updated_at
)
```

Supporting indexes exist per tenant access path — notably `idx_stays_occupancy`
`(room_id, status, date_from, date_to)` for occupancy/capacity queries and
`idx_audit_events_created_at (agency_id, created_at DESC)` for the audit feed.

Foreign keys from `rooms.property_id`, `stays.property_id` and `stays.room_id` are
non-cascading, so referenced rows cannot be deleted — see "Deletion guards" below.

## Multi-Tenancy Strategy

**Approach: Shared database, shared schema, `agency_id` discriminator column.**

- Every entity except `Agency` has an `agency_id` column
- `TenantFilter` extracts `agencyId` from the JWT and sets `TenantContext` (a `ThreadLocal`),
  clearing it — along with the MDC — in a `finally` block
- Services read the tenant via `TenantContext.requireAgencyId()` and pass it explicitly into
  repository methods. There is **no** Hibernate `@Filter` and no PostgreSQL RLS: isolation is
  by convention, enforced in each query, not by the framework.

**Known exceptions to "every query filters by agency_id":**

| Location | Why |
|----------|-----|
| `StayRepository.findPlannedArrivingOn` | Deliberately cross-tenant — backs the nightly scheduler sweep, which runs outside any request and so has no tenant context |
| `OccupancyService.loadWorkers` | Uses `workerRepository.findAllById(...)` with no tenant predicate (its `agencyId` parameter is unused). Safe today only because the IDs come from stays already filtered by tenant |
| `UserRepository.findByEmail` | Login happens before a tenant is known, so the lookup is by email across all agencies |

Row-Level Security remains a candidate defence-in-depth layer; it is not implemented.
If tenant isolation requirements grow, migrate to schema-per-tenant.

## Authentication & Authorization

### Auth Flow
1. User logs in with email + password -> receives JWT access + refresh tokens
2. The access token carries `sub` (userId), `agencyId`, `role`, `properties`, `lang`, `exp`
3. Every request passes through `RateLimitFilter` then `TenantFilter`; the latter validates the
   token, sets `TenantContext`, populates the MDC (`requestId`, `userId`, `agencyId`), and
   installs a `CurrentUser` principal with a `ROLE_<role>` authority
4. `@PreAuthorize` on controller methods enforces role checks
   (`AGENCY_ADMIN`, `AGENCY_PLANNER`, `PROPERTY_ADMIN`, `FRONT_DESK`)
5. **Per-property scoping is partial.** `CurrentUser.hasPropertyAccess(...)` is called in exactly
   two places: `PropertyController.update` and `RoomController.checkPropertyAccess`
   (room create/update). Stays, occupancy, exceptions, inspection, and CSV exports are
   **agency-wide** — any authenticated user with the right role may act on any property in their
   agency. The service layer performs no property scoping at all.

### Error semantics
- Unauthenticated request to a protected endpoint -> **401** with the standard `ErrorResponse`
  envelope, emitted by `RestAuthenticationEntryPoint`. (Without it Spring Security's default
  `Http403ForbiddenEntryPoint` would return a bodyless 403 that clients cannot tell apart from a
  genuine role denial.)
- Authenticated but insufficient role -> **403** (`RestAccessDeniedHandler` at the filter chain,
  `GlobalExceptionHandler` for method-security and `ForbiddenException`)
- Bad credentials / invalid or expired refresh token -> **401** (`UnauthorizedException`);
  a refresh token whose subject no longer exists is reported as 401, not 404, so account
  existence is not leaked
- More than 10 requests/minute per client IP to `/api/v1/auth/**` -> **429**
- Hard or un-overridden soft constraint violation -> **422** with a `details[]` list
- Business conflict (bad status transition, deletion guard, duplicate name) -> **409**

### JWT Structure

Access token:
```json
{
  "sub": "user-uuid",
  "agencyId": "agency-uuid",
  "role": "FRONT_DESK",
  "properties": ["prop-uuid-1", "prop-uuid-2"],
  "lang": "pl",
  "exp": 1234567890
}
```

Refresh tokens are signed with the same HS256 key but carry only `sub`, `iat`, `exp`, and
`type: "refresh"`. Access tokens live 1 hour, refresh tokens 7 days (`beduno.jwt.*`).

## Constraint Engine

`ConstraintEngine` collects every `StayConstraint` bean and runs all of them; `StayService`
invokes it on stay create, update, check-in, move, and each item of a bulk assign.

```
StayService
  └─> ConstraintEngine.evaluate(ConstraintContext)
        ├─> CapacityConstraint       (hard)  room full / overlapping stays >= available spots
        ├─> DoubleBookingConstraint  (hard)  worker already has an overlapping active stay
        ├─> BlockedRoomConstraint    (hard)  room BLOCKED or property INACTIVE
        └─> GenderConstraint         (soft)  room gender_rule vs worker gender

  Returns: ConstraintResult { hardViolations[], softViolations[] }
           isAllowed() == hardViolations.isEmpty()
```

`ConstraintContext` carries `worker`, `room`, `property`, `dateFrom`, `dateTo`, and an optional
`excludeStayId` so a stay being edited does not conflict with itself. `Violation` is a sealed
interface permitting `HardViolation` and `SoftViolation`; both carry a `type`, an i18n
`message` code, and a `params` map.

- **Hard violation**: rejected with 422, always
- **Soft violation**: rejected with 422 unless the caller supplies `overrideReason`. There is no
  separate override *permission* — any role allowed to perform the operation may override.

### Stay lifecycle

`StayStatus` owns the transition table:

```
PLANNED        -> EXPECTED_TODAY | CANCELLED
EXPECTED_TODAY -> CHECKED_IN | NO_SHOW | CANCELLED
CHECKED_IN     -> CHECKED_OUT
CHECKED_OUT / CANCELLED / NO_SHOW -> (terminal)
```

`StayScheduler` runs `beduno.scheduler.arrival-transition-cron` (default 06:00 daily) and
flips every `PLANNED` stay whose `date_from` is today to `EXPECTED_TODAY`, across all tenants.

A **move** is modelled as check-out + new stay: the current stay goes to `CHECKED_OUT` and a
replacement `CHECKED_IN` stay is created in the target room for `[today, original date_to)`.
Because `chk_stays_dates` requires `date_to > date_from`, a move is refused with 409 on the
stay's final day.

### Deletion guards

`rooms`/`stays` foreign keys are non-cascading, so the service layer refuses conflicting
deletes with 409 rather than letting the database fail with a 500:

- Property delete -> 409 if it still has rooms (`error.property.has_rooms`) or any stay
  references it (`error.property.has_stays`); otherwise it is a **hard** delete
- Room delete -> 409 if any stay references it (`error.room.has_stays`); otherwise hard delete
- Worker delete -> **soft**: `status = DELETED` plus `deleted_at`; every worker query excludes
  `DELETED`
- Stay "delete" is a cancel: `status = CANCELLED`

## Audit Trail

State changes on Stay, Worker, Room and Property produce an `AuditEvent`
(`AuditEntityType` = STAY | WORKER | ROOM | PROPERTY):

- Written by **explicit `auditService.log(...)` calls** at ~20 call sites in `StayService`,
  `WorkerService`, `RoomService` and `PropertyService`. There is no `AuditAspect` and no AOP
  anywhere in the codebase — a new write path must remember to log for itself.
- Actions recorded: `CREATED`, `UPDATED`, `DELETED`, `CHECKED_IN`, `CHECKED_OUT`, `NO_SHOW`,
  `CANCELLED`, `MOVED`, `BULK_ASSIGNED`, `BULK_CHECKED_OUT`
- Stores hand-built before/after snapshots as JSONB (via `JsonbConverter`). The snapshots are
  partial summaries of the salient fields, not full entity dumps, so they support review rather
  than byte-exact reconstruction.
- Never modified by application code — but immutability is **not enforced**: `audit_events`
  inherits `updated_at` from `BaseEntity`, and there is no trigger or `REVOKE` preventing
  UPDATE/DELETE at the database
- Queryable via `GET /api/v1/audit` by entity type, entity ID, actor, and date range
  (AGENCY_ADMIN and AGENCY_PLANNER only)

## Export Service

- CSV export of occupancy, arrivals, and exception reports, served from
  `/api/v1/properties/{propertyId}/{occupancy|arrivals|exceptions}/export`
- `language` query parameter (EN default; PL, DE, RU, UA supported) drives localized column
  headers and status labels through the `MessageSource`
- Responses are `text/csv;charset=UTF-8` attachments with a date-stamped filename
- PDF export is not implemented

## API Design Principles

- RESTful resource-based URLs; rooms, occupancy and exports are nested under
  `/api/v1/properties/{propertyId}/...`
- Consistent error envelope `ErrorResponse { error, message, details?, timestamp, traceId? }`,
  where `message` is an i18n message code, never a hardcoded English string
- Pagination via Spring `Pageable` (`page`, `size`, `sort`), wrapped in `PageResponse`
- Filtering via query params (e.g. `?status=CHECKED_IN&propertyId=...`)
- All timestamps in UTC (ISO 8601); all IDs are UUIDs
- API versioning via URL prefix: `/api/v1/`
- OpenAPI served at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`; both are `permitAll`

## Deployment

### Local Development
- Docker Compose: PostgreSQL 16 (`docker/docker-compose.yml`, database/user/password all `beduno`)
- `dev` profile enables SQL logging and DEBUG logging for `com.beduno`
- Flyway runs migrations on startup; Hibernate is `ddl-auto: validate`

### Production
One `t4g.small` EC2 instance in `eu-central-1` running three containers under docker compose
(`deploy/docker-compose.prod.yml`), stopped when the API is not in use. Full runbook in
`README.md`; the shape and its cost trade-off are recorded in
`context/foundation/infrastructure.md`.

- Containerized Spring Boot app (multi-stage `docker/Dockerfile`, JRE 21 Alpine, non-root user,
  `-XX:MaxRAMPercentage=65` against the compose `mem_limit` — the remaining 35% is metaspace,
  code cache and thread stacks, which the percentage does not cover and the cgroup does), built
  for **linux/arm64** because
  the host is Graviton
- **PostgreSQL 16 runs as a container on the same box**, not as a managed service. Its data is a
  docker volume on the instance's root EBS volume, and `deploy/backup.sh` snapshots that volume —
  there is no replica and no point-in-time recovery
- `prod` profile reads `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD`, and
  `JWT_SECRET` must be supplied; all of them come from SSM Parameter Store via `deploy/boot.sh`,
  which writes a 0600 env file on every boot
- Caddy terminates TLS (Let's Encrypt, HTTP-01) and is the only container publishing ports;
  `server.forward-headers-strategy: native` is what makes `getRemoteAddr()` the real client
  behind it, which the login throttle and HSTS both depend on
- The first agency and administrator are created at startup from `BOOTSTRAP_*` on an empty
  database; there is no user-management API (Q6)
- `prod` logging emits one structured line per event including `rid`/`uid`/`aid` from the MDC
- Stateless backend (JWT) allows horizontal scaling, though this deployment is deliberately a
  single instance. The scheduler and the in-memory rate-limit buckets are per-instance and are
  not coordinated across replicas.
- Actuator exposes only `health` and `info`. `/actuator/health` is `permitAll` (used by the
  container `HEALTHCHECK`); `/actuator/info` is exposed but still requires authentication.
  No `metrics` endpoint is exposed.
