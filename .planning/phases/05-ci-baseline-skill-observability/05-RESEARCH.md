# Phase 5: CI Baseline & Skill Observability - Research

**Researched:** 2026-06-14
**Domain:** Gradle 9 test-task wiring · OpenTelemetry Java span attributes · GitHub Actions self-hosted Docker pre-flight · Kotlin sealed-event extension
**Confidence:** HIGH

## Summary

Phase 5 is a hardening phase with **zero new runtime features**. Every change lands on top of code that already exists and compiles. The five research questions resolve cleanly against the actual codebase, and there are exactly two pieces of genuine new external knowledge needed: (1) the Gradle-9-correct, config-cache-safe way to register a tag-filtered `integrationTest` task with a zero-test guard, and (2) the OpenTelemetry Java API for setting a `List<String>` (string-array) attribute on a span.

The single most important new finding: **`Test.afterSuite(Closure)` is deprecated in Gradle 9 and removed in Gradle 10, and it is NOT compatible with the configuration cache** `[VERIFIED: docs.gradle.org/current/userguide/upgrading_version_9]`. Since the project is on Gradle 9.4.1 and CLAUDE.md mandates forward-safe Kotlin DSL, the zero-test guard (D-10) should use a `TestListener` registered via `addTestListener(...)` writing to an `AtomicInteger`, not an `afterSuite {}` closure. This is the idiomatic, config-cache-survivable form the "Claude's Discretion" note in CONTEXT.md asks for.

Second finding worth flagging for the planner: **all three event-bus observers (`EventBusMetricsObserver`, `EventBusSpanObserver`, `EventBusDashboardObserver`) currently use an `else -> Unit` catch-all in their `when (event)` blocks** — so adding `AgentEvent.SkillActivated` will NOT produce compile errors forcing each branch. The planner must explicitly add the `SkillActivated` branches (D-08) as deliberate tasks; the compiler will not surface them. CONTEXT.md's code_context note ("Compilation will surface them all") is **incorrect** for this codebase as written. This is the highest-value correction in this research.

**Primary recommendation:** Use a `TestListener` + `AtomicInteger` zero-test guard (not `afterSuite`); use `AttributeKey.stringArrayKey(...)` + `span.setAttribute(key, list)` for the skill-names array; explicitly add `SkillActivated` branches to all three observers (the `else -> Unit` will otherwise silently swallow them); measure duration with `System.nanoTime()`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Tag-filtered `integrationTest` task + zero-test guard | Build (kore-storage `build.gradle.kts`) | — | Pure Gradle config; runs identically local + CI |
| Docker daemon availability check | CI (GitHub Actions job step) | — | Infra precondition; must read as config error, not test failure |
| `kore.skill.activate` span emission | kore-core (`AgentLoop.runLoop`, raw `Tracer`) | kore-observability (`KoreAttrs` key constants) | Span is created in-process at the activation site; attr keys are the single source of truth |
| `kore.skill.activate` string-array attribute branch | kore-observability (`KoreTracer.withSpan`) | — | Only `KoreTracer` consumers route through the `Map<String,Any>` dispatch; AgentLoop sets attrs directly |
| `AgentEvent.SkillActivated` event type | kore-core (`AgentEvent.kt`) | — | Event domain model is owned by kore-core; stays compileOnly-serializable |
| `SkillActivated` event emission | kore-core (`AgentLoop.runLoop`) | — | Emitted from the loop alongside the existing lifecycle events |
| `SkillActivated` reactions (counter/no-op) | kore-observability (`EventBusMetricsObserver`, `EventBusSpanObserver`) | kore-dashboard (`EventBusDashboardObserver`) | Each observer decides its own reaction; observers are pure adapters |
| `ActivatedSkill` value type | kore-core (`port/` package) | — | Port-adjacent data class; stdlib-only to preserve zero-runtime-dep |
| `SkillRegistryAdapter` migration to `List<ActivatedSkill>` | kore-skills | — | Real adapter; already loads skill `name` + `prompt` from YAML |

## User Constraints (from CONTEXT.md)

### Locked Decisions (D-01 … D-15 — NOT up for debate)

- **D-01:** `SkillRegistry.activateFor()` returns `List<ActivatedSkill>` (breaking, no compat shim — only two in-repo impls).
- **D-02:** `ActivatedSkill` is a new kore-core data class beside `SkillRegistry`, minimal fields `name` + `prompt`.
- **D-03:** Span attrs on `kore.skill.activate`: `kore.skill.names` (string-array), `kore.skill.count` (long), `kore.skill.duration_ms` (long); new `KoreAttrs` constants; add a `List<String>` branch to `KoreTracer.withSpan`'s `attrs` dispatch.
- **D-04:** The span is **always emitted** when a tracer is present, including count=0.
- **D-05:** **One batch event per agent run** carrying the full activated-name list.
- **D-06:** Payload: `agentId: String`, `skillNames: List<String>`, `durationMs: Long`; prompts NOT included; `@Serializable` + `@SerialName("SkillActivated")`.
- **D-07:** Event emitted **only when ≥1 skill matched** (span/event asymmetry is intentional).
- **D-08:** Observer reactions — `EventBusMetricsObserver` increments `kore.skills.activated` counter (per skill name) + records duration; `EventBusSpanObserver` explicit **no-op** branch; `EventBusDashboardObserver` verify handles/no-ops so it compiles.
- **D-09:** Tag-filtered `Test` task `integrationTest` on the **existing `src/test`** source set with `includeTags("integration")` — no source-set move.
- **D-10:** Zero-test guard via test tally that throws `GradleException` if executed count is 0; self-contained in `kore-storage/build.gradle.kts`; satisfies CI-02.
- **D-11:** `integrationTest` decoupled from `build`/`check`; unit `test` keeps excluding `integration`.
- **D-12:** Separate `integration-test` job in `ci.yml` on `[arc-runner-unityinflow]`, `needs: build`.
- **D-13:** Triggers: PR + push to `main`.
- **D-14:** `docker info` real pre-flight step that fails loudly with clear "Docker unavailable" message.
- **D-15:** CI-02 satisfied entirely by the in-Gradle guard (D-10) — no CI-side XML parsing.

### Claude's Discretion (research recommends below)

- Exact Gradle DSL for `integrationTest` + counter (`TestListener` vs `afterSuite` vs `AtomicInteger`+`doLast`) — **resolved → `TestListener` + `AtomicInteger`** (see Pattern 1).
- Package home for `ActivatedSkill` — **resolved → `io.github.unityinflow.kore.core.port`** beside `SkillRegistry.kt`.
- How raw-`Tracer` span code in `AgentLoop.runLoop()` sets the new attrs — **resolved → set directly on `Span` via `AttributeKey.stringArrayKey`/`longKey`** (see Pattern 2).
- `durationMs` via `System.nanoTime()` — **recommended yes** (monotonic, no wall-clock dependency).
- `SkillRegistryAdapter` migration — wrap YAML `name` + `prompt` into `ActivatedSkill`.

### Deferred Ideas (OUT OF SCOPE)

- Richer `ActivatedSkill` metadata (description/version/trigger).
- `EventBusSpanObserver` synthesizing skill spans from events.
- Always-emit `SkillActivated` (count=0) for a bus-only no-match metric.
- Wiring `integrationTest` into `check`.

## Project Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CI-01 | `./gradlew :kore-storage:integrationTest` runs the 7 Testcontainers tests (tag-filtered, fails loudly if 0 execute) | Pattern 1 (TestListener guard), Don't-Hand-Roll, Validation Architecture |
| CI-02 | CI runs integration tests on `arc-runner-unityinflow` with `docker info` pre-flight, asserting tests executed | Pattern 3 (CI job shape), Pitfall 3 (Docker pre-flight wording) |
| OBSV-03 | Skill activation emits `kore.skill.activate` span parented under agent-run span with name/count/duration | Pattern 2 (string-array attr), Pattern 4 (span parenting), Validation Architecture |
| OBSV-04 | Skill activation emits `AgentEvent.SkillActivated` on the event bus for metrics observers | Pattern 5 (sealed-event + observers), Pitfall 1 (`else -> Unit` swallow) |

## Project Constraints (from CLAUDE.md)

- Kotlin 2.x, JVM 21, Gradle **Kotlin DSL only** — never Groovy.
- No `var` — always `val`; refactor if mutation seems needed. (The `AtomicInteger` counter is a `val` holding mutable state — compliant.)
- No `!!` without a comment explaining why it's safe.
- Coroutines only — never `Thread.sleep()` or raw threads.
- JUnit 5 runner + **Kotest assertions** (`shouldBe`, etc.); MockK for mocking; `kotlinx-coroutines-test` (`runTest`) for suspend tests.
- `ktlint` (via `kotlinter` / `lintKotlin`) before every commit — CI runs `./gradlew lintKotlin`.
- Self-hosted runners only (`arc-runner-unityinflow` X64, `orangepi` ARM64) — **never `ubuntu-latest`**.
- kore-core stays zero-runtime-dep except `kotlinx.coroutines` + stdlib; OTel + serialization are `compileOnly`.
- `Result<T>` / sealed classes over exceptions for expected failures (does not apply to the Gradle `GradleException` guard, which is build-time tooling, not runtime domain logic).

## Standard Stack

No new dependencies are required. Every artifact needed is already on the relevant test/compile classpath. This phase adds **zero** production dependencies.

### Already Present (verified in build files & version catalog)

| Library | Version | Module(s) | Purpose | Status |
|---------|---------|-----------|---------|--------|
| `org.testcontainers:postgresql` | 1.20.0 | kore-storage (test) | PostgreSQL container for integration tests | `[VERIFIED: gradle/libs.versions.toml + kore-storage/build.gradle.kts]` |
| `org.testcontainers:junit-jupiter` | 1.20.0 | kore-storage (test) | `@Testcontainers` JUnit 5 extension | `[VERIFIED: build files]` |
| `io.opentelemetry:opentelemetry-api` | 1.49.0 | kore-core (compileOnly+test), kore-observability | `Tracer`, `Span`, `AttributeKey` | `[VERIFIED: build files]` |
| `io.opentelemetry:opentelemetry-sdk-testing` | 1.49.0 | kore-core (test), kore-observability (test) | `InMemorySpanExporter` for span assertions | `[VERIFIED: build files]` |
| `io.opentelemetry:opentelemetry-extension-kotlin` | 1.61.0 | kore-observability | `asContextElement()` coroutine ↔ OTel context bridge | `[VERIFIED: libs.versions.toml]` |
| `kotlinx-serialization-core` / `-json` | (BOM-managed, compileOnly in kore-core) | kore-core | `@Serializable`/`@SerialName` on `AgentEvent` subclasses | `[VERIFIED: kore-core/build.gradle.kts]` |
| `io.micrometer:micrometer-core` | 1.16.0 | kore-observability | `Counter` for the new `kore.skills.activated` metric | `[VERIFIED: kore-observability/build.gradle.kts]` |
| `io.mockk:mockk` | (libs.mockk) | kore-observability (test) | Mocking observers/registries | `[VERIFIED: build files]` |
| Gradle | 9.4.1 | wrapper | `Test`-task API, `TestListener` | `[VERIFIED: gradle/wrapper/gradle-wrapper.properties]` |

### Installation

```bash
# None. No new dependencies. Phase 5 wires existing libraries.
```

**Version verification:** No new packages → no registry verification required. All versions above were read directly from `gradle/libs.versions.toml` and the per-module `build.gradle.kts` files in this session.

## Package Legitimacy Audit

> Not applicable — this phase installs **no external packages**. All libraries it uses are already declared in the existing build files (audited above against `gradle/libs.versions.toml` and module `build.gradle.kts`). No `npm`/`PyPI`/`crates` actions occur.

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
                        AgentLoop.run(task)               [kore-core]
                               │
                               ▼
        ┌────────────── runLoop(...) ──────────────┐
        │                                           │
        │  ① start nanoTime                         │
        │  ② tracer?.spanBuilder("kore.skill.activate")    (raw nullable Tracer)
        │       .setParent(Context.current())  ◄──── parent = kore.agent.run span
        │       .startSpan()                         (set by ObservableAgentRunner)
        │  ③ activated = skillRegistry.activateFor(...)  → List<ActivatedSkill>
        │  ④ span.setAttribute(stringArrayKey("kore.skill.names"), names)
        │     span.setAttribute(longKey("kore.skill.count"), count)
        │     span.setAttribute(longKey("kore.skill.duration_ms"), durMs)
        │     span.end()                            (always, in finally)
        │  ⑤ if activated.isNotEmpty():
        │       inject System message (prompts)      (existing behavior)
        │       eventBus.emit(AgentEvent.SkillActivated(agentId, names, durMs))
        └────────────────────┬──────────────────────┘
                             │  event
                             ▼
                  EventBus.subscribe()  (Flow<AgentEvent>)
            ┌────────────────┼────────────────────────┐
            ▼                ▼                         ▼
  EventBusMetricsObserver  EventBusSpanObserver   EventBusDashboardObserver
  (++ kore.skills.activated  (explicit NO-OP —      (no-op / verify compiles)
   counter, record durMs)     real span made in-loop)   [kore-dashboard]
        [kore-observability]    [kore-observability]

  ─────────────────────────── CI path (orthogonal) ───────────────────────────
  PR / push→main ─► build job ─► integration-test job [arc-runner-unityinflow]
                                   │
                                   ├─ docker info  (pre-flight; loud config error)
                                   └─ ./gradlew :kore-storage:integrationTest
                                         │  TestListener tallies executed count
                                         └─ count==0 ⇒ throw GradleException (fail loud)
```

The agent-run parent span is established by `ObservableAgentRunner.withSpan(KoreSpans.AGENT_RUN)`, which calls `withContext(span.storeInContext(...).asContextElement())`. Because `AgentLoop.run` executes inside that `withContext`, `Context.current()` inside `runLoop` already resolves to the agent-run span — so `setParent(Context.current())` on the skill span parents it correctly (Pattern 4).

### Component Responsibilities

| File | Change |
|------|--------|
| `kore-core/.../port/SkillRegistry.kt` | Change `activateFor` return to `List<ActivatedSkill>`; add `ActivatedSkill(name, prompt)` data class; `NoOpSkillRegistry` returns `emptyList()` (type-only) |
| `kore-core/.../AgentLoop.kt` (runLoop ~92–115) | Reference `KoreSpans.SKILL_ACTIVATE`-equivalent constant (string, since kore-core can't depend on kore-observability — keep hardcoded `"kore.skill.activate"` OR a local const); set 3 attrs on the raw `Span`; nanoTime duration; consume `.prompt` for injection, `.name` for attrs/event; emit `SkillActivated` when `≥1` |
| `kore-observability/.../KoreTracer.kt` | Add `kore.skill.names`/`count`/`duration_ms` to `KoreAttrs`; add `List<String>` branch to `withSpan` `attrs` dispatch |
| `kore-core/.../AgentEvent.kt` | Add `@Serializable @SerialName("SkillActivated") data class SkillActivated(agentId, skillNames, durationMs)` |
| `kore-observability/.../EventBusMetricsObserver.kt` | Add explicit `is SkillActivated ->` branch (counter + duration) BEFORE the `else -> Unit` |
| `kore-observability/.../EventBusSpanObserver.kt` | Add explicit `is SkillActivated -> Unit` (documented no-op) BEFORE `else -> Unit` |
| `kore-dashboard/.../EventBusDashboardObserver.kt` | Verify compiles; optionally add explicit `is SkillActivated -> Unit` (already covered by `else`) |
| `kore-skills/.../SkillRegistryAdapter.kt` | `.map { ActivatedSkill(it.name, it.prompt) }` instead of `.map { it.prompt }` |
| `kore-storage/build.gradle.kts` | Register `integrationTest` `Test` task + `TestListener` zero-test guard |
| `.github/workflows/ci.yml` | Add `integration-test` job (needs: build, docker info, gradle task) |

### Pattern 1: Config-cache-safe `integrationTest` task with zero-test guard (CI-01, D-09/D-10/D-11)

**What:** A second `Test` task on the existing `src/test` source set, tag-filtered to `integration`, with a `TestListener` that tallies executed tests and throws `GradleException` if zero ran.

**Why TestListener and not `afterSuite`:** `Test.afterSuite(Closure)` (and `beforeSuite`/`beforeTest`/`afterTest`) are **deprecated in Gradle 9, removed in Gradle 10, and incompatible with the configuration cache** `[VERIFIED: docs.gradle.org/current/userguide/upgrading_version_9; docs.gradle.org/current/javadoc/deprecated-list]`. The migration path is `addTestListener(TestListener)`. Resolving D-10's discretion this way future-proofs against a Gradle-10 bump and a possible config-cache enable (currently not set in `gradle.properties`).

**When to use:** Always, for this guard.

**Example (mirrors existing `tasks.test { excludeTags("integration") }`):**

```kotlin
// Source: pattern derived from kore-storage/build.gradle.kts existing `tasks.test {}`
//         + Gradle 9 TestListener migration (docs.gradle.org upgrading_version_9)
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import java.util.concurrent.atomic.AtomicInteger

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs kore-storage Testcontainers integration tests (CI-01)."
    group = "verification"

    testClassesDirs = sourceSets["test"].output.classesDirs   // existing src/test
    classpath = sourceSets["test"].runtimeClasspath

    useJUnitPlatform {
        includeTags("integration")
    }

    // Decoupled from build/check (D-11): only runs on explicit invocation / CI job.
    // (No dependency wired into `check`.)

    // Zero-test guard (D-10 / CI-02). `val` holding a mutable counter — CLAUDE.md compliant.
    val executed = AtomicInteger(0)
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
                "integrationTest executed 0 tests — the 'integration' tag filter " +
                    "matched nothing. Failing loudly (CI-01). Check @Tag(\"integration\") " +
                    "on kore-storage test classes.",
            )
        }
    }
}
```

**Notes:**
- `doLast` runs after the test execution; if the task itself fails (a real test failure), Gradle stops before `doLast`, so the guard only fires on the "green but empty" case — exactly D-10's target failure mode.
- The existing unit `tasks.test { useJUnitPlatform { excludeTags("integration") } }` is **kept unchanged** (D-11).
- Counting via `afterTest` on a `TestListener` counts individual test methods; the 7 `@Test` methods across the 3 classes yield `executed == 7`.

### Pattern 2: Setting a string-array span attribute from a raw `Tracer` (OBSV-03, D-03)

**What:** `AgentLoop` holds a nullable raw `io.opentelemetry.api.trace.Tracer` (not `KoreTracer`), so it builds attributes directly on the `Span` using typed `AttributeKey`s.

**Confirmed API:** `AttributeKey.stringArrayKey(name)` produces `AttributeKey<List<String>>`; both `SpanBuilder.setAttribute(key, value)` and `Span.setAttribute(key, value)` accept it with a `List<String>` value `[VERIFIED: github.com/open-telemetry/opentelemetry-java Span.java/SdkSpanBuilder.java; opentelemetry.io/docs/languages/java/api]`.

**Example (kore-core, raw Tracer — keep KoreAttrs as the key-name source of truth):**

```kotlin
// Source: OpenTelemetry Java API (opentelemetry.io/docs/languages/java/api)
import io.opentelemetry.api.common.AttributeKey

// In AgentLoop.runLoop — replaces the current span build at lines ~97–106:
val startNanos = System.nanoTime()
val span = tracer?.spanBuilder("kore.skill.activate")
    ?.setParent(io.opentelemetry.context.Context.current()) // OBSV-03 parenting (Pattern 4)
    ?.startSpan()
val activated: List<ActivatedSkill> =
    try {
        skillRegistry.activateFor(
            taskContent = userMessage.content,
            availableTools = toolDefs.map { it.name },
        )
    } finally {
        val durMs = (System.nanoTime() - startNanos) / 1_000_000
        span?.apply {
            setAttribute(AttributeKey.stringArrayKey("kore.skill.names"), activatedNamesOrEmpty)
            setAttribute(AttributeKey.longKey("kore.skill.count"), count.toLong())
            setAttribute(AttributeKey.longKey("kore.skill.duration_ms"), durMs)
            end()
        }
    }
```

> Implementation note for the planner: because `activated` is referenced inside the `finally`, the planner should structure the code so `names`/`count` are computable there (e.g., assign to a `var`-free outer holder, or compute attrs after the `try` returns and before `span.end()` — restructure so there is no `var`). One clean shape: do the `activateFor` call first into a `val`, then a single `tracer?.let { ... build span, set attrs, end }` block — no `finally` needed because `activateFor` is the only throwing call and a thrown exception will propagate without a stuck span only if the span is created *after* the call. **Trade-off:** creating the span after the call means a thrown `activateFor` produces no span. D-04 says "always emitted when tracer present"; to honor that even on activation failure, keep the `try/finally` and use a small private helper or an `AtomicReference`-style `val` holder for the result. This is a real design micro-decision for the plan, not a blocker.

**`KoreTracer.withSpan` branch (D-03):** add a `List<String>` case to the `attrs` `when`. Note OTel's typed setter — you must build the `AttributeKey` explicitly:

```kotlin
// Source: existing KoreTracer.withSpan dispatch + OTel typed AttributeKey
attrs.forEach { (key, value) ->
    when (value) {
        is String -> span.setAttribute(key, value)
        is Long -> span.setAttribute(key, value)
        is Int -> span.setAttribute(key, value.toLong())
        is Double -> span.setAttribute(key, value)
        is Boolean -> span.setAttribute(key, value)
        // NEW (D-03): string-array attribute
        is List<*> -> {
            @Suppress("UNCHECKED_CAST")
            val strings = value.filterIsInstance<String>() // narrow; non-strings dropped
            span.setAttribute(AttributeKey.stringArrayKey(key), strings)
        }
        else -> Unit
    }
}
```

> The existing `withSpan` dispatch silently drops unmatched types (no `else`). The planner should decide whether `filterIsInstance<String>()` (lossy but safe) or a stricter `as List<String>` cast is wanted. `filterIsInstance` avoids an `UNCHECKED_CAST` runtime risk and needs no `!!`.

### Pattern 3: Self-hosted `integration-test` CI job with Docker pre-flight (CI-02, D-12/D-13/D-14)

**What:** A new job parallel to `arm64-build`, both `needs: build`. A `docker info` step fails the job loudly before tests run, distinguishing infra breakage from test failure.

**Example (mirrors the existing `build`/`arm64-build` jobs in `ci.yml`):**

```yaml
# Source: existing .github/workflows/ci.yml conventions + CLAUDE.md runner labels
  integration-test:
    runs-on: [arc-runner-unityinflow]   # self-hosted X64, never ubuntu-latest
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
            echo "::error title=Docker unavailable::Docker daemon is not reachable on this runner. \
          This is a RUNNER CONFIG ERROR, not a test failure — integration tests need Docker for Testcontainers."
            exit 1
          fi
          echo "Docker daemon reachable."

      - name: Integration tests (CI-01)
        run: ./gradlew :kore-storage:integrationTest
```

**Notes:**
- Triggers are inherited from the workflow-level `on:` (PR + push to main, D-13) — no per-job trigger config needed.
- The `::error title=...::` GitHub Actions annotation surfaces the Docker failure prominently in the PR checks UI, reinforcing "config error, not test failure" (D-14, STATE.md concern).
- The zero-test assertion lives entirely in Gradle (D-15) — no XML-parsing step here.

### Pattern 4: Span parenting verification (OBSV-03)

**Confirmed by reading the code:** `ObservableAgentRunner.run` wraps `loop.run(task)` inside `tracer.withSpan(KoreSpans.AGENT_RUN) { ... }`. `KoreTracer.withSpan` ends with `withContext(span.storeInContext(Context.current()).asContextElement()) { block(span) }`. Therefore the agent-run span is the current OTel `Context` for the entire duration of `loop.run`. Inside `runLoop`, `Context.current()` resolves to the agent-run span, so the skill span's `setParent(Context.current())` parents it under `kore.agent.run`. This is the same mechanism the `KoreTracerTest."child span traceId matches parent..."` test already proves for nested `withSpan` calls.

**Critical caveat for the plan:** OBSV-03 parenting only holds when the loop is driven through `ObservableAgentRunner`. The bare `AgentLoop` (no runner) still emits the skill span but it will be a **root** span (no parent context present). The existing `AgentLoopSkillTest."Test 6"` runs the bare loop and asserts the span exists but does NOT assert parenting — correct. **A new test asserting parenting must run through `ObservableAgentRunner`** (kore-observability module), not the bare loop (kore-core). See Validation Architecture.

### Pattern 5: Adding the `SkillActivated` sealed subclass + observer branches (OBSV-04, D-05/D-06/D-08)

**Subclass (follow the exact existing shape in `AgentEvent.kt`):**

```kotlin
// Source: existing AgentEvent subclasses (AgentEvent.kt lines 23–64)
@Serializable
@SerialName("SkillActivated")
data class SkillActivated(
    val agentId: String,
    val skillNames: List<String>,
    val durationMs: Long,
) : AgentEvent()
```

`List<String>` is serializable by kotlinx.serialization with no extra annotations. Prompts excluded per D-06 (size + sensitivity). The existing `AgentEventSerializationTest.kt` should get a round-trip case for the new subclass.

**Observer branches (D-08):**

```kotlin
// EventBusMetricsObserver — add BEFORE `else -> Unit`
is AgentEvent.SkillActivated -> {
    val agentName = agentNames[event.agentId] ?: "unknown"
    event.skillNames.forEach { skillName ->
        metrics.skillsActivatedCounter(agentName, skillName).increment()
    }
    // record duration (e.g., a timer/counter on KoreMetrics — new method)
}

// EventBusSpanObserver — add BEFORE `else -> Unit`
is AgentEvent.SkillActivated -> Unit // explicit no-op: real span made in-process by AgentLoop (D-08)
```

A new `KoreMetrics.skillsActivatedCounter(agentName, skillName)` follows the existing low-cardinality builder pattern (tags = configured names, never UUIDs — D-24 from Phase 2).

### Anti-Patterns to Avoid

- **Relying on the compiler to find the observer branches.** All three observers use `else -> Unit` — adding `SkillActivated` compiles silently and the event is dropped. Add branches explicitly. (See Pitfall 1.)
- **Using `afterSuite {}` for the zero-test guard.** Deprecated/removed/config-cache-incompatible in Gradle 9/10. Use `TestListener`. (See Pattern 1.)
- **Creating a second source set for integration tests.** D-09 explicitly keeps `src/test`; a `src/integrationTest` source set would orphan the shared fixtures and the existing `@Tag` annotations.
- **`System.currentTimeMillis()` for duration.** Use `System.nanoTime()` (monotonic) — wall-clock can jump.
- **Putting `ActivatedSkill` in kore-skills.** It is consumed by kore-core's `AgentLoop`; it must live in kore-core (`port/`), stdlib-only (D-02).
- **Adding a CI-side XML parse step.** D-15 — one assertion, in Gradle only.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Counting executed tests | Parse `build/test-results/**/*.xml` in a shell step | Gradle `TestListener.afterTest` + `AtomicInteger` | Runs identically local + CI; no XML schema coupling; D-10/D-15 |
| Tag filtering | Custom test class name globbing | JUnit Platform `includeTags("integration")` | Already how unit `test` excludes; native |
| String-array span attribute | Join names into one comma-string | `AttributeKey.stringArrayKey` | Native array type queryable per-element in any OTel backend (D-03) |
| Span parenting | Manually thread a parent span ID | `setParent(Context.current())` + `asContextElement()` | Already proven by `KoreTracerTest`; coroutine-safe |
| Docker availability | `ps aux \| grep dockerd` heuristics | `docker info` exit code | Authoritative daemon reachability check |
| Polymorphic event wire format | Manual `type` field switch | `@Serializable` + `@SerialName` (existing) | Established Phase-4 pattern; broker-readable |

**Key insight:** Phase 5's entire risk surface is "wiring that silently does nothing" — an empty tag filter that passes green, an event subclass that an `else ->` swallows, a span that lands as a root instead of a child. Every recommendation above is chosen to make the silent-no-op case fail loudly (the `GradleException` guard, the explicit observer branches, the through-`ObservableAgentRunner` parenting test).

## Runtime State Inventory

> Phase 5 is not a rename/refactor/migration phase — it adds observability wiring and a build/CI task. No stored data, live-service config, OS-registered state, secrets, or build-artifact renames are involved. The one breaking change (`SkillRegistry.activateFor` return type) is a **compile-time** port change with only two in-repo implementations; no persisted data or wire format references the old `List<String>` return type. **None — verified by reading both implementations (`NoOpSkillRegistry`, `SkillRegistryAdapter`) and all call sites (`AgentLoop.runLoop`).**

## Common Pitfalls

### Pitfall 1: The `else -> Unit` swallow (HIGHEST RISK)
**What goes wrong:** `AgentEvent.SkillActivated` is added; all modules compile; but `EventBusMetricsObserver` never increments a counter because its `when` ends in `else -> Unit`, which absorbs the new subclass. OBSV-04 silently fails verification.
**Why it happens:** Kotlin only enforces exhaustiveness on `when` over sealed types when there is **no** `else` branch. All three observers (`EventBusMetricsObserver`, `EventBusSpanObserver`, `EventBusDashboardObserver`) have an `else -> Unit`. CONTEXT.md's note that "Compilation will surface them all" does **not** hold for this codebase.
**How to avoid:** Add explicit `is AgentEvent.SkillActivated ->` branches as deliberate tasks (D-08). For the metrics observer, add a test that emits the event and asserts the counter moved.
**Warning signs:** A green build with no new test for the metrics path; counter registry shows no `kore.skills.activated` meter after a run.

### Pitfall 2: Skill span parented as root when not run through `ObservableAgentRunner`
**What goes wrong:** A test (or a consumer) drives the bare `AgentLoop` directly; the `kore.skill.activate` span has no parent and OBSV-03's "parented under agent-run" claim fails in production traces.
**Why it happens:** Parenting relies on `Context.current()` being the agent-run span, which is only true inside `ObservableAgentRunner.withSpan(AGENT_RUN)`.
**How to avoid:** Write the OBSV-03 parenting assertion in kore-observability through `ObservableAgentRunner`, and document in KDoc that bare-loop spans are roots by design (graceful degradation).
**Warning signs:** Skill spans appear as separate traces in the OTel backend instead of children of `kore.agent.run`.

### Pitfall 3: Docker pre-flight failure indistinguishable from a test failure
**What goes wrong:** On-call sees a red `integration-test` job and assumes a regression, when in fact the runner's Docker daemon is down (STATE.md flags this is unverified on `arc-runner-unityinflow`).
**Why it happens:** A plain `./gradlew integrationTest` against a dead daemon fails deep inside Testcontainers with a confusing stack trace.
**How to avoid:** The dedicated `docker info` step with an `::error title=Docker unavailable::` annotation and explicit "RUNNER CONFIG ERROR, not a test failure" wording (D-14, Pattern 3).
**Warning signs:** Testcontainers `Could not find a valid Docker environment` buried in test logs.

### Pitfall 4: `var`/`!!`/raw-thread violations from naive implementations
**What goes wrong:** A quick implementation uses `var count = 0` in the listener, or `as List<String>` with `!!`, tripping ktlint/CLAUDE.md review.
**Why it happens:** Counters and casts invite mutable/forced idioms.
**How to avoid:** `val executed = AtomicInteger(0)` (val holding mutable state is fine); `filterIsInstance<String>()` instead of an unchecked cast; never `Thread.sleep` (none needed here).
**Warning signs:** `./gradlew lintKotlin` failures in CI's existing lint step.

### Pitfall 5: `activateFor` throwing leaves the span open or skips it (D-04 tension)
**What goes wrong:** If the span is created *after* `activateFor`, a thrown exception produces no span (violates "always emitted"); if created before but attrs/`end()` are not in `finally`, a throw leaks the span.
**Why it happens:** The `try`/`finally` ordering interacts with D-04's "always emit" guarantee.
**How to avoid:** Keep span creation before the call and `end()` in `finally` (current structure already does the end-in-finally). Set count/names/duration in the `finally` from values computed without re-throwing.
**Warning signs:** A test that makes `activateFor` throw and asserts the span still finished does not exist.

## Code Examples

All load-bearing examples are in Architecture Patterns 1–5 above (Gradle `TestListener` guard, OTel string-array attr, CI job, parenting, sealed subclass + observer branches). They are drawn from the existing files (`kore-storage/build.gradle.kts`, `KoreTracer.kt`, `AgentEvent.kt`, `EventBusMetricsObserver.kt`, `ci.yml`) plus the two verified external APIs (Gradle 9 `TestListener`, OTel `AttributeKey.stringArrayKey`).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `Test.afterSuite(Closure)` for post-test logic | `addTestListener(TestListener)` | Deprecated Gradle 9, removed Gradle 10 | D-10 guard must use `TestListener` (Pattern 1) |
| Comma-joined string for multi-valued span attrs | `AttributeKey.stringArrayKey` native arrays | Long-standing OTel API | D-03 `kore.skill.names` is a real array |

**Deprecated/outdated:**
- `afterSuite` / `beforeSuite` / `afterTest` / `beforeTest` closure methods on `Test` — config-cache-incompatible; use `TestListener`.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | All 7 integration `@Test` methods are individual JUnit methods (so `afterTest` tally == 7, not 3 classes) | Pattern 1 | Guard still fires correctly (count>0); only the asserted number differs. Verified by grep: 3 in MigrationTest, 4 in PostgresAuditLogAdapterTest, 3 in QueryTest = 10 `@Test` annotations seen (some may be parameterized); the guard only needs count>0, so risk is cosmetic. |
| A2 | Configuration cache is not currently enabled and the planner may not enable it this phase | Pattern 1 | If enabled later, `afterSuite` would have broken — `TestListener` is already safe, so no risk. |
| A3 | `KoreMetrics` gains a `skillsActivatedCounter(agentName, skillName)` method following the existing builder pattern | Pattern 5 / D-08 | Low — straightforward mirror of existing counters; tag cardinality (skill names) should be bounded in practice. |
| A4 | kore-core can reference the literal `"kore.skill.activate"` (it cannot import `KoreSpans` from kore-observability without a dependency) | Component table / Pattern 2 | Low — current code already hardcodes the string; a kore-core-local const is the clean fix. The `KoreSpans.SKILL_ACTIVATE` constant in kore-observability stays the source of truth for observability-side code only. |

## Open Questions

1. **Should kore-core define its own span-name constant or keep the hardcoded literal?**
   - What we know: kore-core cannot depend on kore-observability (`KoreSpans` lives there); the literal `"kore.skill.activate"` is already hardcoded in `AgentLoop`.
   - What's unclear: whether to introduce a kore-core-local constant to avoid drift with `KoreSpans.SKILL_ACTIVATE`.
   - Recommendation: add a small kore-core-internal const referenced by `AgentLoop`; keep `KoreSpans.SKILL_ACTIVATE` as a mirror with a comment cross-referencing it. Low stakes.

2. **Exact `KoreMetrics` shape for duration (counter vs timer/`DistributionSummary`)?**
   - What we know: existing metrics are all `Counter`s; D-08 says "record durationMs."
   - What's unclear: whether a `Timer`/`DistributionSummary` is wanted or a simple total-ms counter suffices for v0.0.2.
   - Recommendation: a `DistributionSummary` (or `Timer`) is the idiomatic Micrometer choice for a duration distribution; a plain counter loses percentiles. Planner to confirm; not blocking.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 21 (temurin) | All builds | ✓ (CI sets it up) | 21 | — |
| Gradle | All builds | ✓ | 9.4.1 (wrapper) | — |
| Docker daemon | `integrationTest` (Testcontainers) | ✗ **unverified on `arc-runner-unityinflow`** | — | None — the whole point of D-14 is to fail loudly; no fallback by design |
| PostgreSQL | integration tests | ✓ (provided by Testcontainers `postgres:16-alpine`) | 16-alpine | — |

**Missing dependencies with no fallback:**
- **Docker on `arc-runner-unityinflow`** — STATE.md explicitly flags this as not pre-verified. The CI-02 / D-14 `docker info` pre-flight is the planned mitigation: if Docker is absent, the job fails with a clear config-error message rather than a confusing Testcontainers stack trace. The planner should treat "verify Docker is actually installed on the runner" as a real risk that the pre-flight surfaces on first CI run (it does not pre-install Docker).

## Validation Architecture

> `nyquist_validation: true` and `commit_docs: true` confirmed in `.planning/config.json` — this section is included.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (JUnit Platform 5.12.0) runner + Kotest 6.x assertions + MockK + kotlinx-coroutines-test 1.10.2 |
| Config file | Per-module `build.gradle.kts` (`tasks.test { useJUnitPlatform() }`); no separate config file |
| Quick run command | `./gradlew :kore-core:test :kore-observability:test` (unit; Docker-free, fast) |
| Full suite command | `./gradlew test` (all unit) then `./gradlew :kore-storage:integrationTest` (Docker) |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CI-01 | `integrationTest` runs the 7 tests against real PG | integration | `./gradlew :kore-storage:integrationTest` | ✅ (3 test classes exist; task is new — Wave 0 build wiring) |
| CI-01 | Zero-test tag filter fails loudly with `GradleException` | build behavior | manual: temporarily mistag + run task, observe failure (or a Gradle TestKit test) | ❌ Wave 0 (optional TestKit functional test) |
| CI-02 | CI job runs on self-hosted runner with `docker info` pre-flight | CI behavior | observed on first PR CI run | ❌ Wave 0 (CI YAML — verified by running CI) |
| OBSV-03 | Skill activation emits `kore.skill.activate` span (name/count/duration attrs) | unit | `./gradlew :kore-core:test --tests "*AgentLoopSkillTest*"` | ✅ extend `AgentLoopSkillTest` (assert attrs, not just span presence) |
| OBSV-03 | Span parented under `kore.agent.run` | unit (integration of modules) | `./gradlew :kore-observability:test --tests "*ObservableAgentRunner*"` | ❌ Wave 0 — new test in kore-observability through `ObservableAgentRunner` |
| OBSV-03 | `KoreTracer.withSpan` sets a string-array attr | unit | `./gradlew :kore-observability:test --tests "*KoreTracer*"` | ✅ extend `KoreTracerTest` |
| OBSV-04 | `AgentEvent.SkillActivated` emitted on bus when ≥1 skill matched | unit | `./gradlew :kore-core:test --tests "*AgentLoopSkillTest*"` | ✅ extend (collect events from `InProcessEventBus`) |
| OBSV-04 | event NOT emitted when 0 matched (D-07 asymmetry) | unit | same as above | ❌ Wave 0 — new negative-case test |
| OBSV-04 | `EventBusMetricsObserver` increments counter on `SkillActivated` | unit | `./gradlew :kore-observability:test --tests "*EventBusMetricsObserver*"` | ✅ extend (existing test uses `backgroundScope`+`yield`+`runCurrent` pattern) |
| OBSV-04 | `SkillActivated` JSON round-trips | unit | `./gradlew :kore-core:test --tests "*AgentEventSerialization*"` | ✅ extend `AgentEventSerializationTest` |

**How to assert the span is emitted:** `InMemorySpanExporter.create()` + `SdkTracerProvider` + `SimpleSpanProcessor` (exact pattern already in `KoreTracerTest` and `AgentLoopSkillTest`). Assert attribute values via `span.attributes.get(AttributeKey.stringArrayKey("kore.skill.names"))` etc. For parenting, assert `childSpan.parentSpanContext.spanId == agentRunSpan.spanId` (or `traceId` equality as `KoreTracerTest` does).

**How to assert the event is emitted:** collect from `InProcessEventBus().subscribe()` inside `runTest` using the established `backgroundScope` + `yield()` + `runCurrent()` idiom (from `EventBusMetricsObserverTest`, per Phase-2 decision — `advanceUntilIdle` hangs on never-finishing collect loops). For the observer counter, use a `SimpleMeterRegistry` and assert `registry.find("kore.skills.activated").counter()?.count()`.

### Sampling Rate
- **Per task commit:** `./gradlew lintKotlin :kore-core:test :kore-observability:test` (Docker-free, < 30s typical for these modules).
- **Per wave merge:** `./gradlew test` (all unit) + `./gradlew :kore-storage:integrationTest` if Docker present locally.
- **Phase gate:** Full unit suite green + CI `integration-test` job green (real Docker on runner) before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `kore-storage/build.gradle.kts` — register `integrationTest` task + `TestListener` guard (CI-01). No new test *file*, but the build wiring is a prerequisite.
- [ ] `.github/workflows/ci.yml` — `integration-test` job (CI-02).
- [ ] `kore-observability` new test: skill span parenting through `ObservableAgentRunner` (OBSV-03).
- [ ] `kore-core` `AgentLoopSkillTest` extensions: attr assertions, event-emitted, event-NOT-emitted-on-0 (OBSV-03/04/D-07).
- [ ] `kore-observability` `EventBusMetricsObserverTest` extension: counter increments on `SkillActivated` (OBSV-04/D-08).
- [ ] `kore-core` `AgentEventSerializationTest` extension: `SkillActivated` round-trip (D-06).
- [ ] (Optional) Gradle TestKit functional test asserting the zero-test `GradleException` fires (CI-01 loud-fail). Otherwise verify manually.

## Security Domain

> `security_enforcement` not explicitly set to `false` in config (key absent → treated as enabled). However, Phase 5 introduces **no** new external inputs, network surfaces, auth, or persistence. The one data-handling decision is security-relevant and already locked:

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V5 Input Validation | minimal | Skill names come from in-repo YAML, not user input; `taskContent` already flows through the loop unchanged |
| V8 Data Protection | yes | **Prompts excluded from `SkillActivated` payload (D-06)** — they can be KBs and may contain sensitive content unsuitable for broker (Kafka/RabbitMQ) transport. Only `skillNames` (low-sensitivity identifiers) cross the bus. This is the key security control of the phase. |
| V7 Logging | yes | Span attributes (`kore.skill.names`) and the bus event carry skill *names* only; ensure names themselves are not treated as PII (mirrors Phase-2 D-24 "configured names not UUIDs / no PII in tag values") |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Sensitive prompt content leaking to message broker | Information Disclosure | D-06: prompts never placed on the bus; only names + duration |
| High-cardinality metric tags (skill names) causing meter explosion | Denial of Service (metrics backend) | Keep skill names bounded/configured (Phase-2 D-24 low-cardinality rule); consider capping distinct skill-name tags if user-defined skills grow |

## Sources

### Primary (HIGH confidence)
- Codebase (read this session): `AgentLoop.kt`, `SkillRegistry.kt`, `AgentEvent.kt`, `KoreTracer.kt`, `ObservableAgentRunner.kt`, `EventBusMetricsObserver.kt`, `EventBusSpanObserver.kt`, `EventBusDashboardObserver.kt`, `KoreMetrics.kt`, `SkillRegistryAdapter.kt`, `MigrationTest.kt`, `KoreTracerTest.kt`, `AgentLoopSkillTest.kt`, `kore-storage/build.gradle.kts`, `kore-core/build.gradle.kts`, `kore-observability/build.gradle.kts`, `.github/workflows/ci.yml`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `.planning/config.json`.
- [Gradle 9.x upgrade guide — afterSuite deprecation](https://docs.gradle.org/current/userguide/upgrading_version_9.html) — `afterSuite`/`TestListener` migration, config-cache incompatibility.
- [Gradle 9.4.1 deprecated list](https://docs.gradle.org/current/javadoc/deprecated-list.html) — confirms `AbstractTestTask.afterSuite` removed in Gradle 10.
- [Gradle TestListener javadoc](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/testing/TestListener.html) — listener interface shape.
- [OpenTelemetry Java API docs](https://opentelemetry.io/docs/languages/java/api/) and [opentelemetry-java Span.java](https://github.com/open-telemetry/opentelemetry-java/blob/main/api/all/src/main/java/io/opentelemetry/api/trace/Span.java) — `AttributeKey.stringArrayKey` + `setAttribute(key, List<String>)`.

### Secondary (MEDIUM confidence)
- [Gradle configuration cache enabling](https://docs.gradle.org/current/userguide/configuration_cache_enabling.html) — closure-based test methods unsupported under config cache.

### Tertiary (LOW confidence)
- None.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new deps; all versions read from build files this session.
- Architecture: HIGH — every change site read directly; the `else -> Unit` swallow and span-parenting mechanism verified in source.
- Gradle TestListener / config-cache: HIGH — confirmed against official Gradle docs.
- OTel string-array API: HIGH — confirmed against opentelemetry-java source + official docs.
- Pitfalls: HIGH — derived from reading the actual observer/test code.

**Research date:** 2026-06-14
**Valid until:** 2026-07-14 (stable; the only fast-moving item is Gradle, pinned to 9.4.1 via wrapper)
