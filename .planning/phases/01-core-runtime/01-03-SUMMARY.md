---
phase: 01-core-runtime
plan: "03"
subsystem: kore-core
tags: [kotlin, agent-loop, dsl, coroutines, tdd, react-loop, resilient-backend, in-memory-stubs]

# Dependency graph
requires:
  - 01-01 (Gradle multi-module scaffold with version catalog)
  - 01-02 (AgentResult sealed class, LLMChunk, port interfaces)
provides:
  - AgentLoop.run(): coroutine-based ReAct loop returning AgentResult — never throws
  - AgentRunner: supervised coroutine scope for async agent execution
  - ResilientLLMBackend: exponential backoff retry + fallbackTo infix chain
  - RetryPolicy: configurable exponential backoff parameters
  - InMemoryBudgetEnforcer: ConcurrentHashMap token budget stub (BUDG-02)
  - InProcessEventBus: MutableSharedFlow with DROP_OLDEST buffer=64
  - InMemoryAuditLog: no-op stub (replaced by PostgresAuditLogAdapter in Phase 2)
  - agent { } DSL with @KoreDsl DslMarker — Pitfall 10 prevention
  - AgentBuilder + LLMConfigBuilder DSL builders
  - Dsl.kt top-level agent() entry point
affects:
  - 01-04 (MCP client — ToolProvider implementations wired via AgentBuilder.tools())
  - 01-05 (LLM adapters — LLMBackend implementations used via model = claude() etc.)
  - 01-06 (kore-test — MockLLMBackend scripted backend pattern established here)
  - All subsequent plans using AgentLoop or agent { } DSL

# Tech tracking
tech-stack:
  added: []
  patterns:
    - TDD RED/GREEN: AgentLoopTest written before AgentLoop implementation
    - ReAct loop pattern: task intake → LLM call → tool use (parallel) → result → loop
    - Parallel tool dispatch: coroutineScope { toolCalls.map { async { ... } }.awaitAll() }
    - ResilientLLMBackend decorator: wrap primary with retries, chain fallbacks via infix
    - @KoreDsl DslMarker on all builder receivers (Pitfall 10 prevention)
    - InProcessEventBus: MutableSharedFlow with DROP_OLDEST buffer (Pitfall 9 prevention)
    - ConcurrentHashMap.merge for atomic token accumulation in InMemoryBudgetEnforcer

key-files:
  created:
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentLoop.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentRunner.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/RetryPolicy.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/ResilientLLMBackend.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InMemoryBudgetEnforcer.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InProcessEventBus.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InMemoryAuditLog.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/KoreDsl.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/AgentBuilder.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/Dsl.kt
    - kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentLoopTest.kt
  modified: []

key-decisions:
  - "AgentLoop history is a MutableList passed through runLoop() — mutated in-place each iteration (not re-created), accumulation via + on TokenUsage"
  - "InMemoryBudgetEnforcer uses ConcurrentHashMap.merge for atomic accumulation — thread-safe without locks for concurrent agents in same JVM"
  - "ResilientLLMBackend.retryPolicy made internal (not private) so the fallbackTo infix can copy it when chaining — avoids exposing a getter on the public API"
  - "LLMConfigBuilder added to AgentBuilder DSL (not in plan) — provides DSL-style config block for users who need non-default LLMConfig"
  - "AgentRunner.shutdown() uses scope.coroutineContext.job.cancel() — cleaner than the plan's SupervisorJob() accessor pattern"

patterns-established:
  - "AgentLoop never throws pattern: every catch block logs/wraps into AgentResult; CancellationException always re-thrown"
  - "Parallel tool dispatch pattern: coroutineScope { list.map { async { ... } }.awaitAll() } — established as the canonical form"
  - "DSL DslMarker pattern: @KoreDsl on annotation class + @KoreDsl on every builder class and method receiver"

requirements-completed:
  - CORE-01
  - CORE-03
  - CORE-04
  - CORE-05
  - CORE-06
  - CORE-07
  - BUDG-02
  - BUDG-03
  - BUDG-04

# Metrics
duration: 4min
completed: 2026-04-10
---

# Phase 1 Plan 03: AgentLoop, Kotlin DSL, and In-Memory Stubs Summary

**Coroutine-based ReAct agent loop with AgentResult sealed class returns, `agent { }` DSL with @KoreDsl DslMarker, ResilientLLMBackend with fallbackTo infix, and three in-memory stub implementations — kore-core's beating heart is complete**

## Performance

- **Duration:** 4 min
- **Started:** 2026-04-10T19:05:03Z
- **Completed:** 2026-04-10T19:09:03Z
- **Tasks:** 2
- **Files modified:** 11

## Accomplishments

- AgentLoop.run(): ReAct loop with task intake → LLM call → parallel tool dispatch → result → loop. Never throws (CancellationException always re-thrown)
- Budget enforcement: BudgetEnforcer.checkBudget() called before every LLM call (T-03-02, D-25)
- Parallel tool dispatch: coroutineScope { toolCalls.map { async { callTool } }.awaitAll() } (D-22)
- ResilientLLMBackend: exponential backoff retry + fallbackTo infix chain (`claude() fallbackTo gpt()`)
- InMemoryBudgetEnforcer: ConcurrentHashMap-backed token stub — thread-safe for concurrent agents (BUDG-02)
- InProcessEventBus: MutableSharedFlow with extraBufferCapacity=64 and DROP_OLDEST (Pitfall 9 prevention)
- InMemoryAuditLog: no-op stub matching AuditLog port — will be replaced by PostgresAuditLogAdapter in Phase 2
- `agent { }` DSL: @KoreDsl DslMarker on AgentBuilder prevents outer-scope leakage in nested blocks (Pitfall 10)
- All 4 AgentLoopTest TDD tests pass: Success, BudgetExceeded, LLMError, tool-loop-back

## Critical Implementation Details

### AgentLoop.run() signature

```kotlin
suspend fun run(task: AgentTask): AgentResult
```

INVARIANT: Never throws. Returns AgentResult sealed class variant. Only CancellationException escapes.

### DSL entry point

```kotlin
val runner: AgentRunner = agent("my-agent") {
    model = someBackend  // required
    tools(myProvider)    // optional, multiple
    budget(maxTokens = 10_000L)
    retry(maxAttempts = 3)
    config { model = "claude-3-5-sonnet-20241022"; maxTokens = 8192 }
}
```

### fallbackTo infix chain

```kotlin
val backend = primaryBackend fallbackTo fallbackBackend fallbackTo lastResortBackend
```

## Task Commits

Each task was committed atomically:

1. **Task 1: AgentLoop, AgentRunner, ResilientLLMBackend, in-memory stubs + TDD tests** - `7681308` (feat)
2. **Task 2: Kotlin DSL (KoreDsl, AgentBuilder, Dsl.kt)** - `d85c70b` (feat)

## Files Created

- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentLoop.kt` — ReAct loop, budget check, parallel tool dispatch
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentRunner.kt` — SupervisorJob coroutine scope, async run
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/RetryPolicy.kt` — exponential backoff config
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/ResilientLLMBackend.kt` — retry + fallback decorator + fallbackTo infix
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InMemoryBudgetEnforcer.kt` — ConcurrentHashMap token budget stub
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InProcessEventBus.kt` — SharedFlow with DROP_OLDEST
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InMemoryAuditLog.kt` — no-op stub
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/KoreDsl.kt` — @DslMarker annotation
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/AgentBuilder.kt` — @KoreDsl builder with all config methods
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/Dsl.kt` — top-level `agent()` entry point
- `kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentLoopTest.kt` — 4 TDD tests

## Decisions Made

- **History management**: `MutableList<ConversationMessage>` passed through `runLoop()` — mutated in-place each iteration. Tool results appended after each parallel dispatch. Max iterations enforced by `repeat(config.maxHistoryMessages)` (T-03-02).
- **InMemoryBudgetEnforcer data structure**: `ConcurrentHashMap` with `merge()` for atomic accumulation. No explicit locking needed — merge is atomic. T-03-04 (growth bounded by agent count) accepted.
- **retryPolicy visibility**: Changed from `private` to `internal` in `ResilientLLMBackend` so `fallbackTo` infix can copy it when building a chained resilient backend.
- **LLMConfigBuilder added**: Plan didn't specify it, but `AgentBuilder.config { }` block needed a receiver. Added `LLMConfigBuilder` as a nested `@KoreDsl` builder — pure additive, no plan deviation impact.
- **AgentRunner.shutdown()**: Used `scope.coroutineContext.job.cancel()` instead of the plan's `scope.coroutineContext[SupervisorJob()]?.cancel()` — cleaner Kotlin idiom, same behavior.

## Deviations from Plan

### Auto-added Functionality

**1. [Rule 2 - Missing] Added LLMConfigBuilder for DSL config block**
- **Found during:** Task 2 (DSL implementation)
- **Issue:** `AgentBuilder.config { }` DSL method needs a typed receiver to allow `model = "..."` style assignment. Plan did not specify a `LLMConfigBuilder` class.
- **Fix:** Added `LLMConfigBuilder` as an `@KoreDsl`-annotated class with matching fields, wires to `LLMConfig` on `build()`
- **Files modified:** `AgentBuilder.kt`
- **Impact:** Additive only — does not change any existing API. `config { }` method is optional.

### Auto-fixed Issues

**2. [Rule 1 - Bug] ResilientLLMBackend.retryPolicy visibility**
- **Found during:** Task 1 compilation
- **Issue:** `fallbackTo` infix function in same file as `ResilientLLMBackend` could not access `retryPolicy` when it was `private` — needed to copy it when building chained resilient backend
- **Fix:** Changed `private val retryPolicy` to `internal val retryPolicy`
- **Files modified:** `ResilientLLMBackend.kt`
- **Commit:** `7681308` (included in Task 1 commit)

**3. [Rule 1 - Bug] BufferOverflow import wrong package**
- **Found during:** Task 1 compilation
- **Issue:** `BufferOverflow` imported from `kotlinx.coroutines.flow` — does not exist there; correct package is `kotlinx.coroutines.channels`
- **Fix:** Changed import to `kotlinx.coroutines.channels.BufferOverflow`
- **Files modified:** `InProcessEventBus.kt`
- **Commit:** `7681308` (included in Task 1 commit)

---

**Total deviations:** 1 auto-added (Rule 2 — LLMConfigBuilder), 2 auto-fixed (Rule 1 — visibility + import)
**Impact on plan:** All additions/fixes required for correct compilation and API completeness. No scope changes.

## Threat Mitigations Applied

Per the plan's threat model:

- **T-03-01** (Tampering — ToolCall arguments): AgentLoop passes `chunk.arguments` as-is to `ToolProvider.callTool()` — validation is the ToolProvider's responsibility per the threat model mitigation plan.
- **T-03-02** (DoS — unbounded loop): `repeat(config.maxHistoryMessages)` enforces max iterations; budget check before each LLM call.
- **T-03-03** (DoS — child agents not cancelled): AgentRunner uses `CoroutineScope(SupervisorJob() + Dispatchers.Default)` — child coroutines live in this scope. `shutdown()` cancels the scope job, propagating to all children.
- **T-03-04** (DoS — InMemoryBudgetEnforcer map growth): Accepted for Phase 1. ConcurrentHashMap growth bounded by running agent count.
- **T-03-05** (Repudiation — loop never throws): All catch blocks wrap into `AgentResult`; CancellationException always re-thrown. `auditLog.recordAgentRun()` called in `.also {}` on every path.

## Known Stubs

- `InMemoryBudgetEnforcer` — no persistence, resets on JVM restart. Replaced by budget-breaker adapter (Tool 05) in v2.
- `InMemoryAuditLog` — no-op stub. Replaced by `PostgresAuditLogAdapter` in Phase 2 (kore-storage).

Both stubs are intentional and documented. The plan explicitly calls them out as Phase 1 stubs.

## Threat Flags

None — no new network endpoints, auth paths, file access, or trust boundary changes. All code is in-process JVM logic.

## Next Phase Readiness

- AgentLoop is the core loop — Plans 04 (MCP client) and 05 (LLM adapters) plug in as ToolProvider and LLMBackend implementations
- `agent { }` DSL is the user-facing API — Plans 04/05 will add `mcp()` and `claude()` DSL extension functions
- kore-test (Plan 06) will provide `MockLLMBackend` with the same `scriptedBackend` pattern established here in `AgentLoopTest`

No blockers for Wave 3 parallel execution (Plans 04 + 05).

---
## Self-Check: PASSED

- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentLoop.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentRunner.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/RetryPolicy.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/ResilientLLMBackend.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InMemoryBudgetEnforcer.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InProcessEventBus.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InMemoryAuditLog.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/KoreDsl.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/AgentBuilder.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/dsl/Dsl.kt
- FOUND: kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentLoopTest.kt
- FOUND commit 7681308 (Task 1)
- FOUND commit d85c70b (Task 2)

---
*Phase: 01-core-runtime*
*Completed: 2026-04-10*
