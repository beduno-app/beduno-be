# Beduno Backend - Testing Guidelines

## Testing Philosophy

- Tests verify behavior, not implementation
- Use real databases (Testcontainers), not mocks, for repository and integration tests
- Test the constraint engine thoroughly - it is the core business logic
- Every bug fix gets a regression test

## Test Pyramid

```
         ╱  E2E  ╲           Few: critical workflows only
        ╱─────────╲
       ╱Integration ╲        Medium: API + DB together
      ╱───────────────╲
     ╱    Unit Tests    ╲     Many: services, constraints, logic
    ╱─────────────────────╲
```

| Layer | Scope | Tools | Speed |
|-------|-------|-------|-------|
| Unit | Service logic, constraint engine, mappers | JUnit 5, Mockito (for external deps only) | Fast |
| Integration | Controller + Service + DB | Spring Boot Test, Testcontainers (PostgreSQL) | Medium |
| E2E | Full workflow (arrivals, check-in, inspection) | Spring Boot Test, RestAssured | Slower |

## Unit Tests

### What to Unit Test
- **Constraint engine**: every constraint type, edge cases, combinations
- **Stay status transitions**: valid and invalid transitions
- **Service logic**: business rules, validation, conflict detection
- **MapStruct mappers**: correct field mapping

### Conventions
- Test class: `{ClassName}Test.java` in the same package under `src/test`
- Test method naming: `should{ExpectedBehavior}_when{Condition}`
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

### Base Class

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
abstract class IntegrationTestBase {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    protected HttpHeaders authHeaders(String role, UUID agencyId) {
        // Generate a test JWT with the given role and agency
    }
}
```

### Tenant Isolation Tests

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
        new HttpEntity<>(authHeaders("AGENCY_ADMIN", agency1Id)),
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
