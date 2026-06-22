---
phase: 07-hierarchical-agents
plan: 02
subsystem: kore-storage
tags: [hierarchical-agents, audit-log, flyway, run-tree, postgres]
requires:
  - "AgentTask.parentRunId: String? = null (Plan 01, D-09)"
provides:
  - "agent_runs.parent_run_id — nullable indexed UUID column, NO foreign key (D-10)"
  - "AgentRunsTable.parentRunId Exposed column (javaUUID nullable)"
  - "PostgresAuditLogAdapter.recordAgentRun persists task.parentRunId (null-safe UUID parse)"
  - "Integration proof of HIER-04 / criterion #4 (child==parent id; root IS NULL)"
affects:
  - kore-storage/src/main/resources/db/migration/V2__add_parent_run_id.sql
  - kore-storage/src/main/kotlin/io/github/unityinflow/kore/storage/tables/AgentRunsTable.kt
  - kore-storage/src/main/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapter.kt
  - kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/MigrationTest.kt
  - kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapterTest.kt
tech-stack:
  added: []
  patterns:
    - "Additive nullable-column Flyway migration with NO self-FK (D-10 insert-ordering landmine)"
    - "Null-safe String?->UUID parse via ?.let(UUID::fromString); root runs leave column NULL (Pitfall 4)"
    - "information_schema.columns + pg_indexes assertions for migration column-shape proofs"
    - "Testcontainers JDBC SELECT ...::text / rs.wasNull() for parent/child persistence proofs"
key-files:
  created:
    - kore-storage/src/main/resources/db/migration/V2__add_parent_run_id.sql
  modified:
    - kore-storage/src/main/kotlin/io/github/unityinflow/kore/storage/tables/AgentRunsTable.kt
    - kore-storage/src/main/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapter.kt
    - kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/MigrationTest.kt
    - kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapterTest.kt
decisions:
  - "No foreign key on parent_run_id — a child INSERTs its agent_runs row before the parent's row exists (parent records only after AgentLoop.run returns), so a self-FK would reject the child insert (D-10). A plain nullable indexed column is fully run-tree queryable."
  - "Adapter writes parent_run_id only from task.parentRunId via ?.let(UUID::fromString); a non-UUID forged id throws rather than persisting (T-7-04), and root runs persist NULL."
  - "Integration verification gated on Testcontainers Ryuk: in the local sandbox Ryuk could not launch (LogMessageWaitStrategy timeout); re-running with TESTCONTAINERS_RYUK_DISABLED=true yields a fully green suite. CI (arc-runner-unityinflow) is the canonical gate per the plan's acceptance criteria."
metrics:
  duration: 12min
  completed: 2026-06-22
  tasks: 2
  files: 5
---

# Phase 07 Plan 02: kore-storage Run-Tree Lineage (HIER-04) Summary

Persisted run-tree lineage for hierarchical agents: a V2 Flyway migration adds a nullable, indexed `parent_run_id UUID` column to `agent_runs` with NO foreign key (D-10 landmine), `PostgresAuditLogAdapter.recordAgentRun` writes `task.parentRunId` null-safely, and two Testcontainers integration tests prove the column shape and that a child run's `parent_run_id` equals its parent's id while a root run's is NULL (HIER-04 / criterion #4).

## What Was Built

### Task 1 — V2 migration + AgentRunsTable column + adapter INSERT (`95c9038`, prior partial run)
This task was completed and committed by a prior executor before a network interruption; it was confirmed against the working tree (not redone).
- `V2__add_parent_run_id.sql`: `ALTER TABLE agent_runs ADD COLUMN parent_run_id UUID NULL` + `CREATE INDEX IF NOT EXISTS idx_agent_runs_parent_run_id`, with the verbatim D-10 LANDMINE comment ("DO NOT ADD A FOREIGN KEY HERE") explaining the child-before-parent insert ordering.
- `AgentRunsTable.kt`: `val parentRunId = javaUUID("parent_run_id").nullable()`.
- `PostgresAuditLogAdapter.kt`: `stmt[parentRunId] = task.parentRunId?.let(UUID::fromString)` with a Pitfall-4 comment (String? column is UUID; root runs stay NULL). No `!!`, no FK.

### Task 2 — Extend MigrationTest + PostgresAuditLogAdapterTest (this run, `a2ae573`)
- `MigrationTest.kt`: two new tests mirroring the existing `information_schema` shape — `parent_run_id` is `data_type = uuid` / `is_nullable = YES`, and `pg_indexes` contains `idx_agent_runs_parent_run_id`.
- `PostgresAuditLogAdapterTest.kt`: two new `runTest` JDBC tests — a child run (task `parentRunId = parentId`) reads back `parent_run_id::text == parentId` (HIER-04 / criterion #4); a root run (default-null `parentRunId`) reads back `parent_run_id == null` with `rs.wasNull()` true.
- Both classes reuse the existing companion-object Testcontainers Postgres container + `@BeforeAll` `StorageConfig.migrate()` pipeline verbatim; the V2 migration is applied automatically.

## Verification

- `./gradlew :kore-storage:compileKotlin` — green (Task 1 main sources).
- `./gradlew :kore-storage:compileTestKotlin` — green (new test sources).
- `./gradlew :kore-storage:lintKotlin` — passes (ktlint, no formatting deltas).
- `./gradlew :kore-storage:integrationTest --tests "*MigrationTest*" --tests "*PostgresAuditLogAdapterTest*"` — green with `TESTCONTAINERS_RYUK_DISABLED=true`. All 4 new tests present and passing in the result XML (`V2 adds agent_runs parent_run_id as a nullable uuid column`, `V2 indexes agent_runs parent_run_id`, `child run persists parent_run_id equal to parent id (HIER-04 criterion 4)`, `root run leaves parent_run_id NULL`); zero failures across the suite.

## Deviations from Plan

**1. [Rule 3 - Blocking issue] Testcontainers Ryuk could not launch in the local sandbox**
- **Found during:** Task 2 verification (`integrationTest`).
- **Issue:** The first `integrationTest` run failed with `initializationError` in the companion `@BeforeAll` — `ContainerLaunchException` / `LogMessageWaitStrategy` timeout ("Timed out waiting for log output"). No test containers and no Ryuk reaper container were spawned, confirming the failure was in container/Ryuk bootstrap, not in the new assertions. The container config is identical and pre-existing in both test classes (unchanged by this plan).
- **Fix:** Re-ran with `TESTCONTAINERS_RYUK_DISABLED=true` — the full suite went green. This is an environment-only mitigation for the local sandbox; no test or production code changed. The plan's own acceptance criteria designate CI (`arc-runner-unityinflow` Docker pre-flight, Phase 5) as the canonical integration gate.
- **Files modified:** none.
- **Commit:** n/a (env-only).

Otherwise plan executed as written. (Implementation note inherited from Task 1: the Exposed column uses `javaUUID("parent_run_id")` rather than `uuid(...)` — the `javaUUID` builder is the correct Exposed 1.0 column factory for a `java.util.UUID` column in this module; the must-have `contains: "parent_run_id"` is satisfied.)

## Known Stubs

None introduced. `InMemoryAuditLog` no-op stubs noted in Plan 01 are unchanged and out of scope here.

## Threat Surface Notes

No new threat surface beyond the plan's `<threat_model>`. T-7-04 (forged parent id) is mitigated as designed: `parent_run_id` is written only from `task.parentRunId` via `UUID.fromString`, which rejects non-UUID strings by throwing rather than persisting. No new endpoints, auth paths, or trust-boundary schema beyond the additive nullable UUID column.

## Notes for Later Plans

- Plan 03 (AgentTool) sets `task.parentRunId` from the parent run's server-generated `agentId` UUID — this is the only sanctioned writer of `parent_run_id` (T-7-04 invariant).
- Run-tree reconstruction queries should join `agent_runs.parent_run_id -> agent_runs.id` in application code (no DB-enforced FK by design, D-10).

## Self-Check: PASSED

- `V2__add_parent_run_id.sql`, `AgentRunsTable.kt`, `PostgresAuditLogAdapter.kt`, `MigrationTest.kt`, `PostgresAuditLogAdapterTest.kt` all exist on disk.
- Commit `95c9038` (Task 1) present in git history.
- Commit `a2ae573` (Task 2) present in git history.
