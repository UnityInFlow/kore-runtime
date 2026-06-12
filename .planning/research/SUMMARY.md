# Project Research Summary

**Project:** kore-runtime — milestone v0.0.2 "Hardening & Hierarchy"
**Domain:** Production Kotlin coroutine agent runtime (JVM, hexagonal architecture)
**Researched:** 2026-06-12
**Confidence:** HIGH

## Executive Summary

v0.0.2 is a bounded, additive milestone on top of the shipped v0.0.1 runtime. All four features are clearly scoped, their integration points are verified against actual source code, and no breaking changes to public APIs are required. The existing hexagonal architecture (ports in kore-core, adapters in separate opt-in modules, auto-config in kore-spring) directly accommodates every addition: the budget-breaker adapter follows the established kore-kafka/kore-rabbitmq module pattern, hierarchical agents extend the existing coroutine tool-dispatch model, OBSV-03 fills the one remaining gap in the span hierarchy, and the integrationTest task mirrors the pattern already shipping in kore-kafka.

The recommended approach is to treat the four features as three parallel tracks: (1) kore-storage integrationTest + CI is fully independent and risk-free — do it first as a quick win; (2) OBSV-03 is a small, isolated change to kore-core and kore-observability that unblocks metrics completeness; (3) the budget-breaker adapter (new kore-budget module) and hierarchical agents (kore-core structural changes) are the highest-complexity items and should be sequenced last but can be developed concurrently since they touch different files. The build order in ARCHITECTURE.md is concrete and should be followed.

The primary risk is coroutine scope management for hierarchical agents: constructing a detached `CoroutineScope(SupervisorJob())` for child agents instead of launching them inside the parent scope breaks structured concurrency and produces orphaned coroutines that survive parent cancellation. The secondary risk is the budget-breaker adapter boundary — `BudgetHardLimitException` must never escape the `BudgetEnforcer` port; it must be caught inside `BudgetBreakerAdapter.recordUsage` and surfaced only as `checkBudget()` returning `false`. Both risks have concrete unit test specifications in PITFALLS.md.

---

## Key Findings

### Recommended Stack

The v0.0.1 stack is unchanged. The single new external dependency for v0.0.2 is `io.github.unityinflow:budget-breaker:0.0.1` (verified on Maven Central), added to the new `kore-budget` module only. Its transitive `kotlinx-coroutines-core:1.10.1` is Gradle-resolved to the already-pinned `1.10.2` — no exclusion or shading needed. All OTel work in OBSV-03 uses existing `compileOnly` dependencies. The `integrationTest` Gradle task uses the stable `tasks.registering(Test::class)` API (not the incubating `jvm-test-suite` plugin), reusing the existing test source set with tag-based filtering.

**New/changed dependency:**
- `io.github.unityinflow:budget-breaker:0.0.1` — token budget enforcement — verified published on Maven Central; added to `kore-budget` only

**What NOT to add:**
- `budget-breaker-spring-boot-starter` — not yet published; gate auto-config on `BudgetCircuitBreaker` class presence only
- Any new OTel artifact — all required OTel API already on classpath via existing `compileOnly` declarations
- `jvm-test-suite` incubating plugin — manual `Test` task registration is simpler and sufficient

### Expected Features

**Must have (all four are P1 for v0.0.2):**
- Real budget-breaker adapter — `InMemoryBudgetEnforcer` is a stub; users who configure `budget()` in the DSL expect actual hard stops. Closes the single biggest production-readiness gap.
- Hierarchical agents (spawn model, `maxDepth=5` guard, structured-concurrency cancellation) — deferred from v0.0.1 original scope. Minimum: parent spawns one child, child result feeds back into parent loop as a tool result.
- OBSV-03 OTel span for skill activation — closes the only gap in the span hierarchy (`kore.agent.run → kore.llm.call → kore.tool.use` complete; `kore.skill.activate` is missing).
- `integrationTest` Gradle task + CI step for kore-storage — 7 Testcontainers tests exist and are tagged but excluded from CI.

**Should have (v0.1.0):**
- Budget slice DSL for child agents (`childBudget = parent * 0.5`) — prevents child cost compounding (Augment Code measured 4–15× in production)
- `AgentEvent.BudgetWarning` on soft-limit via EventBus
- Dashboard widget for active child agent trees

**Defer (v0.1.x+):**
- Cross-process budget coordination, dynamic skill hot-reload, streaming child `AgentResult` as `Flow<LLMChunk>`

**Anti-features (explicitly excluded):**
- Handoff model — breaks the ReAct loop, severs coroutine scope chain. Spawn model is strictly superior.
- Global shared budget — concurrent shared counter corrupts under real concurrency (arxiv 2606.04056: 30/30 overshoots).
- `supervisorScope` for child agent dispatch — use `coroutineScope { }` so one child failure cancels siblings cleanly.

### Architecture Approach

All four features fit within the existing hexagonal module structure without boundary violations. The zero-dep constraint on kore-core (coroutines + stdlib only) is preserved throughout.

**New module:**
1. `kore-budget` — `BudgetBreakerAdapter` implementing `BudgetEnforcer`; subscribes to `EventBus` to track agent lifecycles via `AgentStarted`/`AgentCompleted`; `BudgetCircuitBreaker.withBudget` scope opened per agent run

**Modified modules:**
2. `kore-core` — `AgentEvent.SkillActivated`; `AgentLoop` OBSV-03 span attrs + event emit; `ChildAgentProvider` port + `NoOpChildAgentProvider`; `AgentRunner` optional scope param; `ChildAgentDispatcher` internal; `AgentBuilder.child{}` DSL
3. `kore-observability` — `EventBusSpanObserver` `SkillActivated` branch; `KoreAttrs.SKILL_NAME`
4. `kore-spring` — `BudgetBreakerAutoConfiguration` inner class with triple `@ConditionalOnClass(name=[...])` / `@ConditionalOnProperty` / `@ConditionalOnMissingBean` gate
5. `kore-storage` — `integrationTest` task registration (mirrors kore-kafka template)
6. `.github/workflows/ci.yml` — new `integration-test` job (`needs: build`)
7. `settings.gradle.kts` — `include(":kore-budget")`

### Critical Pitfalls

1. **`BudgetHardLimitException` escaping the `BudgetEnforcer` port** — catch inside `recordUsage`, set `AtomicBoolean` flag, `checkBudget` returns `!flag`. Verify: `AgentLoop.run()` returns `AgentResult.BudgetExceeded` (not thrown exception) at hard limit.

2. **`SupervisorJob` severing parent-child cancellation** — child runners must be launched inside `supervisorScope { async { } }` within the parent loop's coroutine context, not via a new detached `CoroutineScope(SupervisorJob())`. Verify: cancel parent deferred, assert child deferred is cancelled within 100ms.

3. **Skill-activation span unparented / context lost on suspension** — existing `AgentLoop` stub uses raw `spanBuilder()?.startSpan()` without `setParent` or `asContextElement()`. OBSV-03 must use `KoreTracer.withSpan(name) { }`. Verify: `kore.skill.activate` span has correct `parentSpanId` in `InMemorySpanExporter`.

4. **`integrationTest` task silently running 0 tests** — five independent wiring points; missing any one gives exit 0 with empty suite. Use `tasks.registering(Test::class)` with `testClassesDirs = sourceSets["test"].output.classesDirs` and `includeTags("integration")`. Verify with `--info`.

5. **`@ConditionalOnClass` class literal causing eager classloading crash** — must use `@ConditionalOnClass(name = ["io.github.unityinflow.kore.budget.BudgetBreakerAdapter"])` string form, not `BudgetBreakerAdapter::class`.

---

## Implications for Roadmap

### Phase 1: CI Baseline + OBSV-03

**Rationale:** Zero architectural risk, fewest files, unblocks CI correctness immediately and closes the observability gap before higher-risk structural changes land.

**Delivers:** `integrationTest` task in kore-storage (7 tests running in CI), `AgentEvent.SkillActivated`, `AgentLoop` OBSV-03 span attrs + emit, `EventBusSpanObserver` `SkillActivated` branch, `KoreAttrs.SKILL_NAME`

**Features from FEATURES.md:** OBSV-03, integrationTest task

**Pitfalls to avoid:** P5 (0-test silent pass), P6 (Docker not available — add `docker info` pre-flight step), P4 (unparented span — use `KoreTracer.withSpan`)

**Build order:** kore-storage task → ci.yml job → `AgentEvent.SkillActivated` → `AgentLoop` OBSV-03 → `EventBusSpanObserver` branch

**Research flag:** Standard patterns — no deeper research needed. kore-kafka is the exact template for both the Gradle task and the CI step.

### Phase 2: budget-breaker Adapter

**Rationale:** Isolated in new `kore-budget` module + kore-spring inner class. No dependency on hierarchical agents code path. Can run in parallel with Phase 1.

**Delivers:** `kore-budget` module, `BudgetBreakerAdapter`, `BudgetBreakerAutoConfiguration` triple-gate, `InMemoryBudgetEnforcer` remains default.

**Features from FEATURES.md:** Real budget-breaker adapter (hard stop, per-agent isolation)

**Stack from STACK.md:** `io.github.unityinflow:budget-breaker:0.0.1`

**Pitfalls to avoid:** P1 (`BudgetHardLimitException` boundary), P2 (concurrent `withBudget` per agentId — use `AgentTask.id` UUID, not agent name), `@ConditionalOnClass` string form

**Build order:** kore-budget module + `BudgetBreakerAdapter` → kore-spring `BudgetBreakerAutoConfiguration`

**Research flag:** Auto-config pattern is standard (verbatim from kore-kafka). The `BudgetBreakerAdapter` lifecycle bridging (event-subscription model for `withBudget` scope management) is MEDIUM confidence — if scope DSL proves incompatible during implementation, fall back to `TokenTracker` low-level API.

### Phase 3: Hierarchical Agents

**Rationale:** Largest structural change in v0.0.2; touches five kore-core files. Done last to avoid rebase conflicts with Phases 1 and 2. Spawn model (agent-as-tool, not handoff) fits cleanly into the existing parallel tool-dispatch path.

**Delivers:** `ChildAgentProvider` port + `NoOpChildAgentProvider` (all existing agents unchanged), `ChildAgentDispatcher`, `AgentRunner` optional scope param, `AgentBuilder.child{}` + `maxDepth(n)` DSL, `AgentTask.depth` field, `AgentResult → ToolResult` translation, `parent_run_id` in metadata for audit trail.

**Features from FEATURES.md:** Hierarchical agents (spawn model, depth guard `maxDepth=5`, structured-concurrency cancellation)

**Pitfalls to avoid:** P3 (SupervisorJob severing cancellation), depth limit guard (default 5), child agentId must be `UUID.randomUUID()` not predictable string

**Build order:** `ChildAgentProvider` port → `AgentRunner` optional scope → `ChildAgentDispatcher` → `AgentBuilder` `child{}` wiring

**Research flag:** Scope propagation through `AgentBuilder.build()` (passing parent scope to `ChildAgentDispatcher` via `buildWithScope` factory method) needs careful implementation-time attention. No deeper pre-implementation research needed beyond what ARCHITECTURE.md documents.

### Phase Ordering Rationale

- Phases 1 and 2 are fully parallel — no shared modified files.
- Phase 3 must follow Phase 1 because both modify `AgentLoop.kt` (OBSV-03 in Phase 1, `childAgentProvider` param in Phase 3).
- All new `AgentLoop` and `AgentTask` constructor parameters must have default values to preserve binary compatibility.
- CI Docker pre-flight (Phase 1, step 2) validates the runner before any further CI steps depend on it.

### Research Flags

**Standard patterns (skip deeper research):**
- Phase 1 integrationTest task: exact template in kore-kafka
- Phase 1 OBSV-03: `KoreTracer.withSpan` is the correct implementation, already exists
- Phase 2 auto-config: triple-gate verbatim from kore-kafka `KoreAutoConfiguration.kt`

**Needs implementation-time attention:**
- Phase 2 `BudgetBreakerAdapter` lifecycle: MEDIUM confidence on event-subscription scope model; fallback documented
- Phase 3 scope threading through `AgentBuilder.build()`: `buildWithScope` factory method needs care

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | `budget-breaker:0.0.1` verified on Maven Central; all other deps unchanged from pinned v0.0.1 stack |
| Features | HIGH | All four features verified against existing port interfaces, sealed classes, and AgentLoop source |
| Architecture | HIGH | All integration points read from actual source files; module placement follows established pattern |
| Pitfalls | HIGH | All six critical pitfalls verified against actual source code — AgentLoop.kt lines 97–106, BudgetCircuitBreaker.kt putIfAbsent guard, kore-storage build.gradle.kts missing task, EventBusSpanObserver.kt OBSV-03 stub |

**Overall confidence:** HIGH

### Gaps to Address

- **`BudgetBreakerAdapter` lifecycle bridging (MEDIUM):** Event-subscription model for bridging `recordUsage` calls to `withBudget` scopes is architecturally correct but requires careful implementation. Fallback to `TokenTracker` low-level API is available if scope DSL proves incompatible — document in phase task.

- **Docker availability on arc-runner-unityinflow:** Not pre-verified. CI integration-test job must include a `docker info` pre-flight step. Failure mode is a configuration error (not a test failure), which blocks all integration test runs.

- **`AgentTask.depth` field binary compatibility:** Adding a field to `AgentTask` must use a default value (`depth: Int = 0`). All construction sites in tests must use named parameters or the primary constructor. Verify before Phase 3 merge.

- **Child budget aggregation gap (known, deferred):** Child agents have independent budgets in v0.0.2; parent token spend does not include child spend. Must be documented explicitly in kore-budget README and `childAgents()` KDoc. Augment Code's 15× cost-compounding measurement makes this a real user concern.

---

## Sources

### Primary (HIGH confidence)
- `io.github.unityinflow:budget-breaker:0.0.1` — [Maven Central](https://central.sonatype.com/artifact/io.github.unityinflow/budget-breaker/0.0.1)
- budget-breaker source (`../05-budget-breaker/` tag `v0.0.1`) — `BudgetCircuitBreaker`, `BudgetScope`, `BudgetHardLimitException` API surface
- kore-core source (v0.0.1) — `AgentLoop.kt` lines 97–106, `AgentEvent.kt`, `BudgetEnforcer.kt`, `AgentRunner.kt`
- kore-observability source — `KoreTracer.kt`, `EventBusSpanObserver.kt` (OBSV-03 stub confirmed)
- kore-storage source — `build.gradle.kts` (missing integrationTest task confirmed), `PostgresAuditLogAdapterTest.kt` (7 tests confirmed)
- kore-kafka source — `build.gradle.kts` (integrationTest task template), `KoreAutoConfiguration.kt` (triple-gate pattern)
- `.github/workflows/ci.yml` — no integrationTest step confirmed
- [Kotlin coroutines structured concurrency](https://kotlinlang.org/docs/exception-handling.html)
- [OpenTelemetry Kotlin extension `asContextElement()`](https://github.com/open-telemetry/opentelemetry-java)

### Secondary (MEDIUM confidence)
- [Koog sub-agents blog — JetBrains AI (Jan 2026)](https://blog.jetbrains.com/ai/2026/01/building-ai-agents-in-kotlin-part-4-delegation-and-sub-agents/) — spawn model comparison
- [Multi-agent cost compounding — Augment Code 2026](https://www.augmentcode.com/guides/multi-agent-cost-compounding) — 4–15× amplification measurement
- [Token budget overrun catalog — arXiv 2606.04056](https://arxiv.org/html/2606.04056) — 30/30 asyncio overrun rate

### Tertiary (supporting)
- [Gradle Java Testing](https://docs.gradle.org/current/userguide/java_testing.html)
- [Infinite subagent recursion — KiloCode](https://github.com/Kilo-Org/kilocode/issues/8637)
- [Claude Code depth cap=5 — griffin.dev](https://www.threads.com/@griffin.dev/post/DX0HnjYnGK8/)

---
*Research completed: 2026-06-12*
*Ready for roadmap: yes*
