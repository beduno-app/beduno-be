# Beduno Backend - Testing Guidelines

> Reconciled against the implementation on 2026-08-09.

## Testing Philosophy

- Tests verify behavior, not implementation
- Use real databases (Testcontainers), not mocks, for repository and integration tests
- Test the constraint engine thoroughly - it is the core business logic
- Every bug fix gets a regression test

## Test Pyramid

There is no separate E2E tier in this codebase - just two layers in practice. Full-lifecycle coverage (arrivals, check-in, inspection) lives as `@Nested` classes inside the integration tests (e.g. `OperationalWorkflowIntegrationTest.FullLifecycle`), not as a distinct test type or dependency. There is no RestAssured dependency anywhere in `build.gradle.kts`; every HTTP-level test uses Spring's `TestRestTemplate`.

```
       ╱Integration ╲        Medium: API + DB together, including full-workflow scenarios
      ╱───────────────╲
     ╱    Unit Tests    ╲     Many: services, constraints, logic
    ╱─────────────────────╲
```

| Layer | Scope | Tools | Speed |
|-------|-------|-------|-------|
| Unit | Service logic, constraint engine, mappers | JUnit 5, Mockito (for external deps only) | Fast |
| Integration | Controller + Service + DB, including full-workflow scenarios as `@Nested` classes | Spring Boot Test, `TestRestTemplate`, Testcontainers (PostgreSQL) | Medium |

## Unit Tests

### What to Unit Test
- **Constraint engine**: every constraint type, edge cases, combinations
- **Stay status transitions**: valid and invalid transitions
- **Service logic**: business rules, validation, conflict detection
- **MapStruct mappers**: correct field mapping

### Conventions
- Test class: `{ClassName}Test.java` in the same package under `src/test`
- Test method naming: `should{ExpectedBehavior}_when{Condition}`. The `_when{Condition}` clause may be omitted when a `@Nested` class already supplies the condition (e.g. a `@Nested class CapacityConstraint` containing `shouldRejectCheckIn()`) - don't invent a redundant condition just to satisfy the pattern. In the current suite, roughly 48 of ~110 `should*` test methods rely on this exemption
- One assertion per test (logical assertion - multiple `assertThat` calls on the same result are fine)
- Use `@Nested` classes to group related scenarios

```java
class ConstraintEngineTest {

    @Nested
    class CapacityConstraint {

        @Test
        void shouldRejectCheckIn_whenRoomAtFullCapacity() {
            // given
            var room = aRoom().withCapacity(4).withCurrentOccupancy(4).build();
            var stay = aStay().inRoom(room).build();

            // when
            var result = engine.evaluate(Action.CHECK_IN, stay);

            // then
            assertThat(result.isAllowed()).isFalse();
            assertThat(result.hardViolations()).hasSize(1);
            assertThat(result.hardViolations().getFirst().type())
                .isEqualTo(ViolationType.CAPACITY_EXCEEDED);
        }

        @Test
        void shouldAllowCheckIn_whenRoomHasAvailableSpots() {
            // given
            var room = aRoom().withCapacity(4).withCurrentOccupancy(3).build();
            var stay = aStay().inRoom(room).build();

            // when
            var result = engine.evaluate(Action.CHECK_IN, stay);

            // then
            assertThat(result.isAllowed()).isTrue();
        }
    }
}
```

### Test Builders
- Use builder pattern for test data: `aWorker()`, `aRoom()`, `aStay()`
- Place builders in a shared `TestBuilders` class or per-domain `{Entity}TestBuilder`
- Builders provide sensible defaults; tests override only what matters

```java
public class TestBuilders {

    public static WorkerBuilder aWorker() {
        return WorkerBuilder.builder()
            .id(UUID.randomUUID())
            .agencyId(DEFAULT_AGENCY_ID)
            .internalId("W-" + ThreadLocalRandom.current().nextInt(10000))
            .firstName("Jan")
            .lastName("Kowalski")
            .gender(Gender.MALE)
            .status(WorkerStatus.ACTIVE);
    }
}
```

## Integration Tests

### Setup
- Use `@SpringBootTest` with Testcontainers for PostgreSQL
- Flyway runs migrations automatically against the test container
- Each test class gets a clean database state via `@Transactional` rollback or explicit cleanup

### What to Integration Test
- **API endpoints**: request validation, response structure, status codes, error format
- **Tenant isolation**: verify that queries never leak data across agencies
- **Permission checks**: verify role-based access control on endpoints
- **Full workflows**: create property -> add rooms -> import workers -> create stays -> check-in
- **Guard rails**: verify state-change guards reject invalid operations (e.g. `property/DeletionGuardIntegrationTest` for property/room deletion guards, `stay/StayGuardIntegrationTest` for stay-mutation guards)

### Base Class

The real `IntegrationTestBase` does **not** use the `@Testcontainers`/`@Container` annotation-driven lifecycle. Instead it starts a single `static` `PostgreSQLContainer` in a static initializer block, so the container is a singleton shared across every integration test class in the run (started once, never stopped, reused via Testcontainers' Ryuk cleanup at JVM exit) rather than started/stopped per test class. This keeps the suite fast - one container spin-up for the whole run instead of one per class - at the cost of tests needing to clean up their own state (see Test Data Management below). Wiring into Spring still goes through `@DynamicPropertySource`, and `authHeaders` takes the `Role` enum, not a raw `String`:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    protected HttpHeaders authHeaders(Role role, UUID agencyId) {
        // Generates a test JWT with the given role and agency via JwtTokenProvider
    }
}
```

`IntegrationTestBase` also provides seeding helpers that write directly via `JdbcTemplate`, bypassing service-layer validation, to satisfy foreign-key constraints without pulling in unrelated setup:
- `ensureAgencyExists(UUID agencyId)` - inserts a minimal `agencies` row if one doesn't already exist for that id
- `ensureUserExists(UUID userId, UUID agencyId)` - inserts a minimal `users` row if one doesn't already exist for that id

Call `ensureUserExists` before any test that sets a `confirmed_by_user_id` (or similar user-referencing) column - skipping it hits a foreign-key violation, since that column references `users(id)`.

### Tenant Isolation Tests

This is the required pattern for a tenant-isolation test: assert that the *other* agency's data is absent from the response (`doesNotContain(...)`), not merely that the querying agency's own data is present. This is not a style nitpick - a test that only checks its own row appears would still pass even if the endpoint leaked every other agency's data too. Two existing tests currently fall short of this and should be treated as gaps to fix, not as examples to copy: `WorkerIntegrationTest.shouldNotReturnWorkersFromOtherAgency` leaves the exclusion check as a comment with no actual assertion, and `PropertyIntegrationTest.shouldNotReturnPropertiesFromOtherAgency` asserts presence of its own row (queried with the same agency's credentials that created it) rather than absence of the other agency's data.

```java
@Test
void shouldNotReturnWorkersFromOtherAgency() {
    // given
    var agency1Worker = createWorker(agency1Id, "Worker A");
    var agency2Worker = createWorker(agency2Id, "Worker B");

    // when
    var response = restTemplate.exchange(
        "/api/v1/workers",
        HttpMethod.GET,
        new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN, agency1Id)),
        WorkerListResponse.class
    );

    // then
    assertThat(response.getBody().content())
        .extracting(WorkerResponse::id)
        .contains(agency1Worker.getId())
        .doesNotContain(agency2Worker.getId());
}
```

## What NOT to Test

- Getter/setter behavior (especially on records)
- Spring framework internals (e.g., that `@Transactional` works)
- MapStruct-generated code correctness (test your mapping config, not MapStruct itself)
- Trivial CRUD with no business logic (covered by integration tests)

## Test Data Management

- Use `@Sql` scripts or test builders for setup - not shared fixtures that create hidden coupling
- Each test must set up its own state (or use a well-documented shared fixture in `IntegrationTestBase`)
- Clean up after tests or rely on `@Transactional` rollback

## Assertions

- Use AssertJ for all assertions (`assertThat(...)`)
- Never use JUnit's `assertEquals` - AssertJ is more readable and provides better failure messages
- For collections, use AssertJ's `extracting`, `filteredOn`, `hasSize`, `containsExactly`

## Running Tests

```bash
# All tests
./gradlew test

# Unit tests only (fast)
./gradlew test --tests '*Test'

# Integration tests only
./gradlew test --tests '*IntegrationTest'

# Specific test class
./gradlew test --tests 'com.beduno.stay.constraint.ConstraintEngineTest'
```

## CI Expectations

- All tests must pass before merge
- Test containers require Docker in CI
- Target: tests complete in < 3 minutes for the full suite
- No flaky tests - if a test is flaky, fix it or delete it
- No code coverage tooling (e.g. JaCoCo) is configured in `build.gradle.kts`, so there is no measured or enforced coverage target - "adequate coverage" is a judgment call based on the rules above (constraint engine, tenant isolation, permissions, workflows, guards), not a percentage gate
