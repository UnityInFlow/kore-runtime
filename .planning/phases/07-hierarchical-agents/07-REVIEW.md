---
phase: 07-hierarchical-agents
reviewed: 2026-06-22T00:00:00Z
depth: standard
files_reviewed: 19
files_reviewed_list:
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentTask.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/dsl/AgentBuilder.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/internal/InMemoryAuditLog.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/AgentTool.kt
  - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/ChildDispatchBinder.kt
  - kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentLoopCancellationTest.kt
  - kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentToolTest.kt
  - kore-spring/build.gradle.kts
  - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAgentFactory.kt
  - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAutoConfiguration.kt
  - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreProperties.kt
  - kore-spring/src/test/kotlin/io/github/unityinflow/kore/spring/KoreHierarchyPropertiesTest.kt
  - kore-storage/src/main/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapter.kt
  - kore-storage/src/main/kotlin/io/github/unityinflow/kore/storage/tables/AgentRunsTable.kt
  - kore-storage/src/main/resources/db/migration/V2__add_parent_run_id.sql
  - kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/MigrationTest.kt
  - kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapterTest.kt
findings:
  critical: 3
  warning: 6
  info: 3
  total: 12
status: issues_found
---

# Phase 07: Code Review Report

**Reviewed:** 2026-06-22
**Depth:** standard
**Files Reviewed:** 19
**Status:** issues_found

## Summary

This phase adds hierarchical agents (child-as-tool spawn model), depth-ceiling
enforcement, parent-run-id lineage in the audit log, and Spring property
threading. The architecture is sound and the cancellation/audit handling is
mostly careful, but there are three correctness defects that can cause wrong
lineage, double-counted audit rows, and crashes in the persistent adapter:

1. **`AgentTool` dispatch-time binding is not concurrency-safe** — shared mutable
   cells are written by `bind()` and read later by `callTool()` across coroutine
   suspension and across concurrent runs, so children can be assigned the wrong
   parent depth / parentRunId (corrupting the depth ceiling and the run tree).
2. **`extractInput` is a hand-rolled JSON parser** that silently mangles inputs
   containing escaped quotes, nested objects, or whitespace — and the whole
   project mandates `kotlinx.serialization` precisely to avoid this.
3. **`PostgresAuditLogAdapter.recordAgentRun` records two rows per child run and
   throws on non-UUID agent ids**, which the persistent path cannot tolerate.

Several quality issues around the `var` ban, audit double-write semantics, and a
missing budget check on the child path are also flagged.

## Narrative Findings (AI reviewer)

## Critical Issues

### CR-01: `AgentTool` dispatch-time binding races across concurrent runs and suspension

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/AgentTool.kt:44-89`
**Also:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:240-274`

**Issue:** `AgentTool` stores per-dispatch lineage in shared instance fields
(`parentDepthCell`, `parentRunIdCell`). `AgentLoop` calls `bind()` on every
`ChildDispatchBinder` *before* the `coroutineScope { ... async { ... } }` block,
then `callTool()` reads those cells *inside* the async coroutines — after one or
more suspension points (`provider.callTool(call)` is `suspend`, and the child
loop itself suspends on the first LLM call). A single `AgentTool` instance is
constructed once per `child {}` in `AgentBuilder.child` (AgentBuilder.kt:164-171)
and embedded in the agent's `toolProviders`. That `AgentRunner`/loop can be run
concurrently for multiple tasks (nothing forbids it, and the Spring factory hands
out one runner per `@Bean`). Two concurrent parent runs — or even a re-entrant
parent at different depths — will both call `bind()` on the same instance; the
second `bind()` overwrites the first before the first child's `callTool()` reads
the cell. The child then runs at the wrong `depth` (defeating the D-03 depth
ceiling) and is recorded with the wrong `parentRunId` (corrupting the HIER-04 run
tree). The single-threaded test dispatcher in `AgentToolTest`/`AgentLoopCancellationTest`
never exercises true concurrency, so this is invisible to the current suite.

This also violates the CLAUDE.md coroutine-safety expectations: lineage that "only
exists once a task is running" is being smuggled through mutable shared state
instead of being passed as a parameter or carried in `CoroutineContext`.

**Fix:** Do not carry runtime lineage in mutable instance state. Two robust
options:
1. Pass lineage through `kotlinx.coroutines` context. Define a `ChildDispatchContext`
   `CoroutineContext.Element` holding `parentDepth`/`parentRunId`; `AgentLoop`
   wraps the dispatch block in `withContext(ChildDispatchContext(parentDepth, agentId)) { ... }`
   and `AgentTool.callTool` reads it from `coroutineContext`. This is per-coroutine,
   so concurrent runs cannot clobber each other.
2. Or extend the `ToolProvider.callTool` contract to carry an immutable dispatch
   descriptor, removing `ChildDispatchBinder` entirely.

```kotlin
// AgentLoop dispatch:
withContext(ChildLineage(parentDepth = parentDepth, parentRunId = agentId)) {
    coroutineScope { toolCalls.map { call -> async { provider.callTool(call) } }.awaitAll() }
}
// AgentTool.callTool:
val lineage = coroutineContext[ChildLineage] ?: ChildLineage(0, null)
val childDepth = lineage.parentDepth + 1
```

### CR-02: `PostgresAuditLogAdapter` writes two audit rows per run and crashes on non-UUID agent ids

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:85-100`
**Also:** `kore-storage/src/main/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapter.kt:53-73`

**Issue (double write):** In `AgentLoop.run`, the success/`Throwable` branches of
the `try` return an `AgentResult`, and `.also { result -> auditLog.recordAgentRun(...) }`
(line 97-100) records it. That is one write — correct. **However**, the persistent
`AgentRunsTable` uses `UUIDTable` with `id` as the PRIMARY KEY and
`recordAgentRun` does `stmt[id] = UUID.fromString(agentId)` (PostgresAuditLogAdapter.kt:60).
The agent id is `task.id` (AgentLoop.kt:68). A child re-run, a retried task, or
any caller that reuses a task id will cause a **duplicate-primary-key INSERT
failure** that propagates out of `recordAgentRun` — and that call sits inside the
`.also` block, *after* `run` has otherwise succeeded. Because `recordAgentRun` is
not wrapped in try/catch in the `.also`, a storage failure turns a successful
agent run into a thrown exception out of `run()`, violating the documented
INVARIANT "`run` NEVER throws" (AgentLoop.kt:26).

**Issue (non-UUID crash):** `UUID.fromString(agentId)` throws
`IllegalArgumentException` for any non-UUID id. `AgentTask.id` is a free-form
`String` (AgentTask.kt:5) and the in-memory tests freely use ids like
`"parent-1"`. The moment a host wires the Postgres adapter and submits a task
with a human-readable id, `run()` throws instead of returning an `AgentResult`.
Same defect applies to `recordLLMCall`/`recordToolCall` (`UUID.fromString(agentId)`).

**Fix:**
1. Wrap the audit write in `run`'s tail so storage failures cannot break the
   no-throw invariant:
   ```kotlin
   }.also { result ->
       runCatching { auditLog.recordAgentRun(agentId, task, result) }
           .onFailure { /* log; never rethrow */ }
       eventBus.emit(AgentEvent.AgentCompleted(agentId = agentId, result = result))
   }
   ```
2. Stop assuming `agentId`/`task.id` is a UUID. Either constrain `AgentTask.id`
   to a UUID type at the boundary, or store it as `varchar` and deterministically
   derive the PK (e.g. `UUID.nameUUIDFromBytes(agentId.toByteArray())`) — and
   guard with `runCatching`/validation so a malformed id degrades gracefully
   rather than crashing the run.

### CR-03: `extractInput` hand-rolled JSON parsing corrupts child inputs

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/AgentTool.kt:121-132`

**Issue:** `extractInput` finds `"input"`, the next `:`, then takes everything
between the next two `"` characters. This breaks on the most common real LLM
output:
- Escaped quotes: `{"input":"say \"hi\""}` → returns `say \` (truncated at the
  first escaped quote).
- Whitespace/ordering: `{ "other":"x", "input" : "real" }` works only by luck;
  `{"inputXtra":"trap","input":"real"}` — `indexOf("\"input\"")` matches inside
  `"inputXtra"`? No (the key literal is `"input"` with closing quote, so
  `inputXtra` won't match) — but `{"input_v2":"trap"}`-style keys and any value
  containing the substring `"input"` before the real key will mis-target.
- Newlines in the value, Unicode escapes, and numbers/objects as the value are
  all silently mishandled.

The "fall back to raw arguments" behaviour (returning the entire JSON blob as the
child's task input) means a parse miss feeds `{"input":"..."}` verbatim to the
child LLM as its task — a silent correctness failure, not a visible error. This
directly contradicts the project mandate to use `kotlinx.serialization` for all
internal DTOs (CLAUDE.md tech-stack section) and the "no hand-rolled parsing"
spirit of the constraints.

**Fix:** Parse with `kotlinx.serialization`, which is already a first-class
dependency:
```kotlin
@Serializable private data class ChildInput(val input: String)
private val json = Json { ignoreUnknownKeys = true }
private fun extractInput(arguments: String): String =
    runCatching { json.decodeFromString<ChildInput>(arguments).input }
        .getOrElse { arguments } // documented, deliberate fallback
```

## Warnings

### WR-01: Child path bypasses the parent budget enforcer

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/AgentTool.kt:64-88`, `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/dsl/AgentBuilder.kt:151-172`

**Issue:** `AgentBuilder.child` inherits the parent's `eventBus`, `auditLog`, and
`tracer` (AgentBuilder.kt:161-163) but **not** the parent's `budgetEnforcer`. The
child `AgentBuilder` keeps its own default `InMemoryBudgetEnforcer()`
(AgentBuilder.kt:45). A parent with `budget(maxTokens = 10_000)` will spawn
children that each get a *fresh, separate* budget — so a parent can blow far past
its configured token ceiling by delegating work to children. For a tool whose
headline feature is "zero-config token budget enforcement," an unbounded child
fan-out is a real cost-control hole.

**Fix:** Inherit the budget enforcer into children the same way the event bus and
audit log are inherited (add a `budgetEnforcer(...)` override and call it in
`child`), or thread a shared enforcer keyed by the run-tree so child usage counts
against the parent's ceiling.

### WR-02: `run`'s audit write is unguarded — breaks the documented no-throw invariant

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:97-100`

**Issue:** Independent of CR-02's UUID problem, *any* `AuditLog` implementation
that throws from `recordAgentRun` (network blip on a future remote sink, DB
constraint, serialization error) will propagate out of `run`, violating the
class INVARIANT (AgentLoop.kt:26 "`run` NEVER throws"). The cancellation branch
already shields its write with `NonCancellable`; the success/error branch has no
protection at all.

**Fix:** Wrap the `.also` audit write in `runCatching` (see CR-02 fix snippet)
and log on failure rather than rethrowing.

### WR-03: Spring `var`-free claim violated by mutable single-element cells (style-as-bug-risk)

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/dsl/AgentBuilder.kt:42-64`, `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/AgentTool.kt:44-45`

**Issue:** The code uses `arrayOf(...)`/`IntArray(1)`/`arrayOfNulls(1)` "val
cells" explicitly to dodge the `no var` rule (comments at AgentBuilder.kt:51-54
say so), while `model` is a plain `var` (AgentBuilder.kt:42) and the builder
already has `private var budgetEnforcer`, `private var eventBus`, etc.
(AgentBuilder.kt:45-49). This is inconsistent and, more importantly, the
single-element mutable arrays defeat the *intent* of the immutability rule: they
are mutable shared state with none of the visibility guarantees a reviewer would
expect from a `val` — which is exactly what makes CR-01's race possible. The rule
exists to push the design toward genuine immutability, and the cells are a
workaround that reintroduces the hazard.

**Fix:** For builder-internal accumulation, plain `private var` is the honest and
acceptable choice (a builder is inherently mutable). For `AgentTool`, remove the
cells entirely per CR-01 (carry lineage in coroutine context). Do not use arrays
to launder mutable state past the lint rule.

### WR-04: First `isError` tool result aborts the whole batch, discarding sibling results

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:244-287`

**Issue:** Tool calls are dispatched in parallel (D-22), but as soon as
`toolResults.firstOrNull { it.isError }` finds one error (line 282), `run`
returns `AgentResult.ToolError` and **all other tool results in the same batch
are silently dropped** — never appended to history, never surfaced. With the new
depth-ceiling refusal being an `isError = true` path (AgentTool.kt:64-74), a
single child hitting the depth limit will abort an otherwise-successful parallel
batch (e.g. three siblings succeeded, one was refused → the whole turn becomes
`ToolError` and the three successes vanish). For a parallel-dispatch design this
is surprising and lossy.

**Fix:** Decide explicitly: either append all tool results (including the error
content) to history and let the LLM react, or document that any `isError`
short-circuits the turn. If short-circuiting is intended, at minimum the dropped
sibling results should be recorded/audited so work isn't silently lost.

### WR-05: `findProvider` calls `listTools()` on every provider for every tool call

**File:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt:310-313`

**Issue:** `findProvider` is a `suspend` function that calls
`provider.listTools()` for each provider on every lookup, and it is invoked once
per tool call inside the parallel dispatch (AgentLoop.kt:252). For `AgentTool`,
`listTools()` allocates a new `ToolDefinition` list each time. More importantly,
`listTools()` is part of a port that may do real I/O for MCP providers (it is
`suspend`), so a parent with N tool calls re-queries every MCP server's tool
catalog N times per turn. This is a correctness-adjacent robustness issue: a
provider whose `listTools()` is slow or flaky now gets hammered, and a name
collision (Pitfall 3, noted in AgentTool docs) silently resolves to whichever
provider happens to be first.

**Fix:** Build a `Map<String, ToolProvider>` once at the top of `runLoop` from the
already-computed `toolDefs`/providers and look up by name, instead of re-scanning
and re-invoking `listTools()` per call.

### WR-06: Integration tests are `@Tag("integration")` only — depth/lineage correctness has no unit coverage on the persistent path

**File:** `kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapterTest.kt:20-22`, `kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/MigrationTest.kt:14-16`

**Issue:** Both storage tests are tagged `integration` and require Testcontainers.
If CI does not run the `integration` tag (common for fast PR builds), the
Postgres adapter — including the CR-02 UUID/double-write hazards — has zero
executed coverage. None of the persistent-path tests pass a non-UUID `agentId`,
so the crash in CR-02 is untested even when integration tests do run. The
concurrency hazard in CR-01 is also untested (single-threaded `runTest`).

**Fix:** Add (a) a unit test asserting `AgentLoop.run` returns an `AgentResult`
(does not throw) when the injected `AuditLog` throws; (b) an integration test
that submits a non-UUID `agentId` and asserts graceful handling; (c) a
multi-coroutine `AgentTool` test on a real multi-threaded dispatcher asserting
each child receives the correct `parentRunId`/`depth`.

## Info

### IN-01: `compileOnly` opentelemetry pinned to 1.49.0 while stack doc specifies OTel 2.x

**File:** `kore-spring/build.gradle.kts:56,90`

**Issue:** The build pins `io.opentelemetry:opentelemetry-api:1.49.0` as a literal
string in two places, while the CLAUDE.md tech stack states OTel 2.x via the
Spring Boot 4 starter. A version skew between the compileOnly pin and the
host-supplied runtime API could surface as `NoSuchMethodError` at runtime. Also,
the hardcoded coordinate should live in the version catalog (`libs.*`) like the
other deps.

**Fix:** Move to `libs.opentelemetry.api` in the catalog and align the version
with the runtime BOM (2.x).

### IN-02: Misleading test name — `recordLLMCall inserts row with correct run_id FK`

**File:** `kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapterTest.kt:70-86`

**Issue:** The test name claims a foreign-key relationship, but `recordLLMCall`
sets `runId` as a plain UUID column and there is no assertion that an FK exists or
is enforced (and the design explicitly avoids FKs on lineage columns per
V2 migration). The name overstates what is verified.

**Fix:** Rename to `recordLLMCall inserts row with matching run_id` and, if an FK
is actually expected on `llm_calls.run_id`, assert it in `MigrationTest`.

### IN-03: Duplicate `koreEventBusScope` configuration blocks differ only by `havingValue`

**File:** `kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAutoConfiguration.kt:285-307`

**Issue:** `KafkaEventBusScopeConfiguration` and `RabbitMqEventBusScopeConfiguration`
are byte-for-byte identical except for the `havingValue` string. The duplication
is acknowledged in a comment, but it is still two maintenance points for one
behaviour (the `limitedParallelism(4)` magic number is duplicated too).

**Fix:** Extract the scope-building body to a shared private helper, or use a
single `@ConditionalOnExpression` covering both `type` values, so the scope is
defined once.

---

_Reviewed: 2026-06-22_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
