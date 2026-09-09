---
project: beduno-be
assessed_at: 2026-08-19T06:59:00Z
agent_readiness: ready
context_type: brownfield
stack_components:
  language: Java 21
  framework: Spring Boot 3.4.4
  build_tool: Gradle 8.12 (Kotlin DSL)
  test_runner: JUnit 5 + Testcontainers (PostgreSQL)
  package_manager: Gradle (Maven Central, Spring BOM via dependency-management plugin)
  ci_provider: null
  deployment_target: Docker (no hosting target configured)
gates_passed: 9
gates_failed: 0
---

## Stack Components

**Language — Java 21.** Pinned via Gradle toolchain (`build.gradle.kts`: `JavaLanguageVersion.of(21)`).
Statically typed by the language; records, sealed interfaces and `var` are in active use per the
project conventions.

**Framework — Spring Boot 3.4.4.** With Spring Security, Data JPA, Validation and Actuator
starters. The project layers its own domain-module convention on top (`com.beduno.<module>`,
Controller/Service/Repository/Entity/DTOs per module), documented in `CLAUDE.md`.

**Build tool — Gradle 8.12, Kotlin DSL.** Wrapper committed (`gradle/wrapper/gradle-wrapper.properties`),
so builds are version-reproducible. Checkstyle 10.21.4 is wired in with `isIgnoreFailures = false` —
lint failures break the build.

**Test runner — JUnit 5 + Testcontainers.** `useJUnitPlatform()` in the build file;
`org.testcontainers:junit-jupiter` and `org.testcontainers:postgresql` for real-database
integration tests (project convention forbids DB mocking); `spring-security-test` for auth
paths. 16 test files against 115 main-source files.

**Supporting cast.** PostgreSQL 16 + Flyway migrations, MapStruct + Lombok (annotation
processing), jjwt, springdoc OpenAPI, Bucket4j. Local infrastructure via
`docker/docker-compose.yml`; a `docker/Dockerfile` exists but no hosting-target config.

## Quality Gate Assessment

| Component   | Typed | Convention | Training Data | Documented | Verdict |
|-------------|-------|------------|---------------|------------|---------|
| Language    | ✓     | —          | —             | —          | pass    |
| Framework   | —     | ✓          | ✓             | ✓          | pass    |
| Build tool  | —     | ✓          | ✓             | ✓          | pass    |
| Test runner | —     | —          | ✓             | ✓          | pass    |

Legend: ✓ = pass, ✗ = fail, ~ = partial, — = not applicable

### Gate Details

- **Typed — pass.** Java is statically typed by the language. Evidence: `build.gradle.kts`
  toolchain block pins Java 21; the codebase compiles under it.
- **Convention-based (framework) — pass.** Spring Boot is a canonical convention-over-configuration
  framework (autoconfiguration, starter conventions, `application.yml` binding). Evidence: the
  criteria reference names Spring Boot as a pass example; on top of that, `CLAUDE.md` documents
  the project-specific module layout, DTO/record rules, tenancy rules and repository rules —
  conventions an agent can pattern-match beyond what the framework enforces.
- **Convention-based (build tool) — pass.** Gradle enforces the standard `src/main/java` /
  `src/test/java` layout; the wrapper pins the version. Evidence: repository layout and
  `gradle-wrapper.properties` (`gradle-8.12-bin.zip`).
- **Popular in training data — pass, all components.** Assessed within the Java family: Spring is
  the mainstream Java web framework; Gradle is one of the two mainstream JVM build tools and its
  Kotlin DSL is the current default for new Spring Boot projects; JUnit 5 is the default JVM test
  framework and Testcontainers is the mainstream integration-test approach for Spring + PostgreSQL.
- **Well-documented — pass, all components.** Spring Boot ships a versioned reference manual per
  release; Gradle publishes a versioned user manual; JUnit 5 and Testcontainers both maintain
  current, versioned official docs.

## Gaps & Compensation

**No quality gate failed. No compensation entries are required.** The existing `CLAUDE.md` already
plays the compensating role for everything project-specific: module layout, multi-tenancy rules
(every repository query filters by `agency_id`, with the two sanctioned exceptions named),
constraint-engine and audit-service call order, and test conventions.

### Observations outside the gates (for /10x-health-check)

These are not agent-friendliness failures, but they are the weakest points the health check
should focus on:

1. **No CI pipeline.** No `.github/workflows/`, no other CI config. Checkstyle and the test
   suite only run when someone runs them. This matters more than usual here because Lombok and
   MapStruct generate code at compile time — an agent cannot fully verify mapper/accessor
   correctness by reading source, so compile-and-test feedback is the backstop, and nothing
   automates it today.
2. **No dependency locking.** No `gradle.lockfile`; versions are governed by the Spring BOM plus
   a handful of explicit pins (jjwt 0.12.6, MapStruct 1.6.3, springdoc 2.8.4, Bucket4j 8.10.1).
   Builds are reproducible only to the extent the BOM is.
3. **No `.editorconfig`.** Checkstyle covers Java style; nothing pins whitespace/encoding
   conventions for the non-Java files (YAML, SQL migrations, Markdown).
4. **Test-to-source ratio.** 16 test files against 115 main-source files, concentrated in
   integration tests. Coverage breadth is a health-check question, not a stack question.

### Recommended Instruction File Additions

None required — no gate failed, and `CLAUDE.md` already carries the project-specific conventions
an instruction file exists to carry.

## Summary

**Verdict: ready.** Java 21 + Spring Boot 3.4 + Gradle + JUnit 5/Testcontainers passes all nine
applicable criteria cells with no compensation needed. This is a mainstream, typed,
convention-heavy stack that agents have deep training exposure to, and the project amplifies
that with an unusually specific `CLAUDE.md` and a lint-gated build.

Key strengths: typed language with modern features in use; convention-based framework plus
documented project conventions; real-database integration testing already the norm; build
reproducibility via the Gradle wrapper.

Key gaps (all outside the quality gates): no CI to run the checks that exist, no dependency
locking, thin test breadth relative to the module count — all squarely `/10x-health-check`
territory, and that is the recommended next step.
