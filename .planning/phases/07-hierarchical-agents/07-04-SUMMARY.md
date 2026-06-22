---
phase: 07-hierarchical-agents
plan: 04
subsystem: kore-spring
tags: [hierarchical-agents, spring-config, configuration-properties, max-depth]
requires:
  - "07-03: AgentBuilder.maxDepth(n) DSL method (D-08)"
provides:
  - "KoreProperties.HierarchyProperties(maxDepth=5) + hierarchy field — kore.hierarchy.max-depth (D-08 / HIER-03)"
  - "KoreAgentFactory(maxDepth: Int = 5) — pre-wires maxDepth() into every built agent before block()"
  - "KoreAutoConfiguration.koreAgentFactory threads properties.hierarchy.maxDepth into the factory bean"
affects:
  - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreProperties.kt
  - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAgentFactory.kt
  - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAutoConfiguration.kt
  - kore-spring/src/test/kotlin/io/github/unityinflow/kore/spring/KoreHierarchyPropertiesTest.kt
  - kore-spring/build.gradle.kts
tech-stack:
  added: []
  patterns:
    - "Nested @ConfigurationProperties data class mirroring BudgetProperties shape (D-08)"
    - "Factory pre-wire before block() so user maxDepth(n) override wins (mirrors port-override precedence)"
    - "ApplicationContextRunner definition-level assertions — no LLM backend constructed, no socket opened"
key-files:
  created:
    - kore-spring/src/test/kotlin/io/github/unityinflow/kore/spring/KoreHierarchyPropertiesTest.kt
  modified:
    - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreProperties.kt
    - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAgentFactory.kt
    - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAutoConfiguration.kt
    - kore-spring/build.gradle.kts
decisions:
  - "KoreAgentFactory.maxDepth exposed as a public val (not private) so the Spring context test can assert the threaded value at the bean-definition level — no agent run, no LLM backend, no socket"
  - "maxDepth: Int param defaulted to 5 on KoreAgentFactory so existing direct callers (and any non-Spring construction) keep prior behavior (criterion #5)"
  - "Rule 1 fix: added opentelemetry-api as testImplementation to kore-spring — Plan 07-03's buildLoop() now references Tracer unconditionally, which NoClassDefFoundError'd the @SpringBootTest integration tests on kore-spring's otel-less test classpath"
metrics:
  duration: 9min
  completed: 2026-06-22
  tasks: 2
  files: 5
requirements: [HIER-03]
---

# Phase 07 Plan 04: kore.hierarchy.max-depth Spring Config Summary

Surfaced the hierarchical-agent depth ceiling through Spring config (D-08 / HIER-03). `KoreProperties` gained a nested `HierarchyProperties(maxDepth = 5)` mirroring `BudgetProperties`; `KoreAgentFactory` now pre-wires `maxDepth(...)` into every agent it builds (before `block()`, so a user `maxDepth(n)` still wins); `KoreAutoConfiguration` threads `properties.hierarchy.maxDepth` into the factory bean. An `ApplicationContextRunner` test proves default 5, override to 9, and that the value reaches the factory — all at the definition level, opening no socket.

## What Was Built

### Task 1 — KoreProperties.hierarchy + factory/auto-config threading (`955b067`)
- **`KoreProperties`**: added `hierarchy: HierarchyProperties = HierarchyProperties()` to the root data-class ctor (after `eventBus`) and a nested `data class HierarchyProperties(val maxDepth: Int = 5)` with KDoc tying it to `kore.hierarchy.max-depth`, AgentTool's depth refusal (T-7-01), the factory pre-wire, and the extensibility note (D-01). Shape mirrors `BudgetProperties` exactly (D-08).
- **`KoreAgentFactory`**: added a public `val maxDepth: Int = 5` ctor param and an `maxDepth(this@KoreAgentFactory.maxDepth)` call alongside the existing `eventBus()/auditLog()/skillRegistry()` pre-wiring, BEFORE `block()` — so a user `maxDepth(n)` inside the block runs after and wins (same precedence as the port overrides, documented in the updated KDoc). Default 5 preserves existing callers.
- **`KoreAutoConfiguration.koreAgentFactory`**: added a `properties: KoreProperties` parameter (same as `inMemoryBudgetEnforcer`) and passes `maxDepth = properties.hierarchy.maxDepth` into the factory. `@ConditionalOnMissingBean(KoreAgentFactory::class)` retained. No `var`, no `!!`.

### Task 2 — KoreHierarchyPropertiesTest + otel test-classpath fix (`8434337`)
- **`KoreHierarchyPropertiesTest`** (4 cases, NEW): `ApplicationContextRunner` over `KoreAutoConfiguration` with `kore.dashboard.enabled=false` (mirrors the Phase 6 budget auto-config test shape). Cases: default `KoreProperties.hierarchy.maxDepth == 5`; `kore.hierarchy.max-depth=9` binds to 9; the always-present `KoreAgentFactory` bean carries `maxDepth == 5` by default and `== 9` under the override. All assertions are definition-level (`getBean(...).maxDepth`) — the factory's `agent { }` is never invoked, so no LLM backend is constructed and no socket opens. Kotest `shouldBe`.
- **`kore-spring/build.gradle.kts`**: added `testImplementation("io.opentelemetry:opentelemetry-api:1.49.0")` (see Deviations — Rule 1).

## Verification

- `./gradlew :kore-spring:compileKotlin` — clean (Task 1 gate).
- `./gradlew :kore-spring:test --tests "*KoreHierarchyPropertiesTest*"` — green (Task 2 gate; 4 new cases pass).
- `./gradlew :kore-spring:test` — full suite green (37 tests; the 10 pre-existing `@SpringBootTest` failures caused by the Plan 07-03 Tracer regression are now resolved by the Rule 1 fix).
- `./gradlew :kore-spring:lintKotlin` — passes (no `var`, no `!!`).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] kore-spring `@SpringBootTest` integration tests broken by Plan 07-03 Tracer reference**
- **Found during:** Task 2 (running the full `:kore-spring:test` verification gate).
- **Issue:** Plan 07-03 made `AgentBuilder.buildLoop()` thread a tracer cell (`Array<Tracer?>`) into `AgentLoop(tracer = ...)` unconditionally. `opentelemetry-api` is `compileOnly` on both kore-core and kore-spring; kore-core declares it as `testImplementation` too, but kore-spring did not. So any kore-spring `@SpringBootTest` whose `@Bean` agent calls `build()` (which all of `KoreIntegrationTest`, `KoreAutoConfigurationSpringContextTest`, `DashboardDegradedModeSpringTest` do via their test app's `testAgent`) threw `NoClassDefFoundError: io/opentelemetry/api/trace/Tracer` at context load — 10 failing tests. Plan 07-03's SUMMARY only ran `:kore-core:test`, so the kore-spring regression went unnoticed.
- **Fix:** Added `testImplementation("io.opentelemetry:opentelemetry-api:1.49.0")` to `kore-spring/build.gradle.kts`, mirroring kore-core's existing test dependency. Main-classpath behavior is unchanged (still `compileOnly`; the host app supplies otel via the Spring Boot 4 BOM, gated by `@ConditionalOnClass`).
- **Files modified:** `kore-spring/build.gradle.kts`
- **Commit:** `8434337`
- **Scope note:** This is independent of the maxDepth/hierarchy feature itself, but it blocked this plan's `<verification>` gate (`:kore-spring:test` green), so it falls under Rule 1/Rule 3 (auto-fix a blocking bug). No production code or behavior changed — test-classpath only.

## Known Stubs

None introduced.

## Notes for Later Plans

- The `kore.hierarchy.*` namespace is now established and extensible — future per-agent depth overrides (`agents: Map<String, ...>`) can be added to `HierarchyProperties` without touching the global `maxDepth` contract.
- D-12 tracer inheritance into `KoreAgentFactory` (passing a wired tracer through `inheritTracer`) is still open — Plan 07-03's note remains valid; this plan only wired the depth ceiling, not the tracer bean. The Rule 1 fix above only puts otel on the TEST classpath; production tracer wiring into the factory is a separate piece of work.

## Self-Check: PASSED

All 5 created/modified files exist on disk; both task commits (`955b067`, `8434337`) present in git history.
