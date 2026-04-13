---
phase: 03-skills-spring-dashboard
plan: 01
subsystem: runtime-ports
tags: [kotlin, skills, yaml, jackson, exposed, r2dbc, opentelemetry, hexagonal]

requires:
  - phase: 01-core-runtime
    provides: kore-core AgentLoop, ConversationMessage.Role.System, AgentBuilder DSL
  - phase: 02-observability-storage
    provides: PostgresAuditLogAdapter, Exposed R2DBC tables, kore-observability Tracer contract
provides:
  - SkillRegistry port (kore-core) with NoOpSkillRegistry default
  - AuditLog.queryRecentRuns + queryCostSummary read methods (AgentRunRecord, AgentCostRecord)
  - AgentLoop skill injection hook + optional kore.skill.activate OTel span
  - AgentBuilder DSL skillRegistry() configuration
  - kore-skills module: YAML SkillLoader (classpath + filesystem merge), PatternMatcher, SkillRegistryAdapter
  - PostgresAuditLogAdapter implementations of queryRecentRuns + queryCostSummary
affects: [03-02 kore-spring auto-config, 03-03 kore-dashboard HTMX fragments]

tech-stack:
  added:
    - jackson-dataformat-yaml (Spring Boot 4 BOM managed, no explicit version)
    - jackson-module-kotlin (Spring Boot 4 BOM managed)
    - io.spring.dependency-management Gradle plugin (1.1.7) for Spring Boot BOM import
    - libs.versions.toml entries for Ktor 3.2.0 + Spring Boot 4.0.5 plugin pins (used by Wave 2)
  patterns:
    - "SkillRegistry port in kore-core + SkillRegistryAdapter in kore-skills (hexagonal ports/adapters, D-09)"
    - "Graceful-degradation OTel tracer: Tracer? nullable constructor parameter, no hard kore-observability dep"
    - "val-cell DSL mutation: Array<T>(1) backing store avoids `var` in CLAUDE.md-compliant DSL builders"
    - "Projection-first dashboard queries: AgentRunRecord/AgentCostRecord do not contain task content (T-03-03)"
    - "In-Kotlin aggregation for cost summary avoids Exposed R2DBC GROUP BY dialect quirks"

key-files:
  created:
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/SkillRegistry.kt
    - kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentLoopSkillTest.kt
    - kore-skills/build.gradle.kts
    - kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillYamlDef.kt
    - kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt
    - kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillRegistryAdapter.kt
    - kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/internal/PatternMatcher.kt
    - kore-skills/src/main/resources/META-INF/kore/skills/code-review.yaml
    - kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillLoaderTest.kt
    - kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillRegistryAdapterTest.kt
    - kore-storage/src/test/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapterQueryTest.kt
  modified:
    - settings.gradle.kts
    - gradle/libs.versions.toml
    - kore-core/build.gradle.kts
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentLoop.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/AgentBuilder.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InMemoryAuditLog.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt
    - kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt

key-decisions:
  - "SkillRegistry port lives in kore-core with NoOpSkillRegistry default (D-09); kore-skills is a pure adapter, kore-core stays zero runtime-dep"
  - "jackson-dataformat-yaml chosen as YAML parser (kaml archived Nov 2025, not a real option); version managed by Spring Boot 4 BOM imported via io.spring.dependency-management plugin"
  - "Tracer is a nullable constructor parameter on AgentLoop (Tracer? = null) so kore-core never requires opentelemetry-api at runtime — compileOnly in kore-core/build.gradle.kts"
  - "AgentBuilder uses Array<SkillRegistry>(1) val-cell backing store instead of `var` to keep the DSL CLAUDE.md-compliant"
  - "PostgresAuditLogAdapter query methods use explicit .select(columns) projections instead of selectAll() to avoid the JsonbTypeMapper column-index bug on joined queries"
  - "queryCostSummary folds results in Kotlin rather than SQL GROUP BY — sidesteps Exposed R2DBC aggregate/column mixing quirks while staying correct for dashboard scale"
  - "AgentLoopSkillTest uses a RecordingBackend that captures the first-call history to assert skill injection happens BEFORE the first LLM invocation — deterministic and no MockK dependency needed"

patterns-established:
  - "Pattern: classpath+filesystem YAML loader with user-wins override (SkillLoader.loadAll)"
  - "Pattern: pre-compile regex matchers once at load time, never per-request (PatternMatcher)"
  - "Pattern: nullable tracer for optional observability (try/finally span lifecycle)"
  - "Pattern: projection-based dashboard read queries from Exposed R2DBC joins"

requirements-completed: [SKIL-01, SKIL-02, SKIL-03, SKIL-04]

duration: ~30 min
completed: 2026-04-13
---

# Phase 3 Plan 1: kore-skills Module and AuditLog Read Contracts Summary

**YAML skills engine backed by jackson-dataformat-yaml with classpath+filesystem loader, SkillRegistry port wired into AgentLoop with optional OTel span, and Exposed R2DBC read queries on PostgresAuditLogAdapter for dashboard consumption.**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-04-13T20:30Z
- **Completed:** 2026-04-13T21:00Z
- **Tasks:** 2 (both TDD, auto mode)
- **Files created:** 11
- **Files modified:** 8

## Accomplishments

- SkillRegistry port in kore-core with NoOpSkillRegistry default — kore-core stays zero runtime-dep on kore-skills
- AuditLog.queryRecentRuns / queryCostSummary read path on the port, implemented by both InMemoryAuditLog (no-op) and PostgresAuditLogAdapter (real Exposed R2DBC queries)
- AgentLoop skill injection hook fires BEFORE the first LLM call; activated prompts become a Role.System message at history[0]
- Graceful-degradation OpenTelemetry span: when a Tracer is supplied, skill activation is wrapped in a `kore.skill.activate` span; when null, no span is emitted and activation still proceeds (D-11)
- kore-skills module: SkillLoader merges classpath (META-INF/kore/skills/) with filesystem skills, filesystem wins on name collision (D-07); PatternMatcher pre-compiles regexes and defends against PatternSyntaxException (T-03-02); SkillRegistryAdapter enforces AND semantics between task_matches and requires_tools (D-04)
- Bundled code-review.yaml example skill
- 20 new tests (6 AgentLoopSkillTest + 4 SkillLoaderTest + 7 SkillRegistryAdapterTest + 3 PostgresAuditLogAdapterQueryTest) all passing
- libs.versions.toml updated with Ktor 3.2.0 + Spring Boot 4.0.5 pins (consumed by Wave 2 plans 03-02 / 03-03)

## Task Commits

1. **Task 1: kore-core port contracts** — `ea265fb` (feat)
   - SkillRegistry + NoOpSkillRegistry port
   - AuditLog read methods + AgentRunRecord/AgentCostRecord
   - InMemoryAuditLog no-op implementations
   - AgentLoop skill injection hook + nullable Tracer parameter
   - AgentBuilder DSL skillRegistry() with val-cell backing
   - AgentLoopSkillTest (6 cases) using RecordingBackend + InMemorySpanExporter
2. **Task 2: kore-skills module + PostgresAuditLogAdapter queries** — `04da8a4` (feat)
   - kore-skills Gradle scaffold with Spring Boot BOM for jackson
   - SkillYamlDef / ActivationDef / PatternMatcher / SkillLoader / SkillRegistryAdapter
   - Bundled code-review.yaml
   - libs.versions.toml: ktor 3.2.0, spring-boot 4.0.5, jackson + ktor entries, spring-dep-mgmt plugin
   - PostgresAuditLogAdapter implements queryRecentRuns / queryCostSummary
   - SkillLoaderTest, SkillRegistryAdapterTest, PostgresAuditLogAdapterQueryTest

**Plan metadata commit:** (this SUMMARY + STATE/ROADMAP updates, see final_commit step)

## Files Created/Modified

**Created:**
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/SkillRegistry.kt` — port interface + NoOp default
- `kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentLoopSkillTest.kt` — 6 tests: no-op baseline, injection, empty result, InMemoryAuditLog stubs, OTel span export
- `kore-skills/build.gradle.kts` — module build with Spring Boot BOM import
- `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillYamlDef.kt` — YAML-bound data classes (@param:JsonProperty snake_case keys)
- `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt` — classpath (JAR + file URL) + filesystem merge loader
- `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillRegistryAdapter.kt` — SkillRegistry port implementation
- `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/internal/PatternMatcher.kt` — pre-compiled regex matcher with PatternSyntaxException defence
- `kore-skills/src/main/resources/META-INF/kore/skills/code-review.yaml` — bundled example
- `kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillLoaderTest.kt` — 4 tests: classpath, filesystem, override, empty dir
- `kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillRegistryAdapterTest.kt` — 7 tests: pattern matcher + activation logic (AND semantics)
- `kore-storage/src/test/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapterQueryTest.kt` — 3 Testcontainers tests seeding 3 runs across 2 agents

**Modified:**
- `settings.gradle.kts` — includes kore-skills
- `gradle/libs.versions.toml` — Ktor + Spring Boot pins, jackson + ktor library entries, spring-boot + spring-dep-mgmt plugin entries
- `kore-core/build.gradle.kts` — compileOnly opentelemetry-api; testImplementation opentelemetry-sdk-testing for span assertion
- `kore-core/.../port/AuditLog.kt` — queryRecentRuns + queryCostSummary read methods + AgentRunRecord + AgentCostRecord
- `kore-core/.../internal/InMemoryAuditLog.kt` — no-op implementations of new read methods
- `kore-core/.../AgentLoop.kt` — skillRegistry + nullable tracer constructor parameters, skill injection hook before runLoop iteration
- `kore-core/.../dsl/AgentBuilder.kt` — skillRegistry() DSL method with val-cell backing, passes registry to AgentLoop
- `kore-storage/.../PostgresAuditLogAdapter.kt` — queryRecentRuns (projection + orderBy + limit) and queryCostSummary (in-Kotlin groupBy over projection)

## Decisions Made

- **Jackson YAML over kaml**: kaml was archived Nov 2025, not viable for new work. jackson-dataformat-yaml ships via Spring Boot 4 BOM so no explicit version pin is needed when the dep-mgmt plugin is applied.
- **Nullable Tracer over hard kore-observability dep**: AgentLoop's tracer parameter defaults to null. kore-core keeps `compileOnly("io.opentelemetry:opentelemetry-api")` so the runtime classpath stays clean.
- **Val-cell backing store in AgentBuilder**: `private val skillRegistryCell: Array<SkillRegistry> = arrayOf(NoOpSkillRegistry)` honours CLAUDE.md's no-`var` rule. ktlint initially rejected the `_skillRegistry` name under the backing-property-naming rule — renamed to `skillRegistryCell` to keep lint happy.
- **Projection queries over selectAll() in dashboard reads**: initial `innerJoin.selectAll()` triggered a JsonbTypeMapper column-index bug (UUID OID 2950 being decoded by the Json mapper when joined tables both have `jsonb metadata` columns). Switched to explicit `.select(columns)` projections that exclude the jsonb columns; doubles as a T-03-03 defence since task content is never read into dashboard records.
- **In-Kotlin aggregation for queryCostSummary**: Exposed R2DBC's aggregate/column mixing has dialect quirks under 1.0. Folding join results in Kotlin on `agentName` keeps the implementation dialect-agnostic and is well within safe memory bounds for dashboard use (<10k rows).
- **RecordingBackend over MockK for AgentLoopSkillTest**: capturing the first-call history directly is simpler than a MockK expectation chain and keeps kore-core test dependencies minimal.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] ktlint backing-property-naming rejected `_skillRegistry`**
- **Found during:** Task 1, after initial implementation of AgentBuilder
- **Issue:** The plan-specified `private val _skillRegistry: Array<SkillRegistry>` triggered `standard:backing-property-naming` — ktlint only allows a `_name` backing property when a matching public property/function named `name` exists. The DSL uses a `skillRegistry(registry)` function not a property, so ktlint rejected the underscore-prefixed name.
- **Fix:** Renamed the backing field to `skillRegistryCell` (matching the "cell" metaphor used in the comment) and updated both the DSL method and the `build()` reference.
- **Files modified:** `kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/AgentBuilder.kt`
- **Verification:** `./gradlew :kore-core:build -x lintKotlinTest` passes.
- **Committed in:** `ea265fb` (Task 1 commit)

**2. [Rule 3 - Blocking] kotlinter plugin aliased twice in kore-skills build.gradle.kts**
- **Found during:** Task 2, after initial copy-paste of plan's build.gradle.kts snippet
- **Issue:** The root `build.gradle.kts` already applies `org.jmailen.kotlinter` to every subproject via a `subprojects { apply(plugin = ...) }` block. Applying the plugin explicitly in kore-skills would double-apply it.
- **Fix:** Removed `alias(libs.plugins.kotlinter)` from kore-skills/build.gradle.kts; the subprojects block handles it.
- **Files modified:** `kore-skills/build.gradle.kts`
- **Verification:** `./gradlew :kore-skills:build` succeeds and runs `lintKotlin` via the inherited plugin.
- **Committed in:** `04da8a4` (Task 2 commit)

**3. [Rule 1 - Bug] PostgresAuditLogAdapter.selectAll() triggered JsonbTypeMapper column-index bug**
- **Found during:** Task 2, running PostgresAuditLogAdapterQueryTest under Testcontainers
- **Issue:** `innerJoin(LlmCallsTable).selectAll()` failed with `java.lang.IllegalArgumentException: Cannot decode value of type io.r2dbc.postgresql.codec.Json with OID 2950` (UUID). The existing JsonbTypeMapper's `getValue` path gets confused on joined result sets where multiple tables have `jsonb` columns and the column indexing maps a UUID column to the JSONB decoder.
- **Fix:** Switched both query methods from `selectAll()` to explicit `.select(columns)` projections — listing only the columns needed by `AgentRunRecord` / `AgentCostRecord`. This doubles as a T-03-03 defence (no task content in dashboard read path). Root-cause of the JsonbTypeMapper bug is left for a future storage-layer plan.
- **Files modified:** `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt`
- **Verification:** All 3 PostgresAuditLogAdapterQueryTest cases pass under Testcontainers.
- **Committed in:** `04da8a4` (Task 2 commit)

**4. [Rule 3 - Blocking] innerJoin(otherTable, { }, { }) signature does not exist in Exposed 1.0**
- **Found during:** Task 2, compiling PostgresAuditLogAdapter
- **Issue:** The plan's snippet used `.innerJoin(LlmCallsTable, { AgentRunsTable.id }, { LlmCallsTable.runId })`. Exposed 1.0 core only exposes `innerJoin(otherTable: ColumnSet): Join` with a single-arg signature that auto-detects the FK via the `reference()` column.
- **Fix:** Used `.innerJoin(LlmCallsTable)` unary form — `LlmCallsTable.runId = reference("run_id", AgentRunsTable, ...)` gives Exposed the FK it needs.
- **Files modified:** `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt`
- **Verification:** Compiles and all query tests pass.
- **Committed in:** `04da8a4` (Task 2 commit)

---

**Total deviations:** 4 auto-fixed (2 Rule 3 blocking, 1 Rule 1 bug, 1 Rule 3 ktlint lint issue)
**Impact on plan:** All four are mechanical fixes to keep compilation/lint clean or to work around a pre-existing storage-layer bug. No scope creep; output contracts match the plan exactly.

### Pre-existing issues out of scope

- `kore-core/src/test/kotlin/.../integration/HeroDemoTest.kt` has lint errors on HEAD (the committed version predates current ktlint rules; a reformatted copy sits in a pre-existing uncommitted working-tree change that was stashed before this plan started). The plan's verify command already uses `-x lintKotlin`; for `./gradlew :kore-core:build` I used `-x lintKotlinTest` to skip only the pre-existing test lint issue. Logged for follow-up.
- `JsonbTypeMapper` column-index bug on joined `selectAll()` in kore-storage is a real defect but out of scope for this plan — worked around via projection-only queries.

## Issues Encountered

- **Docker daemon discovery**: Testcontainers initially reported `Cannot connect to the Docker daemon`. Launching Docker Desktop resolved it — Testcontainers then found the socket via standard discovery.
- **ktlint formatter fix-up pass**: running `./gradlew :kore-skills:formatKotlin` automatically rewrote SkillLoader.kt and SkillRegistryAdapterTest.kt (chain-continuation and indent rules). Accepted the formatter output.

## User Setup Required

None — Docker must be running for `:kore-storage:test` (already a pre-existing requirement; not new in this plan).

## Next Phase Readiness

Wave 2 plans are unblocked:

- **03-02 kore-spring** can now depend on `kore-skills` via classpath detection (`@ConditionalOnClass(name = ["dev.unityinflow.kore.skills.SkillRegistryAdapter"])`) and wire a `SkillRegistry` bean.
- **03-03 kore-dashboard** can call `AuditLog.queryRecentRuns(20)` and `AuditLog.queryCostSummary()` on either adapter — `PostgresAuditLogAdapter` returns real data, `InMemoryAuditLog` returns empty lists (D-27 degraded mode).
- `libs.versions.toml` already contains the Ktor 3.2.0 and Spring Boot 4.0.5 pins that Wave 2 plans will consume.

## Self-Check: PASSED

- [x] `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/SkillRegistry.kt` FOUND
- [x] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentLoopSkillTest.kt` FOUND
- [x] `kore-skills/build.gradle.kts` FOUND
- [x] `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillYamlDef.kt` FOUND
- [x] `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt` FOUND
- [x] `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillRegistryAdapter.kt` FOUND
- [x] `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/internal/PatternMatcher.kt` FOUND
- [x] `kore-skills/src/main/resources/META-INF/kore/skills/code-review.yaml` FOUND
- [x] `kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillLoaderTest.kt` FOUND
- [x] `kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillRegistryAdapterTest.kt` FOUND
- [x] `kore-storage/src/test/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapterQueryTest.kt` FOUND
- [x] Commit `ea265fb` FOUND in `git log`
- [x] Commit `04da8a4` FOUND in `git log`
- [x] `./gradlew :kore-skills:test :kore-storage:test :kore-core:build -x lintKotlinTest` passes
- [x] `interface SkillRegistry` present in SkillRegistry.kt
- [x] `queryRecentRuns` present in AuditLog.kt
- [x] `activateFor` present in AgentLoop.kt
- [x] `Role.System` present in AgentLoop.kt

---
*Phase: 03-skills-spring-dashboard*
*Completed: 2026-04-13*
