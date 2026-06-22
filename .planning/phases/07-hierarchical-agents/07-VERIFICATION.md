---
phase: 07-hierarchical-agents
verified: 2026-06-22T20:30:00Z
status: passed
human_verification_resolved: 2026-06-22T15:35:00Z — :kore-storage:integrationTest ran against real PostgreSQL (Testcontainers, TESTCONTAINERS_RYUK_DISABLED=true); PostgresAuditLogAdapterTest 7/7 + MigrationTest 5/5, 0 failures. See 07-UAT.md.
score: 5/5 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 3/5
  gaps_closed:
    - "Criterion #2 (HIER-02): structured-concurrency / lineage guarantee under concurrent parent runs — CR-01 shared mutable cells replaced by per-coroutine ChildLineage context element; multi-threaded concurrency test on Dispatchers.Default executes and passes."
    - "Criterion #4 (HIER-04): persistent-path run-tree traceability — CR-02 no-throw invariant restored (runCatching-guarded audit write, executed unit test passes); non-UUID ids handled via toStableUuid(); new @Tag(\"integration\") test asserts non-UUID parent/child correlation."
  gaps_remaining: []
  regressions: []
human_verification:
  - test: "Run ./gradlew :kore-storage:integrationTest --tests \"*PostgresAuditLogAdapterTest*\" --tests \"*MigrationTest*\" on a host where Testcontainers/Ryuk starts (CI arc-runner-unityinflow, or local Docker with the reaper enabled)."
    expected: "All PostgresAuditLogAdapterTest cases green, including the new 'recordAgentRun persists a non-UUID agentId and preserves non-UUID parent-child correlation (CR-02)' — recordAgentRun does not throw on non-UUID ids, the child row exists under its derived UUID, and its parent_run_id equals toStableUuid(\"parent-1\"). MigrationTest confirms parent_run_id is UUID/nullable/indexed. Confirms criterion #4 on the persistent path end-to-end."
    why_human: "The verifier could not start testcontainers/ryuk:0.8.1 in this environment (LogMessageWaitStrategy timeout — the integration run failed at initializationError, NOT at any test assertion). This is the pre-declared environment constraint, not a code defect. The persistent-path CODE and TESTS are present and correct; the canonical integration gate is CI."
---

# Phase 07: Hierarchical Agents Verification Report

**Phase Goal:** Parent agents spawn child agents via the spawn model (child runs as a tool call) with structured concurrency, bounded depth, and traceable run trees.
**Verified:** 2026-06-22T20:30:00Z
**Status:** human_needed
**Re-verification:** Yes — after gap closure (previous 3/5 gaps_found → 5/5 code-verified; one CI-gated execution routed to human)

## Re-Verification Summary

The prior verification (2026-06-22T07:05Z) scored the **pre-triage** code 3/5 with two PARTIAL criteria (#2 HIER-02, #4 HIER-04) rooted in three REVIEW BLOCKERs (CR-01, CR-02) and one WARNING (WR-01). Triage commit `eb370c0` and gap-closure commit `2efd537` have since landed. This re-verification confirms — against the actual code and executed test results, NOT SUMMARY prose — that all three BLOCKERs are fixed and both PARTIAL criteria are now closed in code.

| Gap (prior) | Root cause | Fix verified in code | Test evidence (executed) |
|---|---|---|---|
| Criterion #2 PARTIAL | CR-01: shared mutable `parentDepthCell`/`parentRunIdCell` raced across concurrent parent runs | `AgentTool.kt` no longer has the cells (46-53, 64-93); `bind()` is pure, returns immutable `ChildLineage`; `AgentLoop.kt:249-257` installs it via `withContext`; `callTool` reads `coroutineContext[ChildLineage]` | `AgentToolTest > CR-01 concurrent binds on one shared AgentTool do not corrupt per-run lineage` — 50 concurrent runs on `Dispatchers.Default`, each asserts its own parentRunId/depth. EXECUTED, PASSED (test-results XML). |
| Criterion #4 PARTIAL | CR-02a: unguarded `.also` audit write broke run-NEVER-throws | `AgentLoop.kt:98-107`: `.also { runCatching { auditLog.recordAgentRun(...) } }` | `AgentLoopTest > CR-02 run returns an AgentResult and does not throw when AuditLog throws` — injects a throwing AuditLog, asserts `Success`. EXECUTED, PASSED. |
| Criterion #4 PARTIAL | CR-02b: `UUID.fromString(agentId)` crashed on non-UUID ids | `PostgresAuditLogAdapter.kt`: `String.toStableUuid()` (233-235) used at all id sites (60, 71, 83, 100) — verbatim UUID parse else deterministic `nameUUIDFromBytes` | New `PostgresAuditLogAdapterTest > recordAgentRun persists a non-UUID agentId and preserves non-UUID parent-child correlation (CR-02)` — present, compiles, ktlint-clean; non-UUID parent/child correlation asserted. Execution CI-gated (Ryuk). |
| WR-01 (warning) | child did not inherit `budgetEnforcer` | `AgentBuilder.kt:192` `budgetEnforcer = budgetEnforcer` passed into child builder | Covered by compile + existing suite. |

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `child { }` inside `agent { }`; child runs as a tool call; result feeds back as `ToolResult` (HIER-01) | ✓ VERIFIED | `AgentBuilder.child()` (AgentBuilder.kt:151-172) registers an `AgentTool`; `AgentTool.callTool` runs `childLoop.run(childTask)` inline (line 91) and `mapResult`s to a `ToolResult` (102-117). `AgentToolTest > HIER-01 child runs as a tool call and parent reaches Success` PASSED (5/5 AgentToolTest, executed via `--rerun-tasks`). |
| 2 | Cancelling a parent cancels all running child agents — structured concurrency, holds under concurrent parent runs (HIER-02 / criterion #2) | ✓ VERIFIED | Inline child run inside parent `async` scope propagates cancellation; `AgentLoopCancellationTest` 2/2 PASSED. CR-01 fixed: lineage flows per-coroutine via `ChildLineage` (ChildLineage.kt:30-36, AgentLoop.kt:249-257, AgentTool.kt:68), no shared mutable cells. `AgentToolTest > CR-01 concurrent binds ... do not corrupt per-run lineage` — 50 concurrent runs on a REAL multi-threaded `Dispatchers.Default`, each asserting its own `parentRunId`/`parentDepth` — EXECUTED and PASSED (test-results XML confirms the named testcase). |
| 3 | Child spawn beyond `maxDepth` (default 5) yields `ToolError` (HIER-03) | ✓ VERIFIED | Depth guard reads depth per-coroutine from `coroutineContext[ChildLineage]` (AgentTool.kt:68-78) and refuses BEFORE running the child (`isError=true`). `AgentToolTest > D-03 spawn beyond maxDepth refuses with isError=true and never runs the child` PASSED. The racy shared cell that could corrupt the depth value is gone (CR-01). Spring property `maxDepth` defaults to 5 (KoreHierarchyPropertiesTest 4/4 PASSED). |
| 4 | Audit log records `parent_run_id` on child runs; run tree traceable from the DB (HIER-04 / criterion #4) | ✓ VERIFIED (persistent execution CI-gated) | In-memory: `AgentToolTest > HIER-04 shared audit log records a child run with parentRunId == parent id` PASSED. Persistent: V2 migration (nullable indexed UUID, no-FK landmine), `AgentRunsTable.parentRunId` column (line 18), and `PostgresAuditLogAdapter` write `stmt[parentRunId] = task.parentRunId?.toStableUuid()` (line 71) all present and well-formed. CR-02 defect resolved: no-throw invariant restored (runCatching, executed unit test PASSED) and `toStableUuid()` handles non-UUID ids. New non-UUID `@Tag("integration")` test asserts child `parent_run_id` == parent's derived UUID — present, compiles, ktlint-clean. Docker execution could not run locally (Ryuk init failure — env constraint) → routed to human verification. |
| 5 | Existing single-agent definitions compile and run unchanged (binary compat) | ✓ VERIFIED | `AgentTask.depth = 0` / `parentRunId = null` defaulted (AgentTask.kt:9,11); `AgentLoop.maxDepth = 5` defaulted (line 60). Full kore-core suite (41 tests incl. unchanged `AgentLoopTest`, `AgentLoopSkillTest`, `HeroDemoTest`) and kore-spring suite (39 tests) — 0 failures on forced `--rerun-tasks`. |

**Score:** 5/5 truths verified (criterion #4 persistent-path Docker execution is CI/human-gated, not a code gap).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `port/ChildLineage.kt` | per-coroutine lineage CoroutineContext element (CR-01) | ✓ VERIFIED | `AbstractCoroutineContextElement` with immutable `parentDepth`/`parentRunId` + companion `Key` (lines 30-36). |
| `port/ChildDispatchBinder.kt` | pure `bind(): ChildLineage` (CR-01) | ✓ VERIFIED | `bind` documented pure, returns `ChildLineage`; no mutation. |
| `port/AgentTool.kt` | no shared cells, lineage read per-coroutine, depth guard, inline run, D-01 map | ✓ VERIFIED | `bind` pure (50-53); `callTool` reads `coroutineContext[ChildLineage]` (68); depth guard (70-78); inline `childLoop.run` (91); `mapResult` D-01 asymmetry (102-117). |
| `AgentLoop.kt` | runCatching-guarded audit write (CR-02), withContext(ChildLineage) install, NonCancellable cancel audit | ✓ VERIFIED | `.also { runCatching { recordAgentRun } ; runCatching { emit } }` (98-107); lineage derived + `withContext(childLineage ?: EmptyCoroutineContext)` (249-257); NonCancellable cancel audit (92-94). |
| `dsl/AgentBuilder.kt` | child() inherits eventBus/auditLog/tracer AND budgetEnforcer (WR-01) | ✓ VERIFIED | child() (151-172); budgetEnforcer threaded at line 192. |
| `PostgresAuditLogAdapter.kt` | toStableUuid at all id sites (CR-02) | ✓ VERIFIED | `String.toStableUuid()` (233-235); used at id (60), parentRunId (71), runId (83, 100). No bare `UUID.fromString` on free-form ids. |
| `db/migration/V2__add_parent_run_id.sql` | nullable indexed UUID, no FK, landmine comment | ✓ VERIFIED | `ALTER TABLE ... ADD COLUMN parent_run_id UUID NULL` + index; explicit no-FK landmine (D-10). |
| `tables/AgentRunsTable.kt` | parentRunId nullable uuid column | ✓ VERIFIED | `javaUUID("parent_run_id").nullable()` (line 18). |
| `AgentTask.kt` | defaulted depth + parentRunId (binary compat) | ✓ VERIFIED | `depth = 0` (9), `parentRunId = null` (11). |
| `AgentToolTest.kt` (CR-01 test) | multi-threaded concurrency test on Dispatchers.Default | ✓ VERIFIED + EXECUTED | Test present (265-301); 50 concurrent runs; testcase in result XML, 0 failures. |
| `AgentLoopTest.kt` (CR-02 test) | no-throw test with throwing AuditLog | ✓ VERIFIED + EXECUTED | Test present (94-135); testcase in result XML, 0 failures. |
| `PostgresAuditLogAdapterTest.kt` (non-UUID test) | non-UUID agentId + parent/child correlation @Tag(integration) | ✓ VERIFIED (compile/lint) | Test present (154-200); compiles + ktlint-clean; execution CI/Docker-gated (Ryuk). |

### Key Link Verification

| From | To | Via | Status |
|------|----|-----|--------|
| `AgentBuilder.child{}` | `AgentTool(childLoop=buildLoop())` | toolProviders.add after port + budget inheritance | ✓ WIRED (AgentBuilder.kt:161-171, 192) |
| `AgentLoop.runLoop` | `ChildDispatchBinder.bind` → `ChildLineage` | `filterIsInstance<ChildDispatchBinder>().firstOrNull().bind(...)` then `withContext(childLineage)` | ✓ WIRED, per-coroutine, NO shared state (AgentLoop.kt:249-257) |
| `AgentTool.callTool` | `coroutineContext[ChildLineage]` | per-coroutine read, depth + parentRunId | ✓ WIRED (AgentTool.kt:68-89) |
| `AgentLoop.run` (.also) | `recordAgentRun` | runCatching guard (no-throw) | ✓ WIRED (AgentLoop.kt:105) |
| `PostgresAuditLogAdapter` | `AgentRunsTable.parentRunId` | `task.parentRunId?.toStableUuid()` | ✓ WIRED — code-verified, runtime CI-gated (line 71) |
| `String.toStableUuid` | UUID PK | verbatim parse else `nameUUIDFromBytes` | ✓ WIRED (233-235) |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| Child `AgentTask.parentRunId` | `lineage?.parentRunId` | `ChildLineage` installed from `bind(parentRunId = agentId)` in AgentLoop dispatch | Yes — real parent agentId, never from LLM input (T-7-02) | ✓ FLOWING |
| `agent_runs.parent_run_id` (persistent) | `task.parentRunId?.toStableUuid()` | child AgentTask → adapter INSERT | Yes — deterministic UUID derivation preserves correlation; verified in-memory; persistent assertion CI-gated | ✓ FLOWING (in-mem) / CI-gated (persistent) |
| Depth guard | `coroutineContext[ChildLineage].parentDepth + 1` | per-coroutine context element | Yes — per-run, no longer racy | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| kore-core full suite (forced rerun, incl. CR-01 concurrency + CR-02 no-throw + HIER-01..04 + binary-compat) | `./gradlew :kore-core:test --rerun-tasks` | 41 tests, 0 failures, 0 errors; `CR-01 concurrent binds...` and `CR-02 run returns an AgentResult and does not throw...` both present in test-results XML | ✓ PASS |
| kore-spring suite (forced rerun, incl. KoreHierarchyPropertiesTest) | `./gradlew :kore-spring:test --rerun-tasks` | 39 tests, 0 failures; KoreHierarchyPropertiesTest 4/4 | ✓ PASS |
| New non-UUID integration test compiles + ktlint | `./gradlew :kore-storage:lintKotlin :kore-storage:compileTestKotlin` | BUILD SUCCESSFUL | ✓ PASS |
| Persistent-path integration (HIER-04 non-UUID) | `./gradlew :kore-storage:integrationTest --tests "*PostgresAuditLogAdapterTest*"` | `initializationError` — testcontainers/ryuk:0.8.1 `LogMessageWaitStrategy` timeout (Docker reaper would not start); failure is at container init, NOT a test assertion | ? SKIP (env-blocked, human-gated) |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| HIER-01 | 07-03 | child{} spawn model, result feeds back as ToolResult | ✓ SATISFIED | AgentTool + child() + HIER-01 test PASSED |
| HIER-02 | 07-01, 07-03, 07-05 | cancel parent cancels children (structured concurrency, under concurrency) | ✓ SATISFIED | Cancellation tests PASSED + CR-01 multi-threaded concurrency test PASSED (per-coroutine lineage) |
| HIER-03 | 07-01, 07-03, 07-04 | maxDepth ceiling → ToolError, no unbounded recursion | ✓ SATISFIED | Depth guard (per-coroutine) + D-03 test + Spring property; race removed |
| HIER-04 | 07-01, 07-02, 07-03, 07-05 | audit records parent_run_id; run trees traceable | ✓ SATISFIED (persistent execution CI-gated) | In-memory test PASSED; persistent code + non-UUID integration test present/compiling; no-throw + toStableUuid verified; Docker run routed to human |

All 4 phase requirement IDs (HIER-01..04) claimed in PLAN frontmatter and mapped to REQUIREMENTS.md Phase 7 rows (all marked Complete — now accurate). No orphaned requirements.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| AgentTool.kt | 125-136 | Hand-rolled JSON `extractInput` | ℹ️ Info (recorded deferral) | Mandate is kotlinx.serialization, but it is `compileOnly` in kore-core by published-library design (zero hard runtime deps). Runtime `Json.decodeFromString` would break that contract. REVIEW CR-03 warning, NOT a verification criterion and NOT in either gap's `missing`. Recorded deferral per 07-05-PLAN. |
| AgentLoop.kt | 293-298 | First isError aborts the whole parallel batch | ℹ️ Info | REVIEW WR-04 — a depth-refused child discards sibling successes. Warning only; not a phase success criterion. |

No 🛑 BLOCKER anti-patterns remain. The three prior BLOCKERs (CR-01 shared cells, CR-02 unguarded write, CR-02 UUID.fromString crash) are all resolved and confirmed against the source. No debt markers (TODO/FIXME/XXX/TBD/HACK/PLACEHOLDER) in any phase-7 source file.

### Human Verification Required

#### 1. Persistent-path Postgres integration tests (HIER-04 criterion #4, end-to-end)

**Test:** Run `./gradlew :kore-storage:integrationTest --tests "*PostgresAuditLogAdapterTest*" --tests "*MigrationTest*"` on a host where Testcontainers/Ryuk starts (CI arc-runner-unityinflow, or local Docker with the reaper enabled).
**Expected:** All cases green, including the new `recordAgentRun persists a non-UUID agentId and preserves non-UUID parent-child correlation (CR-02)` — `recordAgentRun` does not throw on non-UUID ids, the child row exists under its derived UUID, and its `parent_run_id` equals `toStableUuid("parent-1")`. MigrationTest confirms `parent_run_id` is UUID/nullable/indexed.
**Why human:** The verifier could not start `testcontainers/ryuk:0.8.1` in this environment (`LogMessageWaitStrategy` timeout — the run failed at `initializationError`, not at any assertion). This is the pre-declared infrastructure constraint, not a code defect. The persistent-path code and test are present and correct; the canonical integration gate is CI.

### Gaps Summary

No code gaps remain. Re-verification confirms all five success criteria are met in the codebase:

- **#1 (HIER-01)** ✓ — `child {}` spawns a child that runs inline as a tool call and feeds a `ToolResult` back; HIER-01 test PASSED.
- **#2 (HIER-02)** ✓ — cancellation propagates through structured concurrency; the previously-blocking CR-01 shared-cell race is eliminated (per-coroutine `ChildLineage`), proven by an EXECUTED 50-way concurrency test on `Dispatchers.Default`.
- **#3 (HIER-03)** ✓ — depth ceiling refuses over-limit spawns BEFORE the child runs; depth is now read per-coroutine, so the ceiling holds under concurrency.
- **#4 (HIER-04)** ✓ — in-memory run tree verified; persistent path code is complete and correct (no-throw audit write, `toStableUuid` non-UUID handling, V2 migration + column + adapter write), with a new non-UUID integration test that compiles and lints. The Docker EXECUTION of that integration test is the sole remaining item — blocked locally by a Ryuk infrastructure timeout and routed to CI/human verification per the pre-declared environment constraint.
- **#5 (binary compat)** ✓ — all new params defaulted; full kore-core (41) + kore-spring (39) suites green on forced rerun.

Status is **human_needed** (not `passed`) solely because the persistent-path integration test execution must run where Docker/Ryuk works (CI arc-runner-unityinflow). The code and test for that path are verified present and correct; this is an environment-gated execution, not an outstanding code gap. If the human verification item is the only remaining concern, the phase goal is achieved in code at 5/5.

---

_Verified: 2026-06-22T20:30:00Z_
_Verifier: Claude (gsd-verifier)_
