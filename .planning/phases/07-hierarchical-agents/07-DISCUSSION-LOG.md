# Phase 7: Hierarchical Agents - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-21
**Phase:** 7-hierarchical-agents
**Areas discussed:** Child-as-tool wiring, Cancellation model, Depth ceiling, parent_run_id audit, Child dependency inheritance

---

## Child-as-tool wiring (HIER-01)

### Q1 — How a non-Success child result surfaces to the parent

| Option | Description | Selected |
|--------|-------------|----------|
| Report to parent LLM | Map non-Success to `ToolResult(isError=false)` with descriptive content; parent LLM decides next step; child failure does NOT abort parent | ✓ |
| Propagate as hard error | Map non-Success to `ToolResult(isError=true)`; first failing child ends parent run as `AgentResult.ToolError` | |
| Split by result type | Some failures abort (BudgetExceeded), others report-to-LLM (ToolError/LLMError) | |

**User's choice:** Report to parent LLM
**Notes:** The current loop kills the parent on the first `isError=true` ToolResult — too aggressive for a recoverable sub-agent. Captured as D-01. (Depth-limit refusal is the deliberate exception → see D-03.)

### Q2 — Child tool input schema

| Option | Description | Selected |
|--------|-------------|----------|
| Single input string | One required string param `input` → child `AgentTask.input` | ✓ |
| Input + metadata | `input` string + optional metadata object merged into child metadata | |

**User's choice:** Single input string
**Notes:** Mirrors top-level task intake; smallest schema surface. Captured as D-02.

---

## Cancellation model (HIER-02)

### Q1 — Child audit row on parent cancellation

| Option | Description | Selected |
|--------|-------------|----------|
| Record Cancelled, best-effort | Write child audit row as `AgentResult.Cancelled` in NonCancellable/finally before re-throwing CancellationException | ✓ |
| Skip on cancel | Let CancellationException propagate immediately, no DB write for the cancelled child | |

**User's choice:** Record Cancelled, best-effort
**Notes:** HIER-04 wants traceable run trees — a cancelled branch should still appear. Touches `AgentLoop.run`'s `catch(CancellationException)`; also benefits top-level agents. Captured as D-05. Inline-execution mechanism (run child via `loop.run()` in parent scope, never via AgentRunner) locked separately as D-04 per the Phase-1 D-19 invariant.

### Q2 — Test scope for cancellation propagation

| Option | Description | Selected |
|--------|-------------|----------|
| Single propagation test | One test: cancel parent mid-child, assert child cancelled promptly (criterion #2) | ✓ |
| Add depth-N propagation test | Also test propagation through grandchild (depth 2+) | |

**User's choice:** Single propagation test
**Notes:** Matches criterion #2 exactly. Captured as D-06; grandchild test noted as deferred.

---

## Depth ceiling (HIER-03)

### Q1 — Where the depth counter lives

| Option | Description | Selected |
|--------|-------------|----------|
| New AgentTask.depth field | Add `depth: Int = 0` to AgentTask (default = binary compat); AgentTool sets child depth+1 | ✓ |
| metadata["depth"] | Store depth in existing metadata map (stringly-typed) | |

**User's choice:** New AgentTask.depth field
**Notes:** Type-safe; parentRunId rides alongside it. Captured as D-07.

### Q2 — maxDepth config surface

| Option | Description | Selected |
|--------|-------------|----------|
| Full surface | AgentLoop param `maxDepth: Int = 5` + DSL `maxDepth(n)` + `KoreProperties.hierarchy.maxDepth` | ✓ |
| Loop param only | Just `AgentLoop(maxDepth = 5)`, no DSL/props | |

**User's choice:** Full surface
**Notes:** Mirrors how budget is surfaced. Captured as D-08. Over-limit refusal → `ToolResult(isError=true)` → `AgentResult.ToolError` per criterion #3 (D-03).

---

## parent_run_id audit (HIER-04)

### Q1 — V2 migration shape

| Option | Description | Selected |
|--------|-------------|----------|
| Plain nullable column + index | `parent_run_id UUID NULL` + index, NO foreign key | ✓ |
| Nullable column + self-FK + index | Self-referencing FK for referential integrity | |
| You decide | Defer to research/planning | |

**User's choice:** Plain nullable column + index
**Notes:** Landmine — children insert their audit row before the parent's final row exists, so a self-FK would reject the child insert. Captured as D-10 with the ordering rationale.

### Q2 — How the parent run id is threaded down

| Option | Description | Selected |
|--------|-------------|----------|
| AgentTask.parentRunId field | Add `parentRunId: String? = null` to AgentTask; AgentTool sets parent agentId; AuditLog signature unchanged | ✓ |
| recordAgentRun param | Add `parentRunId` param to AuditLog.recordAgentRun | |

**User's choice:** AgentTask.parentRunId field
**Notes:** Consistent with the depth decision (D-07); keeps the port signature stable. Captured as D-09.

---

## Child dependency inheritance (cross-cutting)

### Q1 — Child infrastructure ports

| Option | Description | Selected |
|--------|-------------|----------|
| Inherit parent's ports | Child reuses parent's AuditLog, EventBus, Tracer by default | ✓ |
| Fresh defaults, opt-in override | Child gets own InMemory defaults unless overridden | |
| You decide | Let planning pick the mechanism, "inherit" as required behavior | |

**User's choice:** Inherit parent's ports
**Notes:** AgentBuilder defaults to a throwaway InMemoryAuditLog — without inheritance the child's parentRunId row never reaches the parent's Postgres store, silently breaking HIER-04. Captured as D-12.

---

## Claude's Discretion

- DSL mechanism by which `child { }` yields a child `AgentLoop` (not an `AgentRunner`) for inline execution.
- Child tool name (default = child agentName) and LLM-facing description source.
- Exact `content` wording for the D-01 non-Success ToolResult and the D-03 depth-limit error message (low-cardinality).
- How `AgentTool.listTools()` advertises the single child tool and how `findProvider` resolves it.
- Exact `NonCancellable` coroutine construct for the D-05 best-effort audit (re-throw invariant must hold).

## Deferred Ideas

- HIER-05 — streaming child `AgentResult` back as `Flow<LLMChunk>`.
- Grandchild/deep-recursion (depth ≥ 2) cancellation propagation test.
- Per-child concurrency caps / child-pool semaphore.
- Self-referencing FK on `parent_run_id` (revisit if audit insert ordering is reworked).
- Splitting child-failure handling by result type (BudgetExceeded abort vs ToolError report).
