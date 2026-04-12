---
phase: 02-observability-storage
plan: 03
subsystem: kore-observability
tags: [micrometer, metrics, eventbus, coroutines, tdd, low-cardinality]
dependency_graph:
  requires:
    - kore-core (AgentEvent, AgentResult, TokenUsage, EventBus port)
    - kore-observability/KoreMetrics (Task 1 output feeds Task 2)
  provides:
    - KoreMetrics: 4 Micrometer counter factories + active-agents gauge (OBSV-05)
    - EventBusMetricsObserver: EventBus subscriber driving all KoreMetrics from agent lifecycle events
  affects:
    - kore-spring: will autowire KoreMetrics(meterRegistry) + EventBusMetricsObserver as beans (Phase 3)
tech_stack:
  added: []
  patterns:
    - KoreMetrics MeterRegistry injection: Counter.builder(...).tag(...).register(registry) — Micrometer deduplicates by name+tag set
    - AtomicInteger gauge: registry.gauge("kore.agents.active", atomicInt) — value tracked by reference, not snapshot
    - EventBusMetricsObserver backgroundScope + yield + runCurrent TDD pattern for infinite-flow coroutine testing
    - ConcurrentHashMap with maxTrackedAgents eviction guard (T-02-10 DoS mitigation)
key_files:
  created:
    - kore-observability/src/main/kotlin/dev/unityinflow/kore/observability/KoreMetrics.kt
    - kore-observability/src/main/kotlin/dev/unityinflow/kore/observability/EventBusMetricsObserver.kt
    - kore-observability/src/test/kotlin/dev/unityinflow/kore/observability/KoreMetricsTest.kt
    - kore-observability/src/test/kotlin/dev/unityinflow/kore/observability/EventBusMetricsObserverTest.kt
  modified: []
decisions:
  - backgroundScope + yield + runCurrent chosen for EventBusMetricsObserverTest: advanceUntilIdle() hangs on infinite collect{} loops; backgroundScope cancels the collector at test end
  - maxTrackedAgents=10000 eviction guard added to EventBusMetricsObserver (T-02-10 threat mitigation — agentNames map can grow unbounded if AgentCompleted never emitted)
  - model/backend tags default to "unknown" in EventBusMetricsObserver: LLMCallCompleted event does not carry model/backend; Phase 3 kore-spring will enrich via agent context injection
  - agentNameResolver injected as (agentId: String) -> String lambda with default { "unknown" }: allows Phase 3 to wire in Spring-managed name registry without changing the observer
metrics:
  duration: 58min
  completed: 2026-04-12
  tasks_completed: 2
  files_created: 4
  files_modified: 0
---

# Phase 02 Plan 03: KoreMetrics + EventBusMetricsObserver Summary

**One-liner:** Micrometer metrics for kore-runtime — KoreMetrics (4 low-cardinality counter factories + active-agents AtomicInteger gauge) and EventBusMetricsObserver (EventBus subscriber driving all counters from agent lifecycle events), completing OBSV-05.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 RED | KoreMetrics failing tests | cf8da39 | KoreMetricsTest.kt |
| 1 GREEN | KoreMetrics implementation | 1429e50 | KoreMetrics.kt, KoreMetricsTest.kt (formatted) |
| 2 RED | EventBusMetricsObserver failing tests | 78ddb8b | EventBusMetricsObserverTest.kt |
| 2 GREEN | EventBusMetricsObserver implementation | b1d9b03 | EventBusMetricsObserver.kt, EventBusMetricsObserverTest.kt (formatted) |

## What Was Built

### KoreMetrics

Micrometer-native class accepting `MeterRegistry` as a constructor parameter (injected, not retrieved statically). Implements the D-21 through D-25 low-cardinality design:

- `agentsActive: AtomicInteger` — registered as gauge `kore.agents.active` with **no labels** (D-23). Value tracked by reference so the gauge always reflects the current count.
- `agentRunCounter(agentName, resultType)` — counter `kore.agent.runs` with tags `agent_name` + `result_type`
- `llmCallCounter(agentName, model, backend)` — counter `kore.llm.calls` with tags `agent_name` + `model` + `backend`
- `tokensUsedCounter(agentName, model, direction)` — counter `kore.tokens.used` with tags `agent_name` + `model` + `direction` ("in"/"out")
- `errorCounter(agentName, errorType)` — counter `kore.errors` with tags `agent_name` + `error_type`

All counter methods call `.register(registry)` on every invocation — Micrometer deduplicates by name+tag set returning the same `Counter` instance for identical inputs. No per-tool counters (D-25).

### EventBusMetricsObserver

Subscribes to `EventBus.subscribe()` Flow and drives all `KoreMetrics` increments:

- `AgentStarted`: populates `agentNames` map via `agentNameResolver`, calls `metrics.agentsActive.incrementAndGet()`
- `AgentCompleted`: removes from `agentNames`, calls `metrics.agentsActive.decrementAndGet()`, increments `agentRunCounter`, and conditionally increments `errorCounter` for `LLMError`/`ToolError` results
- `LLMCallCompleted`: looks up agent name, increments `llmCallCounter` and both `tokensUsedCounter` (direction=in, direction=out) using `increment(double)` for fractional token counts
- `ToolCallStarted`, `ToolCallCompleted`, `LLMCallStarted`: `else -> Unit` — no Micrometer action per D-25

The `agentNameResolver` lambda defaults to `{ "unknown" }` and accepts any `(agentId: String) -> String` — Phase 3 kore-spring will inject a Spring-managed name registry here. Similarly, model/backend tags default to `"unknown"` until Phase 3 adds OTel span context correlation.

**T-02-10 mitigation:** `agentNames` is bounded by `maxTrackedAgents = 10_000`. On `AgentStarted`, if the map reaches capacity, the oldest entries are evicted before inserting the new one. This prevents unbounded growth if `AgentCompleted` is never received (e.g. agent cancelled without emitting the event, or DROP_OLDEST event bus overflow).

## Verification Results

| Check | Result |
|-------|--------|
| `./gradlew :kore-observability:build` | PASS |
| `./gradlew :kore-observability:test` (all 4 test classes, 10+ tests) | PASS |
| `./gradlew :kore-observability:lintKotlin` | PASS |
| `./gradlew :kore-observability:test :kore-storage:test` (full phase) | PASS |
| Metric names: kore.agents.active, kore.agent.runs, kore.llm.calls, kore.tokens.used, kore.errors | VERIFIED |
| D-25: no `kore.tool.*` counter in EventBusMetricsObserver | VERIFIED |
| D-25: ToolCallStarted/ToolCallCompleted only under `else -> Unit` | VERIFIED |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical Functionality] T-02-10 agentNames map size guard**
- **Found during:** Task 2 implementation — reviewing threat model T-02-10
- **Issue:** Plan's threat model explicitly flags unbounded `agentNames` ConcurrentHashMap growth as a DoS risk if `AgentCompleted` is never emitted for an agent. The plan's action section omits the eviction guard.
- **Fix:** Added `maxTrackedAgents: Int = 10_000` constructor parameter and eviction logic on `AgentStarted` — if `agentNames.size >= maxTrackedAgents`, remove oldest entries before inserting. Documented in KDoc with T-02-10 reference.
- **Files modified:** `EventBusMetricsObserver.kt`
- **Commit:** b1d9b03

### Test Infrastructure Adjustments (non-deviation)

**`runCurrent` instead of `advanceUntilIdle` for infinite-flow tests:**
- `advanceUntilIdle()` hangs on `collect {}` loops because they never finish — the test scheduler waits forever.
- Fix: Use `backgroundScope` (cancelled at test end) for the observer's scope + `yield()` to let the subscriber coroutine start + `runCurrent()` to process queued events. This is the standard coroutines-test pattern for infinite flows.
- This is a test infrastructure fix, not a logic deviation.

## Known Stubs

| Stub | File | Line | Reason |
|------|------|------|--------|
| model/backend tags = "unknown" in llmCallCounter | EventBusMetricsObserver.kt | 71-77 | `AgentEvent.LLMCallCompleted` does not carry model or backend. Phase 3 kore-spring will enrich via OTel span context or agent context injection. Documented in KDoc. |
| agentNameResolver defaults to { "unknown" } | EventBusMetricsObserver.kt | 35 | `AgentEvent.AgentStarted` carries only agentId (UUID), not configured name. Phase 3 will wire in a name registry. `agentNameResolver` lambda makes the wiring point explicit and injectable. |

Both stubs are intentional per plan — the metrics infrastructure is complete; enrichment deferred to Phase 3 per D-24.

## Threat Flags

No new threat surface introduced. `KoreMetrics` and `EventBusMetricsObserver` are pure in-process components with no network endpoints, no file access, no auth paths. The T-02-10 DoS threat (agentNames map growth) from the plan's threat model was mitigated inline as a Rule 2 auto-fix.

## Self-Check: PASSED
