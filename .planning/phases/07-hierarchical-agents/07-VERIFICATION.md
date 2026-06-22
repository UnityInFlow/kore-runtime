---
phase: 07-hierarchical-agents
verified: 2026-06-22T07:05:00Z
status: gaps_found
score: 3/5 must-haves verified
overrides_applied: 0
gaps:
  - truth: "Cancelling a parent cancels all running child agents with structured concurrency (HIER-02 / criterion #2) — held under concurrent parent runs, not only single-threaded tests."
    status: partial
    reason: >
      Single-level cancellation propagation is correct and the HIER-02 test passes,
      but AgentTool carries dispatch-time lineage (parentDepth/parentRunId) in shared
      mutable instance cells (parentDepthCell/parentRunIdCell) written by bind() before
      the coroutineScope dispatch and read by callTool() after suspension. Under two
      concurrent parent runs sharing one AgentTool instance (the Spring factory hands out
      one runner per @Bean), the second bind() clobbers the first's cell before the first
      child's callTool() reads it. The structured-concurrency/lineage guarantee is only
      demonstrated under the single-threaded runTest dispatcher (REVIEW CR-01).
    artifacts:
      - path: "kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/AgentTool.kt"
        issue: "Lines 44-89: parentDepthCell/parentRunIdCell are shared mutable state written in bind() and read in callTool() across suspension and across concurrent runs — race corrupts depth/parentRunId."
      - path: "kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt"
        issue: "Lines 240-242: bind() is called on the shared provider instance immediately before the async dispatch block; nothing keys lineage per-coroutine."
    missing:
      - "Carry parentDepth/parentRunId per-coroutine (CoroutineContext element) or via an immutable dispatch descriptor on the callTool contract, removing the shared cells."
      - "A multi-coroutine AgentTool test on a real multi-threaded dispatcher asserting each child receives the correct parentRunId/depth."
  - truth: "Audit log records parent_run_id on child agent runs so a developer can trace a full run tree from the database (HIER-04 / criterion #4) — on the persistent Postgres path."
    status: partial
    reason: >
      The in-memory run-tree path is fully verified (AgentToolTest HIER-04 passes; child
      RunRecord carries parentRunId == parent id). The V2 migration, AgentRunsTable.parentRunId
      column, and PostgresAuditLogAdapter write of task.parentRunId all exist and are well-formed,
      with @Tag("integration") Testcontainers tests that assert child parent_run_id == parent id
      and root NULL. However those integration tests could not be executed in this environment
      (testcontainers/ryuk:0.8.1 reaper container timed out — LogMessageWaitStrategy, an
      infrastructure failure, not a code defect), so the persistent path is unproven here.
      Separately, the persistent path carries an unmitigated defect (REVIEW CR-02): the unguarded
      .also audit write in AgentLoop.run combined with UUID.fromString(agentId) means a non-UUID
      task.id (a free-form String, e.g. "parent-1") or a duplicate PK makes recordAgentRun throw
      OUT of run(), violating the documented "run NEVER throws" invariant. The integration tests
      use only UUID ids, so this crash path has zero coverage even when Testcontainers works.
    artifacts:
      - path: "kore-storage/src/main/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapter.kt"
        issue: "Line 60: stmt[id] = UUID.fromString(agentId) throws IllegalArgumentException for any non-UUID AgentTask.id; duplicate PK (reused id) throws on INSERT."
      - path: "kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt"
        issue: "Lines 97-100: the .also { auditLog.recordAgentRun(...) } success/error audit write is not wrapped in runCatching, so any AuditLog throw propagates out of run() and breaks the no-throw invariant (INVARIANT, line 26)."
    missing:
      - "Wrap the .also audit write in runCatching so storage failures never break the run-NEVER-throws invariant."
      - "Stop assuming agentId/task.id is a UUID on the persistent path (constrain to UUID at the boundary, or store varchar / derive PK deterministically with graceful guard)."
      - "Add a unit test asserting AgentLoop.run returns an AgentResult (does not throw) when the injected AuditLog throws, and an integration test submitting a non-UUID agentId."
      - "Execute :kore-storage:integrationTest on an environment where Testcontainers/Ryuk starts (e.g. arc-runner-unityinflow with docker pre-flight) to confirm the persistent parent_run_id assertions actually pass."
human_verification:
  - test: "Run ./gradlew :kore-storage:integrationTest --tests \"*MigrationTest*\" --tests \"*PostgresAuditLogAdapterTest*\" on a host where Testcontainers Ryuk starts (CI arc-runner-unityinflow, or local Docker with ryuk enabled)."
    expected: "MigrationTest asserts parent_run_id is UUID/nullable/indexed; PostgresAuditLogAdapterTest asserts a child row's parent_run_id == parent id and a root row's parent_run_id IS NULL — all green. Confirms criterion #4 on the persistent path."
    why_human: "Verifier could not start the testcontainers/ryuk:0.8.1 reaper container in this environment (LogMessageWaitStrategy timeout). This is an infrastructure constraint, not a code defect; the persistent-path assertions must be run where Docker/Ryuk works."
---

# Phase 07: Hierarchical Agents Verification Report

**Phase Goal:** Parent agents spawn child agents via the spawn model (child runs as a tool call) with structured concurrency, bounded depth, and traceable run trees — the largest kore-core change, sequenced last so AgentLoop.kt edits land on top of Phase 5's OBSV-03 changes.
**Verified:** 2026-06-22T07:05:00Z
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Developer declares a child via `child { }`; child runs as a tool call; result feeds back as `ToolResult` (HIER-01) | ✓ VERIFIED | `AgentBuilder.child()` (AgentBuilder.kt:150-172) registers an `AgentTool`; `AgentTool.callTool` runs `childLoop.run(childTask)` inline and `mapResult`s to a `ToolResult` (AgentTool.kt:64-113). Test `HIER-01 child runs as a tool call and parent reaches Success` passes (executed 2026-06-22, 4/4 AgentToolTest cases). |
| 2 | Cancelling a parent cancels all running child agents — structured concurrency (HIER-02) | ⚠️ PARTIAL | Inline child run inside parent `coroutineScope` propagates cancellation; `HIER-02 cancelling the parent cancels a running child promptly` passes. BUT verified only single-threaded; CR-01 shared-cell race means lineage/depth can be clobbered under concurrent parent runs. |
| 3 | Child spawn beyond `maxDepth` (default 5) yields `ToolError`; unbounded recursion impossible (HIER-03) | ✓ VERIFIED (single-thread) / ⚠️ concurrency caveat | Depth guard in `AgentTool.callTool` (lines 65-74) refuses BEFORE running the child; `D-03 spawn beyond maxDepth refuses with isError=true and never runs the child` passes (childInvocations==0, no child audit row, parent → `AgentResult.ToolError`). Caveat: the `parentDepthCell` the guard reads is the same shared cell affected by CR-01, so under concurrency the depth value can be wrong. |
| 4 | Audit log records `parent_run_id` on child runs; full run tree traceable from the database (HIER-04) | ⚠️ UNCERTAIN | In-memory path VERIFIED (`HIER-04 shared audit log records a child run with parentRunId == parent id` passes). Persistent path: V2 migration + column + adapter write all exist and have well-formed integration tests, but tests are env-blocked (Ryuk container timeout) and the path carries the CR-02 non-UUID/unguarded-write defect untested by those UUID-only tests. |
| 5 | Existing single-agent definitions compile and run unchanged — all new params defaulted (binary compat) | ✓ VERIFIED | `AgentTask.depth`/`parentRunId` defaulted (AgentTask.kt:9,11); `AgentLoop.maxDepth = 5` defaulted (AgentLoop.kt:59); `KoreAgentFactory.maxDepth = 5` defaulted. Unchanged `AgentLoopTest` (4/4) and all 39 kore-core tests pass with 0 failures. |

**Score:** 3/5 truths verified (criteria 2 and 4 partial)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `AgentTask.kt` | defaulted depth + parentRunId | ✓ VERIFIED | Both fields present and defaulted (lines 9, 11). |
| `port/ChildDispatchBinder.kt` | bind seam interface | ✓ VERIFIED | Interface present; bound via `filterIsInstance` in AgentLoop. |
| `AgentLoop.kt` | maxDepth param, NonCancellable Cancelled audit, dispatch-time bind | ✓ VERIFIED (with WR-02 defect) | maxDepth=5 (59), NonCancellable cancel audit (91-94), bind before dispatch (240-242). OBSV-03 span block intact (122-153). Defect: success-path `.also` audit write unguarded (97-100). |
| `internal/InMemoryAuditLog.kt` | recordedRuns storing parentRunId | ✓ VERIFIED | RunRecord + CopyOnWriteArrayList + recordedRuns accessor (lines 22-39). |
| `port/AgentTool.kt` | ToolProvider+ChildDispatchBinder, depth guard, inline run, D-01 map | ⚠️ ORPHANED-SAFE / racy | Present and substantive; CR-01 shared cells (44-45) and CR-03 hand-rolled `extractInput` (121-132). |
| `dsl/AgentBuilder.kt` | child(), maxDepth(n), buildLoop(), inheritTracer | ✓ VERIFIED (WR-01) | All present (123-203). child() inherits eventBus/auditLog/tracer but NOT budgetEnforcer (WR-01). |
| `db/migration/V2__add_parent_run_id.sql` | nullable indexed UUID, no FK, landmine comment | ✓ VERIFIED | Present with no-FK landmine comment (lines 1-10). |
| `tables/AgentRunsTable.kt` | parentRunId nullable uuid column | ✓ VERIFIED | `javaUUID("parent_run_id").nullable()` (line 18). |
| `PostgresAuditLogAdapter.kt` | writes task.parentRunId null-safe | ✓ VERIFIED (CR-02 defect) | `stmt[parentRunId] = task.parentRunId?.let(UUID::fromString)` (line 70). Defect: `stmt[id] = UUID.fromString(agentId)` (60) crashes on non-UUID id. |
| `KoreProperties.kt` | HierarchyProperties(maxDepth=5) | ✓ VERIFIED | Nested data class + `hierarchy` field (28, 134-136). |
| `KoreAgentFactory.kt` | applies maxDepth before block | ✓ VERIFIED | `maxDepth(this@KoreAgentFactory.maxDepth)` before block() (line 53). |
| `KoreAutoConfiguration.kt` | threads properties.hierarchy.maxDepth | ✓ VERIFIED | Bean passes `maxDepth = properties.hierarchy.maxDepth` (87). |
| Test files (5) | HIER-01/02/03/04 + Spring + storage | ✓ EXIST / mostly executed | AgentToolTest 4/4, AgentLoopCancellationTest 2/2, KoreHierarchyPropertiesTest 4/4 PASS. Storage 2 integration classes env-blocked (Ryuk). |

### Key Link Verification

| From | To | Via | Status |
|------|----|-----|--------|
| `AgentBuilder.child{}` | `AgentTool(childLoop=buildLoop())` | toolProviders.add after port inheritance | ✓ WIRED (AgentBuilder.kt:164-171) |
| `AgentLoop.runLoop` | `ChildDispatchBinder.bind` | filterIsInstance before dispatch | ✓ WIRED (AgentLoop.kt:240-242) — but shared-cell, racy under concurrency (CR-01) |
| `AgentTool.callTool` | `childLoop.run(childTask)` | inline suspend call inside parent scope | ✓ WIRED (AgentTool.kt:87) |
| `AgentLoop.run catch` | `recordAgentRun(Cancelled)` | withContext(NonCancellable) then throw | ✓ WIRED (AgentLoop.kt:91-94) |
| `PostgresAuditLogAdapter` | `AgentRunsTable.parentRunId` | stmt[parentRunId] = task.parentRunId?.let(UUID::fromString) | ✓ WIRED (line 70) — code-verified, runtime env-blocked |
| `KoreProperties.hierarchy.maxDepth` | `AgentBuilder.maxDepth(n)` | factory applies before block | ✓ WIRED (KoreAgentFactory.kt:53, KoreAutoConfiguration.kt:87) |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| kore-core full suite (incl. AgentTool/Cancellation) | `./gradlew :kore-core:test --rerun-tasks` | 39 tests, 0 failures, 0 errors | ✓ PASS |
| kore-spring suite (incl. KoreHierarchyPropertiesTest) | `./gradlew :kore-spring:test --rerun-tasks` | KoreHierarchyPropertiesTest 4/4 pass | ✓ PASS |
| kore-spring/storage compile (Plan 04/02 land) | `./gradlew :kore-spring:compileKotlin :kore-storage:compileKotlin` | BUILD SUCCESSFUL | ✓ PASS |
| ktlint on phase-7 modules | `./gradlew :kore-core:lintKotlin :kore-spring:lintKotlin :kore-storage:lintKotlin` | BUILD SUCCESSFUL | ✓ PASS |
| Storage integration (HIER-04 persistent) | `./gradlew :kore-storage:integrationTest --tests "*Migration*" --tests "*PostgresAuditLogAdapter*"` | ContainerLaunchException — testcontainers/ryuk:0.8.1 wait-strategy timeout | ? SKIP (env-blocked) |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| HIER-01 | 07-03 | child{} spawn model, result feeds back as ToolResult | ✓ SATISFIED | AgentTool + child() + HIER-01 test pass |
| HIER-02 | 07-01, 07-03 | cancel parent cancels children (structured concurrency) | ⚠️ PARTIAL | Single-level test passes; concurrency unproven (CR-01) |
| HIER-03 | 07-01, 07-03, 07-04 | maxDepth ceiling → ToolError, no unbounded recursion | ✓ SATISFIED (single-thread) | Depth guard + D-03 test + Spring property; concurrency caveat (CR-01) |
| HIER-04 | 07-01, 07-02, 07-03 | audit records parent_run_id; run trees traceable | ⚠️ PARTIAL | In-memory verified; persistent path env-blocked + CR-02 defect |

All 4 phase requirement IDs (HIER-01..04) are claimed in PLAN frontmatter and map to REQUIREMENTS.md Phase 7 rows. No orphaned requirements. REQUIREMENTS.md marks all four "Complete" — that status is optimistic for HIER-02/HIER-04 given the concurrency and persistent-path gaps below.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| AgentTool.kt | 44-89 | Shared mutable cells (parentDepthCell/parentRunIdCell) read across suspension/concurrency | 🛑 Blocker | Wrong depth (defeats HIER-03) and wrong parentRunId (corrupts HIER-04 run tree) under concurrent parent runs (REVIEW CR-01) |
| PostgresAuditLogAdapter.kt | 60, 82, 99 | `UUID.fromString(agentId)` on free-form String id | 🛑 Blocker | run() throws on non-UUID/duplicate id — breaks "run NEVER throws" invariant on persistent path (REVIEW CR-02) |
| AgentLoop.kt | 97-100 | Unguarded `.also` audit write | 🛑 Blocker | Any AuditLog throw propagates out of run(), violating documented invariant (REVIEW CR-02/WR-02) |
| AgentTool.kt | 121-132 | Hand-rolled JSON `extractInput` | ⚠️ Warning | Mangles escaped quotes/nested objects; mandate is kotlinx.serialization (REVIEW CR-03) |
| AgentBuilder.kt | 156-171 | child() does not inherit budgetEnforcer | ⚠️ Warning | Each child gets a fresh budget — parent token ceiling escapable via fan-out (REVIEW WR-01) |
| AgentLoop.kt | 282-287 | First isError aborts whole parallel batch | ⚠️ Warning | A single depth-refused child discards sibling successes (REVIEW WR-04) |

No debt markers (TODO/FIXME/XXX/TBD/HACK/PLACEHOLDER) in any phase-7 source file.

### Human Verification Required

#### 1. Storage integration tests (HIER-04 persistent path)

**Test:** Run `./gradlew :kore-storage:integrationTest --tests "*MigrationTest*" --tests "*PostgresAuditLogAdapterTest*"` on a host where Testcontainers Ryuk starts (CI arc-runner-unityinflow, or local Docker with the reaper enabled).
**Expected:** MigrationTest confirms parent_run_id is UUID/nullable/indexed; PostgresAuditLogAdapterTest confirms child row parent_run_id == parent id and root row parent_run_id IS NULL — all green.
**Why human:** The verifier could not start `testcontainers/ryuk:0.8.1` in this environment (LogMessageWaitStrategy timeout). Infrastructure constraint, not a code defect; the persistent-path assertions must run where Docker/Ryuk works.

### Gaps Summary

The phase delivers a working, well-tested, lint-clean hierarchical-agent implementation for the **single-agent / single-threaded** case: `child {}` spawns a child that runs as a tool call and feeds a `ToolResult` back (criterion #1 ✓), the depth ceiling refuses over-limit spawns (criterion #3 ✓ single-thread), binary compatibility is fully preserved (criterion #5 ✓), and the in-memory run tree records parentRunId (part of criterion #4 ✓). All 39 kore-core and the kore-spring hierarchy tests pass on a forced rerun.

Two of the five success criteria are only **partially** met, both rooted in the three BLOCKERs from 07-REVIEW.md:

1. **Structured concurrency + bounded depth under concurrency (criteria #2/#3).** `AgentTool` smuggles per-dispatch lineage (depth, parentRunId) through shared mutable instance cells written by `bind()` and read by `callTool()` after suspension. The goal statement explicitly promises "structured concurrency, bounded depth, and traceable run trees." Under two concurrent parent runs sharing one `AgentTool` (the Spring factory hands out one runner per `@Bean`), the cells race: a child can run at the wrong depth (defeating the ceiling) and be recorded with the wrong parent (corrupting the tree). The current tests use a single-threaded dispatcher and so never exercise this. The single-thread guarantee holds; the concurrency guarantee is unproven and, per the review's analysis, actively broken.

2. **Traceable run trees from the database (criterion #4) on the persistent path.** The V2 migration, Exposed column, and adapter write all exist and have correct-looking integration tests, but (a) those tests could not execute here (Ryuk container timeout — routed to human verification) and (b) the persistent write path crashes on a non-UUID `AgentTask.id` and breaks the documented `run NEVER throws` invariant because the `.also` audit write is unguarded. The integration tests only use UUID ids, so this crash is untested even when Docker works.

Recommendation: address CR-01 (per-coroutine lineage), CR-02 (guard the audit write + stop assuming UUID ids), and add the missing concurrency / no-throw / non-UUID tests before treating HIER-02 and HIER-04 as contract-complete. CR-03 (hand-rolled JSON) and WR-01 (child budget inheritance) are strong warnings worth folding into the same gap-closure pass.

---

_Verified: 2026-06-22T07:05:00Z_
_Verifier: Claude (gsd-verifier)_
