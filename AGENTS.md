# Repository Guidelines

Beduno is a worker-housing management API for temporary work agencies: Java 21, Spring Boot 3.4, PostgreSQL 16, Flyway, JWT. See @README.md for endpoints and environment variables, @CLAUDE.md and @docs/coding-guidelines.md for depth.

## Hard Rules

- Every repository query filters by `agencyId`. Three sanctioned exceptions exist — `StayRepository.findPlannedArrivingOn` (the nightly cross-agency scheduler sweep), `OccupancyService.loadWorkers`, and `BootstrapRunner` (runs at startup before any tenant exists, to ask whether the users table is empty); any new cross-tenant query needs the same explicit justification, recorded in @CLAUDE.md and here.
- Never edit an existing Flyway migration. Add `src/main/resources/db/migration/V{n}__{description}.sql`.
- Never hardcode user-facing text. Throw `BusinessException` subclasses carrying a message code, and define that code in all six bundles under `src/main/resources/i18n/` — `MessageBundleTest` fails on a missing key, a blank value, or a code referenced from Java but undefined.
- Never log PII, passwords, or tokens.
- Constructor injection only, via Lombok `@RequiredArgsConstructor`. No `@Autowired` fields; no `@Data` on entities.

## Project Structure & Module Organization

One package per domain module under `src/main/java/com/beduno/` (`auth`, `user`, `agency`, `worker`, `property`, `room`, `stay`, `occupancy`, `audit`), each holding its Controller, Service, Repository, Entity and a `dto/` subpackage. Shared code lives in `common/` (`exception`, `security`, `model`); Spring wiring in `config/`. Deeper map: @docs/architecture.md.

## Build, Test, and Development Commands

- `docker compose -f docker/docker-compose.yml up -d` — local PostgreSQL.
- `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun` — run the API on port 8080; Swagger UI at `/swagger-ui.html`. The profile matters: `JWT_SECRET` has no default outside `dev`, and startup now fails without it rather than booting with an unusable key.
- `./gradlew build` — compile, Checkstyle, and the full test suite. CI (@.github/workflows/ci.yml) runs the same command on pull requests and on pushes to `main` — a branch pushed with no PR open gets no run at all, so run it yourself before each commit.
- `./gradlew test --tests 'com.beduno.stay.constraint.ConstraintEngineTest'` — one class.
- `deploy/publish.sh`, `deploy/instance.sh`, `deploy/backup.sh` — deploy, lifecycle and snapshots for the single production instance. Runbook in @README.md; never point these at anything but the `beduno-api` instance, whose volume holds the only copy of the database.

## Coding Style & Naming Conventions

Four-space indent, no tabs. Records for DTOs (`CreateWorkerRequest`, `WorkerResponse`), MapStruct for mapping, entities extend `BaseEntity` and carry `agencyId`. Controllers stay thin: `@Valid` on request bodies, `@PreAuthorize` for roles, return DTOs never entities. Services are `@Transactional`, call the constraint engine before a change and the audit service after it. Checkstyle 10.21.4 (@config/checkstyle/checkstyle.xml) fails the build on naming, brace, import and whitespace violations; it checks neither line length nor Javadoc.

## Testing Guidelines

JUnit 5 with AssertJ. Integration tests extend `IntegrationTestBase` (Testcontainers PostgreSQL — Docker required) and sit in `src/test/java/com/beduno/<module>/`; never mock the database. Name tests `should{Behavior}_when{Condition}`. Every module's suite must include a cross-agency isolation case.

## Commit & Pull Request Guidelines

AI-generated commits use `[<model>] <imperative summary>` — for example `[claude-opus-5] Guard destructive stay operations` — naming the model that wrote the code. Never add a `Co-Authored-By` trailer. Keep commits small and green: `./gradlew build` passes before each one.
