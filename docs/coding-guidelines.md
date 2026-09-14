# Beduno Backend - Coding Guidelines

> Reconciled against the implementation on 2026-08-09.

## Java Conventions

### Version & Language Features
- **Java 21** - use modern features: records, sealed classes, pattern matching, text blocks, virtual threads where appropriate
- Use `var` for local variables when the type is obvious from the right-hand side
- Prefer records for DTOs and value objects
- Use sealed interfaces for domain types with a fixed set of subtypes (e.g., constraint violations)

### Naming
- Classes: `PascalCase`
- Methods/variables: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Packages: `com.beduno.<module>`
- Database columns: `snake_case`
- API paths: `kebab-case` (e.g., `/api/v1/bulk-assign`)

### Package Structure
- Organize by domain module, not by technical layer
- Each module (worker, property, stay, etc.) contains its own controller, service, repository, entity, and DTOs
- Shared code goes in `common/`
- No circular dependencies between modules

## Spring Boot Patterns

### Controllers
- Thin controllers: validate input, delegate to service, return response
- Use `@Valid` on request DTOs for input validation
- Return DTOs, never entities
- Use `ResponseEntity` for explicit status codes
- Annotate with `@PreAuthorize` for role-based access

```java
@RestController
@RequestMapping("/api/v1/stays")
@RequiredArgsConstructor
public class StayController {

    private final StayService stayService;

    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasAnyRole('PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<StayResponse> checkIn(
            @PathVariable UUID id,
            @Valid @RequestBody CheckInRequest request) {
        return ResponseEntity.ok(stayService.checkIn(id, request));
    }
}
```

### Services
- All business logic lives in services
- Services are transactional at the method level (`@Transactional`), with `AuthService.refresh` and `AuthService.getCurrentUser` as the current exceptions (no `@Transactional`) - keep new read/token methods consistent with the rest of the codebase unless there's a specific reason not to
- Services call the constraint engine before state changes
- Services call the audit service after state changes
- Throw domain-specific exceptions (`NotFoundException`, `ConflictException`, etc.)

### Repositories
- Spring Data JPA repositories
- Custom queries via `@Query` with JPQL or native SQL
- All queries must filter by `agencyId` (tenant isolation)
- Use projections or DTOs for read-heavy queries to avoid N+1

### Entities
- JPA entities with `@Entity` annotation
- `BaseEntity` provides: `id` (UUID, generated), `createdAt`, `updatedAt`
- Entities include `agencyId` for tenant isolation
- Use `@Version` for optimistic locking on Stay entity
- Soft-delete via `deletedAt` timestamp (no `@SQLDelete` magic - be explicit)

### DTOs
- Use Java records for request and response DTOs
- Separate request and response DTOs (never reuse)
- MapStruct for entity <-> DTO mapping
- Validate requests with Jakarta Bean Validation annotations

```java
public record CreateStayRequest(
    @NotNull UUID workerId,
    @NotNull UUID propertyId,
    @NotNull UUID roomId,
    @NotNull LocalDate dateFrom,
    LocalDate dateTo,
    String overrideReason
) {}

public record StayResponse(
    UUID id,
    WorkerSummary worker,
    PropertySummary property,
    RoomSummary room,
    LocalDate dateFrom,
    LocalDate dateTo,
    StayStatus status,
    Instant createdAt
) {}
```

## Error Handling

### Exception Hierarchy
```
BusinessException (abstract)
├── NotFoundException          -> 404
├── ConflictException          -> 409 (e.g., double booking)
├── ConstraintViolationException -> 422 (hard constraint violated)
├── ForbiddenException         -> 403
├── UnauthorizedException      -> 401 (bad credentials / invalid, expired, or unresolvable refresh token)
└── ValidationException        -> 400
```
`UnauthorizedException` is distinct from `ForbiddenException`: it means the caller cannot be authenticated at all, whereas `ForbiddenException` means the caller is known but lacks the required role.

### Error Response Format
```json
{
  "error": "CONSTRAINT_VIOLATION",
  "message": "error.constraint.violated",
  "details": [
    {
      "type": "BED_OCCUPIED",
      "field": null,
      "message": "constraint.bed.occupied",
      "params": { "bedLabel": "3", "roomNumber": "12" }
    }
  ],
  "timestamp": "2026-04-14T12:00:00Z"
}
```

- `message` is always a message code, never a hardcoded string
- Frontend resolves codes to localized messages
- `GlobalExceptionHandler` maps all exceptions to this format
- `ErrorResponse` also declares a `traceId` field (omitted above because it's `null`ed out by `@JsonInclude(NON_NULL)`): `ErrorResponse.of(...)` always passes `null` for it, so **no real response currently populates `traceId`**. The correlation plumbing exists - `TenantFilter` puts a per-request `requestId` into MDC (alongside `agencyId`/`userId`) for log correlation - but it is not wired into the error response body. Wiring `requestId` through to `traceId` would be a natural follow-up if client-visible correlation IDs are needed.

## Database

### Migrations
- Flyway for all schema changes
- Migration files: `V{number}__{description}.sql`
- Never modify an existing migration
- Always include rollback considerations in migration comments
- Separate data migrations from schema migrations

### Queries
- Use parameterized queries only (never string concatenation)
- Index foreign keys and frequently filtered columns
- Use `EXPLAIN ANALYZE` for complex queries during development

## Security

### General
- Never log PII (worker names, phones, IDs)
- Never expose internal IDs in error messages to unauthorized users
- Validate all input at the API boundary
- Sanitize any user-provided text stored in the database

### Tenant Isolation
- Every service method must operate within `TenantContext`
- Repository queries must always include `agencyId` filter
- Integration tests must verify cross-tenant isolation

## API Conventions

### Pagination
```
GET /api/v1/workers?page=0&size=20&sort=lastName,asc
```
Response wraps in:
```json
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 142,
  "totalPages": 8
}
```

### Filtering
- Use query parameters for simple filters: `?status=CHECKED_IN&propertyId=xxx`
- Use `dateFrom` and `dateTo` for date range filters

### Responses
- `201 Created` for resource creation. No `Location` header: nothing sets one, and the created resource is in the body, so a client never needs to follow up with a GET. Do not add one to a single endpoint -- either every creation gets it or none does.
- `200 OK` for updates, reads
- `204 No Content` for deletes
- `422 Unprocessable Entity` for business rule violations

## Code Quality

### Checkstyle
- Checkstyle is wired into the build (`build.gradle.kts`, `checkstyle` plugin) against `config/checkstyle/checkstyle.xml`, with `isIgnoreFailures = false` - a violation fails `./gradlew build`, not just a lint warning
- Rule families actually enforced: no tab characters in files; import hygiene (unused/redundant/illegal imports); naming (types, constants, local variables, members, methods, parameters, static variables, packages); block structure (braces required, brace placement, no empty blocks); coding rules (one statement per line, no multi-variable declarations, switch fall-through, `default` last, boolean expression/return simplification, no `==` on strings, `equals`/`hashCode` pairing, no hidden fields except in constructors/setters); whitespace around generics, parens, and operators; modifier order and redundant-modifier checks; and misc rules (no `L`-suffix ambiguity, array bracket style, one outer type per file)
- There is **no line-length rule and no Javadoc rule** - don't assume either is enforced

### Logging
- Use SLF4J with structured logging
- **Not currently followed in practice**: "log at service entry points (INFO) and on errors (ERROR)" is the aspiration, not the current state. As of this writing only three classes use `@Slf4j` (`StayScheduler`, `GlobalExceptionHandler`, `WorkerService`), and there is exactly one `log.info` call in `src/main/java` (`StayScheduler`, which is a `@Component`/`@Scheduled` job, not a `@Service`). `GlobalExceptionHandler` has the only `log.error` call, for unhandled exceptions. Do not assume services log at entry or on error today - add logging deliberately rather than relying on convention
- Include `agencyId` and `userId` in MDC for request correlation - this part is implemented: `TenantFilter` puts `requestId`, `agencyId`, and `userId` into MDC for every request
- Never log passwords, tokens, or PII

### Dependency Injection
- Constructor injection only (enforced by `@RequiredArgsConstructor` from Lombok)
- No field injection (`@Autowired` on fields)

### Null Safety
- Use `Optional` for return types that may be absent
- Use `@NotNull` / `@Nullable` annotations for method parameters
- Prefer empty collections over null

### Lombok
- Use sparingly: `@RequiredArgsConstructor`, `@Getter`, `@Builder` where it reduces boilerplate
- Do not use `@Data` on entities (breaks JPA equals/hashCode)
- Prefer records over Lombok for DTOs
