# Beduno Backend - Architecture

## Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Language | Java 21 | LTS, virtual threads, pattern matching, records |
| Framework | Spring Boot 3.4 | Mature ecosystem, security, i18n, actuator |
| Build | Gradle (Kotlin DSL) | Faster builds, better dependency management |
| Database | PostgreSQL 16 | JSONB for flexible fields, row-level security, mature |
| Migrations | Flyway | Version-controlled schema migrations |
| Auth | Spring Security + JWT | Stateless API auth, role-based access |
| API | REST (JSON) | Simple, well-understood, sufficient for MVP |
| Validation | Jakarta Validation | Declarative constraint validation |
| Mapping | MapStruct | Compile-time DTO mapping, no reflection overhead |
| Testing | JUnit 5 + Testcontainers | Real DB in tests, no mocking the database |
| API Docs | SpringDoc OpenAPI | Auto-generated Swagger UI |
| Containerization | Docker + Docker Compose | Local dev and deployment |

## Project Structure

```
beduno-be/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── wrapper/
├── docker/
│   ├── Dockerfile
│   └── docker-compose.yml
├── docs/
├── src/
│   ├── main/
│   │   ├── java/com/beduno/
│   │   │   ├── BedunoApplication.java
│   │   │   ├── config/
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   ├── JwtConfig.java
│   │   │   │   ├── AuditConfig.java
│   │   │   │   └── WebConfig.java
│   │   │   ├── common/
│   │   │   │   ├── exception/
│   │   │   │   │   ├── BusinessException.java
│   │   │   │   │   ├── NotFoundException.java
│   │   │   │   │   ├── ConflictException.java
│   │   │   │   │   ├── ConstraintViolationException.java
│   │   │   │   │   └── GlobalExceptionHandler.java
│   │   │   │   ├── model/
│   │   │   │   │   ├── BaseEntity.java
│   │   │   │   │   └── PageResponse.java
│   │   │   │   └── security/
│   │   │   │       ├── TenantContext.java
│   │   │   │       ├── TenantFilter.java
│   │   │   │       └── CurrentUser.java
│   │   │   ├── auth/
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── AuthService.java
│   │   │   │   ├── JwtTokenProvider.java
│   │   │   │   └── dto/
│   │   │   ├── user/
│   │   │   │   ├── UserController.java
│   │   │   │   ├── UserService.java
│   │   │   │   ├── UserRepository.java
│   │   │   │   ├── User.java
│   │   │   │   ├── Role.java
│   │   │   │   └── dto/
│   │   │   ├── agency/
│   │   │   │   ├── AgencyController.java
│   │   │   │   ├── AgencyService.java
│   │   │   │   ├── AgencyRepository.java
│   │   │   │   ├── Agency.java
│   │   │   │   └── dto/
│   │   │   ├── worker/
│   │   │   │   ├── WorkerController.java
│   │   │   │   ├── WorkerService.java
│   │   │   │   ├── WorkerRepository.java
│   │   │   │   ├── Worker.java
│   │   │   │   ├── WorkerImportService.java
│   │   │   │   └── dto/
│   │   │   ├── property/
│   │   │   │   ├── PropertyController.java
│   │   │   │   ├── PropertyService.java
│   │   │   │   ├── PropertyRepository.java
│   │   │   │   ├── Property.java
│   │   │   │   ├── Room.java
│   │   │   │   ├── RoomRepository.java
│   │   │   │   └── dto/
│   │   │   ├── stay/
│   │   │   │   ├── StayController.java
│   │   │   │   ├── StayService.java
│   │   │   │   ├── StayRepository.java
│   │   │   │   ├── Stay.java
│   │   │   │   ├── StayStatus.java
│   │   │   │   ├── BulkOperationService.java
│   │   │   │   ├── constraint/
│   │   │   │   │   ├── ConstraintEngine.java
│   │   │   │   │   ├── ConstraintResult.java
│   │   │   │   │   ├── CapacityConstraint.java
│   │   │   │   │   ├── DoubleBookingConstraint.java
│   │   │   │   │   ├── GenderConstraint.java
│   │   │   │   │   └── BlockedRoomConstraint.java
│   │   │   │   └── dto/
│   │   │   ├── occupancy/
│   │   │   │   ├── OccupancyController.java
│   │   │   │   ├── OccupancyService.java
│   │   │   │   ├── InspectionService.java
│   │   │   │   ├── ExportService.java
│   │   │   │   └── dto/
│   │   │   └── audit/
│   │   │       ├── AuditController.java
│   │   │       ├── AuditService.java
│   │   │       ├── AuditRepository.java
│   │   │       ├── AuditEvent.java
│   │   │       └── dto/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       ├── db/migration/
│   │       │   ├── V1__create_agencies.sql
│   │       │   ├── V2__create_users.sql
│   │       │   ├── V3__create_workers.sql
│   │       │   ├── V4__create_properties_rooms.sql
│   │       │   ├── V5__create_stays.sql
│   │       │   └── V6__create_audit_events.sql
│   │       └── i18n/
│   │           ├── messages.properties
│   │           ├── messages_pl.properties
│   │           ├── messages_en.properties
│   │           ├── messages_de.properties
│   │           ├── messages_ua.properties
│   │           └── messages_ru.properties
│   └── test/
│       └── java/com/beduno/
│           ├── stay/
│           │   ├── StayServiceTest.java
│           │   ├── StayControllerTest.java
│           │   └── constraint/
│           │       └── ConstraintEngineTest.java
│           ├── occupancy/
│           │   └── OccupancyServiceTest.java
│           └── ...
```

## Database Schema

### ERD (simplified)

```
┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│  Agency   │────<│   User   │     │  Worker  │>────│  Agency  │
└──────────┘     └──────────┘     └──────────┘     └──────────┘
     │                                  │
     │           ┌──────────┐           │
     └──────────<│ Property │           │
                 └──────────┘           │
                      │                 │
                 ┌──────────┐     ┌──────────┐
                 │   Room   │<────│   Stay   │
                 └──────────┘     └──────────┘
                                       │
                                 ┌─────────────┐
                                 │ AuditEvent  │
                                 └─────────────┘
```

### Key Tables

```sql
-- All tables include: id (UUID), created_at, updated_at, agency_id (tenant)

agencies (
    id, name, status, settings JSONB
)

users (
    id, agency_id, email, password_hash, first_name, last_name,
    role, language, assigned_property_ids UUID[],
    status, last_login_at
)

workers (
    id, agency_id, internal_id, first_name, last_name,
    phone, gender, tags TEXT[], notes,
    status, deleted_at
)

properties (
    id, agency_id, name, address, type,
    gender_rule, status, notes
)

rooms (
    id, property_id, room_number, capacity,
    gender_rule, blocked_spots, floor,
    status, notes
)

stays (
    id, agency_id, worker_id, property_id, room_id,
    date_from, date_to,
    status, reason_tag,
    created_by_user_id, confirmed_by_user_id,
    override_reason, -- if soft constraint was overridden
    version, deleted_at
)

audit_events (
    id, agency_id, entity_type, entity_id,
    action, performed_by_user_id,
    previous_state JSONB, new_state JSONB,
    reason_tag, notes,
    created_at
)
```

## Multi-Tenancy Strategy

**Approach: Shared database, shared schema, `agency_id` discriminator column.**

- Every entity has an `agency_id` column
- `TenantFilter` extracts the tenant from the JWT and sets `TenantContext`
- All repository queries automatically filter by `agency_id` (Hibernate filter or manual)
- Database-level: consider PostgreSQL Row-Level Security (RLS) as a defense-in-depth layer

This is the simplest approach for MVP. If tenant isolation requirements grow, migrate to schema-per-tenant.

## Authentication & Authorization

### Auth Flow
1. User logs in with email + password -> receives JWT (access + refresh tokens)
2. JWT contains: `userId`, `agencyId`, `role`, `assignedPropertyIds`
3. Every request passes through `TenantFilter` which sets `TenantContext`
4. Method-level security annotations enforce role checks
5. Service layer enforces property-scoping for property-scoped roles

### JWT Structure
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

## Constraint Engine

The constraint engine is invoked before any stay creation, check-in, or room move.

```
StayService
  └─> ConstraintEngine.evaluate(action, context)
        ├─> CapacityConstraint      (hard)
        ├─> DoubleBookingConstraint  (hard)
        ├─> BlockedRoomConstraint    (hard)
        └─> GenderConstraint         (soft)
        
  Returns: ConstraintResult { allowed, hardViolations[], softViolations[] }
```

- **Hard violation**: operation is rejected
- **Soft violation**: operation proceeds only if caller provides `overrideReason` and has override permission

## Audit Trail

Every state change on core entities (Stay, Worker, Room, Property) produces an `AuditEvent`:

- Captured via an `AuditAspect` (AOP) or explicit `AuditService.log()` calls in services
- Stores before/after state as JSONB for full reconstructibility
- Never modified or deleted
- Queryable by entity, actor, date range, action type

## Export Service

- CSV export of occupancy reports, arrival lists, exception reports
- Language parameter controls column headers and status labels
- PDF export as a future enhancement (not MVP-critical)

## API Design Principles

- RESTful resource-based URLs
- Consistent error response format with message codes (not hardcoded strings)
- Pagination via `page` + `size` query params, response wraps in `PageResponse`
- Filtering via query params (e.g., `?status=checked_in&propertyId=xxx`)
- All timestamps in UTC (ISO 8601)
- All IDs are UUIDs
- API versioning via URL prefix: `/api/v1/`

## Deployment

### Local Development
- Docker Compose: PostgreSQL
- Spring Boot dev profile with hot-reload
- Flyway runs migrations on startup

### Production (future)
- Containerized Spring Boot app
- PostgreSQL managed service
- Stateless backend (JWT) allows horizontal scaling
- Health checks via Spring Actuator
