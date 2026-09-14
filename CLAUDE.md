# Beduno Backend - Claude Code Guidelines

## Project

Beduno is a worker housing management system for temporary work agencies. Java 21 + Spring Boot 3.4 + PostgreSQL 16.

## Build & Test

```bash
./gradlew compileJava          # Compile
./gradlew test                 # All tests (requires Docker for Testcontainers)
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun   # Run locally (needs PostgreSQL via docker/docker-compose.yml).
                                              # Without the dev profile there is no JWT_SECRET default and startup fails.
docker compose -f docker/docker-compose.yml up -d  # Start local PostgreSQL
```

## Project Structure

- Organized by domain module (`auth/`, `user/`, `agency/`, `worker/`, `property/`, `room/`, `stay/`, `occupancy/`, `audit/`)
- Shared code in `common/` (exceptions, security, model)
- Configuration in `config/`
- Each module: Controller, Service, Repository, Entity, DTOs

## Key Conventions

- **Java 21 features**: records for DTOs, `var` for local variables, sealed interfaces where appropriate
- **Package naming**: `com.beduno.<module>`
- **DTOs**: Java records, separate request/response, MapStruct for mapping
- **Entities**: extend `BaseEntity` (UUID id, createdAt, updatedAt), include `agencyId` for tenant isolation
- **Exceptions**: extend `BusinessException`, use message codes (never hardcoded strings)
- **Tests**: JUnit 5 + AssertJ, Testcontainers PostgreSQL (no DB mocking), `should{Behavior}_when{Condition}` naming
- **Controller pattern**: thin, `@Valid` on request DTOs, return DTOs never entities, `@PreAuthorize` for roles
- **Service pattern**: `@Transactional`, call constraint engine before changes, call audit service after changes
- **Repository pattern**: all queries filter by `agencyId`, use `@Query` for custom queries

## Multi-Tenancy

Shared schema with `agency_id` discriminator. `TenantContext` (ThreadLocal) set by `TenantFilter` from JWT claims. Every repository query MUST filter by agencyId, with these sanctioned exceptions:

- `StayRepository.findPlannedArrivingOnOrBefore` — deliberately cross-tenant; it backs the scheduler sweep that transitions due PLANNED stays to EXPECTED_TODAY across all agencies, and runs on a thread that has no `TenantContext`.
- ~~`OccupancyService.loadWorkers`~~ — retired 2026-09-14. It used `findAllById`, safe only because the ids came from an agency-filtered stay query; it now uses `findAllByAgencyIdAndIdIn`, as does `ExportService.bedLabelById`. Whether an unscoped batch lookup is safe depends on where the caller's ids came from, which is not visible at the call site — use the scoped variant.
- `BootstrapRunner` — `userRepository.count()` and `AgencyRepository` (plain `JpaRepository`) run before any tenant exists, which is the point: the runner's whole question is whether the users table is empty. It runs once at startup, never on a request, and there is no `TenantContext` to filter by.
- `UserRepository.findByEmail` / `existsByEmail` / `existsByEmailAndIdNot` and `RoomRepository.existsByPropertyIdAndRoomNumber` / `BedRepository.existsByRoomIdAndLabel` — uniqueness checks against globally or parent-scoped unique constraints. Login has no tenant yet when it resolves an email, and `uq_users_email` is global by design (V8); the room and bed checks key on a parent id that is itself agency-scoped.

Any new cross-tenant query needs the same explicit justification, recorded here and in AGENTS.md.

## Auth

JWT-based stateless auth. Access tokens carry: userId (subject), agencyId, role, assignedPropertyIds ("properties" claim), lang. Refresh tokens carry only userId — request a fresh access token via `/api/v1/auth/refresh` to get the other claims again. Roles: AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK.

## Database

- Flyway migrations in `src/main/resources/db/migration/`
- Never modify existing migrations
- Column naming: `snake_case`
- All timestamps in UTC

## Commit Convention

Prefix AI-generated commit messages with the model that actually wrote the code, in brackets — read the active model from the session environment rather than copying a value from here (e.g. `[claude-opus-5] Add constraint engine`). Never add a `Co-Authored-By` trailer.
