# Bedok Backend - Claude Code Guidelines

## Project

Bedok is a worker housing management system for temporary work agencies. Java 21 + Spring Boot 3.4 + PostgreSQL 16.

## Build & Test

```bash
./gradlew compileJava          # Compile
./gradlew test                 # All tests (requires Docker for Testcontainers)
./gradlew bootRun              # Run locally (needs PostgreSQL via docker/docker-compose.yml)
docker compose -f docker/docker-compose.yml up -d  # Start local PostgreSQL
```

## Project Structure

- Organized by domain module (`auth/`, `user/`, `agency/`, `worker/`, `property/`, `stay/`, `occupancy/`, `audit/`)
- Shared code in `common/` (exceptions, security, model)
- Configuration in `config/`
- Each module: Controller, Service, Repository, Entity, DTOs

## Key Conventions

- **Java 21 features**: records for DTOs, `var` for local variables, sealed interfaces where appropriate
- **Package naming**: `com.bedok.<module>`
- **DTOs**: Java records, separate request/response, MapStruct for mapping
- **Entities**: extend `BaseEntity` (UUID id, createdAt, updatedAt), include `agencyId` for tenant isolation
- **Exceptions**: extend `BusinessException`, use message codes (never hardcoded strings)
- **Tests**: JUnit 5 + AssertJ, Testcontainers PostgreSQL (no DB mocking), `should{Behavior}_when{Condition}` naming
- **Controller pattern**: thin, `@Valid` on request DTOs, return DTOs never entities, `@PreAuthorize` for roles
- **Service pattern**: `@Transactional`, call constraint engine before changes, call audit service after changes
- **Repository pattern**: all queries filter by `agencyId`, use `@Query` for custom queries

## Multi-Tenancy

Shared schema with `agency_id` discriminator. `TenantContext` (ThreadLocal) set by `TenantFilter` from JWT claims. Every repository query MUST filter by agencyId.

## Auth

JWT-based stateless auth. Tokens carry: userId, agencyId, role, assignedPropertyIds, lang. Roles: AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK.

## Database

- Flyway migrations in `src/main/resources/db/migration/`
- Never modify existing migrations
- Column naming: `snake_case`
- All timestamps in UTC

## Commit Convention

Prefix commit messages with `[claude-opus-4.6]` when AI-generated.
