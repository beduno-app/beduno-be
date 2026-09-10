# Beduno Backend — Open Questions & Decision Log

This document exists because the design docs (`analysis.md`, `architecture.md`,
`api-specification.md`, `implementation-plan.md`) were written *before* the build,
and the implementation diverged from them in a lot of places. Those docs have now
been reconciled to describe what the code actually does.

Reconciling in that direction is fast and safe, but it carries one risk: drift that
was never a deliberate decision quietly becomes the specification. This file is the
countermeasure. Nothing was erased — every divergence, gap and judgement call landed
here instead.

**How to use it:** section 3 is the review agenda. Each numbered item is a decision
the code makes today that nobody explicitly ratified. Work down the list and either
bless it (fold it into the design docs as intended behaviour) or file it as work.

---

## 1. Decisions taken during the reconciliation

These were made autonomously while the docs were being reconciled and the approved
defect fixes applied. Each is reversible; none is load-bearing.

| # | Decision | Rationale | Drift risk if wrong |
|---|----------|-----------|---------------------|
| D1 | Docs follow code, divergences recorded here rather than erased | The docs were a pre-build design pass, not a ratified spec; making code follow them would have meant building a large amount of unreviewed scope | Low — nothing was deleted, only relocated |
| D2 | No-show reason stored in a new `stays.no_show_reason` column (V7) rather than appended to `notes` or left audit-only | The old code overwrote `notes`, destroying operator data. A dedicated column preserves both and keeps the reason visible on `StayResponse` | **Ratified 2026-09-09: the column keeps the name `no_show_reason`.** `reason_tag` reads as a generic slot, and Q12 wants reason tags on check-in, check-out and move too — one column called `reason_tag` could not hold them. The request field was the inconsistent half (`reasonTag` in, `noShowReason` out) and was renamed to match, which cost nothing while no client exists |
| D3 | Property/room `DELETE` guarded with 409 instead of converting to soft delete | Explicitly chosen: no migration, and it matches the user-visible contract the docs already promised | See Q7 — the guard blocks on *any* referencing stay, so a property with history becomes permanently undeletable. Soft delete is the real answer |
| D4 | `error.property.has_stays` kept even though it is currently unreachable | A stay requires a room, and the rooms guard fires first, so the stays branch cannot trigger today. Kept as defence-in-depth against future paths | None; it is dead but harmless. Delete it if you dislike unreachable branches |
| D5 | A refresh token whose subject no longer exists returns 401, not 404 | Returning 404 would let an unauthenticated caller probe whether an account was deleted | None |
| D6 | `RateLimitFilter`'s 429 body changed from `{"code":...}` to the standard `ErrorResponse` envelope | It was the only endpoint in the system emitting a different error shape, and the shape was undocumented | Any client parsing the old key would break. Judged very low risk since it was never specified |
| D7 | New i18n values written as raw UTF-8, not `\uXXXX` escapes | `spring.messages.encoding: UTF-8` is set explicitly, and hand-escaping Cyrillic is error-prone | None functionally; the bundles now mix both styles |
| D8 | Property scoping and override permissions documented as-is rather than enforced | Explicitly chosen: document now, ticket the fix. See section 2 | The docs no longer over-promise, but the gap is real and unmitigated |
| D9 | `messages_ua.properties` renamed to `messages_uk.properties` | `ExportService` resolves `Locale("uk")` and `WebConfig` registers `uk`, so the `_ua` file was never loaded and Ukrainian silently served English. `uk` is the ISO 639-1 code | None — the public API parameter is still `UA`. Had the previous commit shipped alone, the UA translations would have been dead weight |
| D10 | `NoShowRequest.noShowReason` bounded with `@Size(max = 100)` | D2 moved the reason from `notes` (unbounded TEXT) to `VARCHAR(100)`, which would have turned a long tag into a 500. This closes a hole D2 opened | None |
| D11 | Two `@Operation` descriptions corrected in code | They are published in the OpenAPI document, so they are user-facing docs: property delete claimed a soft delete, and the exports claimed EN/PL only | None |
| D12 | S5 closed by making `users.email` globally unique (V8), not by scoping login to an agency | Scoping login needs an agency identifier in the login call that no client sends and no endpoint exposes. Global uniqueness keeps the contract at email plus password | One person cannot hold accounts at two agencies. `uq_users_email_agency` was left in place, so dropping `uq_users_email` is the whole revert |
| D13 | First agency and admin created by an env-gated startup bootstrapper, not by a seed migration | A migration runs once ever: if the variables were absent on first boot the chance is gone, and the credentials would have to sit in a committed file. The bootstrapper is idempotent and re-runnable | The variables are read at every startup; leaving them set in the environment is a standing credential. Documented in the deployment runbook |

---

## 2. Security gaps

Originally all documented-but-unfixed. **S3, S4, S5, S7 and S8 were closed before the
first deployment** — the items below are marked individually. S1, S2 and S6 remain
open and are unmitigated.

### S1 — Per-property scoping is barely enforced (highest priority)

`CurrentUser.hasPropertyAccess` is called in exactly two places:
`PropertyController.update` and `RoomController.checkPropertyAccess` (room
create/update).

Everything else is agency-wide. A `PROPERTY_ADMIN` or `FRONT_DESK` user assigned to
one property can operate on every property in the agency:

| Endpoint | Call site needing a check |
|---|---|
| `POST /stays`, `PUT /stays/{id}`, `DELETE /stays/{id}` | `StayController` — no check at all |
| `POST /stays/{id}/check-in`, `/check-out`, `/no-show`, `/move` | `StayController` |
| `GET /stays`, `GET /stays/{id}` | `StayService.getStayOrThrow` filters by `agencyId` only |
| `GET /stays/arrivals` | `StayController` |
| `GET /properties/{id}/occupancy`, `/exceptions`, `/inspection` | `OccupancyController` |
| all three `/export` endpoints | `OccupancyController` |
| `GET /properties`, `GET /properties/{id}` | `PropertyService.findAll` — agency-wide for all roles |

Fixing this needs new tests: today no test asserts that a scoped user is refused.

### S2 — Soft-constraint override is not role-restricted

`StayService.runConstraints` only checks that `overrideReason` is non-null. Any role
that can reach the endpoint can override a soft violation, including `FRONT_DESK`.
The design intended Admin + Property Admin only.

### S3 — CORS is wide open with credentials — **FIXED**

`WebConfig.addCorsMappings` set `allowedOriginPatterns("*")` together with
`allowCredentials(true)` on `/api/**`. It is now an allowlist bound to
`CORS_ALLOWED_ORIGINS`; an empty list registers no mapping at all, so an
unconfigured deployment rejects cross-origin requests rather than allowing every
one of them.

### S4 — Rate-limit bucket map grows without bound — **FIXED**

`RateLimitFilter.buckets` was a `ConcurrentHashMap` keyed by client IP that was never
evicted — unbounded memory growth under sustained traffic from many source addresses.
A scheduled sweep now drops buckets idle past ten minutes, with a fail-open cap as a
backstop between sweeps.

### S5 — Login is not tenant-scoped — **FIXED**

`UserRepository.findByEmail` has no `agencyId` filter, and `users` was only
`UNIQUE (agency_id, email)` — the same address could legitimately exist in two
agencies, and login resolved non-deterministically. V8 makes the address globally
unique; see D12 for why that rather than scoping login by agency.

### S7 — `/auth/refresh` accepts an access token as a refresh token — **FIXED**

`AuthService.refresh` called `validateToken` and `getUserId` but never checked the
`type` claim, so any valid access token could be exchanged for a fresh 7-day refresh
token — a privilege-lifetime escalation on the more widely exposed of the two tokens.
Both directions are now checked: `AuthService.refresh` requires `type: "refresh"`,
and `TenantFilter` refuses a refresh token presented as a bearer credential (which
also removes a 500, since a refresh token carries no `agencyId` claim).

### S8 — `GET /auth/me` returns 500 when called anonymously — **FIXED**

`SecurityConfig` permitted `/api/v1/auth/**` wholesale, so an unauthenticated call
reached `AuthController.me` with a null `@AuthenticationPrincipal` and died in the
catch-all handler as a 500. Only `/auth/login` and `/auth/refresh` are anonymous now;
`/me` falls through to `anyRequest().authenticated()` and answers 401 through
`RestAuthenticationEntryPoint` like every other protected endpoint.

### S6 — No row-level security

`analysis.md` previously asserted RLS prevented cross-tenant leaks. There is no
`CREATE POLICY` anywhere; isolation is application-level `agency_id` filtering only.
Two queries deliberately bypass it: `StayRepository.findPlannedArrivingOn` (the
scheduler sweep) and `OccupancyService.loadWorkers` (`findAllById`, currently safe
because the IDs come from tenant-filtered stays).

---

## 3. Review agenda — decisions the code made that nobody ratified

Each of these is a place the implementation chose differently from the design. They
are not bugs; they are unexamined choices. Confirm or change.

**Q1 — Should PLANNED stays consume capacity?**
`CapacityConstraint` and `DoubleBookingConstraint` count `PLANNED`,
`EXPECTED_TODAY` and `CHECKED_IN`. The design said checked-in only, and separately
wanted over-planning to be a *soft* warning. As built, over-planning is a hard
block. This is arguably the single most consequential divergence: it makes the
planner unable to provisionally over-allocate.

**Q2 — Is "move" correctly modelled as checkout + new stay?**
There is no `MOVED` status. A move terminates the stay (`CHECKED_OUT`) and creates a
fresh `CHECKED_IN` one, linked only by an audit event. Consequence: a worker's stay
history fragments into one row per room, and "how long has this person been here" is
no longer a single query. The design assumed a `MOVED` status instead.

**Q3 — Should a CHECKED_IN stay be cancellable?**
Currently `CHECKED_IN -> CANCELLED` is rejected; only `CHECKED_OUT` is reachable.
The design expected "any non-terminal → cancelled" for early departures.

**Q4 — Cross-property moves are unsupported.**
`StayService.move` copies the original `propertyId`; only the room can change. The
design explicitly promised "possibly different property".

**Q5 — Inspection submissions persist nothing.**
`POST /properties/{id}/inspection` is `@Transactional(readOnly = true)`. It computes
discrepancies and returns them; no report row is written and no audit event is
logged. The design described a persisted report with an id and timestamp. If
inspection is meant to be audit evidence, this does not deliver it.

**Q6 — There is no user-management API.**
No `UserController`, no `AgencyController`. Users can only be created by SQL. Every
doc that says "Agency Admin manages users" describes something unbuilt. Nothing else
in the MVP works without users existing, so this is a real onboarding gap.

**Q7 — Should properties and rooms be soft-deleted?**
They are hard-deleted, now behind a 409 guard. Because the guard counts *terminal*
stays too (they still hold the foreign key), a property that has ever hosted anyone
can never be deleted. Only `workers` has `deleted_at`. Decide whether delete means
archive.

**Q8 — Bulk-assign discards structured constraint details.**
`StayService.bulkAssign` catches the exception and stores `e.getMessage()` — a bare
message code string. The per-item violation `details` (room, capacity, params) are
lost, so a UI cannot explain *why* an assignment failed.

**Q9 — Sort keys are inconsistent between endpoints.**
Workers, properties and stays use native queries, so `?sort=` takes snake_case
column names (`last_name`, `date_from`). Rooms uses a derived JPA query, so it takes
the entity property (`name`). An unknown key reaches Postgres and 500s rather than
400s. Either move to JPQL/Specification or whitelist sort keys.

**Q10 — Bean-validation errors break the message-code rule.**
Every other error returns a message *code*. `MethodArgumentNotValidException` returns
raw English Bean Validation text ("must not be blank") inside `details[]`, which no
translation layer can localise.

**Q11 — Blacklist, property types, and property-level gender rules do not exist.**
`WorkerStatus` is `ACTIVE|INACTIVE|DELETED`. There is no `type` on `Property` and no
property-level `GenderRule` for rooms to inherit. Decide whether these are dropped
or deferred.

**Q12 — Reason tags are free text.**
The design specified a predefined, localisable vocabulary. Only no-show takes a
reason, and it is an unvalidated `@NotBlank String`. Check-in, check-out and move
accept no reason at all, so those actions carry no structured "why" into the audit
trail.

**Q13 — `traceId` is plumbed but never populated.**
`TenantFilter` puts a `requestId` into MDC; `ErrorResponse.traceId` is always null
and omitted from the payload. Correlating a client-reported error with a log line is
currently impossible.

**Q14 — Service-entry logging is a convention nobody follows.**
Three classes use `@Slf4j`; there is exactly one `log.info` in `src/main/java`.
Either adopt the convention or drop it from the guidelines.

**Q15 — UUID v7 was decided but not implemented.**
`implementation-plan.md` chose time-ordered UUID v7 for sortability and index
locality. `BaseEntity` uses `GenerationType.UUID` and the migrations use
`gen_random_uuid()` — both random v4. The stated benefit was never realised.

**Q16 — Client errors surface as 500.**
`GlobalExceptionHandler` declares `@ExceptionHandler(Exception.class)` and does not
extend `ResponseEntityExceptionHandler`, so Spring MVC's own exceptions fall into
the catch-all. A missing required query parameter (`GET /stays/arrivals` with no
`propertyId`), an unparseable UUID, date or enum, malformed JSON, or an empty body
all return 500 instead of 400. This is probably the highest-volume correctness bug
in the codebase — every malformed client request is misreported.

**Q17 — `roomId` is never checked against `propertyId`.**
Stay create, stay update, the check-in room override and move all resolve the room
by agency only (`getRoomOrThrow`). A room belonging to a different property is
accepted, and the stay keeps its original, now-wrong `propertyId`.

**Q18 — No `dateTo > dateFrom` validation at the DTO layer.**
`CreateStayRequest`, `UpdateStayRequest` and `CheckOutRequest.actualDateTo` are
unvalidated, so an inverted range reaches the `chk_stays_dates` CHECK and returns
500 rather than 400.

**Q19 — Check-in corrupts its own audit record.**
`StayService.checkIn` assigns `stay.setRoomId(request.roomId())` *before* taking
`var previous = snapshot(stay)`. When a check-in overrides the room, the "previous
state" already contains the new room, so the original assignment is lost from the
audit trail — precisely the fact an inspection would need.

**Q20 — Move leaves the original stay's `dateTo` untouched.**
The closed stay keeps its original end date while the replacement runs from today,
so the two overlap in any date-range query. The replacement also drops the
original's `notes` and never persists the caller's `overrideReason`.

**Q21 — Bulk operations can report partial success and then roll back entirely.**
`bulkAssign` and `bulkCheckout` catch per-item exceptions inside a single
`@Transactional` method. Any JPA exception marks the transaction rollback-only, so
a response saying "3 created, 1 error" can still fail wholesale at commit. The
per-item `errorCode` is also `e.getMessage()`, which leaks raw exception text for
anything that is not a `BusinessException`.

**Q22 — Occupancy endpoints do not verify the property exists.**
`occupancy`, `exceptions`, `inspection` and the three exports return an empty array
or a header-only CSV for an unknown or foreign `propertyId`, instead of 404.

**Q23 — `PUT /workers/{id}` can set `status: DELETED` without `deletedAt`.**
That produces a state the `DELETE` endpoint would never create, and read filters
key off `status`, so the two paths disagree.

**Q24 — CSV export filenames always use today's date**, ignoring `?date=`.

**Q25 — `Gender.OTHER` violates both `MALE_ONLY` and `FEMALE_ONLY`.**
Probably intended, but it means a worker with `OTHER` can only be placed in an
`ANY` room. Confirm it is a product decision.

**Q26 — Dead code.** `WorkerSummary` and `StaySummary` and their mapper methods are
returned by no endpoint. `StayService.toStringMap` returns its argument unchanged.

**Q27 — `Agency.status` and `User.status` are raw `String`s** while every other
status field in the domain is an `@Enumerated` enum.

**Q28 — Email is matched case-sensitively everywhere.**
`UserRepository.findByEmail` is an exact match and `uq_users_email` (V8) is a
case-sensitive unique index, so `Anna@agency.pl` and `anna@agency.pl` are two
different accounts and a user who capitalises their address at login is simply not
found. Nothing normalises case on the way in either — `BootstrapRunner` trims but
does not fold. Fixing it means lowercasing on write and on lookup, or a functional
unique index on `lower(email)`; until then the deployment runbook says to create
accounts in lowercase.

---

## 4. Specified but never built

Collected from all four design docs. Each is now marked in place, and none has an
implementation:

- User management API and Agency management API
- Dashboard / summary endpoints
- `GET /workers/{id}/stays`, `POST /properties/{id}/rooms/bulk`
- `WORKER_BLACKLISTED` and `OVER_PLANNED` constraints
- `PropertyType` (internal/partner), property-level `GenderRule`, `PER_ROOM` inheritance
- `MOVED` stay status
- Predefined reason-tag vocabulary
- Row-level security
- Soft delete for stays, properties and rooms
- Audit `action` filter (`GET /audit` supports entityType, entityId, actorUserId, date range only)
- Actuator `metrics` endpoint (only `health` and `info` are exposed)
- RestAssured / a distinct E2E test tier
- Load tests for the occupancy and arrivals queries
- Nested worker/property/room objects and `createdBy`/`confirmedBy` on `StayResponse`

---

## 5. Test-quality gaps

- `WorkerIntegrationTest.shouldNotReturnWorkersFromOtherAgency` asserts only HTTP 200
  and a non-null body. The actual isolation check is left as a comment.
- `PropertyIntegrationTest.shouldNotReturnPropertiesFromOtherAgency` asserts that the
  queried agency's *own* property is present — never that the other agency's is
  absent. Both tests pass today even if tenant isolation were broken.
- No test asserts that a property-scoped user is refused access to another property
  (see S1) — because the behaviour does not exist.
- No coverage tool is configured, so any stated coverage target is unenforced.
