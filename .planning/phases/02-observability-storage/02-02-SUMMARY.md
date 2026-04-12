---
phase: 02-observability-storage
plan: 02
subsystem: database
tags: [postgres, flyway, exposed, r2dbc, testcontainers, jsonb, audit-log]

# Dependency graph
requires:
  - phase: 01-core-runtime
    provides: AuditLog port interface (kore-core), AgentResult sealed class, AgentTask, ToolCall, ToolResult, TokenUsage
provides:
  - PostgreSQL audit log schema (3 tables: agent_runs, llm_calls, tool_calls) via Flyway migration
  - Exposed 1.0 DSL table objects for all 3 tables
  - PostgresAuditLogAdapter implementing AuditLog port (append-only, R2DBC)
  - StorageConfig wiring R2dbcDatabase + JsonbTypeMapper + Flyway JDBC
  - Custom JsonbColumnType + JsonbTypeMapper for Exposed JSONB binding
  - Testcontainers integration tests (7 total) confirming migration + all 3 insert methods
affects:
  - 02-03-PLAN.md (kore-spring will autowire StorageConfig and PostgresAuditLogAdapter as beans)
  - kore-spring (StorageConfig exposed as @Bean)

# Tech tracking
tech-stack:
  added:
    - exposed-core 1.0.0 (org.jetbrains.exposed.v1)
    - exposed-r2dbc 1.0.0
    - exposed-java-time 1.0.0
    - r2dbc-postgresql 1.0.7.RELEASE (org.postgresql group — not io.r2dbc)
    - postgresql-jdbc 42.7.10 (Flyway JDBC driver, runtimeOnly)
    - flyway-core 12.3.0
    - flyway-database-postgresql 12.3.0
    - testcontainers-postgresql 1.20.4
    - testcontainers-junit5 1.20.4
  patterns:
    - Flyway JDBC / Exposed R2DBC split for schema migration vs. runtime access
    - Custom TypeMapper for JSONB: JsonbColumnType (TextColumnType subtype) + JsonbTypeMapper (priority 1.0)
    - Exposed 1-based index → R2DBC 0-based index conversion in TypeMapper.setValue
    - R2dbcRegistryTypeMapping.register() for custom type mappers at connect time
    - UUIDTable from org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable (the .java. sub-package)
    - suspendTransaction from org.jetbrains.exposed.v1.r2dbc.transactions (not .r2dbc directly)
    - ConnectionFactoryOptions.parse(url) + ConnectionFactories.get(options) for dialect detection

key-files:
  created:
    - kore-storage/build.gradle.kts
    - kore-storage/src/main/resources/db/migration/V1__init_schema.sql
    - kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/tables/AgentRunsTable.kt
    - kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/tables/LlmCallsTable.kt
    - kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/tables/ToolCallsTable.kt
    - kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/tables/JsonbColumnType.kt
    - kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt
    - kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/StorageConfig.kt
    - kore-storage/src/test/kotlin/dev/unityinflow/kore/storage/MigrationTest.kt
    - kore-storage/src/test/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapterTest.kt
  modified:
    - settings.gradle.kts (added kore-storage include)
    - gradle/libs.versions.toml (added Exposed, Flyway, R2DBC, PostgreSQL, Testcontainers entries)

key-decisions:
  - "org.postgresql:r2dbc-postgresql (not io.r2dbc:r2dbc-postgresql) — 1.0.x series moved group; io.r2dbc only publishes up to 0.8.x"
  - "r2dbc-postgresql moved to implementation scope — compile dep required for JsonbTypeMapper to reference Json.of()"
  - "Custom JsonbColumnType + JsonbTypeMapper with priority 1.0 — DefaultTypeMapper priority 0.01 wins without this"
  - "index - 1 in TypeMapper.setValue — Exposed passes 1-based index; r2dbc Statement.bind(int) is 0-based"
  - "ConnectionFactoryOptions.parse(url) used for R2dbcDatabase — plain url string loses dialect metadata (Unsupported driver dialect: null)"
  - "UUIDTable from org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable — the .java. sub-package is the correct path in Exposed 1.0"
  - "suspendTransaction import is org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction (not .r2dbc directly)"
  - "Append-only constraint enforced by convention: only INSERT operations in PostgresAuditLogAdapter (D-16)"

patterns-established:
  - "Pattern: Flyway JDBC + Exposed R2DBC split — always supply jdbcUrl separately for Flyway, r2dbcUrl for Exposed"
  - "Pattern: JSONB in Exposed 1.0 R2DBC — custom JsonbColumnType + JsonbTypeMapper registered at connect time"
  - "Pattern: Testcontainers PostgreSQL 16 with plain JDBC verification for integration tests"
  - "Pattern: TDD with runTest{} + Testcontainers — RED commit before GREEN implementation"

requirements-completed: [STOR-01, STOR-02, STOR-03, STOR-04]

# Metrics
duration: 45min
completed: 2026-04-12
---

# Phase 02 Plan 02: kore-storage Summary

**PostgreSQL audit log for kore-runtime: Flyway migration, Exposed 1.0 R2DBC table objects, JSONB type mapping, and PostgresAuditLogAdapter — all 7 Testcontainers integration tests passing.**

## Performance

- **Duration:** ~45 min
- **Started:** 2026-04-12T14:30:00Z
- **Completed:** 2026-04-12T16:45:00Z
- **Tasks:** 2 completed (Task 1: scaffold + SQL; Task 2: TDD — RED + GREEN)
- **Files modified:** 12

## Accomplishments

- Flyway V1__init_schema.sql creates 3 audit tables (agent_runs, llm_calls, tool_calls) with UUID PKs, JSONB metadata columns, and FK constraints
- PostgresAuditLogAdapter fully implements the kore-core AuditLog port (3 suspend insert methods) with append-only semantics (D-16)
- Custom JsonbColumnType + JsonbTypeMapper enables transparent JSONB binding in Exposed 1.0 R2DBC without kotlinx.serialization
- All 7 Testcontainers integration tests pass (3 MigrationTest + 4 PostgresAuditLogAdapterTest) against a real PostgreSQL 16 container

## Task Commits

1. **Task 1: kore-storage scaffold + Flyway migration SQL** - `e284245` (feat)
2. **Task 2 RED: failing tests for PostgresAuditLogAdapter** - `0d64a38` (test)
3. **Task 2 GREEN: PostgresAuditLogAdapter with JSONB type mapping** - `8dca522` (feat)

## Files Created/Modified

- `kore-storage/build.gradle.kts` - Module build file with Exposed, Flyway, R2DBC, Testcontainers deps
- `kore-storage/src/main/resources/db/migration/V1__init_schema.sql` - 3 audit tables with JSONB, FK, indexes
- `kore-storage/src/main/kotlin/.../tables/AgentRunsTable.kt` - Exposed DSL table object for agent_runs
- `kore-storage/src/main/kotlin/.../tables/LlmCallsTable.kt` - Exposed DSL table object for llm_calls
- `kore-storage/src/main/kotlin/.../tables/ToolCallsTable.kt` - Exposed DSL table object for tool_calls
- `kore-storage/src/main/kotlin/.../tables/JsonbColumnType.kt` - Custom JSONB column type + TypeMapper (NOT in plan — Rule 2 addition)
- `kore-storage/src/main/kotlin/.../PostgresAuditLogAdapter.kt` - AuditLog port implementation, append-only
- `kore-storage/src/main/kotlin/.../StorageConfig.kt` - R2dbcDatabase + Flyway JDBC wiring
- `kore-storage/src/test/.../MigrationTest.kt` - 3 migration verification tests
- `kore-storage/src/test/.../PostgresAuditLogAdapterTest.kt` - 4 TDD integration tests
- `settings.gradle.kts` - Added kore-storage include
- `gradle/libs.versions.toml` - Added 9 new library entries

## Decisions Made

- `org.postgresql:r2dbc-postgresql` (not `io.r2dbc:r2dbc-postgresql`) — the 1.0.x series moved group; `io.r2dbc` only publishes up to 0.8.x on Maven Central
- `r2dbc-postgresql` in `implementation` scope (not `runtimeOnly`) — compile dependency required for `JsonbTypeMapper` to reference `Json.of()`
- Custom JSONB type mapping via `JsonbColumnType` + `JsonbTypeMapper` — necessary because Exposed's `TextColumnType` sends VARCHAR wire type, which PostgreSQL rejects for JSONB parameterized queries
- `ConnectionFactoryOptions.parse(url)` used for `R2dbcDatabase` construction — plain URL string form loses `ConnectionFactoryOptions` dialect metadata causing "Unsupported driver dialect: null"
- `UUIDTable` from `org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable` — the `.java.` sub-package is the correct path in Exposed 1.0 JAR

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical Functionality] Custom JsonbColumnType + JsonbTypeMapper**
- **Found during:** Task 2 (GREEN phase — integration test execution)
- **Issue:** PostgreSQL rejected VARCHAR→JSONB binding: `column "metadata" is of type jsonb but expression is of type character varying`. Exposed `text()` / `varchar()` column types send VARCHAR OID; PostgreSQL 16 parameterized queries require the correct JSONB OID.
- **Fix:** Created `JsonbColumnType.kt` with `JsonbColumnType extends TextColumnType` (sqlType = "jsonb") and `JsonbTypeMapper implements TypeMapper` that wraps String values in `io.r2dbc.postgresql.codec.Json.of(value)` before binding. Registered at connect time via `R2dbcRegistryTypeMapping.register(JsonbTypeMapper())`. Priority set to 1.0 to run before `DefaultTypeMapper` (priority 0.01).
- **Files modified:** `kore-storage/src/main/kotlin/.../tables/JsonbColumnType.kt` (new), `StorageConfig.kt`, `AgentRunsTable.kt`, `LlmCallsTable.kt`, `ToolCallsTable.kt`, `build.gradle.kts` (r2dbc-postgresql to implementation scope)
- **Verification:** All 4 PostgresAuditLogAdapterTest tests pass; `./gradlew :kore-storage:build` clean
- **Committed in:** `8dca522` (part of Task 2 GREEN commit)

---

**Total deviations:** 1 auto-fixed (Rule 2 - missing critical functionality)
**Impact on plan:** Required addition correctly implements JSONB storage as specified by the DDL in V1__init_schema.sql. No scope creep — JSONB binding is a correctness requirement for the plan's own SQL schema.

## Issues Encountered

Multiple Exposed 1.0 R2DBC API deviations from plan-provided patterns required resolution:

1. **Wrong R2DBC PostgreSQL group**: `io.r2dbc:r2dbc-postgresql` stalls at 0.8.x; actual 1.0.x group is `org.postgresql:r2dbc-postgresql`
2. **Wrong UUIDTable import path**: Actual path in Exposed 1.0 JAR is `org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable` (`.java.` sub-package)
3. **Wrong suspendTransaction import**: Actual path is `org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction` (not `.r2dbc.suspendTransaction`)
4. **R2dbcDatabase.connect(url=...) API mismatch**: Plan showed string URL overload; actual API requires `ConnectionFactory` + config builder
5. **"Unsupported driver dialect: null"**: `ConnectionFactories.get(url-string)` loses options metadata; fixed by `ConnectionFactoryOptions.parse()` + passing `connectionFactoryOptions` to config
6. **TypeMapper Kotlin interface quirks**: `columnTypes` is a `val` property (not fun); `getValue` has nullable `Class<T>?` param; `priority` must be explicitly `> 0.01`
7. **1-based vs 0-based index**: Exposed passes 1-based index to `TypeMapper.setValue`; r2dbc `Statement.bind(int, Object)` is 0-based — required `index - 1`

All issues resolved by JAR inspection (`jar tf`, `javap`) and compiler error messages. No architectural changes needed.

## Known Stubs

None. All columns are populated with real data from the `AgentTask`, `TokenUsage`, `ToolCall`, and `ToolResult` domain objects. Placeholder values (`durationMs = 0`, `llmCallId = null`, `mcpServer = null`) are documented as intentional stubs for Phase 3 OTel span correlation (per plan comments).

## Threat Flags

None. `PostgresAuditLogAdapter` is an outbound port adapter — no new network endpoints, no auth paths, no file access. JSONB columns accept only application-constructed JSON strings, not user-controlled SQL.

## Self-Check: PASSED

- `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt` — FOUND
- `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/StorageConfig.kt` — FOUND
- `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/tables/JsonbColumnType.kt` — FOUND
- `kore-storage/src/main/resources/db/migration/V1__init_schema.sql` — FOUND
- Commit `e284245` (scaffold) — FOUND
- Commit `0d64a38` (RED) — FOUND
- Commit `8dca522` (GREEN) — FOUND
