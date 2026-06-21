# Phase 7: Hierarchical Agents - Research

**Researched:** 2026-06-21
**Domain:** Kotlin structured concurrency · DSL builders · Exposed/Flyway audit storage · Spring Boot auto-config
**Confidence:** HIGH (all findings verified against the actual kore-runtime source on disk; no external package research required — phase adds zero new dependencies)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Child-as-tool wiring (HIER-01)**
- **D-01:** A child that runs and ends in a non-Success result (`ToolError`, `LLMError`, `BudgetExceeded`, `Cancelled`) is surfaced to the parent as `ToolResult(isError = false)` with `content` describing the failure. A failing child does NOT abort the parent run. (Because the parent loop converts the *first* `isError = true` ToolResult into `AgentResult.ToolError` and ends the whole run — too aggressive for a recoverable sub-agent failure.)
- **D-02:** The child tool's JSON input schema is a single required `input` string, fed straight into the child's `AgentTask.input`. No structured/metadata params this phase.
- **D-03 (the asymmetry — read with D-06):** A depth-limit refusal to spawn is the ONE case that returns `ToolResult(isError = true)` → `AgentResult.ToolError`. So: a child that *ran and failed* → `isError = false` (D-01); a spawn *blocked by the depth ceiling* → `isError = true`. Must be covered by two distinct tests.

**Cancellation & structured concurrency (HIER-02)**
- **D-04 (mechanism — locked, near-forced by D-19):** `AgentTool.callTool` runs the child inline via `childLoop.run(childTask)` as a suspend call inside the parent loop's existing `coroutineScope { async { provider.callTool() } }` block. It MUST NOT route through a child `AgentRunner` — that class owns its own `SupervisorJob` scope, which would detach the child and break cancellation propagation.
- **D-05:** On parent cancellation while a child is mid-run, the child records a best-effort audit row as `AgentResult.Cancelled` before the `CancellationException` propagates. Record via the AuditLog inside `withContext(NonCancellable)` then re-throw (T-03-03 preserved). Also benefits top-level agents.
- **D-06 test scope:** One cancellation-propagation test satisfies criterion #2. Deeper (grandchild) recursion propagation is NOT separately tested this phase.

**Depth ceiling (HIER-03)**
- **D-07:** Depth lives in a new `AgentTask.depth: Int = 0` field (threaded through `AgentLoop`). Default `0` preserves binary compatibility. `AgentTool` builds the child task with `depth = parentDepth + 1`. The new `parentRunId` field rides alongside.
- **D-08:** `maxDepth` (default 5) gets the full config surface: an `AgentLoop` constructor param `maxDepth: Int = 5`, a DSL method `maxDepth(n)` on `AgentBuilder`, and a `KoreProperties.hierarchy.maxDepth` property. Guard: when a spawn would make the child's depth exceed `maxDepth`, `AgentTool` returns the error ToolResult of D-03 without running the child (no child loop, no child audit row).

**parent_run_id audit (HIER-04)**
- **D-09:** Parent run id threaded down via a new `AgentTask.parentRunId: String? = null` field. `AgentTool` sets it to the parent's `agentId` (= parent `AgentTask.id`). `AuditLog.recordAgentRun(agentId, task, result)` signature stays unchanged — implementations read `task.parentRunId`. Root agents leave it `null`.
- **D-10:** New V2 Flyway migration adds `parent_run_id UUID NULL` to `agent_runs` plus `idx_agent_runs_parent_run_id`, with NO foreign key. (Landmine: the child inserts its row *before* the parent's row exists, so a self-referencing FK would reject the child insert.)
- **D-11:** Wiring: `AgentRunsTable` (new `parentRunId` column), `PostgresAuditLogAdapter.recordAgentRun` (write `task.parentRunId`), `InMemoryAuditLog` (store it for in-memory run-tree assertions).

**Child dependency inheritance (cross-cutting)**
- **D-12:** A child agent inherits the parent's `AuditLog`, `EventBus`, and `Tracer` by default. The `child { }` block customizes only the agent's own concerns (model, tools, budget, nested children). A child block MAY still override a port explicitly.

### Claude's Discretion
- The exact DSL mechanism by which `child { }` reuses `AgentBuilder` config but yields a child **`AgentLoop`** (not an `AgentRunner`) for inline execution — internal `buildLoop()` path on `AgentBuilder`, or a dedicated `ChildAgentBuilder`.
- The precise child tool **name** (default to child's `agentName`) and LLM-facing **description** source (a `description = "..."` property in the child block, with a sensible default).
- The exact `content` wording for the D-01 non-Success ToolResult and the D-03 depth-limit error message (human/LLM-legible; low-cardinality).
- How `AgentTool.listTools()` advertises the single child tool and how `findProvider` resolves it.
- The `NonCancellable` audit-record detail in D-05 (exact coroutine construct), provided the re-throw invariant holds.

### Deferred Ideas (OUT OF SCOPE)
- **HIER-05** — streaming child `AgentResult` back as `Flow<LLMChunk>`.
- Grandchild/deep-recursion cancellation test (depth ≥ 2 propagation).
- Per-child concurrency caps / child-pool semaphore.
- Self-referencing FK on `parent_run_id`.
- Splitting child-failure handling by result type (e.g. `BudgetExceeded` aborting the tree).
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| HIER-01 | Declare child agents via `child { }`; child runs as a tool call (spawn model); result feeds back into parent loop as `ToolResult` | `ToolProvider` is the single extension seam (verified). `AgentTool` implements it, joins `toolProviders`, resolved by existing `findProvider` with zero loop branching. Result mapping target is `ToolResult(toolCallId, content, isError)` and the "first errored ToolResult → AgentResult.ToolError" path at AgentLoop.kt:258–263. Child tool advertised via `listTools()` with a single-`input`-string `inputSchema` (D-02). |
| HIER-02 | Cancelling a parent cancels all running children — structured concurrency, cancellation-propagation test | Verified: the tool-dispatch block at AgentLoop.kt:222–250 is a `coroutineScope { async { provider.callTool() } }`. A child run as a plain suspend call inside it inherits the parent Job → automatic cancellation. Routing through `AgentRunner` (its own `SupervisorJob` scope, AgentRunner.kt:25) would detach it. |
| HIER-03 | Child spawning bounded by configurable `maxDepth` (default 5); exceeding yields `ToolError`, never unbounded recursion | `AgentTask.depth` (new, default 0) threads through. `AgentLoop` gains `maxDepth: Int = 5` constructor param; `AgentBuilder.maxDepth(n)`; `KoreProperties.hierarchy.maxDepth`. Guard lives in `AgentTool.callTool` → returns `ToolResult(isError = true)` (D-03) without running the child. |
| HIER-04 | Audit log records `parent_run_id` on child runs so run trees are traceable | `AgentTask.parentRunId` (new, default null) set by `AgentTool` to parent `agentId`. V2 Flyway migration adds nullable `parent_run_id UUID` + index, NO FK (D-10 landmine). `AgentRunsTable` column add + `PostgresAuditLogAdapter.recordAgentRun` INSERT + `InMemoryAuditLog` field. D-12 inheritance ensures the child row lands in the parent's persistent store. |
</phase_requirements>

## Summary

Phase 7 is the largest kore-core change of the milestone, but the codebase has been deliberately pre-shaped for it. Every integration seam the locked decisions rely on **already exists and was verified against the source on disk**: `ToolProvider`'s KDoc names "AgentTool (kore-core) — spawns child agents"; `AgentRunner` carries the D-19 comment ("child agents MUST be launched in this scope, not a new scope"); the tool-dispatch block is already a structured-concurrency-correct `coroutineScope { async { } }`; `AgentResult.Cancelled` already exists; and all the result-mapping logic is one cohesive block at `AgentLoop.runLoop:258–263`. **There are no architectural conflicts** between the locked decisions and the actual code — all twelve decisions are CONFIRMED below.

The phase adds **zero new external dependencies** (no Package Legitimacy Audit needed). `kotlinx.coroutines` 1.10.2 (already on the classpath, used throughout) supplies `NonCancellable` and `withContext` for D-05. The entire change is internal Kotlin + one SQL migration + one Exposed column + one Spring property.

The real risk is not "what library" — it is **getting four subtle correctness points exactly right**: (1) the child runs inline as a *loop* not a *runner* (D-04), (2) the D-01/D-03 `isError` asymmetry, (3) the D-05 `NonCancellable` audit that must not swallow the `CancellationException` re-throw (T-03-03), and (4) the D-10 no-FK insert-ordering landmine. This research pins each to a concrete line anchor and a test.

**Primary recommendation:** Add `AgentTask.depth`/`parentRunId` and `AgentLoop.maxDepth` as defaulted params (binary-compat criterion #5); build the child via a new internal `AgentBuilder.buildLoop()` that returns an `AgentLoop` and inherits parent ports; implement `AgentTool : ToolProvider` holding `childLoop + parentDepth + parentRunId + childName + description`; thread `parentRunId` from `AgentTask` into both audit-log implementations; ship a V2 migration with a no-FK nullable column and a code-comment guarding the landmine.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Child agent spawning / inline execution | kore-core (`AgentTool`, `AgentLoop`) | — | The agent loop and tool-provider port both live in kore-core; a child is just another `AgentLoop` invoked as a tool. Zero-dep constraint keeps this in core. |
| Cancellation propagation | kore-core (coroutine scope in `AgentLoop.runLoop`) | — | Structured concurrency is a property of the parent loop's `coroutineScope`; nothing outside core participates. |
| Depth ceiling enforcement | kore-core (`AgentTool` guard) | kore-spring (config value) | The *check* is in `AgentTool.callTool`; the *value* may be supplied by Spring config or DSL default. |
| `parent_run_id` persistence | kore-storage (V2 migration, `AgentRunsTable`, `PostgresAuditLogAdapter`) | kore-core (`InMemoryAuditLog`, `AgentTask` field) | DB schema + Exposed table are storage-tier; the threading field and in-memory store are core-tier. |
| `maxDepth` config surface | kore-spring (`KoreProperties.hierarchy`) | kore-core (DSL `maxDepth(n)`) | Spring auto-config owns the `kore.*` property namespace; the DSL owns the programmatic default. |
| Child port inheritance (audit/event/tracer) | kore-core (`AgentBuilder`/`buildLoop`) | — | The builder composes ports; inheritance is a builder-construction concern. |

## Standard Stack

**No new dependencies.** Phase 7 uses only what is already on the kore-runtime classpath. The relevant existing libraries, with versions verified from the codebase/CLAUDE.md:

### Core (already present)
| Library | Version | Purpose in Phase 7 | Provenance |
|---------|---------|--------------------|-----------|
| `kotlinx-coroutines-core` | 1.10.2 | `coroutineScope`, `async`, `NonCancellable`, `withContext`, `CancellationException` for inline child execution + D-05 best-effort audit | [VERIFIED: CLAUDE.md tech stack + AgentLoop.kt imports] |
| `kotlinx-coroutines-test` | 1.10.2 | `runTest`, `backgroundScope`, time control for the cancellation-propagation test | [VERIFIED: AgentLoopTest.kt uses `runTest`] |
| JetBrains Exposed (`exposed-core` / `exposed-r2dbc`) | 1.0.x | Add `parentRunId` column to `AgentRunsTable`; INSERT in `PostgresAuditLogAdapter` | [VERIFIED: AgentRunsTable.kt / PostgresAuditLogAdapter.kt on disk] |
| Flyway | 12.x | V2 migration adding `parent_run_id` | [VERIFIED: V1__init_schema.sql + StorageConfig.migrate()] |
| Kotest assertions | 6.1.11 | `shouldBe` / `shouldBeInstanceOf` in tests | [VERIFIED: existing tests] |
| MockK | 1.14+ | Mock `LLMBackend` / `AuditLog` in unit tests if needed | [CITED: CLAUDE.md tech stack] |
| Testcontainers | 1.20+ | Real-Postgres `parent_run_id` persistence assertion | [VERIFIED: PostgresAuditLogAdapterTest.kt / MigrationTest.kt] |
| Spring Boot | 4.0.x | `@ConfigurationProperties` for `kore.hierarchy.maxDepth` | [VERIFIED: KoreProperties.kt / KoreAutoConfiguration.kt] |

**Installation:** none. (`gsd-tools query package-legitimacy check` is N/A — zero packages added.)

## Package Legitimacy Audit

**Not applicable.** Phase 7 installs no external packages. All code reuses libraries already on the kore-runtime classpath (verified above). No `SLOP`/`SUS` risk.

## Architecture Patterns

### System Architecture Diagram

```
                         agent("parent") { child("researcher") { ... } }
                                         │
                                         ▼
                            AgentBuilder.build() → AgentRunner(loop=parentLoop)
                                         │  (child() registers AgentTool into toolProviders)
                                         ▼
   task ──▶ AgentRunner.run(task)  [SupervisorJob scope — PARENT runner only]
                                         │
                                         ▼
                              parentLoop.run(task)   ◀── depth=0, parentRunId=null
                                         │
                          ┌──────────────┴───────────────┐
                          ▼                              ▼
                 skill activation span        repeat(maxHistoryMessages):
                 (Phase 5 OBSV-03)              LLM call → chunks → toolCalls
                                                         │
                                                         ▼
                                       coroutineScope { ← STRUCTURED CONCURRENCY BOUNDARY
                                         toolCalls.map { async {
                                           provider = findProvider(call.name)
                                           result = provider.callTool(call)   ◀─┐
                                         } }.awaitAll()                          │
                                       }                                        │
                                                         │                      │
                            ┌────────────────────────────┘                      │
                            ▼                                                    │
                   AgentTool.callTool(call)  ◀── one of the providers            │
                            │                                                    │
              depth+1 > maxDepth? ──yes──▶ ToolResult(isError=TRUE) ─────────────┤  D-03
                            │ no                                                 │
                            ▼                                                    │
        childLoop.run(AgentTask(input=arg, depth=parentDepth+1,                  │
                               parentRunId=parentAgentId))  ◀── INLINE, no runner│
                            │   (inherits parent Job → cancel propagates)        │  D-04
                            ▼                                                    │
                   child AgentResult ──map──▶ ToolResult(isError=FALSE)──────────┘  D-01
                            │
                            ▼  (child records own audit row WITH parent_run_id)
                   parent AuditLog.recordAgentRun(childId, childTask, childResult)
                            │
                            ▼
              agent_runs INSERT: parent_run_id = parentAgentId   ◀── D-10 (no FK)
```

Reading the primary use case: a parent LLM emits a tool call whose name matches the child's tool → `findProvider` returns the `AgentTool` → `callTool` checks depth, runs the child loop inline (cancellation inherited), maps the child result back to a `ToolResult`, which re-enters the parent loop's history. The child's audit row carries `parent_run_id`.

### Component Responsibilities

| File | Change | Anchor |
|------|--------|--------|
| `AgentTask.kt` | Add `depth: Int = 0`, `parentRunId: String? = null` (defaulted) | whole data class (8 lines) |
| `AgentLoop.kt` | Add `maxDepth: Int = 5` ctor param; thread `task.depth` into the child task built by `AgentTool`; add D-05 `catch (CancellationException)` audit-then-rethrow | ctor 30–51; `run` 57–85; tool-dispatch 222–250; error-map 258–263 |
| `AgentTool.kt` (NEW, kore-core) | `ToolProvider` impl: `listTools()` → single child tool; `callTool()` → depth guard (D-03) + inline `childLoop.run` + result map (D-01) | new file |
| `AgentBuilder.kt` | Add `child { }` method + `maxDepth(n)`; add internal `buildLoop()` returning `AgentLoop`; inherit parent ports into child (D-12) | build() 111–132 |
| `ChildAgentBuilder` (NEW or reuse AgentBuilder) | child block receiver, `@KoreDsl`, holds `description` | new |
| `AuditLog.kt` | unchanged signature (D-09) | n/a |
| `InMemoryAuditLog.kt` | store `task.parentRunId` (replace no-op with a recorded list) | 17–21 |
| `AgentRunsTable.kt` | `val parentRunId = uuid("parent_run_id").nullable()` | after line 12 |
| `PostgresAuditLogAdapter.kt` | INSERT `stmt[parentRunId] = task.parentRunId?.let(UUID::fromString)` | recordAgentRun 53–69 |
| `V2__add_parent_run_id.sql` (NEW) | `ALTER TABLE agent_runs ADD COLUMN parent_run_id UUID NULL;` + index, NO FK + landmine comment | new |
| `KoreProperties.kt` | `val hierarchy: HierarchyProperties = HierarchyProperties()` + nested `data class HierarchyProperties(val maxDepth: Int = 5)` | after line 27 |
| `KoreAutoConfiguration.kt` / `KoreAgentFactory.kt` | pass `properties.hierarchy.maxDepth` into agent construction | KoreAgentFactory 36–45 |

### Pattern 1: Inline child loop inside the existing tool-dispatch scope (D-04)

**What:** `AgentTool.callTool` calls `childLoop.run(childTask)` directly — a plain suspend call. Because `callTool` is already invoked from inside `coroutineScope { ... async { provider.callTool(call) } ... }` (AgentLoop.kt:222–250), the child loop runs under the parent's `Job`. Cancelling the parent's `Deferred` cancels the `coroutineScope`, which cancels the `async`, which cancels the suspended `childLoop.run`.

**When to use:** Always — this is the locked mechanism.

**Example (the structure to mirror, verified from AgentLoop.kt:222–250):**
```kotlin
// Source: kore-core/AgentLoop.kt:222-250 (existing parent dispatch)
coroutineScope {
    toolCalls.map { call ->
        async {
            val provider = findProvider(call.name) ?: return@async ToolResult(...)
            val result = provider.callTool(call)  // ← AgentTool.callTool runs the child HERE
            result
        }
    }.awaitAll()
}
```
```kotlin
// AgentTool.callTool (NEW) — runs inline, inherits the parent coroutine context
override suspend fun callTool(call: ToolCall): ToolResult {
    val childDepth = parentDepth + 1
    if (childDepth > maxDepth) {
        return ToolResult(call.id, "child '$childName' refused: maxDepth $maxDepth exceeded", isError = true) // D-03
    }
    val input = extractInput(call.arguments) // single "input" string per D-02
    val childTask = AgentTask(id = UUID.randomUUID().toString(), input = input,
                              depth = childDepth, parentRunId = parentRunId)        // D-07/D-09
    val childResult = childLoop.run(childTask) // ← INLINE; NOT a child AgentRunner (D-04)
    return mapResult(call.id, childResult)     // D-01: non-Success → isError = false
}
```

**Why routing through `AgentRunner` would break propagation:** `AgentRunner` creates `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (AgentRunner.kt:25) and runs the loop via `scope.async { }`. That scope's `Job` is a **new root** — it is not a child of the parent's `Job`, so cancelling the parent never reaches it. The `SupervisorJob` additionally isolates failures. This is exactly the scope `AgentTool` must NOT use, and is precisely what the D-19 comment in `AgentRunner.kt` warns against.

### Pattern 2: D-05 best-effort Cancelled audit without violating the re-throw invariant

**What:** Currently `AgentLoop.run` (AgentLoop.kt:77–78) re-throws `CancellationException` immediately with no audit row. D-05 inserts a `withContext(NonCancellable)` audit write before the re-throw, so a cancelled branch leaves a `Cancelled` row (HIER-04 needs cancelled branches in the run tree). This also fixes top-level agents.

**Example:**
```kotlin
// AgentLoop.run — D-05 modification of the existing catch at line 77
} catch (e: CancellationException) {
    withContext(NonCancellable) {                       // audit survives cancellation
        auditLog.recordAgentRun(agentId, task, AgentResult.Cancelled(reason = e.message ?: "cancelled"))
    }
    throw e                                              // T-03-03 invariant preserved — ALWAYS re-throw
}
```
**Critical:** the `throw e` MUST remain. `NonCancellable` only protects the audit write; it does not suppress the exception. Verify the existing `.also { auditLog.recordAgentRun(...) }` (AgentLoop.kt:81–84) does NOT also fire on the cancel path (it won't — `.also` runs on the `try` block's *return value*, and the cancel path throws rather than returns), so there is no double-record.

### Pattern 3: `buildLoop()` — child reuses AgentBuilder config but yields an `AgentLoop` (Discretion)

**What:** `AgentBuilder.build()` returns an `AgentRunner` (AgentBuilder.kt:111–132). A child needs the `AgentLoop` *inside* that runner, not the runner. Cleanest path: extract the loop construction into an `internal fun buildLoop(): AgentLoop` and have `build()` call it then wrap in `AgentRunner`.

**Recommendation: internal `buildLoop()` on `AgentBuilder` + a `child { }` method.** Rationale: minimal new surface, reuses every existing builder field (model/tools/budget/config/skillRegistry), and lets the child block be just another `AgentBuilder` whose audit/event/tracer ports are overwritten with the parent's after the user block runs (mirrors how `KoreAgentFactory` pre-wires ports, KoreAgentFactory.kt:40–45).

```kotlin
// AgentBuilder.kt — refactor
internal fun buildLoop(): AgentLoop {
    val backend = requireNotNull(model) { "Agent '$agentName': model must be configured." }
    return AgentLoop(
        llmBackend = ResilientLLMBackend(backend, retryPolicy),
        toolProviders = toolProviders,
        budgetEnforcer = budgetEnforcer,
        eventBus = eventBus, auditLog = auditLog,
        skillRegistry = skillRegistryCell[0],
        maxDepth = maxDepth,                 // NEW
        tracer = tracer,                     // NEW field on builder (default null) for D-12 inheritance
        config = llmConfig,
    )
}
fun build(): AgentRunner = AgentRunner(loop = buildLoop())

// child block: reuse AgentBuilder, then force-inherit parent ports (D-12)
@KoreDsl
fun child(name: String, description: String = "Delegates a subtask to the '$name' sub-agent",
          block: AgentBuilder.() -> Unit) {
    val childBuilder = AgentBuilder(name).apply(block)
    childBuilder.eventBus(this.eventBus)        // D-12 inheritance — overrides child's throwaway defaults
    childBuilder.auditLog(this.auditLog)
    childBuilder.inheritTracer(this.tracer)
    val childLoop = childBuilder.buildLoop()
    toolProviders.add(AgentTool(childName = name, description = description,
                                childLoop = childLoop, maxDepth = maxDepth /* parentDepth supplied at call time from task.depth */))
}
```

**Note on parentDepth/parentRunId source:** these are *runtime* values that depend on the parent `AgentTask` actually being run, not build-time. `AgentTool` therefore cannot capture them at build time. **The planner must thread `task.depth` and `task.id` into the dispatch at call time.** Cleanest: `AgentLoop.runLoop` passes its current `task.depth` and `agentId` to `AgentTool` — e.g. give `AgentTool` a `suspend fun callTool(call, parentDepth, parentRunId)` is not possible (the `ToolProvider` signature is fixed). Two viable options (Discretion):
1. **AgentLoop sets context on AgentTool instances before dispatch** — loop iterates its `toolProviders`, and for any `AgentTool` calls `it.bind(parentDepth = task.depth, parentRunId = agentId)` before the dispatch block. Requires a `val`-cell holder inside `AgentTool` (the no-var pattern already used in `AgentBuilder.skillRegistryCell` and `AgentLoop.activatedHolder`).
2. **Pass depth via a coroutine context element** — heavier; not recommended for this phase.
Recommendation: **option 1** (val-cell bind), consistent with the established no-var holder idiom. Flag for the planner as the one genuinely new design decision.

### Pattern 4: D-01/D-03 result mapping (the asymmetry)

```kotlin
// AgentTool.mapResult — D-01: a child that RAN to any non-Success is informational (isError = false)
private fun mapResult(toolCallId: String, result: AgentResult): ToolResult = when (result) {
    is AgentResult.Success        -> ToolResult(toolCallId, result.output, isError = false)
    is AgentResult.BudgetExceeded -> ToolResult(toolCallId, "child '$childName' budget exceeded", isError = false)
    is AgentResult.ToolError      -> ToolResult(toolCallId, "child '$childName' tool error: ${result.toolName}", isError = false)
    is AgentResult.LLMError       -> ToolResult(toolCallId, "child '$childName' LLM error: ${result.backend}", isError = false)
    is AgentResult.Cancelled      -> ToolResult(toolCallId, "child '$childName' cancelled", isError = false)
}
// D-03 (depth limit) is the ONLY isError = true path — handled BEFORE the child runs (Pattern 1).
```
Put a code comment on `mapResult` stating "D-01: a child that ran and failed is `isError=false`; only a depth-limit refusal (handled above) is `isError=true` (D-03)."

### Pattern 5: V2 Flyway migration (D-10, no FK)

```sql
-- V2__add_parent_run_id.sql
-- HIER-04: child agent runs record their parent's run id for run-tree reconstruction.
--
-- LANDMINE (D-10) — DO NOT ADD A FOREIGN KEY HERE.
-- A child agent completes DURING the parent loop and therefore INSERTs its
-- agent_runs row BEFORE the parent's row exists (the parent records its own row
-- only after AgentLoop.run returns). A self-referencing FK would reject the
-- child insert. A plain nullable column is fully queryable for run trees.
ALTER TABLE agent_runs ADD COLUMN parent_run_id UUID NULL;
CREATE INDEX IF NOT EXISTS idx_agent_runs_parent_run_id ON agent_runs(parent_run_id);
```
Verified against `V1__init_schema.sql`: `agent_runs.id` is `UUID PRIMARY KEY`, and the insert-ordering claim is confirmed by `PostgresAuditLogAdapter.recordAgentRun` (the parent's `.also { auditLog.recordAgentRun(...) }` at AgentLoop.kt:81 runs only after `runLoop` returns, while the child's `recordAgentRun` runs *inside* the parent's `runLoop`).

### Anti-Patterns to Avoid
- **Running the child via a `child.run()` on a fresh `AgentRunner`** — detaches cancellation (breaks HIER-02 / criterion #2). Use the inline loop (D-04).
- **Returning `isError = true` for a child that ran and failed** — aborts the whole parent run (AgentLoop.kt:258–263 converts the first errored ToolResult into `AgentResult.ToolError`). Only the depth-limit refusal returns `isError = true` (D-03).
- **Adding a self-FK on `parent_run_id`** — rejects the child insert (D-10).
- **Swallowing `CancellationException` in the D-05 audit branch** — must re-throw (T-03-03). `NonCancellable` wraps only the audit write.
- **Forgetting to annotate the `child { }` receiver with `@KoreDsl`** — nested child blocks could leak the outer builder receiver (CONTEXT Pitfall 10; the marker is `KoreDsl.kt`).
- **Letting the child keep `AgentBuilder`'s throwaway `InMemoryAuditLog`/`InProcessEventBus`** — the child's `parent_run_id` row would land in a discarded in-memory log and never reach Postgres, silently breaking HIER-04 (D-12).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Cancel-children-on-parent-cancel | A manual job registry / child cancellation list | The existing `coroutineScope { async { } }` at AgentLoop.kt:222 | Structured concurrency already guarantees it; a manual registry races and leaks. |
| Best-effort work during cancellation | `try/finally` with a fresh `GlobalScope.launch` | `withContext(NonCancellable)` | `NonCancellable` is the idiomatic, leak-free way to finish a critical write while a coroutine is being cancelled. |
| Depth-tracking across the tree | A thread-local / global counter | The `AgentTask.depth` field threaded through the loop | Per-run UUID isolation (like budget keying by `AgentTask.id`) — globals corrupt under concurrency. |
| Run-tree reconstruction integrity | A self-referencing FK | A plain nullable indexed column | The insert-ordering landmine (D-10) makes the FK reject valid child inserts. |
| Reusing the agent config for a child | A parallel `ChildConfig` type | An internal `buildLoop()` on the existing `AgentBuilder` | Avoids divergence; the builder already composes every needed port. |

**Key insight:** the codebase has already paid the architectural cost (the `coroutineScope` dispatch, `AgentResult.Cancelled`, the `ToolProvider` seam, the per-run UUID convention). Phase 7 is *threading and wiring*, not new machinery — hand-rolling any of the above would re-introduce problems the existing design already solved.

## Runtime State Inventory

This phase adds a column and threads new fields; it does NOT rename anything. The relevant "state beyond the repo" check:

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `agent_runs` table in any deployed Postgres lacks `parent_run_id`. Existing rows are unaffected (column is nullable). | V2 Flyway migration (additive). No data backfill — historical root runs correctly have `parent_run_id = NULL`. |
| Live service config | None — `maxDepth` is a new property with a default; no existing deployment sets it. | `KoreProperties.hierarchy.maxDepth` defaults to 5; absent config = default behavior. |
| OS-registered state | None — verified: no scheduled tasks, daemons, or pm2/systemd registrations reference agent hierarchy. | None. |
| Secrets/env vars | None — verified: no new secret/env var introduced (depth/parentRunId are in-process values). | None. |
| Build artifacts | The Exposed `AgentRunsTable` object and the published `kore-storage`/`kore-core` jars change shape (additively). Consumers recompile transparently because all new params/columns are defaulted/nullable (criterion #5). | Standard Gradle rebuild + republish at milestone close. No consumer code edits. |

**Migration ordering note:** Flyway picks up `V2__*.sql` automatically via the existing `StorageConfig.migrate()` → `.locations("classpath:db/migration")` pipeline (verified). `MigrationTest` already proves idempotency; a new V2 assertion slots into the same Testcontainers class.

## Common Pitfalls

### Pitfall 1: parentDepth/parentRunId are runtime values, not build-time
**What goes wrong:** `child { }` runs at *build* time, but the parent's `task.depth` and `task.id` only exist when a task is actually run. Capturing them in `AgentTool`'s constructor yields stale/zero values.
**Why it happens:** the DSL composes the tool graph once; tasks run many times against the same graph.
**How to avoid:** bind depth/parentRunId into the `AgentTool` instance at dispatch time from inside `AgentLoop.runLoop` (val-cell holder, Pattern 3 option 1), or have the loop pass them. This is the one genuinely new design point — the planner must specify it.
**Warning signs:** a child run with `depth = 0` even though it was spawned by a parent; `parent_run_id` always NULL.

### Pitfall 2: `.also { recordAgentRun }` double-firing on cancel
**What goes wrong:** if D-05 records a `Cancelled` row in the `catch` AND the existing `.also` block (AgentLoop.kt:81) also fires, you get two rows.
**Why it happens:** misreading Kotlin's `.also` as a `finally`.
**How to avoid:** `.also` runs on the *return value* of the `try` expression; the cancel path `throw`s and never produces a return value, so `.also` is skipped. Confirmed by reading AgentLoop.kt:75–84. No guard needed — but add a test asserting exactly one `Cancelled` row.
**Warning signs:** duplicate `agent_runs` rows for a single cancelled run.

### Pitfall 3: child tool name collision with a real tool
**What goes wrong:** `findProvider` (AgentLoop.kt:286–289) returns the *first* provider whose `listTools()` contains the name. If a child's tool name equals an MCP tool name, dispatch is ambiguous.
**Why it happens:** the child tool name defaults to the child's `agentName`.
**How to avoid:** document that child names must be unique within an agent's tool surface; optionally prefix (Discretion — keep low-cardinality content). Not a blocker for the phase, but note for the planner's DSL design.

### Pitfall 4: UUID parsing of `parentRunId` in Postgres adapter
**What goes wrong:** `parentRunId` is a `String?` in `AgentTask` but `parent_run_id` is `UUID` in Postgres. A blind `UUID.fromString(null)` NPEs.
**How to avoid:** `stmt[parentRunId] = task.parentRunId?.let(UUID::fromString)` (Exposed nullable column accepts null). The `agentId` is already a UUID string by convention (matches `agent_runs.id`), so the parent's id parses cleanly.
**Warning signs:** `IllegalArgumentException: Invalid UUID string` or NPE on root-agent inserts.

### Pitfall 5: dispatcher inheritance under `runTest`
**What goes wrong:** the cancellation test must observe the child being cancelled *promptly*. If the child suspends on a real delay rather than a test-controlled suspension point, the virtual-time scheduler won't advance as expected.
**How to avoid:** make the child suspend on a never-completing/awaitable primitive (a `CompletableDeferred` latch or a never-emitting `LLMBackend` mock), launch the parent on `backgroundScope`, cancel, then assert the latch observed cancellation — the established `backgroundScope + yield/runCurrent` idiom (STATE.md Phase 02 decision for infinite-flow tests).
**Warning signs:** test hangs (`advanceUntilIdle` on a never-finishing loop) — use `backgroundScope`, not `advanceUntilIdle`.

## Code Examples

### Adding the defaulted fields (binary-compat criterion #5)
```kotlin
// AgentTask.kt — all additions defaulted; published API compiles unchanged
data class AgentTask(
    val id: String,
    val input: String,
    val metadata: Map<String, String> = emptyMap(),
    val depth: Int = 0,                 // D-07
    val parentRunId: String? = null,    // D-09
)
```
```kotlin
// AgentLoop.kt — maxDepth defaulted (criterion #5); slots between tracer and config
class AgentLoop(
    /* ...existing params... */
    private val tracer: Tracer? = null,
    private val maxDepth: Int = 5,      // D-08
    private val config: LLMConfig,
)
```

### InMemoryAuditLog storing parentRunId for in-memory run-tree assertions (D-11)
```kotlin
// InMemoryAuditLog.kt — replace the no-op recordAgentRun with a recorded list
class InMemoryAuditLog : AuditLog {
    data class RunRecord(val agentId: String, val parentRunId: String?, val resultType: String)
    private val runs = java.util.concurrent.CopyOnWriteArrayList<RunRecord>()
    val recordedRuns: List<RunRecord> get() = runs.toList()
    override suspend fun recordAgentRun(agentId: String, task: AgentTask, result: AgentResult) {
        runs.add(RunRecord(agentId, task.parentRunId, result::class.simpleName ?: "unknown"))
    }
    /* recordLLMCall / recordToolCall / query* stay no-op stubs */
}
```

### Spring property surface (D-08, mirrors budget.defaultMaxTokens)
```kotlin
// KoreProperties.kt — add to the constructor + a nested data class
@ConfigurationProperties("kore")
data class KoreProperties(
    /* ...existing... */
    val budget: BudgetProperties = BudgetProperties(),
    val eventBus: EventBusProperties = EventBusProperties(),
    val hierarchy: HierarchyProperties = HierarchyProperties(),   // NEW
) {
    /** Hierarchical-agent spawn ceiling. `kore.hierarchy.max-depth`, default 5 (HIER-03). */
    data class HierarchyProperties(val maxDepth: Int = 5)
}
```
Then `KoreAgentFactory` / the relevant `@Bean` passes `properties.hierarchy.maxDepth` into agent construction (KoreAgentFactory.kt:36–45 is the natural injection point; it already pre-wires ports).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Single-agent loop only; `ToolProvider` KDoc *mentions* `AgentTool` but it doesn't exist | `AgentTool : ToolProvider` materializes the spawn model | Phase 7 (now) | The KDoc placeholder (ToolProvider.kt:10) becomes real code. |
| Cancelled runs leave no audit row | Cancelled runs leave a `Cancelled` row (D-05) | Phase 7 | Run trees show cancelled branches; also benefits top-level agents. |
| `agent_runs` has no lineage column | `parent_run_id` enables run-tree queries | Phase 7 (V2 migration) | DB-level traceability of agent hierarchies. |

**Deprecated/outdated:** none introduced. Spawn model (not handoff) was already locked at roadmap time (STATE.md) and re-affirmed in REQUIREMENTS.md Out-of-Scope ("Handoff model ... breaks the ReAct loop and severs the coroutine scope chain").

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `Tracer` is not currently a field on `AgentBuilder` (it is only on `AgentLoop`), so D-12 tracer inheritance needs a new builder field/method. | Pattern 3 | LOW — verified `AgentBuilder` has no tracer field (read AgentBuilder.kt in full); a `tracer`/`inheritTracer` affordance must be added. If a tracer field is added elsewhere first, harmonize. |
| A2 | Binding parentDepth/parentRunId into `AgentTool` at dispatch time (val-cell) is the cleanest option vs. a coroutine-context element. | Pattern 3, Pitfall 1 | MEDIUM — this is the one new design decision the locked decisions did NOT fully specify (it's under Discretion). The planner/discuss-phase should confirm the binding mechanism. |
| A3 | `.also { recordAgentRun }` does not fire on the cancel path, so no double-record guard is needed. | Pitfall 2 | LOW — verified by reading AgentLoop.kt:75–84 (Kotlin `.also` semantics); a test asserting exactly one row removes residual risk. |

**Note:** All 12 locked decisions are CONFIRMED (table below); the only genuinely open design point is A2 (binding mechanism), which lives in Claude's Discretion per CONTEXT.

## Locked-Decision Validation (CONFIRMED / CONFLICT)

| Decision | Verdict | Evidence |
|----------|---------|----------|
| D-01 (non-Success child → isError=false) | **CONFIRMED** | AgentLoop.kt:258–263 converts the *first* errored ToolResult into `AgentResult.ToolError` and returns — confirming why a failing child must be `isError=false` to avoid aborting the parent. |
| D-02 (single `input` string schema) | **CONFIRMED** | `ToolDefinition(name, description, inputSchema)` (ToolDefinition.kt) + `ToolCall.arguments` is a JSON string (ToolCall.kt); maps straight to `AgentTask.input`. |
| D-03 (depth-limit → isError=true) | **CONFIRMED** | Same error-map path (AgentLoop.kt:258–263); guard runs in `AgentTool` before the child loop. |
| D-04 (inline loop, not runner) | **CONFIRMED** | Tool-dispatch is `coroutineScope { async { provider.callTool() } }` (AgentLoop.kt:222–250); `AgentRunner` uses a detached `SupervisorJob` scope (AgentRunner.kt:25). D-19 comment present (AgentRunner.kt:16–17). |
| D-05 (Cancelled audit, NonCancellable, re-throw) | **CONFIRMED** | `catch (CancellationException) { throw e }` exists (AgentLoop.kt:77–78); `AgentResult.Cancelled` exists (AgentResult.kt:62–65); `NonCancellable` available via kotlinx-coroutines 1.10.2. |
| D-06 (single-level cancel test) | **CONFIRMED** | No existing cancellation test (verified — only `AgentResultTest` references the word); new test fits the `runTest`/`backgroundScope` idiom. |
| D-07 (`AgentTask.depth=0`) | **CONFIRMED** | `AgentTask` is a 3-field data class (AgentTask.kt); defaulted add preserves API. |
| D-08 (full maxDepth surface) | **CONFIRMED** | `KoreProperties.budget.defaultMaxTokens` (KoreProperties.kt:112–115) + `@ConditionalOnMissingBean` (KoreAutoConfiguration.kt:63–65) are the exact template. |
| D-09 (`parentRunId`, signature unchanged) | **CONFIRMED** | `AuditLog.recordAgentRun(agentId, task, result)` (AuditLog.kt:35–39) takes `task` — implementations read `task.parentRunId`. `agentId == AgentTask.id` convention verified across the loop. |
| D-10 (V2 migration, no FK) | **CONFIRMED** | `agent_runs.id UUID PRIMARY KEY` (V1__init_schema.sql:5); insert-ordering landmine confirmed — parent `.also{recordAgentRun}` at AgentLoop.kt:81 runs after the loop, child records inside the loop. |
| D-11 (table/adapter/in-memory wiring) | **CONFIRMED** | `AgentRunsTable` (Exposed UUIDTable, AgentRunsTable.kt); `PostgresAuditLogAdapter.recordAgentRun` insert block (lines 53–69); `InMemoryAuditLog` is a no-op stub today (InMemoryAuditLog.kt:17–21). |
| D-12 (child inherits ports) | **CONFIRMED** | `AgentBuilder` defaults to `InMemoryAuditLog()`/`InProcessEventBus()` (AgentBuilder.kt:43–45); `KoreAgentFactory` already demonstrates the "pre-wire parent ports into the builder" pattern (KoreAgentFactory.kt:40–45). |

**No conflicts found.** Every locked decision maps to existing code shapes exactly as CONTEXT.md describes.

## Open Questions

1. **Binding mechanism for parentDepth/parentRunId at dispatch time (A2)**
   - What we know: these are runtime values; `ToolProvider.callTool(call)` signature is fixed; the no-var val-cell idiom is established.
   - What's unclear: val-cell bind on the `AgentTool` instance vs. a coroutine-context element.
   - Recommendation: val-cell bind from `AgentLoop.runLoop` before the dispatch block (consistent with `AgentBuilder.skillRegistryCell` / `AgentLoop.activatedHolder`). Planner/discuss to confirm.

2. **Child tool description source (Discretion)**
   - What we know: the parent LLM needs a description to know when to call the child; `ToolDefinition` requires one.
   - Recommendation: a `description = "..."` parameter on the `child(name, description) { }` block with a sensible default (e.g. `"Delegates a subtask to the '$name' sub-agent"`).

3. **Tracer field on `AgentBuilder` (A1)**
   - What we know: `AgentLoop` has a nullable `tracer`; `AgentBuilder` does not expose one today.
   - Recommendation: add an internal tracer field + `inheritTracer()` so D-12 can pass the parent's tracer to `buildLoop()`. Keeps null default for non-observability paths.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker (Testcontainers) | `parent_run_id` persistence integration test | ✓ (CI: arc-runner-unityinflow with `docker info` pre-flight, Phase 5) | — | Local: unit-level `InMemoryAuditLog` run-tree assertion (always available) |
| PostgreSQL | Same integration test | ✓ via Testcontainers `postgres:16-alpine` | 16 | — |
| kotlinx-coroutines / Exposed / Flyway / Spring Boot | All code changes | ✓ already on classpath | see Standard Stack | — |

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** the Testcontainers persistence test requires Docker; the in-memory run-tree test (`InMemoryAuditLog.recordedRuns`) covers HIER-04's logic without Docker for fast local feedback.

## Validation Architecture

`workflow.nyquist_validation: true` (verified in `.planning/config.json`).

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (JUnit Platform) runner + Kotest assertions (`shouldBe`, `shouldBeInstanceOf`) + MockK; `kotlinx-coroutines-test` `runTest`/`backgroundScope` |
| Config file | Gradle Kotlin DSL per-module `build.gradle.kts`; integration tests gated by `@Tag("integration")` + `:kore-storage:integrationTest` task (Phase 5) |
| Quick run command | `./gradlew :kore-core:test` (unit; no Docker) |
| Full suite command | `./gradlew test` then `./gradlew :kore-storage:integrationTest` (Docker) |

### Phase Requirements → Test Map
| Req / Criterion | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| HIER-01 / crit #1 | child runs as a tool call, result feeds back into parent loop as `ToolResult` (parent reaches Success after consuming child output) | unit | `./gradlew :kore-core:test --tests "*AgentToolTest*"` | ❌ Wave 0 (`AgentToolTest.kt`) |
| HIER-01 / D-01 | a child that ran and FAILED → `ToolResult(isError=false)`; parent does NOT abort | unit | `./gradlew :kore-core:test --tests "*AgentToolTest*"` | ❌ Wave 0 |
| HIER-03 / D-03 / crit #3 | spawn at depth > maxDepth → `ToolResult(isError=true)` → `AgentResult.ToolError`; child loop NOT run | unit | `./gradlew :kore-core:test --tests "*AgentToolTest*"` | ❌ Wave 0 |
| HIER-02 / crit #2 / D-06 | cancel parent mid-child → child observes cancellation promptly (latch/suspending child via `backgroundScope`) | unit | `./gradlew :kore-core:test --tests "*AgentLoopCancellationTest*"` | ❌ Wave 0 (`AgentLoopCancellationTest.kt`) |
| HIER-02 / D-05 | cancelled run leaves exactly one `Cancelled` audit row (no double-record) | unit | `./gradlew :kore-core:test --tests "*AgentLoopCancellationTest*"` | ❌ Wave 0 |
| HIER-04 / crit #4 | child `agent_runs` row's `parent_run_id` == parent `id` against real Postgres | integration | `./gradlew :kore-storage:integrationTest --tests "*PostgresAuditLogAdapterTest*"` | ✅ extend existing class |
| HIER-04 (fast) | `InMemoryAuditLog.recordedRuns` shows child record carrying parent's id | unit | `./gradlew :kore-core:test` | ❌ Wave 0 |
| crit #5 (binary compat) | existing single-agent definitions + published `AgentTask("id","input")` / `AgentLoop(...)` compile unchanged | unit (compile) | `./gradlew :kore-core:test` (existing `AgentLoopTest` compiles as-is) | ✅ existing |
| V2 migration | `parent_run_id` column exists, type UUID, nullable, indexed | integration | `./gradlew :kore-storage:integrationTest --tests "*MigrationTest*"` | ✅ extend existing class |

### Sampling Rate
- **Per task commit:** `./gradlew :kore-core:test` (sub-30s, Docker-free).
- **Per wave merge:** `./gradlew test` (all unit) + `ktlintFormat`/`lintKotlin`.
- **Phase gate:** `./gradlew test` green AND `./gradlew :kore-storage:integrationTest` green (the `parent_run_id` Testcontainers assertion) before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `kore-core/src/test/.../AgentToolTest.kt` — covers HIER-01 (D-01 ran-and-failed → isError=false), HIER-03 (D-03 depth-limit → isError=true), the spawn→ToolResult feedback, and the InMemory run-tree assertion (HIER-04 fast path).
- [ ] `kore-core/src/test/.../AgentLoopCancellationTest.kt` — covers HIER-02/crit #2 (cancel propagation via suspending child + `backgroundScope`) and D-05 (single `Cancelled` audit row).
- [ ] Extend `kore-storage/src/test/.../PostgresAuditLogAdapterTest.kt` — add `parent_run_id` persistence assertion (HIER-04/crit #4).
- [ ] Extend `kore-storage/src/test/.../MigrationTest.kt` — assert V2 column present, UUID, nullable, indexed.
- [ ] Framework install: none — JUnit 5 + Kotest + Testcontainers already wired.

## Security Domain

`security_enforcement` is not set to `false`; treat as enabled. Phase 7 adds no auth/session/crypto surface, but two ASVS-relevant points apply.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | — |
| V3 Session Management | no | — |
| V4 Access Control | no | — |
| V5 Input Validation | yes | The child tool's `input` arrives as LLM-supplied JSON (`ToolCall.arguments`). Parse defensively (existing pattern: `arguments` is a JSON string passed as-is). The depth ceiling (`maxDepth`) is the primary DoS control — it bounds recursion (HIER-03). |
| V6 Cryptography | no | — |

### Known Threat Patterns for kore-runtime hierarchy
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Unbounded recursive spawning (resource exhaustion) | Denial of Service | `maxDepth` guard in `AgentTool.callTool` (D-03/D-08) — the locked, tested control. |
| Child cost compounding (a deep tree burning tokens) | Denial of Service | Per-agent budget isolation keyed by `AgentTask.id` (Phase 6, BUDG-07) already bounds each child; child budget *slicing* (BUDG-08) is explicitly deferred. The depth ceiling caps tree size. |
| PII in child `task.input` reaching the audit log | Information Disclosure | Same as the existing T-02-05 note in `PostgresAuditLogAdapter` — task content masking is a deferred kore-spring property, not introduced here. No new exposure (the child's `input` is the LLM's tool argument). |

## Sources

### Primary (HIGH confidence) — verified against source on disk
- `kore-core/AgentLoop.kt` — tool-dispatch scope (222–250), error-map (258–263), cancel catch (77–78), `.also` audit (81–84), findProvider (286–289)
- `kore-core/AgentRunner.kt` — D-19 comment (16–17), detached SupervisorJob scope (25)
- `kore-core/dsl/AgentBuilder.kt` — build() returns AgentRunner (111–132), port defaults (43–45), val-cell idiom (52)
- `kore-core/dsl/Dsl.kt`, `dsl/KoreDsl.kt` — entry point + `@DslMarker`
- `kore-core/AgentTask.kt`, `AgentResult.kt` (Cancelled 62–65), `ToolResult.kt`, `ToolDefinition.kt`, `ToolCall.kt`, `port/ToolProvider.kt` (AgentTool KDoc 10), `port/AuditLog.kt` (35–39), `internal/InMemoryAuditLog.kt` (no-op 17–21), `LLMConfig.kt`
- `kore-storage/db/migration/V1__init_schema.sql`, `tables/AgentRunsTable.kt`, `PostgresAuditLogAdapter.kt` (recordAgentRun 53–69), `StorageConfig.kt` (migrate 52–62)
- `kore-storage` tests: `PostgresAuditLogAdapterTest.kt`, `MigrationTest.kt` (Testcontainers patterns)
- `kore-spring/KoreProperties.kt` (budget template 112–115), `KoreAutoConfiguration.kt` (ConditionalOnMissingBean 63–65, budget bean), `KoreAgentFactory.kt` (port pre-wire 40–45)
- `kore-test/MockToolProvider.kt`; `kore-core/AgentLoopTest.kt` (scriptedBackend + runTest idiom)
- `.planning/config.json` (nyquist_validation true), `.planning/STATE.md` (decisions log)

### Secondary / Tertiary
- None required — phase adds zero external packages; all claims sourced from the codebase or locked CONTEXT.md.

## Project Constraints (from CLAUDE.md)

| Directive | How Phase 7 complies |
|-----------|----------------------|
| No `var` — always `val` | Use val-cell holders for dispatch-time binding (established idiom). |
| No `!!` without a comment | Use `?.let(UUID::fromString)` for nullable parentRunId; safe `?:` fallbacks. |
| Coroutines only (never `Thread.sleep`/raw threads) | Inline `childLoop.run` + `withContext(NonCancellable)`; structured concurrency throughout. |
| Sealed classes for results | Map child `AgentResult` (sealed) → `ToolResult`; exhaustive `when` (no `else`). |
| `Result<T>`/sealed instead of exceptions for expected failures | Child failures become `ToolResult`/`AgentResult` variants; only `CancellationException` escapes (re-thrown). |
| JUnit 5 + Kotest assertions + MockK | All new tests follow `runTest` + `shouldBe`/`shouldBeInstanceOf`. |
| Gradle Kotlin DSL; ktlint before commit | No build-system change; run `formatKotlin`/`lintKotlin`. |
| Group `io.github.unityinflow` | New `AgentTool` in `io.github.unityinflow.kore.core`. |
| Test coverage >80% on core logic | Two new unit test files + extended integration tests cover all four HIER reqs + 5 criteria. |
| Binary compatibility (criterion #5) | Every new `AgentLoop`/`AgentTask` param defaulted; `parent_run_id` column nullable. |

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — zero new deps; every library verified on the live classpath.
- Architecture: HIGH — all 12 locked decisions CONFIRMED against exact line anchors; no conflicts.
- Pitfalls: HIGH — each pitfall traced to a specific line/behavior in the existing code.
- One MEDIUM item: the dispatch-time binding mechanism for parentDepth/parentRunId (A2) is the single genuinely-open design choice, and it falls under Claude's Discretion.

**Research date:** 2026-06-21
**Valid until:** 2026-07-21 (stable — internal codebase, no fast-moving external deps)
