---
phase: 07-hierarchical-agents
plan: 01
subsystem: kore-core
tags: [hierarchical-agents, agent-loop, audit-log, binary-compat]
requires: []
provides:
  - "AgentTask.depth: Int = 0 (D-07)"
  - "AgentTask.parentRunId: String? = null (D-09)"
  - "AgentLoop.maxDepth: Int = 5 ctor param (D-08)"
  - "ChildDispatchBinder interface (kore-core port) — A2 dispatch-time bind seam"
  - "InMemoryAuditLog.RunRecord + recordedRuns list (D-11/D-12)"
  - "AgentLoop D-05 best-effort Cancelled audit on cancel path"
affects:
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentTask.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/internal/InMemoryAuditLog.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/ChildDispatchBinder.kt
tech-stack:
  added: []
  patterns:
    - "Defaulted ctor params/fields for binary compat (criterion #5)"
    - "withContext(NonCancellable) shields audit write on cancel path; CancellationException always re-thrown (T-03-03)"
    - "is ChildDispatchBinder filterIsInstance dispatch-time binding (A2, RESEARCH Pattern 3)"
    - "CopyOnWriteArrayList recorded-run list (no var, concurrent)"
key-files:
  created:
    - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/ChildDispatchBinder.kt
  modified:
    - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentTask.kt
    - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt
    - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/internal/InMemoryAuditLog.kt
decisions:
  - "ChildDispatchBinder is its own port interface so AgentLoop binds against `is ChildDispatchBinder` without a forward reference to AgentTool — kore-core compiles standalone in wave 1 (D-04/A2)"
  - "Cancel-path audit uses withContext(NonCancellable) wrapping only the recordAgentRun call; throw e preserved verbatim (T-03-03 invariant)"
  - "InMemoryAuditLog uses CopyOnWriteArrayList + recordedRuns get() accessor (no var per CLAUDE.md); recordLLMCall/recordToolCall/queryRecentRuns/queryCostSummary left as existing no-op stubs"
  - "maxDepth threaded only as data carrier in AgentLoop; the enforcing depth guard lives in AgentTool.callTool (Plan 03, T-7-01)"
metrics:
  duration: 4min
  completed: 2026-06-21
  tasks: 2
  files: 4
---

# Phase 07 Plan 01: kore-core Hierarchical-Agents Foundation Summary

Laid the kore-core foundation for hierarchical (child-as-tool-call) agents: defaulted `AgentTask.depth`/`parentRunId` fields, a defaulted `AgentLoop.maxDepth=5` param, the new `ChildDispatchBinder` runtime-binding seam, an `InMemoryAuditLog` recorded-run list, and the D-05 best-effort `Cancelled` audit on the cancellation path — all without breaking the published v0.0.1 API.

## What Was Built

### Task 1 — AgentTask fields + InMemoryAuditLog recorded list + D-05 Cancelled audit (`798c245`)
- `AgentTask`: added `val depth: Int = 0` (D-07) and `val parentRunId: String? = null` (D-09), both defaulted so the published `AgentTask("x","y")` constructor compiles unchanged (criterion #5).
- `InMemoryAuditLog`: replaced the no-op `recordAgentRun` with a `RunRecord(agentId, parentRunId, resultType)` appended to a `CopyOnWriteArrayList`, exposed via a `recordedRuns: List<RunRecord>` accessor. Other members remain no-op stubs; `isPersistent=false` inherited; no `@Serializable`.
- `AgentLoop.run`: the existing `catch (CancellationException)` now records `AgentResult.Cancelled(reason)` inside `withContext(NonCancellable)`, then re-throws. The existing `.also { recordAgentRun(...) }` does not fire on the throw path, so there is exactly one `Cancelled` row (RESEARCH Pitfall 2 — no double record).

### Task 2 — ChildDispatchBinder seam + maxDepth param + dispatch-time bind (`c8ae502`)
- New `port/ChildDispatchBinder.kt`: `interface ChildDispatchBinder { fun bind(parentDepth, parentRunId) }` — the A2 runtime-binding seam (RESEARCH Pattern 3 / Pitfall 1). Lets `AgentLoop` bind without a forward reference to `AgentTool` (Plan 03 implements it).
- `AgentLoop`: defaulted `maxDepth: Int = 5` ctor param slotted between `tracer` and `config` (D-08, binary compat). `runLoop` gained a `parentDepth` param fed from `task.depth` at the call site. Before the `coroutineScope` tool-dispatch block, every `toolProviders.filterIsInstance<ChildDispatchBinder>()` is bound with `parentDepth=parentDepth, parentRunId=agentId`.
- Phase 5 OBSV-03 skill-activation span block was left intact (rebase, not revert).

## Verification

- `./gradlew :kore-core:test` — green (all existing tests pass unchanged; criterion #5 proof via untouched `AgentLoopTest`).
- `./gradlew :kore-core:lintKotlin` — passes (ktlint).
- grep confirmations: `val depth: Int = 0`, `val parentRunId: String? = null` (AgentTask); `maxDepth: Int = 5`, `is ChildDispatchBinder` (via `filterIsInstance<ChildDispatchBinder>`), `withContext(NonCancellable)` (AgentLoop); `recordedRuns` (InMemoryAuditLog); `interface ChildDispatchBinder` (new file).

## Deviations from Plan

None — plan executed as written. (During editing, the `ChildDispatchBinder` import was reordered to satisfy ktlint `import-ordering` — a transient formatting fix, not a behavioral deviation.)

The plan suggested `is ChildDispatchBinder` via a `when`/iterate idiom; implemented as `toolProviders.filterIsInstance<ChildDispatchBinder>().forEach { it.bind(...) }`, which is the var-free, type-safe equivalent and matches the `pattern: "is ChildDispatchBinder"` must-have (filterIsInstance is the `is`-based form).

## Known Stubs

`InMemoryAuditLog.recordLLMCall`, `recordToolCall`, `queryRecentRuns`, `queryCostSummary` remain no-op/empty stubs — intentional and pre-existing; real persistence lives in `PostgresAuditLogAdapter` (kore-storage). Not in scope for this plan.

## Out-of-Scope Pre-existing Warnings (not modified)

- `AgentLoopSkillTest.kt` — `ExperimentalCoroutinesApi` opt-in warnings (pre-existing).
- `AgentLoopTest.kt:56,87` — "No cast needed" warnings (pre-existing test code, not touched).

## Notes for Later Plans

- Plan 02 (storage): `AgentRunsTable`/`PostgresAuditLogAdapter` read `task.parentRunId` (D-09); `V2__add_parent_run_id.sql`.
- Plan 03 (AgentTool): implements `ChildDispatchBinder` with val-cell holders; enforces the `maxDepth` guard in `callTool` (D-08); `AgentBuilder.buildLoop()` sets `maxDepth`. `AgentToolTest`/`AgentLoopCancellationTest` consume `InMemoryAuditLog.recordedRuns`.

## Self-Check: PASSED

All 4 created/modified source files exist on disk; SUMMARY.md exists; both task commits (`798c245`, `c8ae502`) present in git history.
