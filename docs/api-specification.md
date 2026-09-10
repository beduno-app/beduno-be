# Beduno Backend - API Specification

> **For the frontend team.** All endpoints are REST/JSON. Base URL: `/api/v1`. An OpenAPI document is generated at `/v3/api-docs` (JSON), `/v3/api-docs.yaml` (YAML) and browsable at `/swagger-ui.html` — all three are reachable without a token. Use them to generate TypeScript API clients.

> **Reconciled against the implementation.** Every endpoint, field, role, status code and message code below was verified against the controllers, DTO records, validation annotations, `GlobalExceptionHandler`, `SecurityConfig`, the enums and the i18n bundles. Anything the original specification described but that was never built has been moved to **[Appendix: Specified but not implemented](#appendix-specified-but-not-implemented)** rather than deleted. Where the current behaviour looks like a bug rather than a design choice, it is called out inline as a **Caveat** — those describe what the server does today, not what it should do.

---

## General Conventions

### Authentication
All endpoints except `/api/v1/auth/login`, `/api/v1/auth/refresh` and `/actuator/health` require a
JWT Bearer token. `/api/v1/auth/me` is **not** anonymous despite its prefix. The OpenAPI paths are
anonymous only when `beduno.security.public-api-docs` is true, which it is by default and is not
under the `prod` profile:

```
Authorization: Bearer <token>
```

The **access token** carries: `sub` (userId), `agencyId`, `role`, `properties` (array of assigned property ID strings), `lang`, `iat`, `exp`. Default lifetime 3 600 s.

The **refresh token** carries only `sub`, `type: "refresh"`, `iat`, `exp`. Default lifetime 7 days.

Tokens are HS256-signed and stateless — there is no server-side session, no token revocation and no logout endpoint.

### Pagination
List endpoints that return a page accept the standard Spring parameters:

```
GET /api/v1/workers?page=0&size=20&sort=last_name,asc
```

Paginated response shape (`PageResponse`):
```json
{
  "content": [ ... ],
  "page": 0,
  "size": 20,
  "totalElements": 142,
  "totalPages": 8
}
```

Not every list endpoint is paginated — arrivals, occupancy, exceptions and the inspection roster return a **bare JSON array** with no envelope. This is noted per endpoint.

### Sort keys (read this before building a sort UI)
The value of `sort=` depends on how the underlying query is written. Passing the wrong flavour produces a **500**, not a 400.

| Endpoint | Query type | `sort=` takes | Default |
|----------|-----------|---------------|---------|
| `GET /workers` | native SQL | **snake_case column names** — `last_name`, `first_name`, `internal_id`, `status`, `gender`, `created_at` | `last_name,asc` |
| `GET /properties` | native SQL | **snake_case column names** — `name`, `city`, `status`, `created_at` | `name,asc` |
| `GET /stays` | native SQL | **snake_case column names** — `date_from`, `date_to`, `status`, `created_at` | `date_from,desc` |
| `GET /properties/{id}/rooms` | derived JPA | **entity property names** — `name`, `floor`, `capacity`, `blockedSpots`, `genderRule`, `status`, `createdAt` | `name,asc` |
| `GET /audit` | JPQL | **entity property names** — `createdAt`, `action`, `entityType` | `createdAt,desc` |

### Error Response
Every handled error uses the same envelope (`ErrorResponse`):

```json
{
  "error": "NOT_FOUND",
  "message": "error.worker.not_found",
  "timestamp": "2026-04-14T12:00:00Z"
}
```

- The record is annotated `@JsonInclude(NON_NULL)`: **null fields are omitted entirely.** `details` is absent unless the error carries specifics.
- `traceId` is part of the record but is **never populated**, so it never appears in a response. Do not build against it.
- `message` is always a **message code**, never a raw sentence — resolve it to the user's language on the frontend. The exception is per-field bean-validation messages (see below), which are English strings.

### Bean validation errors (400)
A `@Valid` failure returns `VALIDATION_ERROR` / `error.validation.failed` and puts **one full nested `ErrorResponse` per rejected field** into `details[]`. In those nested objects `error` is the **field name** and `message` is the **English Bean Validation default message** — not a message code.

```json
{
  "error": "VALIDATION_ERROR",
  "message": "error.validation.failed",
  "details": [
    { "error": "email", "message": "must not be blank", "timestamp": "2026-04-14T12:00:00Z" },
    { "error": "capacity", "message": "must be greater than or equal to 1", "timestamp": "2026-04-14T12:00:00Z" }
  ],
  "timestamp": "2026-04-14T12:00:00Z"
}
```

### Common HTTP Status Codes

| Status | Meaning | Body |
|--------|---------|------|
| 200 | Success (read, update, action, bulk) | resource / result object |
| 201 | Created — **no `Location` header is ever set** | the created resource |
| 204 | No Content (delete / cancel) | empty |
| 400 | Bean validation failure, or a `ValidationException` message code | `VALIDATION_ERROR` |
| 401 | No token, invalid/expired token, or bad credentials | `UNAUTHORIZED` |
| 403 | Authenticated but wrong role, or property scope denied | `FORBIDDEN` |
| 404 | Not found within the caller's agency | `NOT_FOUND` |
| 409 | Business conflict (duplicate key, illegal state transition, delete blocked) | `CONFLICT` |
| 422 | Constraint engine rejected the stay | `CONSTRAINT_VIOLATION` |
| 429 | Auth rate limit exceeded | `RATE_LIMIT_EXCEEDED` |
| 500 | Unhandled error | `INTERNAL_ERROR` / `error.internal` |

Unauthenticated calls to a protected endpoint are answered by `RestAuthenticationEntryPoint` with **401** and the standard envelope:
```json
{ "error": "UNAUTHORIZED", "message": "error.auth.unauthorized", "timestamp": "..." }
```
Role denials raised in the filter chain are answered by `RestAccessDeniedHandler`, and denials raised by `@PreAuthorize` are answered by `GlobalExceptionHandler` — both produce **403** with the identical body:
```json
{ "error": "FORBIDDEN", "message": "error.access_denied", "timestamp": "..." }
```

> **Caveat — request-binding failures return 500.** `GlobalExceptionHandler` declares a catch-all `@ExceptionHandler(Exception.class)` and does not extend `ResponseEntityExceptionHandler`, so Spring's own MVC exceptions never reach their default handlers. A missing required query param (e.g. `GET /stays/arrivals` without `propertyId`), an unparseable UUID/date/enum in a query param, malformed JSON, or an unknown enum value in a request body all come back as **500 `INTERNAL_ERROR`** instead of 400.

### Rate limiting
`RateLimitFilter` applies **only** to paths starting with `/api/v1/auth/`: 10 requests per minute per client IP (first entry of `X-Forwarded-For`, else the socket address), refilled greedily. Over the limit:

```json
{ "error": "RATE_LIMIT_EXCEEDED", "message": "error.rate_limit_exceeded", "timestamp": "..." }
```
returned with **429**. No `Retry-After` header is sent.

### CORS
`/api/**` allows any origin pattern, methods `GET, POST, PUT, DELETE, OPTIONS`, any header, credentials enabled, 1 h preflight cache.

### Timestamps & Dates
- All timestamps are **UTC ISO 8601** instants (`2026-04-14T12:00:00Z`)
- Stay dates (`dateFrom`, `dateTo`) and all `date` query params are **LocalDate** (`2026-04-14`)
- Audit `dateFrom`/`dateTo` filters are **Instants**, not dates
- All IDs are **UUID** strings, except in the auth responses where `user.id` and `user.assignedPropertyIds[]` are serialised as plain strings

### Multi-Tenancy
Tenant isolation is automatic. `TenantFilter` reads `agencyId` from the JWT into a ThreadLocal and every repository query filters on it. The frontend never sends `agencyId`. A resource belonging to another agency is indistinguishable from a missing one — both give 404.

### Property scoping — read this
The original specification described "own property" scoping on roughly ten endpoints. In the implementation `CurrentUser.hasPropertyAccess` is called in exactly **two** places:

- `PUT /api/v1/properties/{id}` — a `PROPERTY_ADMIN` must have the property in its assigned list
- `POST` and `PUT` on `/api/v1/properties/{propertyId}/rooms` — same check

**Everywhere else, access is agency-wide.** A `FRONT_DESK` or `PROPERTY_ADMIN` user can read and act on stays, workers, occupancy, arrivals and inspections for *any* property in their agency, regardless of `assignedPropertyIds`. Both scoped checks fail with **403 `error.property.access_denied`**.

### Full message-code list

| Code | Where |
|------|-------|
| `error.internal` | any unhandled exception (500) |
| `error.validation.failed` | bean validation (400) |
| `error.auth.unauthorized` | no/invalid token on a protected path (401) |
| `error.auth.invalid_credentials` | login (401) |
| `error.auth.invalid_refresh_token` | refresh (401) |
| `error.auth.user_not_found` | `GET /auth/me` (404) |
| `error.access_denied` | role denial (403) |
| `error.rate_limit_exceeded` | auth rate limit (429) |
| `error.worker.not_found` | worker lookups (404) |
| `error.worker.internal_id_exists` | `POST /workers` (409) |
| `error.worker.import.*` | CSV import row/file errors |
| `error.property.not_found` | property lookups (404) |
| `error.property.access_denied` | scoped property/room writes (403) |
| `error.property.has_rooms`, `error.property.has_stays` | `DELETE /properties/{id}` (409) |
| `error.room.not_found` | room lookups (404) |
| `error.room.name_exists` | room create/update (409) |
| `error.room.blocked_spots_exceed_capacity` | room create/update (400) |
| `error.room.has_stays` | `DELETE .../rooms/{id}` (409) |
| `error.stay.not_found` | stay lookups (404) |
| `error.stay.cannot_update_in_current_status` | `PUT /stays/{id}` (409) |
| `error.stay.invalid_status_transition` | check-in / check-out / no-show / cancel (409) |
| `error.stay.cannot_move_in_current_status`, `error.stay.move_same_room`, `error.stay.cannot_move_on_last_day` | move (409) |
| `error.constraint.violated`, `error.constraint.soft_violations` | constraint engine (422) |
| `constraint.*` | individual violations inside `details[]` |

> **Note — the backend returns codes, not prose.** The bundles under `src/main/resources/i18n/`
> (`messages`, `_en`, `_pl`, `_de`, `_ru`, `_uk` — six files with identical key sets) define every
> code the Java sources reference, including `error.worker.not_found`,
> `error.worker.internal_id_exists`, `error.property.not_found`, `error.property.access_denied`,
> `error.room.not_found`, `error.room.name_exists` and
> `error.room.blocked_spots_exceed_capacity`; `MessageBundleTest` fails the build on a missing key,
> a blank value, or a code referenced from Java but undefined. They are used for CSV export
> headers. API error responses still carry the **code**, not a translated string, so the frontend
> supplies its own copy for anything it renders.

---

## Enums

### Role
```
AGENCY_ADMIN | AGENCY_PLANNER | PROPERTY_ADMIN | FRONT_DESK
```

### Language
**Not an enum.** `User.language` is a plain `String` column (`VARCHAR(5)`, default `PL`), echoed back verbatim in the auth response and the `lang` JWT claim. Nothing validates it.

The CSV export `language=` parameter is also a plain string and accepts `EN`, `PL`, `DE`, `RU`, `UA` (case-insensitive). Any other value silently falls back to `EN`. All five locales are fully translated for the exported headers, status labels and exception labels.

### Gender
```
MALE | FEMALE | OTHER
```

### WorkerStatus
```
ACTIVE | INACTIVE | DELETED
```
`DELETED` is set by the soft delete and is filtered out of every worker query.

### PropertyStatus
```
ACTIVE | INACTIVE
```

### RoomStatus
```
ACTIVE | BLOCKED
```

### GenderRule
```
ANY | MALE_ONLY | FEMALE_ONLY
```

### StayStatus
```
PLANNED | EXPECTED_TODAY | CHECKED_IN | CHECKED_OUT | CANCELLED | NO_SHOW
```

State machine (enforced by `StayStatus.canTransitionTo`):

| From | Allowed next |
|------|--------------|
| `PLANNED` | `EXPECTED_TODAY`, `CANCELLED` |
| `EXPECTED_TODAY` | `CHECKED_IN`, `NO_SHOW`, `CANCELLED` |
| `CHECKED_IN` | `CHECKED_OUT` |
| `CHECKED_OUT` | *(terminal)* |
| `CANCELLED` | *(terminal)* |
| `NO_SHOW` | *(terminal)* |

`PLANNED → EXPECTED_TODAY` has **no endpoint**. It happens only in `StayScheduler`, a daily cron (`beduno.scheduler.arrival-transition-cron`, default `0 0 6 * * *`) that promotes every `PLANNED` stay whose `dateFrom` is today. A stay that is still `PLANNED` therefore **cannot be checked in** — check-in returns 409.

### AuditAction
```
CREATED | UPDATED | DELETED | CHECKED_IN | CHECKED_OUT | NO_SHOW | CANCELLED | MOVED | BULK_ASSIGNED | BULK_CHECKED_OUT
```

### AuditEntityType
```
STAY | WORKER | ROOM | PROPERTY
```

---

## 1. Authentication

### POST /api/v1/auth/login
Log in and receive tokens. Rate limited (10/min/IP).

**Roles:** none — public

**Request:**
```json
{
  "email": "jan@agency.pl",
  "password": "secret"
}
```
`email` — `@NotBlank @Email`; `password` — `@NotBlank`.

**Response 200:**
```json
{
  "accessToken": "eyJhbG...",
  "refreshToken": "eyJhbG...",
  "expiresIn": 3600,
  "user": {
    "id": "uuid",
    "email": "jan@agency.pl",
    "firstName": "Jan",
    "lastName": "Kowalski",
    "role": "AGENCY_ADMIN",
    "language": "PL",
    "assignedPropertyIds": []
  }
}
```
`expiresIn` is the access-token lifetime in **seconds**. `id` and the entries of `assignedPropertyIds` are strings.

**Response 401:** unknown email **or** wrong password — the two are indistinguishable.
```json
{ "error": "UNAUTHORIZED", "message": "error.auth.invalid_credentials", "timestamp": "..." }
```

**Response 400:** missing/blank/non-email fields. **Response 429:** rate limited.

### POST /api/v1/auth/refresh
Exchange a refresh token for a fresh token pair. Rate limited.

**Roles:** none — public

**Request:**
```json
{ "refreshToken": "eyJhbG..." }
```

**Response 200:** the **same full `AuthResponse` as login**, including the nested `user` object.

**Response 401:** `error.auth.invalid_refresh_token` — token failed signature/expiry validation, **or** validated but its subject no longer has a user row (deliberately reported as 401, not 404, so it cannot be used to probe for deleted accounts).

Access tokens are **not** accepted here. Both token kinds are signed with the same key, so the
endpoint additionally requires the `type: "refresh"` claim that only refresh tokens carry; an
access token presented here returns 401. The reverse is refused too — a refresh token sent as a
bearer credential leaves the request anonymous rather than authenticating it.

### GET /api/v1/auth/me
Return the authenticated user's profile, re-read from the database.

**Roles:** any authenticated user

**Response 200:** the bare `UserInfo` object — the same shape as `user` in the login response, **not** wrapped in an `AuthResponse`.

**Response 404:** `error.auth.user_not_found` — the token is valid but its subject has no user row.

**Response 401:** no token, or a token that is not an access token. Only `/auth/login` and
`/auth/refresh` are anonymous; this path falls through to `anyRequest().authenticated()` and is
answered by the entry point like any other protected endpoint.

---

## 2. Workers

### GET /api/v1/workers
Paginated worker list.

**Query params:** `?page=0&size=20&sort=last_name,asc&status=ACTIVE&gender=MALE&tag=electrician&search=kowalski`

- `status` — `WorkerStatus`; `gender` — `Gender`
- `tag` — **exact** match against one element of the `tags` array (not a substring)
- `search` — case-insensitive substring against `internalId`, `firstName`, `lastName`. It does **not** match `phone`, `email` or `nationality`.
- Workers with status `DELETED` are always excluded, even when `status=DELETED` is requested
- `sort` takes **snake_case column names** (see the sort table above)

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK

**Response 200:** `PageResponse<WorkerResponse>`

### GET /api/v1/workers/{id}
**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK

**Response 200:** `WorkerResponse` · **404:** `error.worker.not_found`

### POST /api/v1/workers
**Roles:** AGENCY_ADMIN **only**

**Request:**
```json
{
  "internalId": "W-1234",
  "firstName": "Andriy",
  "lastName": "Shevchenko",
  "gender": "MALE",
  "nationality": "UA",
  "phone": "+48123456789",
  "email": "andriy@example.com",
  "dateOfBirth": "1990-05-12",
  "tags": ["welder", "forklift"],
  "notes": "Prefers ground floor"
}
```

| Field | Required | Validation |
|-------|----------|-----------|
| `internalId` | yes | `@NotBlank` |
| `firstName` | yes | `@NotBlank @Size(max=100)` |
| `lastName` | yes | `@NotBlank @Size(max=100)` |
| `gender` | yes | `@NotNull`, one of `Gender` |
| `nationality` | no | `@Size(max=100)` |
| `phone` | no | `@Size(max=50)` |
| `email` | no | `@Size(max=255)` — **not** format-validated |
| `dateOfBirth` | no | ISO date |
| `tags` | no | array of strings; omitted ⇒ `[]` |
| `notes` | no | free text |

Status is always `ACTIVE` on create; it cannot be set here.

**Response 201:** `WorkerResponse` (no `Location` header) · **409:** `error.worker.internal_id_exists`

### PUT /api/v1/workers/{id}
**Full replace, not a partial update.** Every field listed below is sent on every call; omitted optional fields are written as `null`/`[]`.

**Roles:** AGENCY_ADMIN **only**

**Request:** same fields as create **except**:
- `internalId` is **not** part of the payload and can never be changed
- `status` is **required** (`@NotNull`, a `WorkerStatus`)

```json
{
  "firstName": "Andriy",
  "lastName": "Shevchenko",
  "gender": "MALE",
  "nationality": "UA",
  "phone": "+48123456789",
  "email": "andriy@example.com",
  "dateOfBirth": "1990-05-12",
  "tags": ["welder"],
  "notes": null,
  "status": "ACTIVE"
}
```

**Response 200:** `WorkerResponse` · **404:** `error.worker.not_found`

> Sending `"status": "DELETED"` here soft-deletes the worker without setting `deletedAt`. Prefer `DELETE`.

### DELETE /api/v1/workers/{id}
Soft delete — sets `status = DELETED` and `deletedAt`; the row and all its stays are preserved. There is **no** guard against deleting a worker with active stays.

**Roles:** AGENCY_ADMIN **only**

**Response 204** · **404:** `error.worker.not_found`

### POST /api/v1/workers/import
Bulk import from CSV.

**Roles:** AGENCY_ADMIN **only**

**Request:** `multipart/form-data`, part name `file`.

The first line is **always discarded as a header**, whatever it contains. Columns are positional, comma-separated, with `"`-quoting supported:

| # | Column | Required |
|---|--------|----------|
| 0 | `internalId` | yes |
| 1 | `firstName` | yes |
| 2 | `lastName` | yes |
| 3 | `gender` | yes — `MALE`/`FEMALE`/`OTHER`, case-insensitive |
| 4 | `nationality` | no |
| 5 | `phone` | no |
| 6 | `email` | no |
| 7 | `dateOfBirth` | no — ISO `yyyy-MM-dd` |
| 8 | `tags` | no — **semicolon**-separated, e.g. `welder;forklift` |
| 9 | `notes` | no |

```csv
internalId,firstName,lastName,gender,nationality,phone,email,dateOfBirth,tags,notes
W-1234,Andriy,Shevchenko,MALE,UA,+48123456789,a@example.com,1990-05-12,welder;forklift,Ground floor
W-1235,Olena,Kovalenko,FEMALE,UA,+48987654321,,1993-02-01,cleaner,
```

Rows with fewer than 4 columns are rejected; blank lines are skipped silently and are not counted anywhere. A row whose `internalId` already exists in the agency is **skipped**, not reported as an error.

**Response 200:**
```json
{
  "created": 142,
  "skipped": 5,
  "errors": 3,
  "errorDetails": [
    { "row": 12, "internalId": "W-1234", "reason": "error.worker.import.invalid_gender" },
    { "row": 45, "internalId": null,     "reason": "error.worker.import.required_field_missing" }
  ]
}
```
- `errors` is a **count**, not an array — the array is `errorDetails`
- `row` is 1-based counting the header as row 1, so the first data row is `2`
- `internalId` is `null` when the row had no usable ID
- Row reasons: `error.worker.import.too_few_columns`, `error.worker.import.required_field_missing`, `error.worker.import.invalid_gender`, `error.worker.import.row_failed`
- An empty file returns `{"created":0,"skipped":0,"errors":0,"errorDetails":[]}`

**Response 400:** `error.worker.import.file_unreadable` — the upload could not be read at all.

### WorkerResponse
```json
{
  "id": "uuid",
  "internalId": "W-1234",
  "firstName": "Andriy",
  "lastName": "Shevchenko",
  "gender": "MALE",
  "nationality": "UA",
  "phone": "+48123456789",
  "email": "andriy@example.com",
  "dateOfBirth": "1990-05-12",
  "tags": ["welder", "forklift"],
  "notes": "Prefers ground floor",
  "status": "ACTIVE",
  "createdAt": "2026-04-01T08:00:00Z",
  "updatedAt": "2026-04-14T10:00:00Z"
}
```
There is no `currentStay` — join stays yourself via `GET /stays?workerId=...`.

> `WorkerSummary` and `StaySummary` records exist in the codebase with mapper methods, but **no endpoint returns them**. Nothing nests a worker or stay summary in a response today.

---

## 3. Properties

### GET /api/v1/properties
**Query params:** `?page=0&size=20&sort=name,asc&status=ACTIVE&search=warszawa`

- `status` — `PropertyStatus`
- `search` — case-insensitive substring against **`name` OR `city`**
- There is no `type` filter (see appendix)
- `sort` takes **snake_case column names**

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK — **agency-wide for all four**, `assignedPropertyIds` is not applied here

**Response 200:** `PageResponse<PropertyResponse>`

### GET /api/v1/properties/{id}
**Roles:** all four, agency-wide

**Response 200:** `PropertyResponse` · **404:** `error.property.not_found`

### POST /api/v1/properties
**Roles:** AGENCY_ADMIN **only**

**Request:**
```json
{
  "name": "Hotel Warszawa",
  "address": "ul. Przykładowa 10, 00-001 Warszawa",
  "city": "Warszawa",
  "notes": "Main worker hotel, 3 floors"
}
```
`name` — `@NotBlank @Size(max=255)`; `city` — `@Size(max=100)`; `address`, `notes` — optional free text. Status is always `ACTIVE` on create.

**Response 201:** `PropertyResponse` (no `Location` header)

### PUT /api/v1/properties/{id}
**Full replace.** `status` is required.

**Roles:** AGENCY_ADMIN, PROPERTY_ADMIN — a `PROPERTY_ADMIN` must have this property in `assignedPropertyIds`, otherwise **403 `error.property.access_denied`**. This is one of only two scope-checked endpoints in the API.

**Request:**
```json
{
  "name": "Hotel Warszawa",
  "address": "ul. Przykładowa 10",
  "city": "Warszawa",
  "notes": null,
  "status": "ACTIVE"
}
```

**Response 200:** `PropertyResponse` · **404:** `error.property.not_found`

### DELETE /api/v1/properties/{id}
**Hard delete** — the row is removed. This is *not* a soft delete, and setting `status: INACTIVE` via `PUT` is the way to retire a property without deleting it.

**Roles:** AGENCY_ADMIN **only**

**Response 204**

**Response 409:**
- `error.property.has_rooms` — one or more rooms still belong to the property
- `error.property.has_stays` — one or more stays reference it, **including cancelled, no-show and checked-out ones**

Both guards exist because `rooms.property_id` and `stays.property_id` are `RESTRICT` foreign keys. In practice a property that has ever been used can never be deleted.

### PropertyResponse
```json
{
  "id": "uuid",
  "name": "Hotel Warszawa",
  "address": "ul. Przykładowa 10, 00-001 Warszawa",
  "city": "Warszawa",
  "status": "ACTIVE",
  "notes": "Main worker hotel, 3 floors",
  "createdAt": "2026-04-01T08:00:00Z",
  "updatedAt": "2026-04-14T10:00:00Z"
}
```
There is no `type`, no `genderRule` (that lives on the room) and no `roomSummary`. For counts and occupancy use `GET /properties/{id}/occupancy`.

---

## 4. Rooms

All room endpoints are nested under a property: `/api/v1/properties/{propertyId}/rooms`. Every one of them first resolves the property and returns **404 `error.property.not_found`** if it does not exist in the caller's agency.

Rooms are identified by **`name`** (a string, unique per property) — there is no `roomNumber` field anywhere in the API. `floor` is also a **string**, not a number.

### GET /api/v1/properties/{propertyId}/rooms
**Query params:** `?page=0&size=50&sort=name,asc`

There are **no** `status`, `floor` or `search` filters on this endpoint. `sort` takes **entity property names** (`name`, `floor`, `capacity`, `blockedSpots`, `genderRule`, `status`, `createdAt`), not column names.

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK — agency-wide

**Response 200:** `PageResponse<RoomResponse>`

### GET /api/v1/properties/{propertyId}/rooms/{roomId}
**Roles:** all four, agency-wide

**Response 200:** `RoomResponse` · **404:** `error.property.not_found` or `error.room.not_found` (the room must belong to that property)

### POST /api/v1/properties/{propertyId}/rooms
**Roles:** AGENCY_ADMIN, PROPERTY_ADMIN — a `PROPERTY_ADMIN` must have the property in `assignedPropertyIds`, else **403 `error.property.access_denied`**

**Request:**
```json
{
  "name": "12",
  "floor": "2",
  "capacity": 4,
  "blockedSpots": 1,
  "genderRule": "MALE_ONLY",
  "notes": "Corner room, good ventilation"
}
```

| Field | Required | Validation |
|-------|----------|-----------|
| `name` | yes | `@NotBlank @Size(max=100)`, unique within the property |
| `floor` | no | `@Size(max=50)`, **string** |
| `capacity` | yes in practice | primitive `int` with `@Min(1)` — omitting it sends `0`, which fails validation with 400 |
| `blockedSpots` | no | primitive `int` with `@Min(0)` — omitting it yields `0` and is accepted |
| `genderRule` | no | defaults to `ANY` when omitted |
| `notes` | no | free text |

Status is always `ACTIVE` on create.

**Response 201:** `RoomResponse` (no `Location` header)

**Response 409:** `error.room.name_exists` — another room in this property already has that name

**Response 400:** `error.room.blocked_spots_exceed_capacity` (a `ValidationException`, so `details` is absent) when `blockedSpots > capacity`

### PUT /api/v1/properties/{propertyId}/rooms/{roomId}
**Full replace.** `genderRule` and `status` are both `@NotNull` and required.

**Roles:** AGENCY_ADMIN, PROPERTY_ADMIN (same scope check as create)

**Request:**
```json
{
  "name": "12",
  "floor": "2",
  "capacity": 4,
  "blockedSpots": 0,
  "genderRule": "ANY",
  "status": "ACTIVE",
  "notes": null
}
```

**Response 200:** `RoomResponse` · **409:** `error.room.name_exists` (only when the name actually changes) · **400:** `error.room.blocked_spots_exceed_capacity`

Setting `status: BLOCKED` makes the constraint engine reject any new or moved stay into this room with a hard `ROOM_BLOCKED` violation. It does not evict existing occupants.

### DELETE /api/v1/properties/{propertyId}/rooms/{roomId}
**Hard delete.**

**Roles:** AGENCY_ADMIN **only** — a `PROPERTY_ADMIN` cannot delete rooms even in an assigned property

**Response 204**

**Response 409:** `error.room.has_stays` — **any** stay references the room, including cancelled, no-show and checked-out ones (`stays.room_id` is a `RESTRICT` foreign key). Use `status: BLOCKED` to retire a room that has history.

### RoomResponse
```json
{
  "id": "uuid",
  "propertyId": "uuid",
  "name": "12",
  "floor": "2",
  "capacity": 4,
  "blockedSpots": 1,
  "availableSpots": 3,
  "genderRule": "MALE_ONLY",
  "status": "ACTIVE",
  "notes": "Corner room, good ventilation",
  "createdAt": "2026-04-01T08:00:00Z",
  "updatedAt": "2026-04-14T10:00:00Z"
}
```
`availableSpots` is computed as `capacity - blockedSpots` — it is **static bed inventory, not live vacancy**; it does not subtract current occupants. There is no `currentOccupancy` and no `occupants[]`; use `GET /properties/{id}/occupancy` for that.

---

## 5. Stays

### GET /api/v1/stays
**Query params:** `?page=0&size=20&sort=date_from,desc&workerId=uuid&propertyId=uuid&status=CHECKED_IN&dateFrom=2026-04-01&dateTo=2026-04-30`

- There is **no `roomId` filter**
- `dateFrom`/`dateTo` select stays that **overlap** the window: `(dateTo IS NULL OR dateTo >= dateFrom_param)` and `(dateFrom <= dateTo_param)`
- `sort` takes **snake_case column names**

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK — agency-wide for all four

**Response 200:** `PageResponse<StayResponse>`

### GET /api/v1/stays/{id}
**Roles:** all four, agency-wide

**Response 200:** `StayResponse` · **404:** `error.stay.not_found`

### POST /api/v1/stays
Create a planned stay. Runs the constraint engine.

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN — **not** FRONT_DESK

**Request:**
```json
{
  "workerId": "uuid",
  "propertyId": "uuid",
  "roomId": "uuid",
  "dateFrom": "2026-04-20",
  "dateTo": "2026-05-20",
  "overrideReason": null,
  "notes": "Night shift"
}
```
`workerId`, `propertyId`, `roomId`, `dateFrom` are `@NotNull`. `dateTo` is optional — `null` means open-ended. `overrideReason` and `notes` are optional free text.

Supplying any non-null `overrideReason` suppresses **soft** violations (see §6). It never suppresses hard ones.

**Response 201:** `StayResponse` with `status: "PLANNED"` (no `Location` header)

**Response 404:** `error.worker.not_found` / `error.room.not_found` / `error.property.not_found`

**Response 422:** constraint violation

> **Caveat.** Nothing validates that `dateTo > dateFrom`, nor that `roomId` belongs to `propertyId`. A bad date pair trips the `chk_stays_dates` database CHECK and surfaces as **500**; a room from another property in the same agency is accepted and silently creates an inconsistent stay.

### PUT /api/v1/stays/{id}
**Full replace** of the mutable fields. Re-runs the constraint engine, excluding this stay from its own capacity and double-booking counts.

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN

**Request:**
```json
{
  "roomId": "uuid",
  "dateFrom": "2026-04-22",
  "dateTo": "2026-05-22",
  "overrideReason": null,
  "notes": null
}
```
`roomId` and `dateFrom` are `@NotNull`. `workerId`, `propertyId` and `status` **cannot** be changed here.

**Response 200:** `StayResponse`

**Response 409:** `error.stay.cannot_update_in_current_status` — only `PLANNED` and `EXPECTED_TODAY` stays are editable. Use move for a checked-in stay.

**Response 404 / 422:** as for create.

### DELETE /api/v1/stays/{id}
Cancel — sets `status: CANCELLED`. The row is kept.

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN

**Response 204**

**Response 409:** `error.stay.invalid_status_transition` — only `PLANNED` and `EXPECTED_TODAY` stays can be cancelled. A checked-in stay must be checked out.

### POST /api/v1/stays/bulk-assign
Create many planned stays in one call. Never fails as a whole — each assignment is attempted and reported individually.

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, **PROPERTY_ADMIN**

**Request:**
```json
{
  "assignments": [
    { "workerId": "uuid-1", "propertyId": "uuid", "roomId": "uuid-a", "dateFrom": "2026-04-20", "dateTo": "2026-05-20" },
    { "workerId": "uuid-2", "propertyId": "uuid", "roomId": "uuid-a", "dateFrom": "2026-04-20", "dateTo": "2026-05-20", "overrideReason": "Manager approved" },
    { "workerId": "uuid-3", "propertyId": "uuid", "roomId": "uuid-b", "dateFrom": "2026-04-20" }
  ]
}
```
`assignments` is `@NotEmpty` and each element is validated: `workerId`, `propertyId`, `roomId`, `dateFrom` are `@NotNull`; `dateTo` and `overrideReason` are optional. There is no `notes` field here.

**Response 200:**
```json
{
  "created": 2,
  "errors": 1,
  "results": [
    { "index": 0, "workerId": "uuid-1", "stayId": "uuid", "status": "created", "errorCode": null },
    { "index": 1, "workerId": "uuid-2", "stayId": "uuid", "status": "created", "errorCode": null },
    { "index": 2, "workerId": "uuid-3", "stayId": null,  "status": "error",   "errorCode": "error.constraint.violated" }
  ]
}
```
- `status` is a **lowercase** string: `"created"` or `"error"`
- `index` is the 0-based position in the submitted array
- `errorCode` carries the failing exception's message. For business failures that is a message code (`error.constraint.violated`, `error.stay.not_found`, …). **The individual constraint details are not returned** — you only learn that *a* constraint failed, not which one.

> **Caveat.** Per-item failures are caught inside a single `@Transactional` method. A failure originating in the persistence layer can mark the transaction rollback-only, in which case the whole batch is lost despite the response reporting partial success. Non-business exceptions also leak their raw English message into `errorCode`.

### POST /api/v1/stays/bulk-checkout
See §8.

### StayResponse
**Flat — all references are bare UUIDs.** There are no nested `worker`/`property`/`room` objects and no `createdBy`/`confirmedBy`.

```json
{
  "id": "uuid",
  "workerId": "uuid",
  "propertyId": "uuid",
  "roomId": "uuid",
  "dateFrom": "2026-04-20",
  "dateTo": "2026-05-20",
  "status": "PLANNED",
  "overrideReason": null,
  "noShowReason": null,
  "notes": "Night shift",
  "createdAt": "2026-04-14T12:00:00Z",
  "updatedAt": "2026-04-14T12:00:00Z"
}
```
`confirmed_by_user_id` **is** stored on check-in and move but is deliberately not exposed; read it from the audit trail if you need it.

---

## 6. Constraint Violations

When the constraint engine rejects a stay operation the API returns **422** with the standard `ErrorResponse` envelope. There is no bespoke body: no `allowed`, no `hardViolations`/`softViolations` arrays, no `overridable` flag.

```json
{
  "error": "CONSTRAINT_VIOLATION",
  "message": "error.constraint.violated",
  "details": [
    {
      "type": "CAPACITY_EXCEEDED",
      "field": null,
      "message": "constraint.room.capacity.exceeded",
      "params": { "roomName": "12", "capacity": 4, "occupied": 4 }
    }
  ],
  "timestamp": "2026-04-14T12:00:00Z"
}
```

- `details[]` entries are `ViolationDetail{ type, field, message, params }`. `field` is **always `null` and always serialised** — unlike the outer envelope, this nested record is not `NON_NULL`-filtered.
- `params` values are typed (strings, numbers) — use them to interpolate the localized `message`.

### Hard vs soft — the discriminator is `message`

| Envelope `message` | Meaning | What to do |
|--------------------|---------|-----------|
| `error.constraint.violated` | one or more **hard** violations | block the operation and show `details[]`; an override cannot help |
| `error.constraint.soft_violations` | only **soft** violations, and no `overrideReason` was supplied | show the warnings, ask for a reason, resubmit with `overrideReason` |

**Hard and soft never arrive together.** Hard violations are evaluated first and short-circuit; if any exist, the soft ones are discarded from the response. Only after all hard checks pass are soft violations reported.

**Override:** resubmit the identical request with a non-null `overrideReason` string. Any non-null value works (including `""`), and the value is persisted on the stay's `overrideReason` and written to the audit event's `reason`.

Frontend flow:
1. Submit the create/update/check-in/move request
2. On 422 with `message: "error.constraint.violated"` → hard stop, render `details[]`
3. On 422 with `message: "error.constraint.soft_violations"` → warn, collect a reason, resubmit with `overrideReason`
4. On 201/200 → done

### Constraint Types

| `type` | Hard/Soft | `message` | `params` | Raised when |
|--------|-----------|-----------|----------|-------------|
| `CAPACITY_EXCEEDED` | Hard | `constraint.room.capacity.full` | `roomName`, `capacity`, `blocked` | `capacity - blockedSpots <= 0` — the room has no usable beds at all |
| `CAPACITY_EXCEEDED` | Hard | `constraint.room.capacity.exceeded` | `roomName`, `capacity`, `occupied` | overlapping `PLANNED`/`EXPECTED_TODAY`/`CHECKED_IN` stays already fill every usable bed for the requested period |
| `DOUBLE_BOOKING` | Hard | `constraint.worker.double_booking` | `workerName` | the worker already has an overlapping active stay anywhere in the agency |
| `ROOM_BLOCKED` | Hard | `constraint.room.blocked` | `roomName` | target room `status = BLOCKED` |
| `PROPERTY_INACTIVE` | Hard | `constraint.property.inactive` | `propertyName` | target property `status = INACTIVE` |
| `GENDER_MISMATCH` | Soft | `constraint.room.gender_mismatch` | `roomName`, `genderRule`, `workerGender` | room rule is `MALE_ONLY`/`FEMALE_ONLY` and the worker's gender does not match (`OTHER` mismatches both; rule `ANY` never fires) |

Note that one `type` (`CAPACITY_EXCEEDED`) maps to two different message codes — branch on `message`, not on `type`, when rendering.

The engine runs on: `POST /stays`, `PUT /stays/{id}`, `POST /stays/{id}/check-in`, `POST /stays/{id}/move`, and each item of `POST /stays/bulk-assign`. It does **not** run on check-out, no-show, cancel or bulk-checkout.

---

## 7. Arrivals Workflow

### GET /api/v1/stays/arrivals
Expected arrivals for one property on one date.

**Query params:** `?propertyId=uuid&date=2026-04-14`

- **`propertyId` is required.** Omitting it currently yields **500**, not 400.
- `date` is optional and defaults to today.

Returns exactly the stays whose `status = EXPECTED_TODAY` **and** `dateFrom = date`. Because only the daily scheduler promotes `PLANNED → EXPECTED_TODAY`, querying a future date returns an empty list.

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK — agency-wide

**Response 200:** a **bare JSON array** of `StayResponse`. No wrapper object, no `property`, no `summary`, no pagination.

```json
[
  {
    "id": "uuid",
    "workerId": "uuid",
    "propertyId": "uuid",
    "roomId": "uuid",
    "dateFrom": "2026-04-14",
    "dateTo": "2026-05-14",
    "status": "EXPECTED_TODAY",
    "overrideReason": null,
    "noShowReason": null,
    "notes": null,
    "createdAt": "2026-04-01T09:00:00Z",
    "updatedAt": "2026-04-14T06:00:00Z"
  }
]
```

Counts such as "expected / checked-in / no-show / pending" are not provided — derive them client-side from `GET /stays?propertyId=...&dateFrom=...&dateTo=...`.

### POST /api/v1/stays/{id}/check-in
**Roles:** PROPERTY_ADMIN, FRONT_DESK

**Request:** the body is mandatory even when empty — send `{}` at minimum.
```json
{
  "roomId": "uuid",
  "overrideReason": "Manager approved mixed-gender room"
}
```
- `roomId` — optional; send it only to check the worker into a room other than the planned one. When omitted the planned room is used.
- `overrideReason` — optional; suppresses soft violations.
- There is **no** `notes` field on check-in; the stay's `notes` are untouched.

The constraint engine always runs (against the effective room), excluding this stay from its own counts. On success `status` becomes `CHECKED_IN` and `confirmed_by_user_id` is recorded internally.

**Response 200:** `StayResponse` with `status: "CHECKED_IN"`

**Response 409:** `error.stay.invalid_status_transition` — only an `EXPECTED_TODAY` stay can be checked in. A `PLANNED` stay cannot, even on its arrival date, until the scheduler has promoted it.

**Response 422:** constraint violation · **404:** `error.stay.not_found` / `error.room.not_found`

> **Caveat.** An overriding `roomId` is only checked for existence within the agency — it is not required to belong to the stay's property. The stay's `propertyId` is left unchanged, so a cross-property override produces an inconsistent record.

### POST /api/v1/stays/{id}/no-show
**Roles:** PROPERTY_ADMIN, FRONT_DESK

**Request:**
```json
{ "noShowReason": "NO_CONTACT" }
```
`noShowReason` is a **`@NotBlank` free-form string** — there is no enum and no server-side vocabulary. It is stored in the dedicated `stays.no_show_reason` column (`VARCHAR(100)`) and copied to the audit event's `reason`. It **no longer overwrites `notes`**; any operational note on the stay survives.

There is no `notes` field on this request.

**Response 200:** `StayResponse` with `status: "NO_SHOW"` and `noShowReason` populated

**Response 409:** `error.stay.invalid_status_transition` — only from `EXPECTED_TODAY`

**Response 400:** blank `noShowReason`, or longer than 100 characters (`@Size` matches the column width, so an over-long value is rejected at validation rather than at the database)

---

## 8. Check-out & Move

### POST /api/v1/stays/{id}/check-out
**Roles:** PROPERTY_ADMIN, FRONT_DESK

**Request:** body mandatory; send `{}` to check out on the planned end date.
```json
{ "actualDateTo": "2026-04-30" }
```
`actualDateTo` is optional. When present it **overwrites** `dateTo` — earlier or later, no validation either way. When absent `dateTo` is left as planned (including `null` for an open-ended stay). There is no reason field and no `notes` on this request.

The constraint engine does not run.

**Response 200:** `StayResponse` with `status: "CHECKED_OUT"`

**Response 409:** `error.stay.invalid_status_transition` — only from `CHECKED_IN`

> **Caveat.** An `actualDateTo` on or before `dateFrom` violates the `chk_stays_dates` CHECK and surfaces as **500**.

### POST /api/v1/stays/bulk-checkout
**Roles:** **all four** — AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK

**Request:**
```json
{ "stayIds": ["uuid-1", "uuid-2", "uuid-3"] }
```
`stayIds` is `@NotEmpty`. There is no reason field and no `actualDateTo` — dates are left exactly as planned.

**Response 200:**
```json
{
  "checkedOut": 2,
  "errors": 1,
  "results": [
    { "stayId": "uuid-1", "status": "checked_out", "errorCode": null },
    { "stayId": "uuid-2", "status": "checked_out", "errorCode": null },
    { "stayId": "uuid-3", "status": "error", "errorCode": "error.stay.invalid_status_transition" }
  ]
}
```
`status` is a **lowercase** string: `"checked_out"` or `"error"`. An unknown ID yields `errorCode: "error.stay.not_found"`. The same partial-rollback caveat as bulk-assign applies.

### POST /api/v1/stays/{id}/move
Move a checked-in worker to another room **in the same property**.

**Roles:** PROPERTY_ADMIN, FRONT_DESK

**Request:**
```json
{
  "targetRoomId": "uuid",
  "overrideReason": "Maintenance in the old room"
}
```
`targetRoomId` is `@NotNull`. `overrideReason` is optional and suppresses soft violations. There is **no** `targetPropertyId`, no reason field and no `notes` — **cross-property moves are not supported**.

Mechanics:
1. The current stay is set to `CHECKED_OUT` (its `dateFrom`/`dateTo` are left untouched)
2. A **new** stay is created: same worker, same property, `roomId = targetRoomId`, `dateFrom = today`, `dateTo` = the original stay's `dateTo`, `status = CHECKED_IN`
3. The constraint engine runs against the target room for `[today, originalDateTo)`, excluding the original stay
4. Audit records a `CHECKED_OUT` event on the old stay and a `MOVED` event on the new one

**Response 200:** a **single `StayResponse` — the new stay.** There is no `{previousStay, newStay}` wrapper. Re-fetch the original by ID if you need its post-move state.

**Response 409:**
- `error.stay.cannot_move_in_current_status` — the stay is not `CHECKED_IN`
- `error.stay.move_same_room` — `targetRoomId` equals the current room
- `error.stay.cannot_move_on_last_day` — the stay's `dateTo` is today or earlier, so there is no night left to reassign (an open-ended stay with `dateTo: null` is always movable)

**Response 422:** constraint violation on the target room · **404:** `error.stay.not_found` / `error.room.not_found`

> **Caveats.** The old stay keeps its original `dateTo`, so the closed and the new stay overlap on paper. `overrideReason` is used for the constraint check and written to the audit event but is **not** persisted on the new stay. `targetRoomId` is resolved by agency only, so a room in a different property is accepted while the new stay keeps the original `propertyId`.

---

## 9. Occupancy

All endpoints in this section and §10 are mounted under `/api/v1/properties/{propertyId}`.

> **Caveat.** None of them verifies that the property exists. An unknown or foreign `propertyId` returns an **empty array** (or a header-only CSV), not 404.

### GET /api/v1/properties/{id}/occupancy
Room-by-room occupancy for one date.

**Query params:** `?date=2026-04-14` (defaults to today)

Counts only stays with `status = CHECKED_IN` that span the date (`dateFrom <= date` and `dateTo` null or `> date`). Rooms with no occupants are included with an empty `occupants` array.

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, FRONT_DESK — agency-wide

**Response 200:** a **bare JSON array** of `RoomOccupancyResponse`. No `property` object, no `summary`, no pagination.

```json
[
  {
    "roomId": "uuid",
    "roomName": "12",
    "floor": "2",
    "capacity": 4,
    "blockedSpots": 0,
    "occupiedSpots": 2,
    "occupants": [
      { "stayId": "uuid", "workerId": "uuid", "firstName": "Andriy", "lastName": "Shevchenko" }
    ]
  }
]
```
`OccupantSummary` carries only these four fields — no `internalId`, no `gender`, no dates, no status. Totals and occupancy rates must be computed client-side.

### GET /api/v1/properties/{id}/exceptions
Rooms that need attention on a date.

**Query params:** `?date=2026-04-14` (defaults to today)

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, **FRONT_DESK** — agency-wide

**Response 200:** a **bare JSON array** of `OccupancyExceptionResponse`.

```json
[
  {
    "roomId": "uuid",
    "roomName": "5",
    "exceptionType": "OVER_CAPACITY",
    "capacity": 4,
    "blockedSpots": 0,
    "occupiedSpots": 5,
    "occupants": [ { "stayId": "uuid", "workerId": "uuid", "firstName": "Olena", "lastName": "Kovalenko" } ]
  }
]
```

`exceptionType` is one of exactly two values:

| Value | Meaning | `occupants[]` contains |
|-------|---------|------------------------|
| `OVER_CAPACITY` | checked-in occupants exceed `capacity - blockedSpots` | the checked-in occupants |
| `PENDING_ARRIVAL` | at least one `EXPECTED_TODAY` stay for this room, and the room is not over capacity | the expected (not yet arrived) occupants |

At most one entry per room — `OVER_CAPACITY` takes precedence. Rooms with neither condition are omitted. There is no worker-centric `UNASSIGNED_WORKER` exception.

### CSV exports

Three exports share the same mechanics: `?date=` (defaults to today) and `?language=` (defaults to `EN`; accepts `EN`, `PL`, `DE`, `RU`, `UA`, case-insensitive; anything else silently falls back to `EN`). All five languages are fully translated.

Responses are `Content-Type: text/csv;charset=UTF-8` with `Content-Disposition: attachment; filename="<prefix>_<today>.csv"`. Fields containing `,`, `"` or a newline are quoted with doubled inner quotes.

> The filename always uses **today's** date, even when `?date=` asks for another day.

| Endpoint | Roles | Localized header row |
|----------|-------|----------------------|
| `GET /properties/{id}/occupancy/export` | AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN | `Room,Floor,Capacity,Blocked,Occupied,WorkerId,FirstName,LastName` |
| `GET /properties/{id}/arrivals/export` | AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN, **FRONT_DESK** | `StayId,WorkerId,RoomId,DateFrom,DateTo,Status` |
| `GET /properties/{id}/exceptions/export` | AGENCY_ADMIN, AGENCY_PLANNER, PROPERTY_ADMIN | `Room,ExceptionType,Capacity,Blocked,Occupied,WorkerId,FirstName,LastName` |

Details:
- **occupancy export** — one row per occupant; a room with no occupants still emits one row with `Occupied` = `0` and blank worker columns.
- **arrivals export** — one row per `EXPECTED_TODAY` stay arriving on `date`; `Status` is the localized label (e.g. `Expected Today` / `Oczekiwany dzisiaj`).
- **exceptions export** — one row per occupant of each exception room; `ExceptionType` is the localized label (`Over Capacity`, `Pending Arrival`). A room-level exception with no occupants emits one row with blank worker columns.

---

## 10. Inspection Mode

### GET /api/v1/properties/{id}/inspection
Room-by-room roster to walk the building with.

**Query params:** `?date=2026-04-14` (defaults to today)

**Roles:** PROPERTY_ADMIN **only** — no other role, including AGENCY_ADMIN, can call this

**Response 200:** a **bare JSON array** of `InspectionRoomEntry`.

```json
[
  {
    "roomId": "uuid",
    "roomName": "12",
    "floor": "2",
    "expectedOccupants": [
      { "stayId": "uuid", "workerId": "uuid", "firstName": "Andriy", "lastName": "Shevchenko" }
    ],
    "checkedInOccupants": [
      { "stayId": "uuid", "workerId": "uuid", "firstName": "Andriy", "lastName": "Shevchenko" }
    ]
  }
]
```
`expectedOccupants` = stays that are `CHECKED_IN` **or** `EXPECTED_TODAY` on the date. `checkedInOccupants` = the `CHECKED_IN` subset only. Every room in the property is listed, empty ones included.

### POST /api/v1/properties/{id}/inspection
Compare what the inspector actually found against the checked-in roster.

**This endpoint persists nothing.** It writes no inspection record, no audit event and changes no stay. It is a pure read-only computation and returns **200**, not 201.

**Roles:** PROPERTY_ADMIN **only**

**`date` is a query parameter, not a body field:** `POST /api/v1/properties/{id}/inspection?date=2026-04-14` (defaults to today).

**Request:**
```json
{
  "rooms": [
    { "roomId": "uuid-a", "presentWorkerIds": ["uuid-1", "uuid-2"] },
    { "roomId": "uuid-b", "presentWorkerIds": [] }
  ]
}
```
`rooms` is `@NotNull` (an empty array is valid). Each entry needs `roomId` and `presentWorkerIds`, both `@NotNull`. There is no per-room `status` and no `notes`.

Every room of the property is evaluated, not just the ones you submit — **a room omitted from the body is treated as having zero present workers**, so all of its checked-in occupants come back as `EXPECTED_NOT_PRESENT`.

**Response 200:**
```json
{
  "discrepancies": [
    {
      "roomId": "uuid-a",
      "roomName": "12",
      "items": [
        { "workerId": "uuid-1", "discrepancyType": "EXPECTED_NOT_PRESENT" },
        { "workerId": "uuid-9", "discrepancyType": "UNEXPECTED_PRESENT" }
      ]
    }
  ],
  "hasDiscrepancies": true
}
```

| `discrepancyType` | Meaning |
|-------------------|---------|
| `EXPECTED_NOT_PRESENT` | the worker has a `CHECKED_IN` stay in that room for the date but was not reported present |
| `UNEXPECTED_PRESENT` | the worker was reported present in that room but has no `CHECKED_IN` stay there |

Rooms with no discrepancies are omitted. `hasDiscrepancies` is simply `discrepancies` being non-empty. Only worker IDs are returned — resolve names via `GET /workers/{id}`.

---

## 11. Audit Log

### GET /api/v1/audit
Query the immutable audit trail. Always scoped to the caller's agency.

**Query params:** `?page=0&size=50&sort=createdAt,desc&entityType=STAY&entityId=uuid&actorUserId=uuid&dateFrom=2026-04-01T00:00:00Z&dateTo=2026-04-14T23:59:59Z`

- The actor filter is **`actorUserId`**, not `userId`
- There is **no `action` filter** — filter client-side
- `dateFrom`/`dateTo` are **ISO instants**, not dates, and are compared against `createdAt`
- `sort` takes **entity property names** (`createdAt`, `action`, `entityType`)

**Roles:** AGENCY_ADMIN, AGENCY_PLANNER **only** — PROPERTY_ADMIN and FRONT_DESK get 403. Both permitted roles see the whole agency's trail; there is no per-property narrowing.

**Response 200:** `PageResponse<AuditEventResponse>`

### AuditEventResponse
```json
{
  "id": "uuid",
  "entityType": "STAY",
  "entityId": "uuid",
  "action": "CHECKED_IN",
  "actorUserId": "uuid",
  "previousState": { "status": "EXPECTED_TODAY", "workerId": "uuid", "roomId": "uuid", "propertyId": "uuid", "dateFrom": "2026-04-14" },
  "newState": { "status": "CHECKED_IN", "workerId": "uuid", "roomId": "uuid", "propertyId": "uuid", "dateFrom": "2026-04-14" },
  "reason": null,
  "createdAt": "2026-04-14T14:30:00Z"
}
```
- `actorUserId` is a **bare UUID** (nullable) — there is no expanded `performedBy` object with a name or role
- `previousState` / `newState` are free-form JSON snapshots and may be `null` (create events have no previous state, delete events have no new state). Their keys differ per entity type:
  - **STAY:** `status`, `workerId`, `roomId`, `propertyId`, `dateFrom`, and `dateTo`/`noShowReason` when set
  - **WORKER:** `status`, `internalId`, `firstName`, `lastName`, `gender`
  - **ROOM:** `name`, `status`, `capacity`, `blockedSpots`, `genderRule`
  - **PROPERTY:** `name`, `status`, and `city` when set
- `reason` is populated only from: stay update / check-in / move `overrideReason`, no-show `noShowReason`, and the literal `"bulk_import"` on workers created by CSV import. It is `null` everywhere else. There is no separate `notes` or reason-tag field.

---

## Appendix: Specified but not implemented

Everything below appeared in the original specification and **is not built**. There is no endpoint, no field and no enum constant for any of it. Nothing here should be called or coded against.

### Endpoints that do not exist

| Was specified as | Status |
|------------------|--------|
| `GET /api/v1/users` | **Not built.** There is no `UserController` at all — the entire Users section is unimplemented. Users exist only as a database table and JWT subject; they can be created and managed solely by direct database access. |
| `POST /api/v1/users` | **Not built.** No endpoint. |
| `GET /api/v1/users/{id}` | **Not built.** No endpoint. |
| `PUT /api/v1/users/{id}` | **Not built.** No endpoint. |
| `DELETE /api/v1/users/{id}` | **Not built.** No endpoint; there is no user deactivation path. |
| `PUT /api/v1/users/me/language` | **Not built.** No endpoint; `User.language` can only be changed in the database. |
| `UserResponse` (with `status`, `lastLoginAt`, `createdAt`) | **Not built.** The only user payload in the API is `AuthResponse.UserInfo` (§1), which has no `status`, `lastLoginAt` or `createdAt`. |
| `GET /api/v1/workers/{id}/stays` | **Not built.** No endpoint. Use `GET /api/v1/stays?workerId={id}` instead — same data, paginated, with the documented stay filters. |
| `POST /api/v1/properties/{propertyId}/rooms/bulk` | **Not built.** No endpoint. Create rooms one at a time with `POST /properties/{propertyId}/rooms`. |
| `GET /api/v1/dashboard` | **Not built.** There is no dashboard controller, service or aggregate query anywhere in the codebase. |
| `GET /api/v1/properties/{id}/dashboard` | **Not built.** No endpoint. Property-level totals must be aggregated client-side from `GET /properties/{id}/occupancy` and `GET /stays`. |

### Enum values that do not exist

| Was specified as | Status |
|------------------|--------|
| `PropertyType` (`INTERNAL` \| `PARTNER`) | **Not built.** There is no `PropertyType` enum, no `type` column on `properties`, no `type` field on any request or response, and no `?type=` filter. |
| `StayStatus.MOVED` | **Not built.** `StayStatus` has no `MOVED` constant. A move sets the original stay to `CHECKED_OUT` and creates a new `CHECKED_IN` stay (§8). `MOVED` exists only as an **`AuditAction`**, recorded against the new stay. |
| `WorkerStatus.BLACKLISTED` | **Not built.** `WorkerStatus` is `ACTIVE \| INACTIVE \| DELETED`. |
| `GenderRule.MIXED` / `GenderRule.PER_ROOM` | **Not built.** `GenderRule` is `ANY \| MALE_ONLY \| FEMALE_ONLY`, and it lives on the room only — properties have no gender rule. `ANY` is the closest equivalent of `MIXED`. |
| `EntityStatus.MAINTENANCE` | **Not built.** There is no shared `EntityStatus`. `PropertyStatus` is `ACTIVE \| INACTIVE`; `RoomStatus` is `ACTIVE \| BLOCKED`. |
| `AuditAction.IMPORTED` | **Not built.** CSV-imported workers are logged as `CREATED` with `reason: "bulk_import"`. |
| `Language` as an enum | **Not built.** `User.language` is an unvalidated `String` (§Enums). |

### Constraint types that do not exist

| Was specified as | Status |
|------------------|--------|
| `WORKER_BLACKLISTED` (soft, `constraint.worker.blacklisted`) | **Not built.** No such constraint class, no such message code, and no blacklist concept on `Worker`. |
| `OVER_PLANNED` (soft, `constraint.room.over_planned`) | **Not built.** No such constraint class and no such message code. Over-planning is caught as the hard `CAPACITY_EXCEEDED` / `constraint.room.capacity.exceeded` violation instead. |
| `PROPERTY_BLOCKED` (`constraint.property.blocked`) | **Not built under that name.** The implemented equivalent is `PROPERTY_INACTIVE` / `constraint.property.inactive` (§6). |
| `constraint.gender.mismatch` message code | **Not built under that name.** The implemented code is `constraint.room.gender_mismatch`. |
| `error.constraint.violations_found` message code | **Not built.** The implemented codes are `error.constraint.violated` (hard) and `error.constraint.soft_violations` (soft). |

### Reason-tag vocabulary

The original specification defined a fourteen-value predefined tag vocabulary (`ON_TIME`, `ARRIVED_LATE`, `DOCS_MISSING`, `NO_CONTACT`, `TRANSPORT_DELAY`, `PLANNED_DEPARTURE`, `PROJECT_ENDED`, `EARLY_DEPARTURE`, `ROOM_CONFLICT`, `MAINTENANCE`, `CAPACITY_ISSUE`, `WORKER_REQUEST`, `MANAGER_DECISION`, `OTHER`) shared across check-in, check-out, move, stay and room-block operations.

**Not built.** There is no reason-tag enum, constant set or validation anywhere in the codebase, and no endpoint accepts a reason tag except one:

- `POST /stays/{id}/no-show` takes `noShowReason` as a **free-form `@NotBlank` String** (max 100 chars, matching the column). Any text is accepted.

Check-in, check-out, bulk-checkout, move and cancel accept **no** reason tag at all. Check-in and move take a free-form `overrideReason` instead, which serves a different purpose (suppressing soft constraint violations).

If the frontend wants a controlled vocabulary for no-show, it must define and enforce it client-side; the backend will store whatever string it is sent.

### Response fields that do not exist

| Was specified as | Status |
|------------------|--------|
| `Location` header on 201 responses | **Not built.** No `201` response sets one. |
| `traceId` in error bodies | Present in the `ErrorResponse` record but **never populated**, and omitted from the JSON by `@JsonInclude(NON_NULL)`. |
| `WorkerResponse.currentStay` | **Not built.** |
| `PropertyResponse.roomSummary` (`totalRooms`, `totalCapacity`, `totalBlockedSpots`, `currentOccupancy`) | **Not built.** |
| `RoomResponse.occupants[]`, `RoomResponse.currentOccupancy` | **Not built.** Use `GET /properties/{id}/occupancy`. |
| `RoomResponse.roomNumber` | **Not built.** Rooms use `name` (a string). No API surface uses `roomNumber`. |
| `StayResponse.worker` / `.property` / `.room` nested objects | **Not built.** `StayResponse` is flat (§5). |
| `StayResponse.createdBy` / `.confirmedBy` | **Not built.** `confirmed_by_user_id` is stored but never serialised. |
| `WorkerSummary`, `StaySummary`, `PropertySummary`, `RoomSummary` nested payloads | `WorkerSummary` and `StaySummary` records exist with mapper methods but **no endpoint returns them**. `PropertySummary` and `RoomSummary` do not exist at all. |
| Arrivals wrapper with `date`, `property`, `expected[]`, `summary{}` | **Not built.** The endpoint returns a bare array (§7). |
| Occupancy wrapper with `property`, `date`, `rooms[]`, `summary{}` | **Not built.** Bare array (§9). |
| Exceptions wrapper with `property`, `date`, `exceptions[]` | **Not built.** Bare array (§9). |
| `UNASSIGNED_WORKER` exception type (`exception.worker.no_bed_tonight`) | **Not built.** Only `OVER_CAPACITY` and `PENDING_ARRIVAL` exist. |
| Inspection wrapper with `property`, `date`, `inspectedBy` | **Not built.** Bare array (§10). |
| Persisted inspection report (`id`, `inspectedBy`, `totalRooms`, `okRooms`, `discrepancyRooms`) | **Not built.** `POST .../inspection` computes and returns discrepancies without storing anything (§10). |
| `AuditEventResponse.performedBy` object, `.notes`, `.reasonTag` | **Not built.** Only `actorUserId` (a bare UUID) and a single `reason` string (§11). |
| Bulk-assign / bulk-checkout `total` / `succeeded` / `failed` counters and uppercase `CREATED` / `FAILED` / `CHECKED_OUT` statuses | **Not built.** The real shapes use `created`/`errors` and `checkedOut`/`errors` with **lowercase** `created`, `checked_out`, `error` (§5, §8). |
| Worker import `totalRows`, and `errors` as an array | **Not built.** `errors` is an **integer count**; the array is `errorDetails` (§2). |
