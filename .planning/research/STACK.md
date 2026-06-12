# Technology Stack

**Project:** kore-runtime — v0.0.2 Hardening & Hierarchy (delta from v0.0.1)
**Researched:** 2026-06-12
**Confidence:** HIGH

> This document covers only the FOUR new capabilities in v0.0.2. The full v0.0.1 stack
> (Kotlin 2.3, Spring Boot 4.0.5, Ktor 3.2, Exposed 1.0, OTel, etc.) remains unchanged.
> Do not alter any version already pinned in `gradle/libs.versions.toml` unless noted below.

---

## Feature 1 — Real budget-breaker Adapter

### Artifact Coordinates (verified: Maven Central, 2026-06-12)

| Coordinate | Value |
|------------|-------|
| GroupId | `io.github.unityinflow` |
| ArtifactId | `budget-breaker` |
| Version | **0.0.1** |
| Published | Maven Central (Sonatype Central Portal) |
| Runtime deps | `kotlinx-coroutines-core-jvm:1.10.1`, `kotlin-stdlib:2.1.0` |

```kotlin
// kore-budget (new module) build.gradle.kts
implementation("io.github.unityinflow:budget-breaker:0.0.1")
```

The `0.0.1` artifact is the only published release. The local source tree shows
`version=0.1.0` in `gradle.properties` — that is the post-release snapshot, not yet
published. Gate on `0.0.1` only.

### budget-breaker Public API Surface (read from source, v0.0.1 tag)

Everything the adapter needs lives in package `io.github.unityinflow.budget`:

| Class / Function | Role |
|-----------------|------|
| `BudgetCircuitBreaker(defaultBudget, pricing, onSoftLimit)` | Top-level entry point. Holds one `TokenTracker` per in-flight agent. |
| `suspend fun BudgetCircuitBreaker.withBudget(agentId, budget, block)` | Wraps an agent run in a budget-tracked `coroutineScope`. Single concurrent call per `agentId` enforced — second call throws `IllegalArgumentException`. |
| `suspend fun BudgetScope.trackCall(promptTokens, completionTokens)` | Call after each LLM response. Checks soft/hard limits. Throws `BudgetHardLimitException` on hard limit breach. |
| `AgentBudget(model, hardLimitTokens, softLimitTokens)` | Budget configuration value object. Defaults: model=`claude-sonnet-4-6`, hard=100 000, soft=80 000. |
| `BudgetHardLimitException` | Thrown (not a sealed result) when hard limit exceeded. Caught by the adapter, mapped to `AgentResult.BudgetExceeded`. |
| `BudgetReport` | Data class with `totalTokens`, `estimatedCostUsd`, `hardLimitBreached`, `softLimitBreachCount`. Retrieve via `getReport(agentId)`. |
| `SharedFlow<BudgetEvent>` via `BudgetCircuitBreaker.events` | Reactive event stream: `CallTracked`, `SoftLimitReached`, `HardLimitExceeded`. Subscribe for dashboard integration. |

**Mismatch with kore's `BudgetEnforcer` port:**

kore's port has three methods — `recordUsage`, `checkBudget`, `getUsage`. budget-breaker does
NOT expose these directly; it uses a DSL-scope model (`withBudget { trackCall() }`).
The adapter must bridge the two:

- `recordUsage(agentId, usage)` → calls `scope.trackCall(usage.inputTokens.toLong(), usage.outputTokens.toLong())`. The adapter holds a `BudgetScope` per active `agentId` (stored in a `ConcurrentHashMap`). The scope is created when the first `recordUsage` arrives.
- `checkBudget(agentId)` → `true` while no `BudgetHardLimitException` has been thrown for this scope. The simplest implementation tracks a per-agent boolean flag set by catching `BudgetHardLimitException` from `trackCall`.
- `getUsage(agentId)` → `breaker.getReport(agentId)?.let { TokenUsage(it.promptTokens.toInt(), it.completionTokens.toInt()) } ?: TokenUsage(0, 0)`.

The `withBudget` scope wrapper is **not usable** from the `BudgetEnforcer` port because the port's `recordUsage` / `checkBudget` are called from inside `AgentLoop.runLoop`, not from a wrapping block. The adapter uses `BudgetCircuitBreaker` as a tracker only (create `TokenTracker` directly, or use `BudgetCircuitBreaker.withBudget` wrapping at the `AgentRunner` level — see Architecture section below).

**Recommended adapter boundary:** Create a new module `kore-budget` (mirrors `kore-kafka`, `kore-rabbitmq` as opt-in modules). `BudgetBreakerAdapter` implements `BudgetEnforcer`, holds a `BudgetCircuitBreaker`, and delegates. kore-spring gains a `BudgetBreakerAutoConfiguration` inner class gated on `@ConditionalOnClass(name=["io.github.unityinflow.budget.BudgetCircuitBreaker"])` + `@ConditionalOnMissingBean(BudgetEnforcer::class)`. `InMemoryBudgetEnforcer` remains as the zero-dep default when budget-breaker is absent.

**Version constraint:** budget-breaker 0.0.1 uses `kotlinx-coroutines-core:1.10.1`. kore uses
`1.10.2`. The coroutines library guarantees patch-level backward compatibility; no shading or
exclusion needed. Gradle resolves to the higher version (`1.10.2`) by default.

---

## Feature 2 — Hierarchical Agents (Parent Spawns Children)

### Coroutine Primitives — What to Use

`AgentRunner` already uses `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
`SupervisorJob` is already the right choice at the runner level: one agent failure does not
cancel sibling agents. No version change required — all primitives are in `kotlinx-coroutines-core:1.10.2`.

The parent/child agent hierarchy requires a NEW API surface on top of what v0.0.1 shipped:

| Primitive | Where | Why |
|-----------|-------|-----|
| `CoroutineScope` passed as `AgentContext` field or constructor arg | `AgentLoop` / new `HierarchicalAgentRunner` | Child agents must be launched in the **parent's** coroutine scope — not a new independent scope. Cancelling the parent's `Job` then propagates automatically to all children (structured concurrency). |
| `Job` (regular, NOT `SupervisorJob`) for child agents | Child launch site | A child agent failure should propagate to the parent. Use `Job`, not `SupervisorJob`, when launching children from within a parent agent loop. If isolation is desired, use `supervisorScope { }` at the call site instead. |
| `supervisorScope { }` (function) | Optional: parent launching independent workers | When a parent wants to spawn children that can each fail independently without cancelling siblings, wrap their launches in `supervisorScope { }`. This is NOT the default — the default parent/child model uses plain `Job`. |
| `coroutineScope { }` (function) | Tool dispatch (already used in `AgentLoop`) | Already present. Cancels all children on the first failure. Used for parallel tool dispatch. |
| `Deferred<AgentResult>` via `async { }` | Child agent handle | `AgentRunner.run()` already returns `Deferred<AgentResult>`. Parent agent can `await()` results. |

**Concrete pattern — passing scope to child:**

```kotlin
// Parent's AgentLoop receives a CoroutineScope for sub-agent spawning.
// The scope IS the parent coroutine's scope — cancellation propagates down.
class AgentLoop(
    // existing params...
    private val childAgentRunner: AgentRunner? = null,   // null = leaf agent
)

// Inside runLoop, parent spawns child:
val childResult: AgentResult = childAgentRunner
    ?.run(childTask)
    ?.await()
    ?: AgentResult.ToolError("no child runner", RuntimeException("no child runner"))
```

**What NOT to do:** Do not create a new `CoroutineScope(SupervisorJob())` inside the parent agent loop for child agents. That would be an unstructured scope — cancelling the parent would not cancel children. The child runner's scope must be a child `Job` of the parent coroutine context.

**`Job` vs `SupervisorJob` decision table:**

| Scenario | Use |
|----------|-----|
| `AgentRunner` top-level scope (peer agents) | `SupervisorJob` — peer failures must not cancel each other (already correct in v0.0.1) |
| Parent agent spawning child agents (hierarchy) | Plain `Job` as child of parent scope — parent cancellation cascades down |
| Parent launching independent workers that should isolate | `supervisorScope { }` at the spawn site |

No new library dependencies required for hierarchical agents. Everything is in `kotlinx-coroutines-core:1.10.2`.

---

## Feature 3 — OBSV-03: OTel Span on Skill Activation

### What Already Exists (do not re-add)

- `opentelemetry-api:1.49.0` — `compileOnly` in kore-core, `compileOnly` in kore-observability
- `opentelemetry-extension-kotlin:1.61.0` — `implementation` in kore-observability; provides `asContextElement()` for coroutine context propagation
- `KoreTracer.withSpan(name, kind, attrs, block)` — already implemented in `kore-observability/KoreTracer.kt`
- `KoreSpans.SKILL_ACTIVATE = "kore.skill.activate"` — constant already defined
- `AgentLoop.tracer` nullable `Tracer?` parameter — already in constructor; the loop already creates a `kore.skill.activate` span around `skillRegistry.activateFor()`

**Finding: the span already fires in `AgentLoop.runLoop` (lines 97–106 of AgentLoop.kt).** OBSV-03 is partially implemented. What's missing:

1. `AgentEvent.SkillActivated` event is not emitted. The `EventBusSpanObserver` has a comment at line 25: "OBSV-03 stub: SkillActivated event handling will be added in Phase 3 when kore-skills emits the event."
2. `AgentEvent` sealed class has no `SkillActivated` variant.
3. The span in `AgentLoop` is created/ended inline (not via `KoreTracer.withSpan`), and does not set skill-identifying attributes.

### Changes Needed

**In kore-core (`AgentEvent.kt`):** Add `SkillActivated` variant:

```kotlin
@Serializable
@SerialName("SkillActivated")
data class SkillActivated(
    val agentId: String,
    val skillNames: List<String>,
) : AgentEvent()
```

No new library dependencies — `@Serializable` is already `compileOnly`.

**In kore-core (`AgentLoop.kt`):** Upgrade the existing inline span to emit the event and use skill name attributes. The `tracer` parameter already provides `Tracer?`; use `KoreTracer` (which lives in kore-observability) is NOT possible from kore-core (that would add a runtime dependency). Use the raw OTel API directly as already done:

```kotlin
val span = tracer
    ?.spanBuilder(KoreSpans.SKILL_ACTIVATE)   // wait — KoreSpans lives in kore-observability
    ?.startSpan()
```

**Problem:** `KoreSpans` lives in `kore-observability`, not `kore-core`. The existing code references `KoreSpans` from within `AgentLoop` (kore-core) — this is a compile-time cross-module reference that works only because kore-core already has `compileOnly("io.opentelemetry:opentelemetry-api")` but does NOT have `compileOnly(project(":kore-observability"))`.

**Confirmed by re-reading the code:** `AgentLoop.kt` line 97 uses a string literal `"kore.skill.activate"` directly, not `KoreSpans.SKILL_ACTIVATE`. The span constant approach is fine.

**In kore-observability (`EventBusSpanObserver.kt`):** Add `SkillActivated` branch:

```kotlin
is AgentEvent.SkillActivated -> {
    tracer.withSpan(
        name = KoreSpans.SKILL_ACTIVATE,
        attrs = mapOf(KoreAttrs.AGENT_ID to event.agentId),
    ) { span ->
        event.skillNames.forEachIndexed { i, name ->
            span.setAttribute("kore.skill.name.$i", name)
        }
    }
}
```

`KoreTracer.withSpan` already exists and handles `asContextElement()` for coroutine context propagation. No new API surface or dependency needed.

**Attribute for skill name:** Not in the existing `KoreAttrs` object. Add:

```kotlin
const val SKILL_NAME = "kore.skill.name"
```

### OTel API Needed (no version change)

The existing `opentelemetry-api:1.49.0` (already `compileOnly` in kore-observability) provides all required types:
- `Tracer.spanBuilder(name)` → `SpanBuilder.startSpan()` — already used
- `Span.setAttribute(key, value)` — already used
- `Span.end()` — already used

The `opentelemetry-extension-kotlin:1.61.0` provides `asContextElement()` — already `implementation` in kore-observability. No version bump needed.

**No new OTel dependencies required for OBSV-03.**

---

## Feature 4 — kore-storage `integrationTest` Gradle Task

### Recommended Pattern: `jvm-test-suite` Plugin (Gradle 9.4.1)

The `jvm-test-suite` plugin (`id("jvm-test-suite")`) is incubating but stable in Gradle 9.x.
It provides a first-class `testing { suites { } }` DSL that is the idiomatic Gradle 9 way to
model integration test source sets. The alternative (manual `sourceSets.create("integrationTest")` +
manual task wiring) requires ~30 lines of boilerplate versus ~10 with the plugin.

**Recommended configuration for `kore-storage/build.gradle.kts`:**

```kotlin
plugins {
    // ... existing plugins ...
    `jvm-test-suite`
}

testing {
    suites {
        // Default "test" suite — keep existing excludeTags("integration") behavior
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
        }

        // New integration test suite — runs Testcontainers tests
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
            // Shares the main source set classpath (project() = the compiled main classes)
            dependencies {
                implementation(project())
                // Testcontainers already in the test config — must redeclare for this suite
                implementation(libs.testcontainers.postgresql)
                implementation(libs.testcontainers.junit5)
                implementation(libs.coroutines.test)
                implementation(libs.kotest.assertions)
                implementation(libs.serialization.core)
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(test)   // run unit tests first
                        useJUnitPlatform {
                            includeTags("integration")   // only @Tag("integration") tests
                        }
                    }
                }
            }
        }
    }
}
```

**Key behavior:**
- The `integrationTest` suite creates its own source set (`src/integrationTest/kotlin`). Since the existing integration tests are in `src/test/kotlin` with `@Tag("integration")`, you do NOT need to move files — configure `testTask` to filter by tag instead. The source set can be configured to point at `src/test/kotlin` (via `sources.kotlin.srcDirs`) or simply keep the tests in `src/test` and use tag filtering.
- `jvm-test-suite` does NOT auto-attach to `check`. You must explicitly wire it if needed: `tasks.named("check") { dependsOn(testing.suites.named("integrationTest")) }`. For CI, add it as a separate step instead.
- The `test` task retains `excludeTags("integration")` — unit tests stay fast.

**Simpler alternative (no source set split):** Register a manual Gradle task of type `Test` that reuses the existing `test` source set but filters by tag. This is fewer moving parts and does not require the incubating `jvm-test-suite` plugin:

```kotlin
val integrationTest by tasks.registering(Test::class) {
    description = "Runs Testcontainers integration tests (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}
```

**Recommendation: use the manual `Test` task approach.** Reasons:
1. No file migration — existing test files stay in `src/test/kotlin`.
2. No incubating plugin risk.
3. Zero additional dependency declarations — reuses the existing `test` classpath.
4. Gradle 9.4.1 compatible — `tasks.registering(Test::class)` is stable API.
5. The only thing that changes in `kore-storage/build.gradle.kts` is: (a) remove `excludeTags("integration")` from the existing `tasks.test` block (or keep it — it is harmless since `integrationTest` filters by `includeTags`), and (b) add the `integrationTest` task registration.

### CI Step Addition

Add to `.github/workflows/ci.yml` in the `build` job after the `Test` step:

```yaml
- name: Integration Tests (kore-storage)
  run: ./gradlew :kore-storage:integrationTest
```

This step requires Docker on the runner to spin up PostgreSQL via Testcontainers.
`arc-runner-unityinflow` (Hetzner x64) has Docker available. The `orangepi-runner` (ARM64)
does not need this step — the `arm64-build` job can skip it.

**No new Gradle plugin version required.** `Test` task type is part of `gradle-core` in 9.4.1.

---

## Version Compatibility Summary (v0.0.2 additions only)

| Dependency | Version | Scope | Module | Notes |
|------------|---------|-------|--------|-------|
| `io.github.unityinflow:budget-breaker` | 0.0.1 | `implementation` | kore-budget (new) | Transitive: coroutines 1.10.1 (Gradle upgrades to 1.10.2) |
| All other dependencies | unchanged | — | — | No version bumps needed |

---

## What NOT to Add

| Avoid | Why | What to Do Instead |
|-------|-----|--------------------|
| `budget-breaker-spring-boot-starter` (if/when published) | Not yet published; Spring Boot starter for budget-breaker is pending. Gate auto-config on `@ConditionalOnClass` against `BudgetCircuitBreaker` (the core class) so the starter can add value later without changing kore-spring. | Use `BudgetCircuitBreaker` from the core artifact directly in `BudgetBreakerAdapter`. |
| Any new OTel dependency for OBSV-03 | All required OTel API is already on the classpath via existing `compileOnly` declarations. Adding `opentelemetry-sdk` at runtime would conflict with the host application's SDK. | Use existing `opentelemetry-api` compileOnly + `opentelemetry-extension-kotlin` already in kore-observability. |
| `jvm-test-suite` incubating plugin for integrationTest | Requires source set migration; higher churn for no gain over manual `Test` task. | Manual `tasks.registering(Test::class)` with `includeTags("integration")`. |
| New `CoroutineScope` with `SupervisorJob` for child agents | Would break structured concurrency — parent cancellation would not propagate. | Pass parent coroutine scope to child runner; child uses plain `Job`. |
| Upgrading `kotlinx-coroutines` to 1.10.x beyond 1.10.2 | No feature needed beyond what 1.10.2 provides. | Pin at 1.10.2 until a specific fix is needed. |

---

## Module Map for v0.0.2

| New/Changed | Module | What |
|-------------|--------|------|
| New module | `kore-budget` | `BudgetBreakerAdapter` implementing `BudgetEnforcer` port; `implementation("io.github.unityinflow:budget-breaker:0.0.1")` |
| Changed | `kore-core` | Add `AgentEvent.SkillActivated`; wire `eventBus.emit(SkillActivated)` after skill activation in `AgentLoop`; add `childAgentRunner` optional param |
| Changed | `kore-observability` | Add `SkillActivated` branch in `EventBusSpanObserver`; add `KoreAttrs.SKILL_NAME` |
| Changed | `kore-spring` | Add `BudgetBreakerAutoConfiguration` inner class; `compileOnly(project(":kore-budget"))` |
| Changed | `kore-storage` | Add `integrationTest` task; keep existing `@Tag("integration")` annotations |
| Changed | `.github/workflows/ci.yml` | Add `integrationTest` step for `kore-storage` in `build` job |

---

## Sources

- `io.github.unityinflow:budget-breaker:0.0.1` coordinates — [Maven Central](https://central.sonatype.com/artifact/io.github.unityinflow/budget-breaker/0.0.1) — HIGH confidence (verified)
- budget-breaker source code — local at `../05-budget-breaker/` (tag `v0.0.1`) — HIGH confidence (source of truth)
- `kotlinx.coroutines` SupervisorJob / Job hierarchy — [official API docs](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-scope/) — HIGH confidence
- Gradle `jvm-test-suite` plugin — [official userguide](https://docs.gradle.org/current/userguide/jvm_test_suite_plugin.html) — HIGH confidence
- kore-core `AgentLoop.kt`, `AgentEvent.kt`, `BudgetEnforcer.kt` — read directly from source — HIGH confidence
- kore-observability `KoreTracer.kt`, `EventBusSpanObserver.kt` — read directly from source — HIGH confidence
- `opentelemetry-extension-kotlin:1.61.0` `asContextElement()` — already in use in kore-observability — HIGH confidence

---
*Stack research for: kore-runtime v0.0.2 Hardening & Hierarchy (delta only)*
*Researched: 2026-06-12*
