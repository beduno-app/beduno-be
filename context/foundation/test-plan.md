# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-09-12

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the
   risk wins. Do not promote to e2e because e2e "feels safer." Do not put a
   vision model on top of a deterministic check that already catches the
   regression.
2. **User concerns are first-class evidence.** Risks anchored in "the team
   is worried about X, and the failure would surface somewhere in area Y"
   carry the same weight as PRD lines or hot-spot data.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from documents,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is
   produced by `/10x-research` during each rollout phase. If the plan and
   research disagree about where the failure lives, research is the
   ground truth.

Hot-spot scope used for likelihood weighting: `src/main/java/com/beduno`,
`src/test/java/com/beduno`, `src/main/resources/i18n`,
`src/main/resources/db`.

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact × likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (that is
research's job, see §1 principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|---|---|---|---|
| 1 | A worker ends up assigned to a bed that is already occupied or blocked, undetected until move-in | High | High | Interview Q1; PRD guardrail "no double-booking, over-capacity, or assignment into a blocked room" (prd.md:115,217-218); hot-spot dir `src/main/java/com/beduno/stay` |
| 2 | The constraint engine mishandles combined rules or date-range boundary cases (checkout-day == checkin-day, multiple constraints firing together) | High | Medium | Interview Q3 (low confidence in `stay/constraint/`) + Q4 (under-tested boundary/combination cases); hot-spot dir `src/main/java/com/beduno/stay` |
| 3 | A FRONT_DESK/PROPERTY_ADMIN user acts on stays/rooms/beds at a property they are not assigned to, within their own agency | High | Medium | PRD explicit gap statement (prd.md:279-281); roadmap: `properties` claim enforced at only 3 of ~30 endpoints (roadmap.md:82); roadmap slice S-08 `enforce-property-scoping` is `ready` |
| 4 | A deletable entity with a RESTRICT foreign key (room/bed/a future entity such as a crew) has no application-level guard, surfacing an unhandled 500 instead of a clean 409 | Medium | Medium | AGENTS.md integration-test conventions; roadmap slice S-04 `worker-crews` is `ready` (next entity likely to need this pattern) |
| 5 | A bed-touching write path, or a new non-agency identity (hotel partner, roadmap F-01), leaks or accepts another agency's data | High | Medium | Interview Q1 alternative; AGENTS.md "every module's suite must include a cross-agency isolation case"; shape-notes.md:357-359 (F-01 is "the first identity that does not belong to exactly one agency") |
| 6 | A future migration/backfill (for roadmap slices S-02/S-04/F-02) behaves correctly against a fresh test database but corrupts or mis-sets data shaped like production | High | Medium | Interview Q2 (a migration/backfill ran clean in test but corrupted real data); roadmap sequencing (roadmap.md:43-60) |
| 7 | The rate limiter's client-IP derivation can be bypassed by a forged proxy header on a non-CloudFront connection | Medium | Medium | Hot-spot: `RateLimitFilter.java` (4 touches/30d) plus a recent `fix/cloudfront-client-ip` merge in git history — abuse-lens (resource abuse / rate-limit bypass) |

**Impact × Likelihood rubric:**

| Rating | Impact | Likelihood |
|---|---|---|
| High | user loses access, data, or money; failure is publicly visible | area changes weekly, or we have already been burned here |
| Medium | feature degrades, a workaround exists, only some users affected | touched occasionally, has been a source of bugs |
| Low | cosmetic, easily reverted, no data effect | stable code, rarely touched |

No High-impact × Low-likelihood rows were found; nothing was deferred to
observability. Risk #3 and #7 are the abuse-lens scenarios required for a
product with auth, role-scoped access, and rate limiting (IDOR-class access
control gap and resource-abuse/rate-limit bypass respectively).

**Challenger findings:** Risks 4 and 6 were reframed during synthesis —
both initially risked describing a safeguard that does not exist yet for
entities/migrations not yet built. Reframed to "verify the established
guard pattern generalizes correctly to the next entity/migration the
roadmap concretely introduces," which is testable against what exists
today plus what's next in sequence, not speculative future code.

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|---|---|---|---|---|---|
| #1 | Attempting to place two active stays in the same bed (or into a BLOCKED bed) is rejected end-to-end via every public write path (create/move/bulk-assign/check-in), and an overlapping-but-not-identical date range is rejected too, not just an exact-same-day one. | "The constraint engine returning a hard violation implies the HTTP layer stops the write" — verify the controller/exception-translation layer actually returns 409/422, not just that `ConstraintEngine.evaluate()` reports a violation. | Exact call chain from each of the 5 write paths into `ConstraintEngine`/`BedOccupancyConstraint`/`DoubleBookingConstraint`; which HTTP status each rejection maps to. | integration (real HTTP + Testcontainers) | Asserting only against the constraint engine's internal violation list without confirming the write actually failed through the real HTTP path; an oracle-problem test that re-asserts current `resolveBed` output as correct without checking it against the "same bed = reject" business rule. |
| #2 | Overlapping stays whose date ranges touch at a boundary resolve per the actual adjacency rule (not off-by-one), and two constraints that should both fire (e.g. gender rule + occupancy) are both evaluated and surfaced, not short-circuited by the first hard violation. | "One passing test per constraint type implies all constraints compose correctly" — false if the engine short-circuits or constraints don't independently accumulate. | Whether `ConstraintEngine` evaluates every `StayConstraint` bean or stops at the first hard violation; the exact boundary semantics (`<` vs `<=`) used by `resolveBed` and the active-stay-count queries. | integration, or unit against `ConstraintEngine` directly if research confirms it needs no DB dependency for the boundary logic itself | Copying the exact boundary operator from the query into the test assertion (oracle problem) instead of deriving expected behavior from the business rule ("a worker checking out today frees the bed for a worker checking in today"). |
| #3 | A FRONT_DESK/PROPERTY_ADMIN token scoped to property A cannot read or write stays/rooms/beds at property B in the same agency, across every endpoint accepting a property-scoped resource — not only the 3 currently enforced. | "Cross-agency isolation tests cover this" — they don't; this is a within-tenant, cross-property authorization gap, a different failure class from tenant isolation. | Every endpoint that should check `assignedPropertyIds`, and which of the ~30 actually call the property-access check today. | integration test matrix (one request per resource type, property-A token targeting property B) | Testing only the 3 endpoints already known to enforce this and calling the module "covered" — the finding's whole point is the other ~27. |
| #4 | Every entity with a RESTRICT foreign key referenced by an active row returns a clean 409 on delete, never an unhandled 500, across current entities (property, room, bed) and the next one the roadmap introduces. | "We fixed it for one entity so the pattern is now safe everywhere" — each entity needs its own explicit guard and test; nothing enforces this structurally. | Every RESTRICT FK in the schema (migrations without `ON DELETE`) and whether each referencing service's `delete()` has a guard. | integration, one test per entity | A single generic "delete guard" test assumed to generalize; not verifying the 409 is actually caused by the reference conflict and not some other cause. |
| #5 | Every write path touching a bed, and once F-01 lands, hotel-partner-scoped reads, rejects or 404s a request whose id belongs to a different agency, even when the id is syntactically valid. | "Explicit-bedId validation is the only place tenant injection could happen" — must verify the auto-assign path (no client-supplied id) can't leak another agency's bed through a different query path. | Every repository method reachable from bed-touching write paths filters by `agencyId`; whether the sanctioned cross-tenant exception pattern is being copied incorrectly anywhere newer. | integration, one test per write path | One cross-agency test per *module* treated as sufficient when a module has 5 write paths — the failure mode lives per-path, not per-module. |
| #6 | A migration that alters or backfills a table with pre-existing rows (not a fresh empty DB) produces the exact expected end state, verified for the next roadmap migration, not retrospectively for one already shipped. | "It ran clean against Testcontainers' fresh schema, so it's safe" — the past incident was exactly this: clean-against-fresh does not imply clean-against-populated. | How (or whether) the project currently seeds a realistic pre-migration dataset for migration testing; what Flyway test harness exists beyond the app's own bootstrap. | dedicated Flyway migration integration test seeding representative pre-migration rows | Asserting the backfilled value by re-deriving it with the same logic the migration used (oracle problem) instead of independently known expected values from fixture data. |
| #7 | The rate limiter's IP derivation cannot be bypassed by a caller forging a proxy header unless the request actually came through the trusted CloudFront hop; a forged header on a direct connection is still limited by the real connection IP. | "Trusting the forwarded-IP header because CloudFront is the expected front door" — must verify the app rejects or ignores forwarded headers on non-CloudFront connections. | How `RateLimitFilter` derives the caller IP today, whether a trusted-proxy allowlist exists, behavior when hit directly (e.g. local/staging without CloudFront in front). | unit/slice test on the filter's IP-extraction method | Testing only the happy path (well-formed header via CloudFront) and never a forged or missing header — the actual abuse scenario. |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|---|---|---|---|---|---|
| 1 | Constraint engine hardening | Prove bed conflicts are rejected end-to-end and constraint combinations/boundaries compose correctly | #1, #2 | integration | complete | `context/changes/testing-constraint-engine-hardening/` |
| 2 | Authorization boundary closure | Prove property-scoping and cross-agency isolation hold across every write path, not just the 3 known-enforced endpoints | #3, #5 | integration | complete | `context/changes/testing-authorization-boundary-closure/` |
| 3 | Data-integrity guardrails | Prove RESTRICT-FK delete guards and migration/backfill correctness generalize to the next entity/migration in sequence | #4, #6 | integration | change opened | `context/changes/testing-data-integrity-guardrails/` |
| 4 | Rate-limit abuse hardening | Prove the IP-derivation/rate-limit path cannot be trivially bypassed via forged headers off-CloudFront | #7 | unit/slice | not started | — |

**Status vocabulary** (fixed — parser literals): `not started` →
`change opened` → `researched` → `planned` → `implementing` → `complete`.

## 4. Stack

The classic test base for this project. Recommendations here are grounded
in local manifests/configs plus the MCP/tools actually exposed in the
current session.

| Layer | Tool | Version | Notes |
|---|---|---|---|
| unit + integration | JUnit 5 + AssertJ + Testcontainers (PostgreSQL) | per `build.gradle.kts`; Spring Boot 3.4.4 BOM | Real-DB integration tests only — "no DB mocking" convention (AGENTS.md); 25 test files across 10/12 modules today |
| migration testing | Flyway, via Testcontainers bootstrap | per `build.gradle.kts` | No dedicated pre-migration-data harness yet — see §3 Phase 3 |
| static analysis | Checkstyle | 10.21.4, `isIgnoreFailures = false` | Blocks the build on lint failure; runs locally via `./gradlew build` |
| e2e | none | n/a | API-only backend, no frontend in this repo; not a gap for this stack layer |
| CI | none | n/a | No `.github/workflows/` or other CI config; `./gradlew build` runs locally only, not wired to this rollout |

**Stack grounding tools (current session):**
- Docs: none available — no Context7 or framework-docs MCP exposed this session; checked: 2026-09-11
- Search: WebSearch/WebFetch tools available but not used — no framework-version question arose during discovery; checked: 2026-09-11
- Runtime/browser: Chrome browser automation available — not relevant to this backend-only API surface; checked: 2026-09-11
- Provider/platform: none — no GitHub/Cloudflare/Supabase/database MCP exposed; `gh` CLI exists via shell but is not an MCP; checked: 2026-09-11

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required for §3 Phase N" means the gate is enforced once that rollout
phase lands; before that, the gate is `planned`.

| Gate | Where | Required? | Catches |
|---|---|---|---|
| Checkstyle + compile | local (`./gradlew build`) | required today | syntactic / style drift |
| unit + integration suite | local (`./gradlew test`) | required today | logic regressions |
| constraint-engine integration coverage | local | required after §3 Phase 1 | bed-conflict and boundary/combination regressions |
| authorization/property-scoping integration matrix | local | required after §3 Phase 2 | IDOR-class access-control regressions |
| delete-guard + migration integration coverage | local | required after §3 Phase 3 | RESTRICT-FK 500s and migration data corruption |
| rate-limit filter unit/slice test | local | required after §3 Phase 4 | IP-spoofing rate-limit bypass |

No CI pipeline exists; all gates above run locally only (see §4). No
rollout phase in this plan wires CI — that gap was not raised as a top
risk in discovery or the interview, so it is out of scope here rather
than aspirational.

## 6. Cookbook Patterns

How to add new tests in this project. Each sub-section is filled in once
the relevant rollout phase ships; before that, the sub-section reads
"TBD — see §3 Phase N."

### 6.1 Adding a unit test

- **Location**: `src/test/java/com/beduno/stay/constraint/`.
- **Mocking policy**: mock `StayRepository`'s count methods directly via
  `@ExtendWith(MockitoExtension.class)` — this layer tests constraint
  **composition** (which `StayConstraint` beans fire, hard vs. soft, do
  multiple violations co-occur correctly), not the underlying SQL.
  Boundary/date-adjacency semantics against the real query need the
  integration layer instead — see the `BoundaryConditions` nested class
  in `src/test/java/com/beduno/stay/BedAssignmentIntegrationTest.java` —
  a unit test here mocks past the real predicate and would prove nothing
  about it.
- **Reference test**: `src/test/java/com/beduno/stay/constraint/ConstraintEngineTest.java`.
- **Run locally**: `./gradlew test --tests "com.beduno.stay.constraint.ConstraintEngineTest"`.

### 6.2 Adding an integration test

- **Location**: `src/test/java/com/beduno/<module>/`, extends
  `IntegrationTestBase` (Testcontainers PostgreSQL).
- **Mocking policy**: never mock the database — this is a hard project
  convention (AGENTS.md).
- **Naming**: `should{Behavior}_when{Condition}`.
- **Reference test**: `src/test/java/com/beduno/bed/BedIntegrationTest.java`.
- **Run locally**: `./gradlew test` (requires Docker for Testcontainers).

### 6.3 Adding a cross-agency isolation test

- TBD — see §3 Phase 2.

### 6.4 Adding a test for a new API endpoint

- **Test type**: integration (preferred), via `TestRestTemplate` against
  the running Spring context.
- **Pattern**: assert request → response shape AND the resulting
  database/audit-log state; use real `authHeaders(Role, agencyId)` helpers
  already established in the test base.
- **Reference test**: `src/test/java/com/beduno/stay/OperationalWorkflowIntegrationTest.java`.

### 6.5 Adding a Flyway migration test

- **Location**: `src/test/java/com/beduno/migration/`. Plain JUnit 5, no
  `@SpringBootTest` — boots no Spring context at all.
- **Container**: own dedicated `@Testcontainers`/`@Container`
  `PostgreSQLContainer`, independent of `IntegrationTestBase`'s shared
  instance. That shared container is already fully migrated to the latest
  version once, for the whole test JVM run, and cannot be rewound to an
  intermediate schema version without breaking every other integration
  test.
- **Pattern**: seed → partial-migrate → seed more → continue-migrate →
  assert. Drive Flyway directly via its Java API through
  `MigrationTestSupport` (`migrateTo("<version>")` /
  `migrateToLatest()`), so a test can stop mid-sequence, insert rows with
  plain JDBC, then resume — Flyway's own `flyway_schema_history` table
  tracks what's already applied, so a second `.migrate()` call only runs
  what's pending. Reset the schema (`DROP SCHEMA public CASCADE; CREATE
  SCHEMA public;`) in `@BeforeEach` so each test method starts from a
  clean, unmigrated database.
- **Mocking policy**: never mock the database — same hard project
  convention as every other integration test (AGENTS.md).
- **Reference test**: `src/test/java/com/beduno/migration/BedBackfillMigrationTest.java`
  — proves the real V10→V14 sequence both for a safe case and for a
  known, intentionally-unfixed gap (see §6.6 below).
- **Run locally**: `./gradlew test --tests "com.beduno.migration.*"`.

### 6.6 Per-rollout-phase notes

(Appended by `/10x-implement`'s final sub-phase after each rollout phase
lands.)

- **Phase 1 (constraint engine hardening)** found that engine composition
  and query-boundary semantics need different test layers — the existing
  unit test (`ConstraintEngineTest`) mocks past the real SQL, so a new
  integration test was needed for the checkout==checkin boundary case.
  Bulk-assign was also found to structurally swallow per-item errors into
  a `200 OK` response rather than surfacing a 422 like the other 4
  bed-assigning write paths — see
  `context/changes/testing-constraint-engine-hardening/research.md` Open
  Question 1 if that contract is ever revisited.

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption
changes.

- **i18n bundle wording per-language** — `MessageBundleTest` already
  enforces key-parity and non-blank values across all 6 bundles; asserting
  exact translated phrasing beyond that adds no signal without a
  translation-review workflow in place. Re-evaluate if translation review
  becomes part of the process. (Source: Phase 2 interview Q5.)

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-09-11
- Stack versions last verified: 2026-09-11
- AI-native tool references last verified: n/a — none recommended this round

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
