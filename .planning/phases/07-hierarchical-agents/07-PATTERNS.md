# Phase 7: Hierarchical Agents - Pattern Map

**Mapped:** 2026-06-21
**Files analyzed:** 13 (2 new code, 7 modified, 4 test new/extend)
**Analogs found:** 13 / 13

All analogs verified against source on disk. Phase adds zero new dependencies — every pattern is an existing in-repo shape.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `kore-core/.../AgentTool.kt` (NEW) | provider/adapter | request-response (spawn) | `kore-test/.../MockToolProvider.kt` + `AgentLoopTest.kt:113-125` inline `ToolProvider` | exact (same port) |
| `kore-core/.../AgentTask.kt` (MOD) | model | transform | self (defaulted-field add: `AgentResult.kt:31-65` defaulted-param precedent) | exact |
| `kore-core/.../AgentLoop.kt` (MOD) | service (core loop) | event-driven / request-response | self (rebase on existing `runLoop`; `tracer: Tracer? = null` ctor at :49 = defaulted-param precedent) | self/exact |
| `kore-core/dsl/AgentBuilder.kt` (MOD) | builder | transform | self (`tools()`/`budget()` methods :54-108; `build()` :111-132; `skillRegistryCell` val-cell :49-52) | self/exact |
| `kore-core/dsl/ChildAgentBuilder` (NEW or reuse) | builder | transform | `AgentBuilder` + `KoreDsl.kt` `@DslMarker` | exact |
| `kore-core/internal/InMemoryAuditLog.kt` (MOD) | adapter (in-mem) | CRUD/store | self (no-op stub :16-40 → recorded list) | self |
| `kore-storage/.../V2__add_parent_run_id.sql` (NEW) | migration | DDL | `V1__init_schema.sql:1-39` | exact (sibling migration) |
| `kore-storage/.../tables/AgentRunsTable.kt` (MOD) | model (Exposed) | CRUD | self (`finishedAt = ...nullable()` :11) | self/exact |
| `kore-storage/.../PostgresAuditLogAdapter.kt` (MOD) | adapter (Postgres) | CRUD (append-only) | self (`recordAgentRun` insert block :53-69) | self/exact |
| `kore-spring/.../KoreProperties.kt` (MOD) | config | transform | self (`BudgetProperties` :112-115) | self/exact |
| `kore-spring/.../KoreAutoConfiguration.kt` / `KoreAgentFactory.kt` (MOD) | config | transform | self (`inMemoryBudgetEnforcer` `@ConditionalOnMissingBean` :62-65; factory pre-wire :40-45) | self/exact |
| `kore-core/test/.../AgentToolTest.kt` (NEW) | test (unit) | request-response | `AgentLoopTest.kt:14-132` (scriptedBackend + `runTest` + Kotest) | exact |
| `kore-core/test/.../AgentLoopCancellationTest.kt` (NEW) | test (unit, coroutine) | event-driven | `AgentLoopTest.kt` `runTest` idiom + `backgroundScope` (kotlinx-coroutines-test) | role-match |
| `kore-storage/test/...PostgresAuditLogAdapterTest.kt` / `MigrationTest.kt` (EXTEND) | test (integration) | CRUD | self (Testcontainers classes verbatim) | self/exact |

## Pattern Assignments

### `kore-core/.../port/AgentTool.kt` (NEW — provider, spawn request-response)

**Analog:** `kore-test/.../MockToolProvider.kt` (the cleanest full `ToolProvider` impl) + inline `ToolProvider` in `AgentLoopTest.kt:113-125`. The KDoc placeholder it materializes is `ToolProvider.kt:9` ("AgentTool (kore-core) — spawns child agents").

**Port interface to implement** (`port/ToolProvider.kt:12-18`):
```kotlin
interface ToolProvider {
    suspend fun listTools(): List<ToolDefinition>
    suspend fun callTool(call: ToolCall): ToolResult
}
```

**`listTools()` single-tool advertising pattern** (mirror `MockToolProvider.kt:23-30,47`):
```kotlin
// AgentTool advertises exactly ONE tool: name = childName, description = the child block's
// description, inputSchema = the single-required-"input"-string JSON schema (D-02).
override suspend fun listTools(): List<ToolDefinition> =
    listOf(ToolDefinition(name = childName, description = description, inputSchema = INPUT_SCHEMA))
```
`ToolDefinition` shape (`ToolDefinition.kt:7-11`): `(name, description, inputSchema)` — `inputSchema` is a JSON Schema string. D-02 schema: a single required `input` string.

**`callTool()` — depth guard (D-03) + inline child run (D-04) + result map (D-01).** This is the core new code. Structure to write (per RESEARCH Pattern 1/4):
```kotlin
override suspend fun callTool(call: ToolCall): ToolResult {
    // parentDepth / parentRunId are bound at dispatch time — see A2 binding below.
    val childDepth = parentDepth + 1
    if (childDepth > maxDepth) {
        // D-03: the ONLY isError = true path — refuse BEFORE running the child.
        return ToolResult(call.id, "child '$childName' refused: maxDepth $maxDepth exceeded", isError = true)
    }
    val input = extractInput(call.arguments)          // parse the single "input" string (D-02)
    val childTask = AgentTask(
        id = UUID.randomUUID().toString(),
        input = input,
        depth = childDepth,                            // D-07
        parentRunId = parentRunId,                     // D-09 (= parent agentId)
    )
    val childResult = childLoop.run(childTask)         // D-04: INLINE suspend call, NOT an AgentRunner
    return mapResult(call.id, childResult)             // D-01
}
```

**Result-mapping `when` (D-01 asymmetry — must carry a code comment).** Mirror the exhaustive sealed `when` from `PostgresAuditLogAdapter.kt:216-223` (`typeName()`) and `AgentResult.kt:27-66` (the 5 variants). No `else` (CLAUDE.md sealed rule):
```kotlin
// D-01: a child that RAN and failed is informational (isError=false) so the parent
// loop does NOT abort (AgentLoop.kt:258-263 turns the first isError=true into ToolError).
// Only a depth-limit refusal (handled above, BEFORE the child runs) is isError=true (D-03).
private fun mapResult(toolCallId: String, result: AgentResult): ToolResult = when (result) {
    is AgentResult.Success        -> ToolResult(toolCallId, result.output, isError = false)
    is AgentResult.BudgetExceeded -> ToolResult(toolCallId, "child '$childName' budget exceeded", isError = false)
    is AgentResult.ToolError      -> ToolResult(toolCallId, "child '$childName' tool error: ${result.toolName}", isError = false)
    is AgentResult.LLMError       -> ToolResult(toolCallId, "child '$childName' LLM error: ${result.backend}", isError = false)
    is AgentResult.Cancelled      -> ToolResult(toolCallId, "child '$childName' cancelled", isError = false)
}
```
`AgentResult` variants and fields (`AgentResult.kt:31-65`): `Success(output, tokenUsage)`, `BudgetExceeded(spent, limit)`, `ToolError(toolName, cause)`, `LLMError(backend, cause)`, `Cancelled(reason)`.

**A2 binding (the one genuinely-new design point — Discretion).** `parentDepth`/`parentRunId` are *runtime* values; `ToolProvider.callTool(call)` signature is fixed. Use the established **val-cell holder** idiom — same as `AgentBuilder.skillRegistryCell` (`AgentBuilder.kt:49-52`) and `AgentLoop.activatedHolder`/`durationMsHolder` (`AgentLoop.kt:117-118`):
```kotlin
// AgentTool holds val-cell holders; AgentLoop.runLoop binds them before the dispatch block.
private val parentDepthCell = IntArray(1)          // index 0; no `var` (CLAUDE.md)
private val parentRunIdCell = arrayOfNulls<String>(1)
fun bind(parentDepth: Int, parentRunId: String?) { parentDepthCell[0] = parentDepth; parentRunIdCell[0] = parentRunId }
```
`AgentLoop.runLoop` iterates `toolProviders`, and for any `AgentTool` calls `it.bind(task.depth, agentId)` before the `coroutineScope { async { } }` dispatch block (`AgentLoop.kt:220-250`). **Note:** `runLoop` currently does not receive `task` (only `agentId`) — thread `task.depth` in via the existing `run(task)` → `runLoop(...)` call site (`AgentLoop.kt:76`).

---

### `kore-core/.../AgentTask.kt` (MOD — model, defaulted-field add for binary compat)

**Analog:** self. Precedent for defaulted params preserving API: `AgentLoop.kt:42,49` (`skillRegistry = NoOpSkillRegistry`, `tracer: Tracer? = null`) and `AgentResult.kt` defaulted `@Transient cause`.

**Current** (`AgentTask.kt:4-8`):
```kotlin
data class AgentTask(
    val id: String,
    val input: String,
    val metadata: Map<String, String> = emptyMap(),
)
```
**Add (both defaulted — criterion #5):**
```kotlin
    val depth: Int = 0,                 // D-07
    val parentRunId: String? = null,    // D-09
```

---

### `kore-core/.../AgentLoop.kt` (MOD — core service, rebase on Phase 5 span code)

**Analog:** self. The OBSV-03 span code at `runLoop:104-154` must be preserved (rebase, do not revert).

**Defaulted ctor param** (slot near `tracer` at `AgentLoop.kt:49`, follow its defaulted style):
```kotlin
    private val tracer: Tracer? = null,
    private val maxDepth: Int = 5,      // D-08 — defaulted (criterion #5)
    private val config: LLMConfig,
```

**D-05 best-effort Cancelled audit** — modify the existing catch at `AgentLoop.kt:77-78`:
```kotlin
} catch (e: CancellationException) {
    withContext(NonCancellable) {                       // audit survives cancellation
        auditLog.recordAgentRun(agentId, task, AgentResult.Cancelled(reason = e.message ?: "cancelled"))
    }
    throw e                                              // T-03-03 — MUST re-throw (do NOT swallow)
}
```
Imports already present: `CancellationException` (`AgentLoop.kt:14`). Add `kotlinx.coroutines.NonCancellable` + `kotlinx.coroutines.withContext`. **Pitfall:** the existing `.also { auditLog.recordAgentRun(...) }` at `AgentLoop.kt:81-82` does NOT fire on the cancel path (`.also` runs on the `try` return value; the cancel path throws) — no double-record. Add a test asserting exactly one `Cancelled` row.

**Dispatch block to leave structurally intact** (`AgentLoop.kt:220-263`) — the child runs inline inside `provider.callTool(call)` at :235; the "first errored ToolResult → ToolError" map at :258-263 is what drives the D-01/D-03 asymmetry.

**findProvider** (`AgentLoop.kt:286-289`) resolves `AgentTool` like any other provider — no loop branching needed. (Pitfall 3: child tool name must not collide with a real tool name.)

---

### `kore-core/dsl/AgentBuilder.kt` (MOD) + `ChildAgentBuilder` (NEW or reuse)

**Analog:** self. Existing DSL method shapes: `tools()`/`budget()`/`eventBus()`/`auditLog()` (`AgentBuilder.kt:54-108`); `build()` (:111-132); val-cell idiom (:49-52). `@DslMarker` is `KoreDsl.kt:11-12`. Port-pre-wire precedent: `KoreAgentFactory.kt:40-45`.

**Extract `buildLoop()` from `build()`** (refactor `AgentBuilder.kt:111-132` — `build()` currently returns `AgentRunner`; children need the `AgentLoop` inside it, D-04):
```kotlin
internal fun buildLoop(): AgentLoop {
    val backend = requireNotNull(model) { "Agent '$agentName': model must be configured. ..." }
    return AgentLoop(
        llmBackend = ResilientLLMBackend(primary = backend, retryPolicy = retryPolicy),
        toolProviders = toolProviders,
        budgetEnforcer = budgetEnforcer,
        eventBus = eventBus,
        auditLog = auditLog,
        skillRegistry = skillRegistryCell[0],
        maxDepth = maxDepth,        // NEW (D-08)
        tracer = tracerCell[0],     // NEW field for D-12 tracer inheritance (A1 — builder has no tracer today)
        config = llmConfig,
    )
}
fun build(): AgentRunner = AgentRunner(loop = buildLoop())
```

**`maxDepth(n)` method** (mirror `budget()` at `AgentBuilder.kt:61-64`, using a val-cell or a `private var`-free holder; CLAUDE.md no-`var` — use `IntArray(1)` cell like `skillRegistryCell`):
```kotlin
@KoreDsl
fun maxDepth(n: Int) { maxDepthCell[0] = n }
```

**`child { }` method** (new — `@KoreDsl` annotated; the receiver MUST be `@KoreDsl` per Pitfall 10 / `KoreDsl.kt`). Reuses `AgentBuilder`, then force-inherits parent ports (D-12), mirroring `KoreAgentFactory.kt:40-45`:
```kotlin
@KoreDsl
fun child(
    name: String,
    description: String = "Delegates a subtask to the '$name' sub-agent",
    block: AgentBuilder.() -> Unit,
) {
    val childBuilder = AgentBuilder(name).apply(block)
    childBuilder.eventBus(this.eventBus)     // D-12 inheritance — overrides child's throwaway defaults
    childBuilder.auditLog(this.auditLog)     // (AgentBuilder defaults to InMemoryAuditLog/InProcessEventBus :43-45)
    childBuilder.inheritTracer(this.tracerCell[0])  // A1: new tracer affordance on builder
    toolProviders.add(
        AgentTool(childName = name, description = description, childLoop = childBuilder.buildLoop(), maxDepth = maxDepthCell[0]),
    )
}
```
**A1 (verified):** `AgentBuilder` has NO tracer field today (only `AgentLoop` does, `AgentLoop.kt:49`). Add an internal tracer val-cell + `inheritTracer()` so D-12 can pass the parent's tracer through `buildLoop()`.

**Entry point** (`Dsl.kt:20-23`) stays `agent(name) { }.build()` → returns `AgentRunner` — unchanged for top-level.

---

### `kore-core/internal/InMemoryAuditLog.kt` (MOD — in-memory adapter, store parentRunId)

**Analog:** self (the no-op stub at `InMemoryAuditLog.kt:16-40`). D-11/D-12 require it to actually store `parentRunId` so in-memory run-tree assertions work.

**Replace the no-op `recordAgentRun` (:17-21)** with a recorded list (no `var`; use a concurrent collection):
```kotlin
data class RunRecord(val agentId: String, val parentRunId: String?, val resultType: String)
private val runs = java.util.concurrent.CopyOnWriteArrayList<RunRecord>()
val recordedRuns: List<RunRecord> get() = runs.toList()
override suspend fun recordAgentRun(agentId: String, task: AgentTask, result: AgentResult) {
    runs.add(RunRecord(agentId, task.parentRunId, result::class.simpleName ?: "unknown"))
}
```
Leave `recordLLMCall`/`recordToolCall`/`queryRecentRuns`/`queryCostSummary` (:23-39) as no-op/empty stubs. `isPersistent` stays `false` (inherited from `AuditLog.kt:33`).

---

### `kore-storage/.../db/migration/V2__add_parent_run_id.sql` (NEW — migration, DDL)

**Analog:** `V1__init_schema.sql:1-39` (sibling migration; note its `CREATE INDEX IF NOT EXISTS` style at :36-38 and the file-top D-16 append-only comment at :1-2). `agent_runs.id UUID PRIMARY KEY` is at `V1:5`.

**Write (additive, nullable, indexed, NO FK — D-10 landmine documented in the file):**
```sql
-- V2__add_parent_run_id.sql
-- HIER-04: child agent runs record their parent's run id for run-tree reconstruction.
--
-- LANDMINE (D-10) — DO NOT ADD A FOREIGN KEY HERE.
-- A child completes DURING the parent loop and INSERTs its agent_runs row BEFORE the
-- parent's row exists (the parent records its own row only after AgentLoop.run returns,
-- AgentLoop.kt:81). A self-referencing FK would reject the child insert. A plain
-- nullable column is fully queryable for run trees.
ALTER TABLE agent_runs ADD COLUMN parent_run_id UUID NULL;
CREATE INDEX IF NOT EXISTS idx_agent_runs_parent_run_id ON agent_runs(parent_run_id);
```
Picked up automatically by `StorageConfig.migrate()` → `.locations("classpath:db/migration")` (existing pipeline).

---

### `kore-storage/.../tables/AgentRunsTable.kt` (MOD — Exposed model)

**Analog:** self. The `.nullable()` column idiom is at `AgentRunsTable.kt:11` (`finishedAt = timestampWithTimeZone("finished_at").nullable()`).

**Add after line 12** (Exposed `uuid()` column, nullable to match V2 DDL):
```kotlin
val parentRunId = uuid("parent_run_id").nullable()
```

---

### `kore-storage/.../PostgresAuditLogAdapter.kt` (MOD — Postgres adapter, append-only INSERT)

**Analog:** self. The insert pattern is `recordAgentRun` at `PostgresAuditLogAdapter.kt:53-69` (`suspendTransaction(database) { AgentRunsTable.insert { stmt -> ... } }`). UUID parsing idiom is at :60 (`stmt[id] = UUID.fromString(agentId)`).

**Add inside the existing `insert` block (after :66):**
```kotlin
// Pitfall 4: parentRunId is String? but the column is UUID — null-safe parse.
stmt[parentRunId] = task.parentRunId?.let(UUID::fromString)
```
Imports already present: `java.util.UUID` (:23), `org.jetbrains.exposed.v1.r2dbc.insert` (:18). No FK in DDL means the child insert (which lands before the parent row) is never rejected (D-10).

---

### `kore-spring/.../KoreProperties.kt` (MOD — config property)

**Analog:** `BudgetProperties` at `KoreProperties.kt:112-115` (the EXACT template per D-08), registered in the root data-class constructor at :26. Note the "intentionally extensible shape" KDoc precedent (:108-111).

**Add to the root constructor (after :27)** and a nested data class (mirror `BudgetProperties`):
```kotlin
    val hierarchy: HierarchyProperties = HierarchyProperties(),   // NEW
    ...
    /** Hierarchical-agent spawn ceiling. `kore.hierarchy.max-depth`, default 5 (HIER-03). */
    data class HierarchyProperties(val maxDepth: Int = 5)
```

---

### `kore-spring/.../KoreAutoConfiguration.kt` + `KoreAgentFactory.kt` (MOD — config wiring)

**Analog:** `inMemoryBudgetEnforcer` bean at `KoreAutoConfiguration.kt:62-65` — the exact `@Bean @ConditionalOnMissingBean` + `properties.budget.defaultMaxTokens` template. Factory port pre-wire is `KoreAgentFactory.kt:36-45`.

**Threading:** pass `properties.hierarchy.maxDepth` into agent construction. The natural injection point is `KoreAgentFactory` (`:36-45`) — it already pre-wires `eventBus`/`auditLog`/`skillRegistry` into the builder before `block()`. Add a `maxDepth(properties.hierarchy.maxDepth)` call alongside (and pass the factory's tracer if/when one is wired, per D-12). The `@ConditionalOnMissingBean` pattern (`:62-65`) applies if a `maxDepth`-carrying bean is introduced.

---

### `kore-core/test/.../AgentToolTest.kt` (NEW — unit test)

**Analog:** `AgentLoopTest.kt:14-132`. Reuse verbatim: the inline `scriptedBackend(vararg chunks)` helper (`:16-25`), the `makeLoop(...)` helper (`:27-41`), `runTest { }` (`:45`), Kotest `shouldBe`/`shouldBeInstanceOf` (`:8-9,55-56`), and the inline `ToolProvider` style (`:113-125`).

**Covers (RESEARCH Test Map):**
- HIER-01 / crit #1: child runs as a tool call, result feeds back → parent reaches `Success`.
- HIER-01 / D-01: a child that ran and FAILED → `ToolResult(isError=false)`; parent does NOT abort (assert parent result is NOT `ToolError`).
- HIER-03 / D-03: spawn at `depth > maxDepth` → `ToolResult(isError=true)` → parent `AgentResult.ToolError`; child loop NOT run (assert no child audit row).
- HIER-04 fast path: `InMemoryAuditLog.recordedRuns` shows a child record whose `parentRunId == parent id`.

Construct the child loop with the same `makeLoop(...)` helper; wrap it in `AgentTool`; add the `AgentTool` to the parent loop's `toolProviders`; script the parent backend to emit a `LLMChunk.ToolCall` naming the child tool (mirror `AgentLoopTest.kt:104-105`).

---

### `kore-core/test/.../AgentLoopCancellationTest.kt` (NEW — unit test, coroutine cancellation)

**Analog:** `AgentLoopTest.kt` `runTest` + Kotest idiom, extended with `backgroundScope` (kotlinx-coroutines-test 1.10.2, already on classpath). NO existing cancellation test exists (verified) — this is the closest role-match.

**Covers:**
- HIER-02 / crit #2 / D-06: cancel parent mid-child → child observes cancellation promptly. Make the child suspend on a never-completing primitive (a `CompletableDeferred` latch or never-emitting `LLMBackend` mock — Pitfall 5), launch the parent on `backgroundScope`, cancel its `Deferred`/job, assert the latch observed cancellation. Use `backgroundScope` + `yield`/`runCurrent`, NOT `advanceUntilIdle` (would hang on the infinite loop).
- HIER-02 / D-05: a cancelled run leaves exactly ONE `Cancelled` audit row (assert `InMemoryAuditLog.recordedRuns` has one record with `resultType == "Cancelled"` — no double-record).

---

### `kore-storage/test/...PostgresAuditLogAdapterTest.kt` & `MigrationTest.kt` (EXTEND — integration)

**Analog:** self — both are `@Tag("integration") @Testcontainers` classes using `postgres:16-alpine`, `StorageConfig(...).migrate()`, and JDBC assertions (`PostgresAuditLogAdapterTest.kt:20-50,52-68`; `MigrationTest.kt:14-89`).

**Extend `PostgresAuditLogAdapterTest`** (HIER-04 / crit #4): record a child run with `task.parentRunId = parentId`, then `SELECT parent_run_id::text FROM agent_runs WHERE id = ?::uuid` and assert it equals the parent's id. Mirror the existing `recordAgentRun inserts a row` test (`:52-68`) — same `postgres.createConnection("").use { conn -> ... }` JDBC assertion shape.

**Extend `MigrationTest`** (V2 migration): assert `parent_run_id` column exists, is UUID, nullable, indexed. Mirror the `metadata column is jsonb` test (`MigrationTest.kt:59-70`) — same `information_schema.columns` query shape; add an `information_schema`/`pg_indexes` query for the index.

## Shared Patterns

### val-cell holder (no `var`) — runtime/swappable state
**Source:** `AgentBuilder.kt:49-52` (`skillRegistryCell: Array<SkillRegistry> = arrayOf(...)`), `AgentLoop.kt:117-118` (`activatedHolder = arrayOfNulls<...>(1)`, `durationMsHolder = LongArray(1)`).
**Apply to:** `AgentTool` parentDepth/parentRunId binding (A2); `AgentBuilder.maxDepthCell` / `tracerCell`. CLAUDE.md forbids `var` — single-element arrays are the established workaround.
```kotlin
private val skillRegistryCell: Array<SkillRegistry> = arrayOf(NoOpSkillRegistry) // swap at index 0
```

### Exhaustive sealed `when`, no `else`
**Source:** `PostgresAuditLogAdapter.kt:216-223` (`AgentResult.typeName()`); `AgentResult.kt:27-66` (the sealed hierarchy).
**Apply to:** `AgentTool.mapResult` (D-01). CLAUDE.md requires exhaustive sealed matching — list all 5 `AgentResult` variants, no catch-all.

### Defaulted params/columns for binary compatibility (criterion #5)
**Source:** `AgentLoop.kt:42,49` (`skillRegistry = NoOpSkillRegistry`, `tracer: Tracer? = null`); `AgentRunsTable.kt:11` (`.nullable()`).
**Apply to:** `AgentTask.depth=0`/`parentRunId=null`, `AgentLoop.maxDepth=5`, `parent_run_id UUID NULL`. Every new param/column defaulted/nullable so the published v0.0.1 API compiles unchanged.

### CancellationException re-throw invariant (T-03-03)
**Source:** `AgentLoop.kt:77-78,171-172,238-239,251-252` (every catch re-throws `CancellationException`).
**Apply to:** D-05 audit branch — wrap only the audit write in `withContext(NonCancellable)`; the `throw e` MUST remain. Never swallow.

### Port pre-wiring / inheritance (overwrite throwaway defaults)
**Source:** `KoreAgentFactory.kt:40-45` (pre-sets `eventBus`/`auditLog`/`skillRegistry` before `block()`); `AgentBuilder.kt:43-45` (the throwaway in-memory defaults being overwritten).
**Apply to:** D-12 child port inheritance in `AgentBuilder.child { }` — overwrite the child builder's `eventBus`/`auditLog`/tracer with the parent's after `block` runs.

### `@ConditionalOnMissingBean` + property-driven default bean
**Source:** `KoreAutoConfiguration.kt:62-65` (`inMemoryBudgetEnforcer(properties)` reads `properties.budget.defaultMaxTokens`).
**Apply to:** wiring `properties.hierarchy.maxDepth` into agent construction.

### Testcontainers integration test scaffold
**Source:** `PostgresAuditLogAdapterTest.kt:20-50` / `MigrationTest.kt:14-39` (`@Tag("integration") @Testcontainers`, `postgres:16-alpine`, `StorageConfig(...).migrate()`, `postgres.createConnection("").use { }` JDBC).
**Apply to:** both extended integration tests. Copy the companion-object container + `@BeforeAll setup()` verbatim.

### Unit-test scaffold (scriptedBackend + runTest + Kotest)
**Source:** `AgentLoopTest.kt:16-41` (`scriptedBackend`, `makeLoop`), `:8-9` (Kotest imports), `:45` (`runTest`).
**Apply to:** `AgentToolTest` and `AgentLoopCancellationTest`. Reuse the inline `scriptedBackend`/`makeLoop` helpers; do NOT depend on a separate test module.

## No Analog Found

None. Every file maps to an existing in-repo analog (often the file itself). The single genuinely-new design decision is the **A2 binding mechanism** for runtime `parentDepth`/`parentRunId` — but it reuses the established val-cell idiom (`AgentBuilder.kt:49-52`), so there is no pattern gap, only a design choice to confirm in planning.

## Metadata

**Analog search scope:** `kore-core/` (core, dsl, port, internal, test), `kore-storage/` (tables, migration, adapter, test), `kore-spring/` (properties, autoconfig, factory), `kore-test/`.
**Files scanned:** 22 located; 20 read in full (all ≤ ~380 lines, single-pass each).
**Pattern extraction date:** 2026-06-21
