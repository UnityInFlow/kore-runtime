# Feature Research

**Domain:** Kotlin agent runtime — milestone v0.0.2 "Hardening & Hierarchy"
**Researched:** 2026-06-12
**Confidence:** HIGH

## Scope Note

This file covers the FOUR new features in v0.0.2 only. Everything from v0.0.1 (agent loop, LLM backends, MCP, skills, Spring starter, dashboard, audit log, observability decorators, event bus) is already shipped — see PROJECT.md `## Requirements / Validated`. The analysis below maps expected behaviors for what is NEW, with explicit dependency back to the existing `AgentResult`, `AgentEvent`, `BudgetEnforcer`, and `EventBusSpanObserver` model.

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features that users of an agent runtime with existing budget enforcement and observability will assume work correctly at v0.0.2.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Real token-budget enforcement (hard stop + graceful `AgentResult.BudgetExceeded`) | `InMemoryBudgetEnforcer` is a stub — any user who configures `budget()` in the DSL expects it to actually stop the agent when exceeded, not silently accumulate forever. Users expect **pre-flight refusal** before the next LLM call, not post-call cleanup. Industry consensus: structured failure is correct, not an abrupt JVM exception. | MEDIUM | Already has `BudgetEnforcer` port + `BudgetExceeded` sealed class variant. Work is only the adapter wiring budget-breaker's `BudgetCircuitBreaker` behind that port. |
| Soft-limit warning without stopping the agent | Users expect two thresholds: a warning at 80% spend (soft) and a hard stop at 100%. This matches budget-breaker's `AgentBudget(softLimitTokens, hardLimitTokens)` model. The agent should continue after soft-limit but emit an observable event. | LOW | `BudgetScope.trackCall()` already emits `BudgetEvent.SoftLimitReached` — the adapter just needs to translate it to a kore `AgentEvent`. |
| Per-agent budget isolation | If two agents run concurrently, one exceeding its budget must not affect the other. Users assume this is the default. | LOW | budget-breaker's `BudgetCircuitBreaker` already tracks per `agentId`. The adapter maps kore's `agentId` to budget-breaker's scope. |
| OTel span for every observable operation | Users of kore-observability expect a closed span hierarchy: agent run → LLM call → tool use. Skill activation already starts a span inside `AgentLoop.runLoop()` via the optional `Tracer?` parameter (the `kore.skill.activate` stub). OBSV-03 closes the gap: skill activation must go through the event bus so the span observer handles it consistently, rather than the `tracer?.spanBuilder()` inline stub in `AgentLoop`. | LOW | The span name constant (`KoreSpans.SKILL_ACTIVATE`) and attributes scaffolding exist in `KoreTracer.kt`. Work is: (1) add `AgentEvent.SkillActivated` with `agentId` + `skillNames` list, (2) emit it in `AgentLoop.runLoop()`, (3) handle it in `EventBusSpanObserver`. |
| Integration tests that actually run in CI | Three Testcontainers test classes are tagged `@Tag("integration")` and excluded from `tasks.test` via `excludeTags("integration")`. Users who install kore-storage assume its PostgreSQL adapter is tested against a real database in CI. Without a separate `integrationTest` task wired into CI, those 7 tests never run. | LOW | Pure Gradle configuration work + one new CI step. No changes to production logic. Existing test code is complete and correct. |

### Differentiators (Competitive Advantage)

Features in v0.0.2 that go beyond what existing JVM agent frameworks provide and strengthen kore's position.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Hierarchical agents with structured concurrency (parent cancels children) | LangChain4j and LangGraph on the JVM have no first-class parent-child agent execution model with Kotlin structured concurrency semantics. kore's model — `coroutineScope { }` inside `AgentLoop.run()` — makes parent cancellation automatically propagate to children without extra infrastructure. No other JVM runtime ships this. | HIGH | This is the biggest v0.0.2 work item. Requires new `ChildAgentTool` (a `ToolProvider` + `ToolDefinition` that wraps an `AgentRunner`), a `HierarchicalAgentRunner` that tracks child coroutines inside a `coroutineScope`, depth-limiting guard (max depth encoded as `AgentContext` field), and result translation (`AgentResult` → `ToolResult`). |
| Agent-as-a-tool spawn model (not handoff) | The two industry models are: **handoff** (OpenAI Agents SDK — parent hands over conversation, never returns) and **agent-as-tool / spawn** (Koog, LangGraph agent nodes — parent calls child, child returns result, parent continues). For coroutine-based structured concurrency, spawn is strictly superior: it preserves parent scope, allows parallel child dispatch via `async { }` + `awaitAll()`, and maps cleanly to `ToolCall` + `ToolResult`. Handoff would break the existing ReAct loop model and is explicitly an anti-feature here. | MEDIUM | The LLM triggers child agent invocation like any other tool call. The parent loop already handles `toolCallChunks` with parallel `async { }` dispatch (D-22). A child agent invocation is just another tool result. |
| Child agents share parent's coroutine scope (not a separate `CoroutineScope`) | Cancelling the `AgentRunner.shutdown()` propagates through the `SupervisorJob` to all child coroutines including nested child agent runs. No orphan threads, no resource leaks. This is not achievable with any thread-based framework. | LOW | Already follows from the coroutine-scope model. The implementation requires explicitly launching child agent coroutines inside a `coroutineScope { }` block (not a new detached scope) so that cancellation chains correctly. |
| Budget propagation slice model for child agents | The parent budget-breaker adapter tracks tokens for `parentAgentId`. Child agents run with their own `agentId` but the parent can optionally carve out a budget slice: `childBudget = parentBudget * fraction`. If no slice is specified, children run with their own independent budget caps. Industry research (Augment Code 2026) confirms that every handoff re-bills child outputs at the parent's model tier, compounding 4–15× vs single-agent. Exposing an explicit slice API gives platform engineers control. | MEDIUM | Requires the `AgentBuilder` DSL to accept a `parentBudget: AgentBudget?` parameter. Budget slicing is additive behavior — the default (no slice) keeps existing single-agent behavior unchanged. |
| Depth limit guard (`maxDepth`) | Production systems (Claude Code cap depth=5, Hermes limit depth=2, EffGen limit depth=2) all enforce a maximum nesting depth to prevent infinite recursion. OpenCode shipped without a depth guard and suffered infinite-recursion incidents. kore must ship with a default `maxDepth = 5` configurable via the DSL. | LOW | `AgentContext` (or a new `HierarchyContext`) carries current depth. Before spawning a child, check `depth < maxDepth`. Return `AgentResult.LLMError` with a descriptive message if limit is reached — this threads through the existing sealed class hierarchy without new variants. |
| `SkillActivated` event on the event bus (OBSV-03) | Every existing span (LLM call, tool use) flows through `EventBusSpanObserver`. Skill activation is the only observable operation that does NOT flow through the bus — the span is started inline in `AgentLoop.runLoop()` with the optional `Tracer?`. Moving skill activation to the bus means: dashboards can show which skills activated, Kafka/RabbitMQ consumers see skill events, tests can assert on skill activation without a tracer. | LOW | Small isolated change. The inline `tracer?.spanBuilder("kore.skill.activate")?.startSpan()` block in `AgentLoop` becomes: emit `AgentEvent.SkillActivated(agentId, activatedSkillNames)` → `EventBusSpanObserver` creates the span with correct attributes. |

### Anti-Features (Commonly Requested, Often Problematic)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Handoff model (parent transfers conversation ownership to child) | OpenAI Agents SDK popularized handoffs — "triage agent routes to specialist, specialist takes over." Engineers familiar with that model may request it. | Breaks the ReAct loop: once the parent hands off, it cannot resume. This severs the coroutine scope chain, orphaning any resources the parent holds. The existing parallel tool-dispatch model (`async + awaitAll`) becomes impossible. In kore's streaming loop, there is no natural "transfer ownership" hook. | Agent-as-tool spawn: child runs inside a tool call, result returns to parent, loop continues. Parent always owns the scope. |
| Global shared budget across all agents | Some users want one budget pool for the whole application. | Concurrent agents with a single shared budget require atomic distributed counters. Under real concurrency (tested in the arxiv budget-overrun catalog: 30/30 overshoots with asyncio), naive shared state corrupts counting. budget-breaker's `BudgetCircuitBreaker` explicitly rejects concurrent runs with the same `agentId`. | Per-agent budgets with optional per-workflow cap. The workflow cap is a sum over completed agent reports — computable post-hoc, not a shared live counter. Expose via `BudgetCircuitBreaker.getAllReports()`. |
| Child agent output forwarded verbatim into parent context | Tempting to give the parent the full child conversation history for maximum context. | This is precisely the cost-compounding failure mode documented by Augment Code (2026): parent context grows with every child round trip, rebilling child outputs as parent inputs at the parent's model tier — up to 15× cost amplification measured in production. | Child agent returns a `ToolResult` containing a summary or structured output (not the full history). The parent's `ToolProvider` adapter for the child should let the child's `AgentResult.Success.output` be the result string, not the full conversation. |
| `supervisorScope` for child agent dispatch | Sounds right — if one child fails, others continue. | For hierarchical agent work, a child failure IS significant. Silently continuing with sibling children that were waiting on the failed child's output produces wrong results. The existing `coroutineScope { async { }... awaitAll() }` model for parallel tool dispatch is correct: one failure cancels sibling async blocks cleanly. | Use `coroutineScope { }` (not `supervisorScope`) for child agent dispatch. Handle `AgentResult.ToolError` from the child agent and return it up the chain. |
| Automatic depth tracking via call-stack inspection | Inspect the JVM call stack to determine the current nesting depth. | JVM stack inspection is expensive, fragile with coroutine continuation frames, and breaks with virtual threads. | Explicit `depth` field passed as `AgentTask` metadata or a `HierarchyContext` coroutine context element. The parent increments depth before launching a child. |

---

## Feature Dependencies

```
[SkillActivated event on EventBus]
    └──requires──> [AgentEvent sealed class — add SkillActivated variant]
                       └──requires──> [AgentLoop: replace inline span stub with eventBus.emit()]
    └──requires──> [EventBusSpanObserver: handle SkillActivated → create kore.skill.activate span]

[Hierarchical agents]
    └──requires──> [ChildAgentTool: wraps AgentRunner as ToolProvider]
                       └──requires──> [AgentRunner.run() is already public]
    └──requires──> [HierarchyContext or AgentTask.depth field: depth tracking, maxDepth guard]
    └──requires──> [AgentBuilder DSL: childAgents(...) / maxDepth(n) methods]
    └──enhances──> [Budget slice: parentBudget parameter in AgentBuilder — optional, v0.1.0]

[Real budget-breaker adapter]
    └──requires──> [BudgetCircuitBreaker (budget-breaker library) — io.github.unityinflow:budget-breaker:0.0.1]
    └──requires──> [BudgetBreakerEnforcer: implements BudgetEnforcer port]
                       └──replaces──> [InMemoryBudgetEnforcer in Spring auto-config]
    └──requires──> [kore-spring AutoConfiguration: @ConditionalOnClass(BudgetCircuitBreaker)]
    └──conditional──> [budget-breaker-spring-boot-starter NOT yet shipped;
                       gate on BudgetCircuitBreaker class presence only, not on starter]

[Testcontainers integrationTest task]
    └──requires──> [kore-storage/build.gradle.kts: add integrationTest task with includeTags("integration")]
    └──requires──> [CI .github/workflows/ci.yml: add integrationTest step after test step]
    └──no new production code required]
```

### Dependency Notes

- **SkillActivated requires AgentEvent change.** `AgentEvent` is `@Serializable` with `@JsonClassDiscriminator("type")`. Adding a new sealed subclass is additive and backward-compatible — existing Kafka/RabbitMQ consumers that do not handle `SkillActivated` will receive a `type` discriminator they do not recognise and must handle the `else ->` branch (already present in `EventBusSpanObserver.start()`).
- **Hierarchical agents require careful scope management.** Child agent coroutines MUST be launched inside the parent's `coroutineScope { }` block (the tool dispatch block already exists in `AgentLoop`). Launching in a new detached `CoroutineScope` breaks parent cancellation. This is the most common mistake in coroutine-based hierarchies.
- **Budget-breaker adapter does not require the Spring Boot starter.** The core `budget-breaker` library (`io.github.unityinflow:budget-breaker:0.0.1`) is on Maven Central. The kore-spring auto-configuration should gate on `BudgetCircuitBreaker` class presence via `@ConditionalOnClass(name = ["io.github.unityinflow.budget.BudgetCircuitBreaker"])` AND `@ConditionalOnMissingBean(BudgetEnforcer::class)`. This matches the existing conditional adapter pattern in kore-spring.
- **BudgetCircuitBreaker concurrency contract.** `BudgetCircuitBreaker.withBudget()` rejects concurrent calls with the same `agentId` (`putIfAbsent` fails fast). The kore adapter must ensure the agent id used as `agentId` is unique per run, not per agent name. `AgentTask.id` (UUID) is already the right key.

---

## MVP Definition for v0.0.2

### Launch With (v0.0.2 — all four items are P1)

- [ ] Real budget-breaker adapter — closes the InMemoryBudgetEnforcer stub. Unblocks platform engineers who need real cost control before using kore in production.
- [ ] Hierarchical agents (spawn model, depth guard, structured concurrency cancellation) — ships the deferred v0.0.1 original scope item. Minimum: parent spawns one child, child result returned to parent loop, depth=5 guard enforced.
- [ ] OTel span for skill activation via EventBus (OBSV-03) — closes the only gap in the span hierarchy. Minimum: `AgentEvent.SkillActivated` emitted, `EventBusSpanObserver` creates `kore.skill.activate` span with `kore.agent.id` + `kore.skill.names` attributes.
- [ ] `integrationTest` Gradle task + CI step for kore-storage — makes the 7 existing Testcontainers tests visible in CI without breaking the fast `test` task.

### Add After Validation (v0.1.0)

- [ ] Budget slice DSL for child agents (`childBudget = parent.budget * 0.5`) — useful once hierarchical agents are in the wild and users see cost compounding.
- [ ] `AgentEvent.BudgetWarning` for soft-limit notifications on the event bus — requires the Spring Boot starter for budget-breaker to ship first (pending as of v0.0.2).
- [ ] Dashboard widget for active child agent trees — visual hierarchy in HTMX dashboard; deferred until hierarchy API stabilises.
- [ ] Parallel child agent dispatch (fan-out: parent spawns N children with `async + awaitAll`) — follows from the spawn model but requires explicit API for declaring N parallel child runners.

### Future Consideration (v0.1.x+)

- [ ] Cross-process budget coordination (multi-JVM shared budget via Redis/Postgres counter) — only needed at scale; single-JVM `BudgetCircuitBreaker` sufficient for v0.0.x.
- [ ] Dynamic skill loading (hot-reload YAML skill files without restart) — separate v0.1.0 feature.
- [ ] Agent result streaming back to parent (streaming child `AgentResult` as `Flow<LLMChunk>` rather than completed `AgentResult`) — complex interaction with the existing chunk model.

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Real budget-breaker adapter | HIGH — production blocker; stub gives false confidence | LOW — port exists, BudgetCircuitBreaker API is well-fit | P1 |
| Hierarchical agents | HIGH — differentiating feature, original v0.0.1 deferred scope | HIGH — new DSL methods, ChildAgentTool, HierarchyContext, depth guard | P1 |
| OTel span for skill activation | MEDIUM — observability completeness | LOW — one new AgentEvent subclass, ~20 lines in EventBusSpanObserver | P1 |
| integrationTest task + CI | MEDIUM — CI correctness | LOW — pure Gradle config + one CI step | P1 |
| Budget slice for child agents | MEDIUM — multi-agent cost control | MEDIUM — DSL addition, BudgetCircuitBreaker scoping | P2 |
| Dashboard child agent tree view | LOW — nice to have | MEDIUM — HTMX fragment, data model for hierarchy | P3 |

---

## Behavior Specifications by Feature

### Budget-Breaker Adapter

**Hard stop semantics.** `BudgetCircuitBreaker.withBudget()` throws `BudgetHardLimitException` when the hard limit is exceeded. The kore adapter must catch this inside `AgentLoop.runLoop()` and return `AgentResult.BudgetExceeded(spent = current, limit = hardLimitTokens)`. This is already the only `AgentResult` variant for budget exhaustion — no sealed class changes needed. The adapter must NOT re-throw `BudgetHardLimitException` — it is not a `CancellationException` and must be translated to the sealed result.

**Soft limit semantics.** Soft limit fires a `BudgetEvent.SoftLimitReached` on `BudgetCircuitBreaker.events` flow. Execution continues. The adapter may translate this to a kore `AgentEvent` — deferred to v0.1.0 until the Spring Boot starter for budget-breaker ships and provides auto-config for `onSoftLimit` callbacks.

**Per-agent key.** Use `AgentTask.id` (already a UUID) as the `agentId` passed to `BudgetCircuitBreaker.withBudget()`. Do NOT use the agent's `name` — the same named agent may run multiple concurrent tasks, and `BudgetCircuitBreaker` rejects concurrent calls with the same id.

**Spring auto-config gate.** `@ConditionalOnClass(name = ["io.github.unityinflow.budget.BudgetCircuitBreaker"])` AND `@ConditionalOnMissingBean(BudgetEnforcer::class)`. When budget-breaker is not on the classpath, the `InMemoryBudgetEnforcer` remains active (already wired as the default in `AgentBuilder`).

### Hierarchical Agents

**Spawn model (not handoff).** The LLM describes a child agent invocation as a tool call. The parent loop treats it exactly as any other tool call: it goes through `findProvider()`, the `ChildAgentTool.callTool()` suspends waiting for the child's `AgentResult`, and returns the result as a `ToolResult`. The parent loop continues with the next iteration. This reuses the existing parallel tool dispatch path — child agents can run in parallel with other tools in the same LLM response.

**Result translation (`AgentResult` → `ToolResult`):**
- `AgentResult.Success(output, tokenUsage)` → `ToolResult(content = output, isError = false)`
- `AgentResult.BudgetExceeded(spent, limit)` → `ToolResult(content = "Child agent budget exceeded: $spent/$limit tokens", isError = true)`
- `AgentResult.ToolError(toolName, cause)` / `AgentResult.LLMError(backend, cause)` → `ToolResult(content = cause.message, isError = true)`
- `AgentResult.Cancelled(reason)` → `ToolResult(content = "Child agent cancelled: $reason", isError = true)`

**Depth guard.** `AgentTask` carries an `Int` field `depth` (default 0). Before `ChildAgentTool.callTool()` launches the child, it checks `task.depth < maxDepth`. If at limit, return `ToolResult(content = "Max agent depth $maxDepth exceeded", isError = true)` immediately without launching. Default `maxDepth = 5` matches Claude Code's production limit.

**Cancellation.** Child agent coroutines launched via `async { childRunner.run(childTask).await() }` inside `coroutineScope { }` inside `ChildAgentTool.callTool()`. When the parent's coroutine is cancelled (e.g., `AgentRunner.shutdown()`), the `CancellationException` propagates into the parent's `runLoop`, which re-throws it (T-03-03 invariant), which cascades into the `coroutineScope { }` in tool dispatch, cancelling all in-flight child `async` blocks. No orphan child coroutines.

**DSL surface area:**
```kotlin
agent("orchestrator") {
    model = claude()
    childAgents(workerRunner1, workerRunner2)  // registers ChildAgentTool instances
    maxDepth(5)                                 // optional, default 5
}
```

### OTel Span for Skill Activation (OBSV-03)

**New event.** `AgentEvent.SkillActivated(agentId: String, skillNames: List<String>)` — added to the `AgentEvent` sealed class. Emitted in `AgentLoop.runLoop()` after `activatedPrompts` is resolved, replacing the inline `tracer?.spanBuilder()` stub.

**Span attributes (kore custom namespace, not OTel GenAI SemConv, per existing decision D-06 "Do NOT use GenAI semantic conventions"):**
- `kore.agent.id` — the agentId (existing attr, already defined in `KoreAttrs`)
- `kore.skill.names` — comma-separated list of activated skill names (new attr, add to `KoreAttrs`)
- `kore.skill.count` — number of activated skills (new attr, add to `KoreAttrs`)
- `kore.skill.duration_ms` — duration of `activateFor()` call in ms (new attr, matches `kore.llm.duration_ms` and `kore.tool.duration_ms` pattern)

**Observer change.** `EventBusSpanObserver.start()` currently has `else -> Unit` for unhandled events. Add `is AgentEvent.SkillActivated -> { ... create and immediately end the span ... }`. The span is instantaneous (skill activation is a local pattern-match, no I/O) — no open-span tracking needed, unlike LLM and tool spans.

**AgentLoop change.** Remove the `tracer?.spanBuilder("kore.skill.activate")?.startSpan()` try/finally block. Replace with: record `startMs`, run `activateFor()`, compute duration, then `eventBus.emit(AgentEvent.SkillActivated(agentId, activatedSkillNames))`. The `tracer: Tracer?` parameter on `AgentLoop` can be removed — it has no other usages after this change and its removal is a clean break (not a public API, constructor-injected).

### integrationTest Gradle Task

**Approach — tag-based, same source set, no file movement.** The 7 Testcontainers tests are already in `src/test/kotlin/` tagged `@Tag("integration")`. A separate source set is overkill. Add a second `Test` task that uses the same source set but inverts the tag filter:

```kotlin
tasks.register<Test>("integrationTest") {
    useJUnitPlatform { includeTags("integration") }
    group = "verification"
    description = "Runs @Tag(\"integration\") Testcontainers tests against a real PostgreSQL."
    dependsOn(tasks.compileTestKotlin)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}
```

**CI step.** Add to `.github/workflows/ci.yml` under the `build` job, after the `Test` step, on `arc-runner-unityinflow` only (Docker must be available; `orangepi-runner` likely does not have Docker). The step runs `./gradlew :kore-storage:integrationTest`.

**What runs.** `MigrationTest`, `PostgresAuditLogAdapterTest`, `PostgresAuditLogAdapterQueryTest` — 7 tests total. All use `@Testcontainers` + `PostgreSQLContainer`. The arc-runner-unityinflow runners (Hetzner, x64 Linux) have Docker available.

---

## Competitor Feature Analysis

| Feature | LangChain4j (JVM) | Koog (Alpha, JetBrains) | kore approach |
|---------|------------------|------------------------|---------------|
| Multi-agent hierarchy | No first-class API. Agents-as-tools pattern requires manual wiring. No structured concurrency. | Agent-as-tool via `Tool` wrapper (Jan 2026 blog). Sequential parent→child, no parallel dispatch, no structured concurrency mentioned. | Spawn model via `ChildAgentTool` inside existing parallel tool dispatch. Structured concurrency via `coroutineScope { async { } }`. Cancellation automatic. |
| Token budget enforcement | None built-in. | None mentioned in public docs. | `BudgetEnforcer` port backed by `budget-breaker` library. Hard stop → `AgentResult.BudgetExceeded`. |
| Skill-level OTel tracing | No skills concept. | No public observability story for skills. | `kore.skill.activate` span via `EventBusSpanObserver`. Attributes: `kore.skill.names`, `kore.skill.count`, `kore.skill.duration_ms`. |
| Depth limiting | Not applicable (no hierarchy). | No public depth-limiting API (Alpha status). | `maxDepth(n)` DSL, default 5. `AgentTask.depth` field propagates down the chain. |

---

## Sources

- [LangGraph Supervisor Pattern 2026 — CallSphere Blog](https://callsphere.ai/blog/langgraph-supervisor-multi-agent-orchestration-2026)
- [LangGraph Multi-Agent Supervisor Reference](https://reference.langchain.com/python/langgraph-supervisor)
- [OpenAI Agents SDK — Multi-Agent Orchestration](https://openai.github.io/openai-agents-python/multi_agent/)
- [OpenAI Agents SDK — Handoffs](https://openai.github.io/openai-agents-python/handoffs/)
- [Koog Sub-Agents — JetBrains AI Blog (Jan 2026)](https://blog.jetbrains.com/ai/2026/01/building-ai-agents-in-kotlin-part-4-delegation-and-sub-agents/)
- [Koog GitHub — FindAgent sub-agent example](https://github.com/JetBrains/koog/blob/develop/examples/code-agent/step-04-add-subagent/src/main/kotlin/FindAgent.kt)
- [Multi-Agent Cost Compounding (15x measurement) — Augment Code](https://www.augmentcode.com/guides/multi-agent-cost-compounding)
- [Token Budget Overrun Catalog — arXiv 2606.04056](https://arxiv.org/html/2606.04056)
- [Token Economy of Agent Networks — Medium / 3K Technologies](https://medium.com/3k-technologies/the-token-economy-of-agent-networks-63507fb48d70)
- [Infinite subagent recursion issue — KiloCode](https://github.com/Kilo-Org/kilocode/issues/8637)
- [Subagents max depth — OpenCode issue](https://github.com/anomalyco/opencode/issues/18100)
- [Recursive depth Claude Code cap=5 — griffin.dev](https://www.threads.com/@griffin.dev/post/DX0HnjYnGK8/)
- [OTel GenAI Agent Span Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans/)
- [OTel GenAI Semantic Conventions overview](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
- [Kotlin Coroutines — supervisorScope vs coroutineScope — Droidcon](https://www.droidcon.com/2025/04/07/kotlin-coroutine-scopes-coroutinescope-vs-supervisorscope/)
- [Kotlin Exception Handling docs](https://kotlinlang.org/docs/exception-handling.html)
- [Gradle Java Testing — separate test tasks](https://docs.gradle.org/current/userguide/java_testing.html)

---
*Feature research for: kore-runtime v0.0.2 — Hardening & Hierarchy*
*Researched: 2026-06-12*
