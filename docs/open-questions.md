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
| D2 | No-show reason stored in a new `stays.no_show_reason` column (V7) rather than appended to `notes` or left audit-only | The old code overwrote `notes`, destroying operator data. A dedicated column preserves both and keeps the reason visible on `StayResponse` | The design doc called this `reason_tag`. The column is new and unreleased, so rename it now if you prefer that name — after a deploy it costs a migration |
| D3 | Property/room `DELETE` guarded with 409 instead of converting to soft delete | Explicitly chosen: no migration, and it matches the user-visible contract the docs already promised | See Q7 — the guard blocks on *any* referencing stay, so a property with history becomes permanently undeletable. Soft delete is the real answer |
| D4 | `error.property.has_stays` kept even though it is currently unreachable | A stay requires a room, and the rooms guard fires first, so the stays branch cannot trigger today. Kept as defence-in-depth against future paths | None; it is dead but harmless. Delete it if you dislike unreachable branches |
| D5 | A refresh token whose subject no longer exists returns 401, not 404 | Returning 404 would let an unauthenticated caller probe whether an account was deleted | None |
| D6 | `RateLimitFilter`'s 429 body changed from `{"code":...}` to the standard `ErrorResponse` envelope | It was the only endpoint in the system emitting a different error shape, and the shape was undocumented | Any client parsing the old key would break. Judged very low risk since it was never specified |
| D7 | New i18n values written as raw UTF-8, not `\uXXXX` escapes | `spring.messages.encoding: UTF-8` is set explicitly, and hand-escaping Cyrillic is error-prone | None functionally; the bundles now mix both styles |
| D8 | Property scoping and override permissions documented as-is rather than enforced | Explicitly chosen: document now, ticket the fix. See section 2 | The docs no longer over-promise, but the gap is real and unmitigated |

---

## 2. Security gaps — documented, not fixed

Ticketed deliberately. The docs previously claimed these protections existed; they
now describe reality instead. **The underlying exposure is unchanged.**

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

### S3 — CORS is wide open with credentials

`WebConfig.addCorsMappings` sets `allowedOriginPatterns("*")` together with
`allowCredentials(true)` on `/api/**`. Should be an env-driven allowlist before any
public deployment.

### S4 — Rate-limit bucket map grows without bound

`RateLimitFilter.buckets` is a `ConcurrentHashMap` keyed by client IP that is never
evicted. Unbounded memory growth under sustained traffic from many source addresses.

### S5 — Login is not tenant-scoped

`UserRepository.findByEmail` has no `agencyId` filter, but `users` is
`UNIQUE (agency_id, email)` — the same address can legitimately exist in two
agencies, and login would resolve non-deterministically. Either scope login by
agency or make email globally unique.

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
