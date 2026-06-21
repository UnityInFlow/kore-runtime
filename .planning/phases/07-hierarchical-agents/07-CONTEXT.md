# Phase 7: Hierarchical Agents - Context

**Gathered:** 2026-06-21
**Status:** Ready for planning

<domain>
## Phase Boundary

Parent agents can declare child agents via a `child { }` block inside the
`agent { }` DSL. The **spawn model** is used: each child is exposed to the
parent's LLM as a single callable tool; when the LLM invokes it, the child
`AgentLoop` runs and its `AgentResult` is mapped back into a `ToolResult` that
re-enters the parent loop. Cancelling a parent cancels all running children
(structured concurrency), spawning is bounded by a configurable `maxDepth`
(default 5), and the child's audit row records `parent_run_id` so a full run
tree is traceable from the database (HIER-01, HIER-02, HIER-03, HIER-04).

This is the largest kore-core change of the milestone. Its `AgentLoop.kt` edits
land **on top of** Phase 5's OBSV-03 span work (rebase, do not revert).

**In scope:** `child { }` DSL, the `AgentTool : ToolProvider` (named in
`ToolProvider.kt` KDoc + `AgentRunner.kt` D-19 but not yet built), inline child
execution with cancellation propagation, depth threading + `maxDepth` guard,
`parent_run_id` audit column + threading.

**Out of scope:** streaming a child's `AgentResult` back as `Flow<LLMChunk>`
(HIER-05 — deferred); per-child concurrency limits; child result caching.

</domain>

<decisions>
## Implementation Decisions

### Child-as-tool wiring (HIER-01)
- **D-01:** A child that **runs and ends in a non-Success result**
  (`ToolError`, `LLMError`, `BudgetExceeded`, `Cancelled`) is surfaced to the
  parent as `ToolResult(isError = false)` with `content` describing the failure
  (e.g. `"child 'researcher' budget exceeded"`). The parent LLM treats it as
  information and decides the next step — **a failing child does NOT abort the
  parent run.** Rationale: the parent loop converts the *first* `isError = true`
  ToolResult into `AgentResult.ToolError` and ends the whole run
  (`AgentLoop.runLoop` → "Check if any tool call errored"), which is too
  aggressive for a recoverable sub-agent failure.
- **D-02:** The child tool's JSON input schema is a **single required `input`
  string**, fed straight into the child's `AgentTask.input` (mirrors top-level
  task intake). No structured/metadata params this phase.
- **D-03 (the asymmetry — read with D-06):** A **depth-limit refusal to spawn**
  is the ONE case that returns `ToolResult(isError = true)` →
  `AgentResult.ToolError` (HIER-03 criterion #3's "yields a ToolError", the hard
  recursion guard). So: a child that *ran and failed* → `isError = false`
  (D-01); a spawn *blocked by the depth ceiling* → `isError = true`. This
  distinction is deliberate and must be covered by tests.

### Cancellation & structured concurrency (HIER-02)
- **D-04 (mechanism — locked, near-forced by D-19):** `AgentTool.callTool` runs
  the child **inline** via `childLoop.run(childTask)` as a suspend call inside
  the parent loop's existing `coroutineScope { async { provider.callTool() } }`
  tool-dispatch block. It **MUST NOT** route through a child `AgentRunner` — that
  class owns its own `SupervisorJob` scope (`AgentRunner.kt`), which would detach
  the child and break cancellation propagation. Inheriting the parent's
  coroutine context is what makes "cancel parent → cancel children" work for
  free (criterion #2). This is the Phase-1 **D-19** invariant made real.
- **D-05:** On parent cancellation while a child is mid-run, the child records a
  best-effort audit row as **`AgentResult.Cancelled`** before the
  `CancellationException` propagates. Implementation: `AgentLoop.run`'s
  `catch (e: CancellationException)` branch records via the AuditLog inside a
  `withContext(NonCancellable)` (or equivalent `finally`) and then re-throws —
  the re-throw invariant (T-03-03) is preserved. This also benefits **top-level**
  agents (a cancelled root agent now leaves a `Cancelled` audit row, where today
  it leaves none). HIER-04 wants traceable run trees, so cancelled branches must
  appear.
- **D-06 test scope:** One cancellation-propagation test satisfies criterion #2
  — cancel the parent mid-child and assert the child coroutine observes
  cancellation promptly (latch / suspending child). Deeper (grandchild) recursion
  propagation is NOT separately tested this phase.

### Depth ceiling (HIER-03)
- **D-07:** Depth lives in a **new `AgentTask.depth: Int = 0` field** (threaded
  through `AgentLoop`). Default `0` preserves binary compatibility (criterion #5).
  `AgentTool` builds the child task with `depth = parentDepth + 1`. The new
  `parentRunId` field (D-09) rides alongside it in the same data class.
- **D-08:** `maxDepth` (default **5**) gets the **full config surface**, mirroring
  how budget is surfaced: an `AgentLoop` constructor param `maxDepth: Int = 5`, a
  DSL method `maxDepth(n)` on `AgentBuilder`, and a `KoreProperties.hierarchy.maxDepth`
  property for Spring auto-config. The guard check: when a spawn would make the
  child's depth exceed `maxDepth`, `AgentTool` returns the error ToolResult of
  D-03 **without running the child** (no child loop, no child audit row).

### parent_run_id audit (HIER-04)
- **D-09:** The parent run id is threaded down via a **new
  `AgentTask.parentRunId: String? = null` field** (alongside `depth`). `AgentTool`
  sets it to the **parent's `agentId`** (= parent `AgentTask.id`) when building
  the child task. The `AuditLog.recordAgentRun(agentId, task, result)` **signature
  stays unchanged** — implementations read `task.parentRunId`. Root agents leave
  it `null`.
- **D-10:** New **V2 Flyway migration** adds `parent_run_id UUID NULL` to
  `agent_runs` plus `idx_agent_runs_parent_run_id`, with **NO foreign key**.
  Rationale (landmine): the parent records its `agent_runs` row in `AgentLoop.run`'s
  `.also { auditLog.recordAgentRun(...) }` **after** the loop completes, but a
  child completes *during* the parent loop and therefore **inserts its row before
  the parent's row exists** — a self-referencing FK would reject the child insert.
  The plain nullable column is fully queryable for run-tree reconstruction without
  that ordering hazard.
- **D-11:** Wiring touch points for D-09/D-10: `AgentRunsTable` (new
  `parentRunId` column), `PostgresAuditLogAdapter.recordAgentRun` (write
  `task.parentRunId`), and `InMemoryAuditLog` (store it so in-memory run-tree
  assertions work in tests).

### Child dependency inheritance (cross-cutting — makes HIER-04 actually land)
- **D-12:** A child agent **inherits the parent's `AuditLog`, `EventBus`, and
  `Tracer` by default.** The `child { }` block customizes only the agent's own
  concerns (model, tools, budget, nested children). Rationale: `AgentBuilder`
  defaults to a throwaway `InMemoryAuditLog` / `InProcessEventBus` / null tracer;
  if a child kept those, its audit row (carrying `parentRunId`) would land in a
  *discarded in-memory log* and never reach the parent's Postgres store —
  silently breaking HIER-04. Inheriting the parent's ports keeps the whole run
  tree on one audit store, one event stream, and one trace. (A child block MAY
  still override a port explicitly if a future use case needs it.)

### Claude's Discretion
- The exact DSL mechanism by which `child { }` reuses `AgentBuilder` config but
  yields a child **`AgentLoop`** (not an `AgentRunner`) for inline execution —
  e.g. an internal `buildLoop()` path on `AgentBuilder`, or a dedicated
  `ChildAgentBuilder`. The requirement (D-04) is "produce a loop runnable inline
  in the parent scope," not the builder shape.
- The precise child tool **name** (default to the child's `agentName`) and
  LLM-facing **description** source — a child likely needs a `description` so the
  parent LLM knows when to call it; pick the cleanest DSL affordance (e.g. a
  `description = "..."` property in the child block, with a sensible default).
- The exact `content` wording for the D-01 non-Success ToolResult and the D-03
  depth-limit error message (must be human/LLM-legible; keep low-cardinality).
- How `AgentTool.listTools()` advertises the single child tool and how
  `findProvider` in `AgentLoop` resolves it (AgentTool is just another
  `ToolProvider` in the `toolProviders` list).
- The `NonCancellable` audit-record detail in D-05 (exact coroutine construct),
  provided the `CancellationException` re-throw invariant (T-03-03) holds.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements & Roadmap
- `.planning/REQUIREMENTS.md` §Hierarchical Agents — HIER-01, HIER-02, HIER-03, HIER-04 acceptance wording (and HIER-05 listed as deferred/out-of-scope)
- `.planning/ROADMAP.md` §"Phase 7: Hierarchical Agents" — the 5 Success Criteria (what must be TRUE), including criterion #5 (binary compatibility via defaults)

### The core loop & DSL being extended (HIER-01/02/03)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt` — the ReAct loop; the `coroutineScope { async { provider.callTool() } }` tool-dispatch block (where children run inline, D-04); the `catch (CancellationException)` branch in `run` (D-05 best-effort Cancelled audit); the "first errored ToolResult → AgentResult.ToolError" path (drives D-01/D-03); rebases on Phase 5 OBSV-03 span code already in `runLoop`
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/dsl/AgentBuilder.kt` — the `@KoreDsl` builder to extend with `child { }` and `maxDepth(n)`; note `build()` currently returns `AgentRunner` (D-04: children need the loop, not the runner)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/dsl/Dsl.kt` — `agent(name) { }` entry point
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/dsl/KoreDsl.kt` — `@DslMarker` (child block receiver must be annotated, Pitfall 10)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentRunner.kt` — **D-19 comment** ("child agents MUST be launched in this scope, not a new scope"); its `SupervisorJob` scope is exactly what `AgentTool` must NOT use (D-04)

### Tool port & result mapping (HIER-01)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/ToolProvider.kt` — KDoc already names "AgentTool (kore-core) — spawns child agents"; this is the interface `AgentTool` implements (`listTools()` + `callTool()`)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/ToolResult.kt` — `(toolCallId, content, isError)`; the D-01/D-03 mapping target
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/ToolDefinition.kt` — `(name, description, inputSchema)`; the child tool's single-`input`-string schema (D-02)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/ToolCall.kt` — `(id, name, arguments)`; arguments is the JSON the LLM sends to the child tool
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentResult.kt` — sealed result incl. the existing `Cancelled` variant (D-05); the child results D-01 must map from
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentTask.kt` — `(id, input, metadata)`; add `depth: Int = 0` (D-07) and `parentRunId: String? = null` (D-09), both defaulted for binary compat

### Audit / storage (HIER-04)
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/AuditLog.kt` — `recordAgentRun(agentId, task, result)` signature stays unchanged (D-09); implementations read `task.parentRunId`
- `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/internal/InMemoryAuditLog.kt` — store `parentRunId` for in-memory run-tree test assertions (D-11)
- `kore-storage/src/main/resources/db/migration/V1__init_schema.sql` — current `agent_runs` DDL; V2 migration adds the column (D-10)
- `kore-storage/src/main/kotlin/io/github/unityinflow/kore/storage/tables/AgentRunsTable.kt` — Exposed table object; add `parentRunId` column (D-11)
- `kore-storage/src/main/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapter.kt` — `recordAgentRun` INSERT writes `task.parentRunId`; note its append-only/`suspendTransaction` pattern (D-16/D-18) and the existing insert-ordering (parent row written after loop completes — drives D-10's no-FK choice)

### Spring config surface (HIER-03)
- `kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAutoConfiguration.kt` + `KoreProperties` — add `hierarchy.maxDepth` (default 5) following the existing `budget.defaultMaxTokens` property + conditional-bean conventions (D-08)
- `.planning/phases/06-real-budget-enforcement/06-CONTEXT.md` — precedent for "keep KoreProperties shape extensible"; `agentId == AgentTask.id` per-run UUID isolation (the same id used as `parentRunId`)
- `.planning/phases/05-ci-baseline-skill-observability/05-CONTEXT.md` — the OBSV-03 span code in `AgentLoop.runLoop` that Phase 7's loop edits rebase on top of

### Cross-cutting constraints
- `08-kore-runtime/CLAUDE.md` §Constraints / §Do Not — no `var`, no `!!` without comment, coroutines only (never `Thread.sleep`/raw threads), Gradle Kotlin DSL, JUnit 5 + Kotest assertions, MockK, group `io.github.unityinflow`, ktlint before commit, AgentResult sealed hierarchy
- `08-kore-runtime/claude-code-harness-engineering-guide-v2.md` — harness patterns (read if child-spawn loop design needs reference)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`AgentLoop.run` / `runLoop`** already provide the full ReAct cycle a child
  needs — the child is just another `AgentLoop` invoked inline (D-04). No new
  loop logic; reuse verbatim.
- **`coroutineScope { ... async { } ... }`** tool dispatch in `runLoop` is
  already structured-concurrency-correct — a child run launched inside it
  inherits cancellation with zero extra machinery (D-04 / criterion #2).
- **`AgentResult.Cancelled`** already exists — D-05 just needs to *emit* it on
  the cancel path, no new type.
- **`AgentBuilder`** already composes model/tools/budget/eventBus/auditLog/
  skillRegistry — `child { }` reuses it; only the AuditLog/EventBus/Tracer
  defaults must be swapped for inheritance (D-12).
- **`KoreProperties.budget.defaultMaxTokens` + `@ConditionalOnMissingBean`**
  conventions are the template for `hierarchy.maxDepth` (D-08).

### Established Patterns
- The loop **NEVER throws** except `CancellationException` (re-thrown, T-03-03).
  D-05's best-effort audit must NOT violate this — record in `NonCancellable`,
  then re-throw.
- `agentId` passed everywhere IS `AgentTask.id` (a per-run UUID). The parent's
  `agentId` becomes the child's `parentRunId` (D-09) — UUIDs already line up with
  the `agent_runs.id UUID` column (D-10).
- All new `AgentLoop` / `AgentTask` params are **defaulted** (criterion #5) —
  existing single-agent definitions and the published v0.0.1 API compile
  unchanged.
- `ToolProvider` is the single extension seam: `AgentTool` joins the
  `toolProviders` list and is resolved by the existing `findProvider` — no loop
  branching for "is this a child" is required (a child tool looks like any tool).

### Integration Points
- `AgentTool` (new, kore-core) → implements `ToolProvider`; wraps a child
  `AgentLoop`; advertises one tool (child name + description); maps child
  `AgentResult` → `ToolResult` (D-01/D-03).
- `AgentTask` field additions ripple to: `AgentTool` (sets depth+1 / parentRunId),
  `AgentLoop` (threads depth, enforces nothing itself — the guard is in
  `AgentTool`), `PostgresAuditLogAdapter` + `InMemoryAuditLog` (read `parentRunId`).
- V2 Flyway migration is picked up by the existing kore-storage migration
  pipeline; the Testcontainers integration tests (Phase 5 `integrationTest` task)
  are the natural home for a parent_run_id persistence assertion.
- `maxDepth` Spring property connects through `KoreAutoConfiguration` →
  `AgentLoop` construction (or DSL default when not Spring-wired).

</code_context>

<specifics>
## Specific Ideas

- The **D-01 vs D-03 asymmetry** (ran-and-failed child → `isError=false`;
  depth-limit refusal → `isError=true`) should be called out in a code comment
  on `AgentTool` and covered by two distinct tests — it's the subtle correctness
  point of HIER-01 + HIER-03.
- The **insert-ordering landmine** behind D-10 (child audit row inserts before
  the parent's row exists, so no self-FK) should be documented in the V2
  migration file comment so a future maintainer doesn't "tidy up" by adding the FK.
- HIER-04's traceability should be provable end-to-end: a parent-with-child run
  against real Postgres (Testcontainers) yields a child `agent_runs` row whose
  `parent_run_id` equals the parent's `id` — this is the acceptance demo.
- Criterion #2 test: a child that suspends (e.g. on a never-completing LLM mock or
  a latch); cancel the parent's `Deferred`; assert the child is cancelled promptly.

</specifics>

<deferred>
## Deferred Ideas

- **HIER-05 — streaming child `AgentResult` back as `Flow<LLMChunk>`** to the
  parent (instead of a single terminal `ToolResult`). Explicitly listed as a
  future requirement in REQUIREMENTS.md; out of scope for Phase 7.
- **Grandchild/deep-recursion cancellation test** (depth ≥ 2 propagation) —
  considered, deferred (D-06); single-level test satisfies criterion #2.
- **Per-child concurrency caps / a child-pool semaphore** — not raised as a
  requirement; would belong to a later scaling phase.
- **Self-referencing FK on `parent_run_id`** — deferred (D-10) until the audit
  insert ordering is reworked (e.g. parent row written before children, or a
  deferred-constraint approach); revisit only if referential integrity becomes a
  real need.
- **Splitting child-failure handling by result type** (e.g. `BudgetExceeded`
  aborting the tree while `ToolError` reports to the LLM) — considered during
  D-01, rejected for uniform "report to parent LLM"; revisit if budget exhaustion
  in a deep tree proves hard to stop via the LLM alone.

</deferred>

---

*Phase: 07-hierarchical-agents*
*Context gathered: 2026-06-21 via /gsd-discuss-phase (deep-dive, all 4 HIER areas + dependency inheritance)*
