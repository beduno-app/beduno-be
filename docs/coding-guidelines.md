# Bedok Backend - Coding Guidelines

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
- Packages: `com.bedok.<module>`
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
- Services are transactional at the method level (`@Transactional`)
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
└── ValidationException        -> 400
```

### Error Response Format
```json
{
  "error": "CONSTRAINT_VIOLATION",
  "message": "constraint.capacity.exceeded",
  "details": [
    {
      "type": "CAPACITY_EXCEEDED",
      "field": "roomId",
      "message": "constraint.room.capacity.full",
      "params": { "roomNumber": "12", "capacity": 4, "current": 4 }
    }
  ],
  "timestamp": "2026-04-14T12:00:00Z",
  "traceId": "abc-123"
}
```

- `message` is always a message code, never a hardcoded string
- Frontend resolves codes to localized messages
- `GlobalExceptionHandler` maps all exceptions to this format

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
- `201 Created` for resource creation (with `Location` header)
- `200 OK` for updates, reads
- `204 No Content` for deletes
- `422 Unprocessable Entity` for business rule violations

## Code Quality

### Logging
- Use SLF4J with structured logging
- Log at service entry points (INFO) and on errors (ERROR)
- Include `agencyId` and `userId` in MDC for request correlation
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
