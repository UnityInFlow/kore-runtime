---
phase: 07-hierarchical-agents
plan: 03
subsystem: kore-core
tags: [hierarchical-agents, spawn-model, agent-tool, dsl, cancellation]
requires:
  - "07-01: AgentTask.depth/parentRunId, AgentLoop.maxDepth, ChildDispatchBinder, InMemoryAuditLog.recordedRuns"
provides:
  - "AgentTool (kore-core port): ToolProvider + ChildDispatchBinder spawn implementation"
  - "AgentBuilder.child(name, description, block) — child-as-tool DSL with port inheritance (D-12)"
  - "AgentBuilder.maxDepth(n) (D-08)"
  - "AgentBuilder.buildLoop(): AgentLoop (internal) — children run the loop inline (D-04)"
  - "AgentBuilder.inheritTracer(tracer) (A1) + tracer val-cell"
affects:
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/AgentTool.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/dsl/AgentBuilder.kt
  - kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentToolTest.kt
  - kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentLoopCancellationTest.kt
tech-stack:
  added: []
  patterns:
    - "val-cell holders (IntArray(1)/arrayOfNulls(1)) for runtime-bound state — no var (A2)"
    - "Exhaustive sealed when over AgentResult, no else (D-01 mapResult)"
    - "Inline childLoop.run(childTask) inside parent coroutine scope — cancellation propagates (D-04)"
    - "Depth guard BEFORE child run — the only isError=true path (D-03/T-7-01)"
    - "backgroundScope + runCurrent for never-completing coroutine cancellation tests (Pitfall 5)"
key-files:
  created:
    - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/AgentTool.kt
    - kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentToolTest.kt
    - kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentLoopCancellationTest.kt
  modified:
    - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/dsl/AgentBuilder.kt
decisions:
  - "AgentTool extracts the single \"input\" string from LLM JSON via a defensive string scan (no kotlinx.serialization on kore-core runtime — compileOnly only); falls back to raw arguments if no input field (T-7-07 opaque-text handling)"
  - "child { } overwrites the child builder's eventBus/auditLog/tracer with the parent's AFTER block runs, so D-12 inheritance wins over the child's throwaway InProcessEventBus/InMemoryAuditLog defaults"
  - "AgentBuilder.buildLoop() extracted as internal; build() now delegates to it — children need the AgentLoop directly because AgentTool runs it inline (D-04), not via AgentRunner"
  - "tracer + maxDepth threaded into AgentLoop(...) via the defaulted ctor params added in Plan 01 (binary compat preserved)"
metrics:
  duration: 7min
  completed: 2026-06-22
  tasks: 2
  files: 4
---

# Phase 07 Plan 03: AgentTool Spawn Model Summary

Materialized the child-as-tool-call spawn model: `AgentTool` (implements both `ToolProvider` and Plan 01's `ChildDispatchBinder`) advertises one child tool, enforces the depth ceiling before running, runs the child `AgentLoop` inline so cancellation propagates, and maps the child `AgentResult` to a `ToolResult` with the D-01 asymmetry. `AgentBuilder` gained `child { }`, `maxDepth(n)`, an internal `buildLoop()`, and a tracer cell + `inheritTracer`.

## What Was Built

### Task 1 — AgentTool + AgentBuilder child/maxDepth/buildLoop/tracer (`a3619da`)
- **`AgentTool`** (new, `kore-core` `port` package): ctor `(childName, description, childLoop: AgentLoop, maxDepth: Int)`; implements `ToolProvider` + `ChildDispatchBinder`.
  - A2 val-cell holders `IntArray(1)` (parentDepth) + `arrayOfNulls<String>(1)` (parentRunId); `bind()` writes index 0.
  - `listTools()` → exactly one `ToolDefinition(childName, description, INPUT_SCHEMA)` where `INPUT_SCHEMA` (companion const) is a single required `input` string (D-02).
  - `callTool()`: `childDepth = parentDepth + 1`; `childDepth > maxDepth` → `ToolResult(isError=true)` BEFORE running the child (D-03/T-7-01 — no child loop, no child audit row). Else parse `input` defensively, build `AgentTask(id=UUID, input, depth=childDepth, parentRunId=bound)`, `childLoop.run(childTask)` INLINE (D-04), `mapResult`.
  - `mapResult`: exhaustive `when` over all 5 `AgentResult` variants, no `else`; `Success` → output; every other variant → descriptive low-cardinality content, all `isError=false` (D-01).
- **`AgentBuilder`**: `maxDepth(n)` (D-08, `IntArray(1)` cell default 5); `inheritTracer(tracer)` (A1, `arrayOfNulls<Tracer>(1)` cell); `buildLoop(): AgentLoop` extracted from `build()` (adds `tracer` + `maxDepth` to the `AgentLoop(...)` call); `child(name, description, block)` builds a child `AgentBuilder`, overwrites its ports with the parent's (D-12), and registers an `AgentTool`. `build()` now `= AgentRunner(loop = buildLoop())`.

### Task 2 — AgentToolTest + AgentLoopCancellationTest (`668b0ed`)
- **`AgentToolTest`** (4 cases): HIER-01 child runs as a tool call → parent `Success`; D-01 ran-and-failed child → `isError=false` and parent NOT `ToolError` (ends `Success`); D-03 depth>maxDepth → `isError=true`, child backend invocation counter `0`, no child audit row in the shared log, and through a parent loop → `AgentResult.ToolError`; HIER-04 shared `InMemoryAuditLog.recordedRuns` has a child record with `parentRunId == parent id`.
- **`AgentLoopCancellationTest`** (2 cases): HIER-02 child suspends on a never-completing `CompletableDeferred`; parent launched on `backgroundScope`; cancelling the parent job propagates cancellation into the inline child promptly (child's `CancellationException` latch completes after `runCurrent`); D-05 exactly one `Cancelled` audit row in the shared log. Uses `backgroundScope` + `runCurrent`, never `advanceUntilIdle` (Pitfall 5).

## Verification

- `./gradlew :kore-core:compileKotlin :kore-core:compileTestKotlin` — clean (Task 1 gate; Plan 01's `bind` call resolves `AgentTool` via `ChildDispatchBinder`).
- `./gradlew :kore-core:test` — green including both new test classes (6 new cases) and all pre-existing tests.
- `./gradlew :kore-core:lintKotlin` — passes (no `var` in new code, no `!!`, exhaustive sealed `when`).

## Deviations from Plan

None behavioral — plan executed as written. Three transient ktlint formatting fixes during editing (not behavioral deviations):
- `AgentTool` super-type list split onto its own line (`standard:class-signature`).
- `AgentBuilder` `AgentTool` import reordered into lexicographic position (`standard:import-ordering`).
- `AgentToolTest` removed a redundant `as AgentResult.Success` cast flagged "No cast needed" (Kotest `shouldBeInstanceOf` already smart-casts).

## Known Stubs

None introduced. (`InMemoryAuditLog`'s pre-existing `recordLLMCall`/`recordToolCall`/`queryRecentRuns`/`queryCostSummary` no-op stubs are unchanged and out of scope — real persistence lives in `PostgresAuditLogAdapter`.)

## Notes for Later Plans

- Plan 04 (kore-spring wiring): thread `properties.hierarchy.maxDepth` into `KoreAgentFactory` via the new `AgentBuilder.maxDepth(n)`; pass the factory's tracer through `inheritTracer` once a tracer bean is wired (D-12). `HierarchyProperties(maxDepth=5)` per 07-PATTERNS.md.
- `child { }` port inheritance relies on the parent builder having its `eventBus`/`auditLog`/tracer set BEFORE `child { }` is called — the factory pre-wires those, so order is satisfied.
- D-06: grandchild cancellation propagation is deferred; the single-level HIER-02 test satisfies criterion #2.

## Self-Check: PASSED

All 4 created/modified source files exist on disk; SUMMARY.md exists; both task commits (`a3619da`, `668b0ed`) present in git history.
