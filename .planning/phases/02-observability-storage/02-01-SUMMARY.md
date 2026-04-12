---
phase: 02-observability-storage
plan: 01
subsystem: kore-observability
tags: [opentelemetry, tracing, coroutines, context-propagation, observability]
dependency_graph:
  requires: [kore-core]
  provides: [kore-observability]
  affects: []
tech_stack:
  added:
    - opentelemetry-extension-kotlin 1.61.0
    - opentelemetry-api 1.49.0 (compileOnly; provided by kore-spring BOM at runtime)
    - opentelemetry-sdk-testing 1.49.0 (test only)
    - micrometer-core 1.16.0 (compileOnly)
  patterns:
    - Decorator pattern for ObservableAgentRunner wrapping AgentLoop
    - asContextElement() bridging OTel ThreadLocal context into coroutine context
    - ConcurrentHashMap for thread-safe span lifecycle tracking in EventBusSpanObserver
    - finally-block span.end() ensuring spans always close
key_files:
  created:
    - kore-observability/build.gradle.kts
    - kore-observability/src/main/kotlin/dev/unityinflow/kore/observability/KoreTracer.kt
    - kore-observability/src/main/kotlin/dev/unityinflow/kore/observability/ObservableAgentRunner.kt
    - kore-observability/src/main/kotlin/dev/unityinflow/kore/observability/EventBusSpanObserver.kt
    - kore-observability/src/test/kotlin/dev/unityinflow/kore/observability/KoreTracerTest.kt
    - kore-observability/src/test/kotlin/dev/unityinflow/kore/observability/ObservableAgentRunnerTest.kt
  modified:
    - settings.gradle.kts (added kore-observability include)
    - gradle/libs.versions.toml (added otel-extension-kotlin 1.61.0, testcontainers 1.20.0, otel-api, micrometer-core entries)
decisions:
  - OTel kept out of kore-core entirely (D-10): all observability lives in kore-observability as a pure adapter
  - Decorator pattern chosen for ObservableAgentRunner (not subclass): AgentRunner has private scope, not extensible
  - compileOnly for otel-api and micrometer-core in kore-observability: consumers supply versions via Spring Boot 4 BOM
  - AgentCompleted leak guard added to EventBusSpanObserver (T-02-02 mitigation): closes any open spans if LLMCallCompleted is never received
metrics:
  duration: 3min
  completed: 2026-04-12
  tasks_completed: 2
  files_created: 6
  files_modified: 2
---

# Phase 02 Plan 01: kore-observability Module + KoreTracer + ObservableAgentRunner Summary

**One-liner:** kore-observability module with KoreTracer (withSpan helper using asContextElement for coroutine context propagation) and ObservableAgentRunner decorator emitting 3-level OTel span hierarchy (agent run → LLM call → tool use) driven by EventBus events.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | kore-observability module scaffold + KoreTracer | f486bd8 | settings.gradle.kts, libs.versions.toml, build.gradle.kts, KoreTracer.kt, KoreTracerTest.kt |
| 2 | ObservableAgentRunner with OTel 3-level span hierarchy | 40c7cb0 | ObservableAgentRunner.kt, EventBusSpanObserver.kt, ObservableAgentRunnerTest.kt |

## What Was Built

### kore-observability module
New Gradle module registered in `settings.gradle.kts`. Build configuration uses `compileOnly` for `opentelemetry-api` and `micrometer-core` since these are provided at runtime by the Spring Boot 4 BOM (via kore-spring). Full test deps include `opentelemetry-sdk-testing` for in-memory span capture without a real OTel collector.

### KoreTracer
Thin wrapper around `io.opentelemetry.api.trace.Tracer` providing the `withSpan()` suspend extension function. Key design: `setParent(Context.current())` ensures every span inherits the active trace context; `span.storeInContext(Context.current()).asContextElement()` wraps the coroutine context so OTel's ThreadLocal-backed context survives coroutine suspension points (Pitfall 3 / OBSV-04). `span.end()` in a `finally` block guarantees spans always close even on exceptions.

### KoreSpans + KoreAttrs constants
4 span name constants (AGENT_RUN, LLM_CALL, TOOL_USE, SKILL_ACTIVATE) and 10 attribute key constants (AGENT_ID, AGENT_NAME, LLM_MODEL, LLM_TOKENS_IN, LLM_TOKENS_OUT, LLM_BACKEND, LLM_DURATION_MS, TOOL_NAME, TOOL_MCP_SERVER, TOOL_DURATION_MS). All kore-namespaced per D-02/D-03/D-04/D-05 — no GenAI semantic conventions (D-06).

### ObservableAgentRunner
Decorator wrapping `AgentLoop` (not `AgentRunner` — AgentRunner has a private scope). Creates its own `CoroutineScope` with `Context.current().asContextElement()` injected at construction (D-08/D-09). The `run()` method opens a `kore.agent.run` root span, runs `loop.run(task)`, records the result type via `typeName()` extension, then closes the span. `typeName()` covers all 5 `AgentResult` variants exhaustively.

### EventBusSpanObserver
Subscribes to `EventBus.subscribe()` Flow and translates `LLMCallStarted/Completed` and `ToolCallStarted/Completed` events into child OTel spans. Uses `ConcurrentHashMap` for thread-safe span/time tracking. `AgentCompleted` event triggers a leak guard that closes any open spans for the agent (T-02-02 mitigation). OBSV-03 stub: `SkillActivated` handling deferred to Phase 3 when kore-skills emits the event.

## Verification Results

| Check | Result |
|-------|--------|
| `./gradlew :kore-observability:build` | PASS |
| `./gradlew :kore-core:dependencies --configuration runtimeClasspath \| grep opentelemetry` | CLEAN (no OTel) |
| `grep -r "opentelemetry" kore-core/src/` | CLEAN (no OTel) |
| Module files exist: KoreTracer.kt, ObservableAgentRunner.kt, EventBusSpanObserver.kt | PASS |
| KoreSpans defines 4 constants: AGENT_RUN, LLM_CALL, TOOL_USE, SKILL_ACTIVATE | PASS |
| KoreAttrs defines 10 attribute key constants | PASS (14 total `const val`s — 4 span names + 10 attrs) |
| `./gradlew :kore-observability:lintKotlin` | PASS |

## Deviations from Plan

### Auto-fixed Issues

None — plan executed exactly as written with one clarification:

**Cosmetic: class-signature lint fix on KoreTracer**
- Found during: Task 1 lintKotlin run
- Issue: ktlint rule `class-signature` required newline after opening parenthesis for single-param class
- Fix: Reformatted `class KoreTracer(val tracer: Tracer)` to multi-line form
- Files modified: KoreTracer.kt
- This is a ktlint formatting requirement, not a logic deviation

**Enhancement: AgentCompleted leak guard in EventBusSpanObserver (Rule 2)**
- Found during: Task 2 — reviewing T-02-02 threat (span leak if LLMCallCompleted never received)
- Issue: The threat model explicitly flags span map leak as a DoS concern; the plan's action section omits the AgentCompleted handler
- Fix: Added `AgentCompleted` handling in the `when` block — closes all open LLM and tool spans for the agent
- Files modified: EventBusSpanObserver.kt

## Known Stubs

| Stub | File | Line | Reason |
|------|------|------|--------|
| SkillActivated event handling | EventBusSpanObserver.kt | 23 (KDoc) | kore-skills module (Phase 3) does not exist yet. The OBSV-03 stub is intentional per plan. KoreSpans.SKILL_ACTIVATE constant is defined; handler will be added when kore-skills emits SkillActivated. |
| kore.tool.mcp_server = "" | EventBusSpanObserver.kt | 76 | AgentEvent.ToolCallStarted does not carry MCP server info yet. Will be wired when the event is extended in a future plan. |

## Threat Flags

No new threat surface introduced beyond what is documented in the plan's threat model (T-02-01 through T-02-04). The T-02-02 span leak mitigation was implemented inline as a Rule 2 auto-fix.

## Self-Check: PASSED
