# Phase 5: CI Baseline & Skill Observability - Pattern Map

**Mapped:** 2026-06-14
**Files analyzed:** 11 (2 new + 9 modified)
**Analogs found:** 11 / 11

All analogs are **in-repo** — this is a hardening phase that wires existing patterns. Every change site has a sibling already in the codebase. No `RESEARCH.md`-only fallbacks are needed.

> **Highest-value correction carried from RESEARCH.md (Pitfall 1):** all three observers end their `when (event)` in `else -> Unit`. Adding `AgentEvent.SkillActivated` compiles silently and the event is dropped. The compiler will NOT surface the missing branches. The planner MUST add explicit `is AgentEvent.SkillActivated ->` branches as deliberate tasks, placed BEFORE the `else -> Unit`.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `kore-core/.../port/SkillRegistry.kt` → `ActivatedSkill` data class (NEW, same file) | model (value type) | transform | sibling subclasses in `AgentEvent.kt` (data classes) | role-match |
| `kore-core/.../AgentEvent.kt` → `SkillActivated` subclass (NEW) | model (event) | event-driven / pub-sub | existing `AgentEvent.LLMCallCompleted` subclass | exact |
| `kore-core/.../port/SkillRegistry.kt` (MODIFY port + `NoOpSkillRegistry`) | port + adapter | transform | itself (return-type-only change) | exact |
| `kore-skills/.../SkillRegistryAdapter.kt` (MODIFY) | adapter | transform | itself (`.map { it.prompt }` → `.map { ActivatedSkill(...) }`) | exact |
| `kore-core/.../AgentLoop.kt` runLoop span (MODIFY) | service (orchestrator) | event-driven + transform | `EventBusSpanObserver` raw-`spanBuilder` + attr code | role-match |
| `kore-observability/.../KoreTracer.kt` (MODIFY `KoreAttrs` + `withSpan`) | utility (tracing helper) | transform | existing `KoreAttrs` LLM/Tool group + `withSpan` `when` | exact |
| `kore-observability/.../EventBusMetricsObserver.kt` (MODIFY) | observer (adapter) | event-driven / pub-sub | its own `is AgentEvent.LLMCallCompleted ->` branch | exact |
| `kore-observability/.../EventBusSpanObserver.kt` (MODIFY) | observer (adapter) | event-driven / pub-sub | its own `else -> Unit` + branch shape | exact |
| `kore-dashboard/.../EventBusDashboardObserver.kt` (MODIFY/verify) | observer (adapter) | event-driven / pub-sub | its own `when` (already has `else -> Unit`) | exact |
| `kore-observability/.../KoreMetrics.kt` (MODIFY — new counter, per A3) | service (metrics factory) | transform | existing `llmCallCounter` / `tokensUsedCounter` builders | exact |
| `kore-storage/build.gradle.kts` (MODIFY) | config (build) | batch | existing `tasks.test { excludeTags("integration") }` | exact |
| `.github/workflows/ci.yml` (MODIFY) | config (CI) | batch | existing `arm64-build` job (`needs: build`, runner labels) | exact |

## Pattern Assignments

### NEW: `ActivatedSkill` data class — `kore-core/.../port/SkillRegistry.kt` (model, transform)

**Analog:** `AgentEvent` subclasses (plain Kotlin data classes); home is alongside the port per D-02 / Open-Q resolution.

**Pattern to copy — minimal stdlib-only data class (D-02: fields `name` + `prompt` ONLY):**
```kotlin
// Same file as SkillRegistry, package io.github.unityinflow.kore.core.port
// NO @Serializable — ActivatedSkill does NOT cross the bus (only skillNames: List<String> does, D-06).
// stdlib-only to preserve kore-core's zero-runtime-dep rule.
data class ActivatedSkill(
    val name: String,
    val prompt: String,
)
```
Do NOT add description/version/trigger (deferred). KDoc should note: `prompt` is consumed by `AgentLoop` for System-message injection; `name` feeds span/event attributes.

---

### NEW: `AgentEvent.SkillActivated` — `kore-core/.../AgentEvent.kt` (model, event-driven)

**Analog:** `AgentEvent.LLMCallCompleted` (lines 37-42) and `AgentEvent.ToolCallCompleted` (lines 51-57) — exact shape to mirror.

**Existing pattern (every subclass is `@Serializable` + `@SerialName` data class):**
```kotlin
@Serializable
@SerialName("LLMCallCompleted")
data class LLMCallCompleted(
    val agentId: String,
    val tokenUsage: TokenUsage,
) : AgentEvent()
```

**New subclass to add (D-05/D-06 — prompts excluded; `List<String>` needs no extra annotations):**
```kotlin
@Serializable
@SerialName("SkillActivated")
data class SkillActivated(
    val agentId: String,
    val skillNames: List<String>,
    val durationMs: Long,
) : AgentEvent()
```
File header KDoc (lines 8-18) already documents the `@JsonClassDiscriminator("type")` wire contract and the `compileOnly` serialization rule — `SkillActivated` inherits both automatically. Extend `AgentEventSerializationTest` with a round-trip case (Validation map / D-06).

---

### MODIFY: `SkillRegistry.kt` port + `NoOpSkillRegistry` (port + adapter, transform)

**Analog:** itself — type-only breaking change (D-01, no compat shim; only two in-repo impls).

**Current (lines 26-29, 40-43):**
```kotlin
suspend fun activateFor(
    taskContent: String,
    availableTools: List<String>,
): List<String>           // ← change return type

// NoOpSkillRegistry:
): List<String> = emptyList()   // ← type-only change, body unchanged
```

**Target:** both return `List<ActivatedSkill>`. `NoOpSkillRegistry` body stays `= emptyList()`. Update the interface KDoc that currently says "Return the prompts" → "Return the activated skills (name + prompt)".

---

### MODIFY: `SkillRegistryAdapter.kt` (adapter, transform)

**Analog:** itself (lines 32-45) — the only real implementation. `SkillYamlDef` already carries `name` (line 16) and `prompt` (line 20), so the wrap is direct.

**Current terminal map (line 45):**
```kotlin
        }.map { it.prompt }
```

**Target (wrap name + prompt; return type → `List<ActivatedSkill>`):**
```kotlin
        }.map { ActivatedSkill(name = it.name, prompt = it.prompt) }
```
Add `import io.github.unityinflow.kore.core.port.ActivatedSkill`. Update the `override fun activateFor(...): List<String>` signature to `List<ActivatedSkill>`.

---

### MODIFY: `AgentLoop.kt` runLoop span (service, event-driven + transform)

**Analog for the raw-`Tracer` span + attribute code:** `EventBusSpanObserver` (lines 44-61) — it uses `tracer.tracer.spanBuilder(...).setParent(Context.current())...startSpan()` then `setAttribute(KoreAttrs.X, value)` and `end()`. `AgentLoop` holds a nullable raw `Tracer` (field at line 46), so it mirrors this directly on the `Span` (no `KoreTracer.withSpan` routing — see Shared Patterns).

**Current span + activation block (lines 96-115) to replace:**
```kotlin
val userMessage = history.first { it.role == ConversationMessage.Role.User }
val span = tracer?.spanBuilder("kore.skill.activate")?.startSpan()
val activatedPrompts =
    try {
        skillRegistry.activateFor(
            taskContent = userMessage.content,
            availableTools = toolDefs.map { it.name },
        )
    } finally {
        span?.end()
    }
if (activatedPrompts.isNotEmpty()) {
    history.add(
        0,
        ConversationMessage(
            role = ConversationMessage.Role.System,
            content = activatedPrompts.joinToString("\n\n"),
        ),
    )
}
```

**Changes required (D-03/D-04/D-07; RESEARCH Pattern 2 + Pitfall 5):**
- Add `setParent(Context.current())` to the span builder (OBSV-03 parenting; `EventBusSpanObserver` line 47 is the exact precedent).
- Measure duration with `System.nanoTime()` around the `activateFor` call (D / Anti-pattern: never `currentTimeMillis` for duration). `EventBusSpanObserver` uses `currentTimeMillis` for wall-clock start/stop — do NOT copy that for the skill duration; use `nanoTime`.
- `activateFor` now returns `List<ActivatedSkill>`; consume `.prompt` for injection (`activated.joinToString("\n\n") { it.prompt }`) and `.name` for attrs/event.
- Set 3 attributes on the span in the `finally` (keep span-before-call + end-in-finally per Pitfall 5 so the span ALWAYS emits even if `activateFor` throws):
  ```kotlin
  import io.opentelemetry.api.common.AttributeKey
  import io.opentelemetry.context.Context
  // ...
  span?.apply {
      setAttribute(AttributeKey.stringArrayKey("kore.skill.names"), names)        // names: List<String>
      setAttribute(AttributeKey.longKey("kore.skill.count"), names.size.toLong())
      setAttribute(AttributeKey.longKey("kore.skill.duration_ms"), durMs)
      end()
  }
  ```
- Use the literal `"kore.skill.activate"` (already hardcoded today) or a kore-core-local `const` (Open-Q 1, low stakes). kore-core CANNOT import `KoreSpans` from kore-observability — `KoreSpans.SKILL_ACTIVATE` (KoreTracer.kt line 17) stays the observability-side mirror only.
- Emit the event ONLY when `≥1` matched (D-07 asymmetry — document the asymmetry in a comment): inside the existing `if (activated.isNotEmpty()) { ... }` block, after the System-message inject, add:
  ```kotlin
  eventBus.emit(AgentEvent.SkillActivated(agentId = agentId, skillNames = names, durationMs = durMs))
  ```
  This mirrors the existing `eventBus.emit(AgentEvent.LLMCallStarted(...))` call style at line 126.
- Restructure to avoid `var` (CLAUDE.md): research suggests assigning the result to a `val` and computing `names`/`durMs` for use in the `finally` (RESEARCH Pattern 2 implementation note).

---

### MODIFY: `KoreTracer.kt` — `KoreAttrs` keys + `withSpan` list branch (utility, transform)

**Analog (same file):** the existing per-domain `KoreAttrs` groups (lines 22-36) and the `withSpan` `when (value)` dispatch (lines 67-75).

**KoreAttrs — add a `// Skill` group following the LLM/Tool shape (lines 26-36):**
```kotlin
    // Skill (D-03)
    const val SKILL_NAMES = "kore.skill.names"
    const val SKILL_COUNT = "kore.skill.count"
    const val SKILL_DURATION_MS = "kore.skill.duration_ms"
```

**`withSpan` dispatch — current (lines 67-75) has NO `else`, silently drops unmatched types:**
```kotlin
attrs.forEach { (key, value) ->
    when (value) {
        is String -> span.setAttribute(key, value)
        is Long -> span.setAttribute(key, value)
        is Int -> span.setAttribute(key, value.toLong())
        is Double -> span.setAttribute(key, value)
        is Boolean -> span.setAttribute(key, value)
    }
}
```

**Add the `List<String>` (string-array) branch (D-03; RESEARCH Pattern 2 — use `filterIsInstance` to avoid `UNCHECKED_CAST`/`!!`, Pitfall 4):**
```kotlin
        is List<*> -> {
            val strings = value.filterIsInstance<String>()
            span.setAttribute(AttributeKey.stringArrayKey(key), strings)
        }
```
Add `import io.opentelemetry.api.common.AttributeKey`. Extend `KoreTracerTest` with a string-array attr assertion (Validation map).

---

### MODIFY: `EventBusMetricsObserver.kt` (observer, event-driven)

**Analog (same file):** the `is AgentEvent.LLMCallCompleted ->` branch (lines 68-78) — agentId → name lookup, then per-event counter increments. The `agentNames` map + `agentNameResolver` infra (lines 35, 39, 54) is already in place.

**Existing branch to mirror (lines 68-78):**
```kotlin
is AgentEvent.LLMCallCompleted -> {
    val agentName = agentNames[event.agentId] ?: "unknown"
    metrics.llmCallCounter(agentName, model = "unknown", backend = "unknown").increment()
    metrics
        .tokensUsedCounter(agentName, model = "unknown", direction = "in")
        .increment(event.tokenUsage.inputTokens.toDouble())
    ...
}
```

**Add BEFORE the `else -> Unit` (line 82) — D-08:**
```kotlin
is AgentEvent.SkillActivated -> {
    val agentName = agentNames[event.agentId] ?: "unknown"
    event.skillNames.forEach { skillName ->
        metrics.skillsActivatedCounter(agentName, skillName).increment()
    }
    // record duration — see KoreMetrics change (A3 / Open-Q 2)
}
```
Extend `EventBusMetricsObserverTest` using the established `backgroundScope` + `yield()` + `runCurrent()` idiom and a `SimpleMeterRegistry` assertion (Validation map; Pitfall 1 — add a test that proves the counter moved).

---

### MODIFY: `EventBusSpanObserver.kt` (observer, event-driven)

**Analog (same file):** its `else -> Unit` (line 103) and the branch structure. Note the **stale KDoc** at lines 23-24 ("SkillActivated event handling will be added in Phase 3") — this phase fulfills/supersedes it; update the comment.

**Add BEFORE the `else -> Unit` (line 103) — D-08 explicit no-op:**
```kotlin
is AgentEvent.SkillActivated ->
    Unit // explicit no-op (D-08): the real kore.skill.activate span is created
         // in-process by AgentLoop. Synthesizing a second span here would duplicate
         // it in the default single-JVM topology, and this observer's Started/Completed
         // pair model does not fit a single instantaneous event.
```

---

### MODIFY: `EventBusDashboardObserver.kt` (observer, event-driven) — verify/compiles

**Analog (same file):** its `when` already ends in `else -> Unit` (line 88), so `SkillActivated` is absorbed and it **compiles unchanged** (kore-dashboard, lives in a separate module). Per D-08, optionally add an explicit `is AgentEvent.SkillActivated -> Unit` before line 88 for symmetry/discoverability — but no behavior change is required (dashboard does not surface skill activations in v0.0.2).

---

### MODIFY: `KoreMetrics.kt` — new `skillsActivatedCounter` (service, transform) [A3]

**Analog (same file):** `llmCallCounter` (lines 50-60) / `tokensUsedCounter` (lines 71-81) — the `Counter.builder(name).tag(...).register(registry)` factory pattern with low-cardinality configured-name tags (D-24).

**Existing pattern:**
```kotlin
fun llmCallCounter(agentName: String, model: String, backend: String): Counter =
    Counter
        .builder("kore.llm.calls")
        .tag("agent_name", agentName)
        .tag("model", model)
        .tag("backend", backend)
        .register(registry)
```

**New (mirror exactly — keep skill_name bounded/low-cardinality per D-24 / Security V7):**
```kotlin
fun skillsActivatedCounter(agentName: String, skillName: String): Counter =
    Counter
        .builder("kore.skills.activated")
        .tag("agent_name", agentName)
        .tag("skill_name", skillName)
        .register(registry)
```
For duration (Open-Q 2): a Micrometer `DistributionSummary`/`Timer` is idiomatic over a plain counter (keeps percentiles). Planner to confirm; not blocking.

---

### MODIFY: `kore-storage/build.gradle.kts` — `integrationTest` task + guard (config, batch)

**Analog (same file):** the existing unit-test exclusion (lines 34-38) — the exact tag-filter idiom to mirror, inverted to `includeTags`. Keep this block UNCHANGED (D-11):
```kotlin
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}
```
Testcontainers deps (lines 25-26) already present — no dependency changes.

**Add the new task (D-09/D-10/D-11; RESEARCH Pattern 1 — `TestListener`, NOT `afterSuite`, which is removed in Gradle 10 / config-cache-incompatible):**
```kotlin
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import java.util.concurrent.atomic.AtomicInteger

tasks.register<Test>("integrationTest") {
    description = "Runs kore-storage Testcontainers integration tests (CI-01)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs   // existing src/test (D-09, no source-set move)
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    // Decoupled from build/check (D-11) — NOT wired into `check`.

    val executed = AtomicInteger(0)   // val holding mutable state — CLAUDE.md compliant (Pitfall 4)
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit
        override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
        override fun beforeTest(testDescriptor: TestDescriptor) = Unit
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            executed.incrementAndGet()
        }
    })
    doLast {
        if (executed.get() == 0) {
            throw GradleException(
                "integrationTest executed 0 tests — the 'integration' tag filter matched nothing. " +
                    "Failing loudly (CI-01). Check @Tag(\"integration\") on kore-storage test classes.",
            )
        }
    }
}
```
**Verified:** all 3 integration test classes carry `@Tag("integration")`; `@Test` counts are MigrationTest=4, PostgresAuditLogAdapterTest=5, QueryTest=4 (≈13 methods; some may be parameterized). The guard only needs `count > 0`, so the exact number is cosmetic (Assumption A1).

---

### MODIFY: `.github/workflows/ci.yml` — `integration-test` job (config, batch)

**Analog (same file):** the `arm64-build` job (lines 34-51) — exact precedent for `needs: build`, the checkout + JDK 21 temurin + setup-gradle preamble, and a self-hosted `runs-on` label. The workflow-level `on:` (lines 3-7) already gives PR + push-to-main (D-13) — no per-job trigger needed.

**Existing `arm64-build` shape to mirror (lines 34-51):**
```yaml
  arm64-build:
    runs-on: [orangepi]
    needs: build
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Build
        run: ./gradlew build
```

**New job to add (parallel to `arm64-build`, both `needs: build` — D-12; RESEARCH Pattern 3):**
```yaml
  integration-test:
    runs-on: [arc-runner-unityinflow]   # self-hosted X64 — never ubuntu-latest
    needs: build
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Docker pre-flight (CI-02 / D-14)
        run: |
          if ! docker info > /dev/null 2>&1; then
            echo "::error title=Docker unavailable::Docker daemon is not reachable on this runner. This is a RUNNER CONFIG ERROR, not a test failure — integration tests need Docker for Testcontainers."
            exit 1
          fi
          echo "Docker daemon reachable."
      - name: Integration tests (CI-01)
        run: ./gradlew :kore-storage:integrationTest
```
No CI-side XML parsing (D-15) — the zero-test assertion lives entirely in the Gradle guard above.

## Shared Patterns

### Raw-`Tracer` span attribute setting (kore-core vs kore-observability boundary)
**Source:** `EventBusSpanObserver.kt` lines 44-61 (raw `spanBuilder` + typed `setAttribute(KoreAttrs.X, v)` + `end()`).
**Apply to:** `AgentLoop.kt` skill span. **Caveat:** `AgentLoop` is in kore-core and CANNOT depend on kore-observability, so it cannot use `KoreAttrs`/`KoreSpans` constants or `KoreTracer.withSpan`. It builds `AttributeKey`s inline (`AttributeKey.stringArrayKey/longKey`) with literal key names that MUST match the new `KoreAttrs` constants. `KoreAttrs` remains the single source of attribute-key truth for observability-side code (D-03, A4, Open-Q 1).

### Span parenting via `Context.current()` (OBSV-03)
**Source:** `ObservableAgentRunner.kt` lines 43-58 (`tracer.withSpan(AGENT_RUN)`) + `KoreTracer.withSpan` line 76 (`withContext(span.storeInContext(...).asContextElement())`).
**Apply to:** the new skill span — `setParent(Context.current())` parents it under `kore.agent.run` ONLY when the loop is driven through `ObservableAgentRunner`. Bare `AgentLoop` produces a root skill span by design (graceful degradation). **The OBSV-03 parenting test MUST run through `ObservableAgentRunner` (kore-observability), not the bare loop** (Pitfall 2; Validation map Wave-0 gap).

### Exhaustive-`when` observer branch placement (Pitfall 1 — highest risk)
**Source:** all three observers (`EventBusMetricsObserver` line 82, `EventBusSpanObserver` line 103, `EventBusDashboardObserver` line 88) end in `else -> Unit`.
**Apply to:** every observer. The new branch MUST be added explicitly BEFORE the `else -> Unit`; the compiler does not enforce exhaustiveness when an `else` is present, so a missing branch is a silent drop. Pair the metrics-observer branch with a counter-moved test.

### Low-cardinality Micrometer counter factory (D-24)
**Source:** `KoreMetrics.kt` lines 36-96 (`Counter.builder(name).tag(...).register(registry)`).
**Apply to:** the new `skillsActivatedCounter`. Tag values must be configured names, never UUIDs; skill-name tags must stay bounded (Security V7 / threat: meter explosion).

### Tag-filtered test task idiom
**Source:** `kore-storage/build.gradle.kts` lines 34-38 (`useJUnitPlatform { excludeTags("integration") }`).
**Apply to:** the new `integrationTest` task, inverted to `includeTags("integration")`. Keep the unit `test` exclusion unchanged (D-11).

### `needs: build` self-hosted CI job shape
**Source:** `.github/workflows/ci.yml` `arm64-build` job lines 34-51.
**Apply to:** the new `integration-test` job — same checkout/JDK/gradle preamble, `needs: build`, self-hosted `runs-on` label (`arc-runner-unityinflow`, never `ubuntu-latest`).

## No Analog Found

None. Every new/modified file has a strong in-repo analog (most are same-file or same-module siblings). The only genuinely external knowledge (Gradle 9 `TestListener` migration, OTel `AttributeKey.stringArrayKey`) is captured in RESEARCH.md Patterns 1-2 and reproduced inline above.

## Metadata

**Analog search scope:** `kore-core`, `kore-skills`, `kore-observability`, `kore-dashboard`, `kore-storage`, `.github/workflows` (build/ dirs excluded).
**Files scanned:** AgentEvent.kt, SkillRegistry.kt, AgentLoop.kt, KoreTracer.kt, EventBusMetricsObserver.kt, EventBusSpanObserver.kt, EventBusDashboardObserver.kt, KoreMetrics.kt, SkillRegistryAdapter.kt, SkillYamlDef.kt, ObservableAgentRunner.kt, kore-storage/build.gradle.kts, .github/workflows/ci.yml, 3 integration test classes (tag/count verification).
**Pattern extraction date:** 2026-06-14
