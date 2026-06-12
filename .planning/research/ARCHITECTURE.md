# Architecture Research — kore-runtime v0.0.2 Integration Points

**Domain:** Production Kotlin agent runtime (hexagonal, multi-module Gradle)
**Researched:** 2026-06-12
**Confidence:** HIGH — all findings from direct codebase reads; no speculation

---

## Scope

This document covers only the four new features in milestone v0.0.2:

1. budget-breaker adapter (real `BudgetEnforcer` backed by `io.github.unityinflow:budget-breaker`)
2. Hierarchical agents (parent/child with structured concurrency)
3. OBSV-03: OTel span on skill activation
4. kore-storage `integrationTest` Gradle task + CI step

Zero-dep kore-core constraint is preserved throughout — no new runtime dependencies enter kore-core.

---

## Feature 1: budget-breaker Adapter

### Where the adapter lives

The adapter belongs in a new module **`kore-budget`** rather than inside kore-spring. Rationale:

- kore-kafka and kore-rabbitmq are both separate modules despite being wired exclusively via kore-spring auto-config. The pattern is: adapter logic in its own module, auto-config wiring in kore-spring. Keeping `kore-budget` separate allows consumers to use it without kore-spring (standalone DSL users), mirrors the established pattern, and keeps kore-spring's build.gradle.kts from accumulating adapter implementation code.
- kore-spring declares all optional modules as `compileOnly` and gates them with `@ConditionalOnClass(name=[...])`. A new `kore-budget` module follows this exact path.

### budget-breaker public API (from codebase read)

The core library (`io.github.unityinflow:budget-breaker`) exposes:

- `BudgetCircuitBreaker` — stateful tracker, `withBudget(agentId, budget) { ... }` scope DSL, `SharedFlow<BudgetEvent>` for reactive consumers
- `BudgetScope.trackCall(promptTokens, completionTokens)` — records usage, checks soft/hard limits, throws `BudgetHardLimitException` on hard breach
- `AgentBudget(model, hardLimitTokens, softLimitTokens)` — configuration data class

The kore `BudgetEnforcer` port has three methods: `recordUsage(agentId, usage)`, `checkBudget(agentId): Boolean`, `getUsage(agentId): TokenUsage`. The adapter must bridge `BudgetCircuitBreaker` to this port.

### Adapter implementation strategy

`BudgetBreakerAdapter` in `kore-budget` wraps a `BudgetCircuitBreaker` singleton per adapter instance. Because `BudgetCircuitBreaker.withBudget` is a scope DSL (the agent must execute *inside* it), and `BudgetEnforcer.checkBudget` / `recordUsage` are called from within `AgentLoop` iteration steps, the adapter cannot use `withBudget`'s scope DSL directly — the loop is not entered inside a `withBudget` lambda.

The correct approach: `BudgetBreakerAdapter` subscribes to `EventBus` to track agent lifecycles, opening a `withBudget` scope per agent run and storing the live `BudgetScope` reference indexed by agentId. `recordUsage` looks up the stored `BudgetScope` and calls `trackCall`. `checkBudget` returns false when a hard-limit breach has been flagged.

**Concrete data flow:**

```
AgentLoop emits AgentStarted(agentId)
    → BudgetBreakerAdapter (EventBus subscriber) launches coroutine:
      budgetCircuitBreaker.withBudget(agentId) { scope ->
          budgetScopes[agentId] = scope
          runCompletionSuspend(agentId)   // suspends until AgentCompleted
      }

AgentLoop calls budgetEnforcer.recordUsage(agentId, usage)
    → BudgetBreakerAdapter.recordUsage()
      → budgetScopes[agentId]?.trackCall(usage.inputTokens.toLong(), usage.outputTokens.toLong())
      → BudgetHardLimitException caught → sets breachedAgents.add(agentId)

AgentLoop calls budgetEnforcer.checkBudget(agentId)
    → returns agentId !in breachedAgents

AgentLoop emits AgentCompleted
    → BudgetBreakerAdapter signals runCompletionSuspend to return
    → withBudget scope closes → BudgetCircuitBreaker finalizes BudgetReport
```

This approach requires `BudgetBreakerAdapter` to take `EventBus` as a constructor parameter and a `CoroutineScope` for the subscriber coroutine. kore-spring wires both.

### Auto-configuration gating (mirror kore-kafka pattern)

In `KoreAutoConfiguration`, add a new inner class following the exact kore-kafka triple-gate:

```kotlin
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["io.github.unityinflow.kore.budget.BudgetBreakerAdapter"])
@ConditionalOnProperty(
    prefix = "kore.budget",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class BudgetBreakerAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(BudgetEnforcer::class)
    fun budgetBreakerAdapter(
        properties: KoreProperties,
        eventBus: EventBus,
        @Qualifier("koreBudgetScope") scope: CoroutineScope,
    ): BudgetEnforcer =
        io.github.unityinflow.kore.budget.BudgetBreakerAdapter(
            eventBus = eventBus,
            scope = scope,
            defaultHardLimitTokens = properties.budget.defaultMaxTokens,
        )

    @Bean("koreBudgetScope", destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["koreBudgetScope"])
    fun koreBudgetScope(): CoroutineScope =
        CloseableCoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
}
```

Note: unlike Kafka/RabbitMQ (which require `kore.event-bus.type=kafka`), budget-breaker uses `kore.budget.enabled=true` because it replaces a default rather than selecting among event bus adapters. When `kore-budget` is on the classpath but `kore.budget.enabled` is not set, `InMemoryBudgetEnforcer` remains active — same zero-config default.

The `KoreProperties` `budget` section already has `defaultMaxTokens`. No new property namespace needed beyond `kore.budget.enabled`.

kore-spring `build.gradle.kts` additions:

```kotlin
compileOnly(project(":kore-budget"))
// test classpath to fire the @ConditionalOnClass gate in auto-config tests:
testImplementation(project(":kore-budget"))
```

The budget-breaker core library is a dependency of `kore-budget`, not of kore-spring.

### Spring Boot starter status gate

The budget-breaker Spring Boot starter (`budget-breaker-spring-boot-starter`) is still pending at v0.0.2. The `kore-budget` adapter depends on the core library only (`io.github.unityinflow:budget-breaker:0.0.1`), not the starter. The adapter is the Spring integration layer. This mirrors how kore-observability wraps OTel without pulling in the Spring Boot OTel starter.

### New module: kore-budget structure

```
kore-budget/
├── build.gradle.kts
└── src/main/kotlin/io/github/unityinflow/kore/budget/
    └── BudgetBreakerAdapter.kt
```

`build.gradle.kts` dependencies:

```kotlin
implementation(project(":kore-core"))
implementation("io.github.unityinflow:budget-breaker:0.0.1")
implementation(libs.coroutines.core)
```

---

## Feature 2: Hierarchical Agents

### Zero-dep constraint analysis

`AgentLoop` and `AgentRunner` live in kore-core. `kotlinx-coroutines-core` is already a runtime dependency of kore-core. Structured concurrency for parent/child is purely a coroutines concept — no new dependencies required.

### Where the API surfaces

The parent/child relationship surfaces in two places:

1. **kore-core port** — `ChildAgentProvider` interface and `NoOpChildAgentProvider` default
2. **kore-core DSL** — `child { }` block in `AgentBuilder`
3. **kore-core internal** — `ChildAgentDispatcher` implementation

No module boundary is crossed. All changes are within kore-core.

### ChildAgentProvider port in kore-core

```kotlin
// kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/ChildAgentProvider.kt
fun interface ChildAgentProvider {
    suspend fun spawn(
        name: String,
        task: AgentTask,
        parentRunId: String,
    ): AgentResult
}

object NoOpChildAgentProvider : ChildAgentProvider {
    override suspend fun spawn(name: String, task: AgentTask, parentRunId: String): AgentResult =
        AgentResult.LLMError(
            backend = "none",
            cause = UnsupportedOperationException("no child agent configured for '$name'")
        )
}
```

`AgentLoop` gains an optional constructor parameter:

```kotlin
private val childAgentProvider: ChildAgentProvider = NoOpChildAgentProvider,
```

This maintains the zero-dep core — `ChildAgentProvider` is a pure interface, `NoOpChildAgentProvider` is the default, so all existing agents work unchanged.

### DSL surfacing

`AgentBuilder` gains a `child()` method that registers named child `AgentBuilder` instances. On `build()`, a `ChildAgentDispatcher` is constructed that holds a `Map<String, AgentRunner>` and implements `ChildAgentProvider` by looking up the named child runner.

```kotlin
// inside AgentBuilder
private val childBuilders = mutableMapOf<String, AgentBuilder>()

@KoreDsl
fun child(name: String, block: AgentBuilder.() -> Unit) {
    childBuilders[name] = AgentBuilder(name).apply(block)
}
```

On `AgentBuilder.build()`:

```kotlin
val childProvider = if (childBuilders.isEmpty()) NoOpChildAgentProvider
                   else ChildAgentDispatcher(childBuilders, parentScope = /* scope created below */)
val loop = AgentLoop(
    ...
    childAgentProvider = childProvider,
)
```

### Structured concurrency: child scope from parent CoroutineScope

`AgentRunner` owns `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. For parent cancellation to propagate to children, child `AgentRunner` scopes must be children of the parent scope's Job.

`AgentRunner` gains a secondary constructor that accepts an externally-owned `CoroutineScope`:

```kotlin
class AgentRunner(
    private val loop: AgentLoop,
    scope: CoroutineScope? = null,
) {
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ...
}
```

`ChildAgentDispatcher` constructs child runners with scopes that are children of the parent scope:

```kotlin
class ChildAgentDispatcher(
    private val childBuilders: Map<String, AgentBuilder>,
    private val parentScope: CoroutineScope,
) : ChildAgentProvider {
    private val childRunners: Map<String, AgentRunner> by lazy {
        childBuilders.mapValues { (_, builder) ->
            val childScope = CoroutineScope(
                parentScope.coroutineContext +
                SupervisorJob(parentScope.coroutineContext.job)
            )
            builder.buildWithScope(childScope)
        }
    }

    override suspend fun spawn(name: String, task: AgentTask, parentRunId: String): AgentResult {
        val runner = childRunners[name]
            ?: return AgentResult.LLMError(
                backend = "none",
                cause = RuntimeException("unknown child agent '$name'")
            )
        return runner.run(
            task.copy(metadata = task.metadata + ("parent_run_id" to parentRunId))
        ).await()
    }
}
```

`AgentBuilder.build()` passes its own scope to `ChildAgentDispatcher`. Top-level `AgentBuilder.build()` (no parent) creates its own scope as before — no breaking change.

### Event attribution via parent run ID

`AgentTask.metadata` already exists as `Map<String, String>`. Child tasks carry `"parent_run_id"` in metadata. `AgentLoop` emits `AgentStarted(agentId = childTask.id)` — the agentId is the child's own ID. No new fields on `AgentEvent` are required. The parent/child relationship is traceable via audit log metadata.

OTel trace context propagation works automatically: `ObservableAgentRunner` injects `Context.current().asContextElement()` into its scope. Child agents launched within the parent scope inherit this context element, so child agent run spans are automatically nested under the parent agent run span without additional wiring.

### AgentResult aggregation

For v0.0.2, child results are returned to the `AgentLoop` via `ChildAgentProvider.spawn`. The loop includes child results in conversation history as tool results — a child agent call is modelled as a tool call where the "tool" is the child agent. `AgentResult.ToolError` is returned if the child fails; `BudgetExceeded` propagates. This reuses existing tool result handling in `AgentLoop.runLoop` without new sealed class variants.

---

## Feature 3: OBSV-03 — OTel Span on Skill Activation

### Current state

`AgentLoop.runLoop` already creates a span via the nullable `tracer: Tracer?`:

```kotlin
val span = tracer?.spanBuilder("kore.skill.activate")?.startSpan()
val activatedPrompts = try {
    skillRegistry.activateFor(...)
} finally {
    span?.end()
}
```

The span is created and ended but:
- No attributes are set (no skill names, no agent ID)
- No event is emitted to `EventBus` for the activation
- `EventBusSpanObserver` has a stub comment for OBSV-03

`KoreSpans.SKILL_ACTIVATE = "kore.skill.activate"` is already defined in kore-observability.

### Recommended approach for v0.0.2

**Keep the existing in-loop span, augment it with attributes, and add a `SkillActivated` event to the bus for metrics.**

This avoids replacing a working span with an event-driven lifecycle (which would introduce zero-duration spans since `SkillActivated` fires after activation completes). The bus event serves `EventBusMetricsObserver` for Micrometer counters. The span serves OTel tracing.

In `AgentLoop.runLoop`, change:

```kotlin
val span = tracer?.spanBuilder("kore.skill.activate")?.startSpan()
```

to:

```kotlin
val span = tracer
    ?.spanBuilder(KoreSpans.SKILL_ACTIVATE)
    ?.setParent(Context.current())
    ?.setAttribute(KoreAttrs.AGENT_ID, agentId)
    ?.startSpan()
```

After `activatedPrompts` is collected, before `finally`:

```kotlin
if (activatedPrompts.isNotEmpty()) {
    span?.setAttribute("kore.skill.count", activatedPrompts.size.toLong())
    eventBus.emit(AgentEvent.SkillActivated(agentId = agentId, skillCount = activatedPrompts.size))
    // existing system message prepend follows
}
```

The `KoreSpans.SKILL_ACTIVATE` constant is defined in kore-observability which is a `compileOnly` dependency of kore-core. Since `AgentLoop` already uses `tracer?.spanBuilder("kore.skill.activate")` with a string literal, the refactor to use the constant requires no new `compileOnly` import — the string literal is replaced with the constant only if kore-observability is available. For safety, keep it as a string literal in kore-core and use the constant only in kore-observability. Alternatively, move the constants to a separate object in kore-core (no OTel dependency). The cleanest option: move `KoreSpans` to kore-core as a pure string-constants object (no OTel import needed).

### New AgentEvent variant

```kotlin
@Serializable
@SerialName("SkillActivated")
data class SkillActivated(
    val agentId: String,
    val skillCount: Int,
) : AgentEvent()
```

`skillCount` rather than a list of names keeps the event lean and avoids revealing internal skill naming in external event streams.

### EventBusSpanObserver change

Add a `SkillActivated` branch in `EventBusSpanObserver.start()`:

```kotlin
is AgentEvent.SkillActivated -> {
    // Span lifecycle is managed in AgentLoop directly (D-11 graceful degradation).
    // Observer role here: metrics counter only (see EventBusMetricsObserver).
    // No span management in this observer to avoid double-span.
}
```

Remove the "OBSV-03 stub" comment. This explicitly documents the design decision.

### Span constant relocation (optional but clean)

Move `KoreSpans` and `KoreAttrs` objects to kore-core as `KoreSpanNames` and `KoreAttrKeys` (pure string constants, no OTel import). kore-observability re-exports or delegates to them. This lets `AgentLoop` reference the constant without a `compileOnly` OTel import for the constant alone. This is a refactor with no behavioral impact; defer if scope is tight.

---

## Feature 4: kore-storage integrationTest Task + CI Step

### Current state in kore-storage/build.gradle.kts

```kotlin
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}
```

No `integrationTest` task exists. The 7 Testcontainers tests are excluded from `test` and unrunnable in CI.

kore-kafka already provides the exact template:

```kotlin
tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed integration tests for kore-kafka."
    group = "verification"
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}
```

### kore-storage/build.gradle.kts addition

```kotlin
tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed integration tests for kore-storage."
    group = "verification"
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}
```

No buildSrc convention plugin change needed — `integrationTest` is registered per-module. Adding it to `kore.publishing.gradle.kts` would force it on all publishable modules including those with no Testcontainers tests.

### CI workflow addition

```yaml
integration-test:
  runs-on: [arc-runner-unityinflow]
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

    - name: Integration Tests (kore-storage)
      run: ./gradlew :kore-storage:integrationTest
```

Key decisions:
- `needs: build` — integration tests run only after the unit test job passes. Testcontainers PostgreSQL image pulls are slow; avoid wasting time if unit tests are red.
- Scoped to `:kore-storage:integrationTest` — not `./gradlew integrationTest` which tries all subprojects. kore-kafka uses identical scoping in README instructions.
- `arc-runner-unityinflow` (X64 Hetzner) — Docker is available on these runners. `orangepi-runner` (ARM64) is excluded because Testcontainers ryuk + postgres ARM64 image availability is inconsistent.
- No Docker Compose setup — Testcontainers manages the PostgreSQL container lifecycle.

---

## Component Boundary Map: New vs Modified

```
kore-core (zero-dep: coroutines + stdlib only)
  MODIFIED files:
  ├── AgentEvent.kt           + SkillActivated variant
  ├── AgentLoop.kt            + SkillActivated emit; span setAttribute; childAgentProvider param
  ├── AgentRunner.kt          + optional scope constructor param; buildWithScope factory
  ├── dsl/AgentBuilder.kt     + child() DSL method; ChildAgentDispatcher wiring in build()
  NEW files:
  ├── port/ChildAgentProvider.kt
  └── internal/ChildAgentDispatcher.kt

kore-budget  (NEW MODULE)
  NEW files:
  ├── build.gradle.kts        depends on kore-core + budget-breaker:0.0.1
  └── BudgetBreakerAdapter.kt implements BudgetEnforcer, subscribes to EventBus

kore-spring
  MODIFIED files:
  ├── KoreAutoConfiguration.kt  + BudgetBreakerAutoConfiguration inner class
  │                               + koreBudgetScope bean
  └── build.gradle.kts          + compileOnly(project(":kore-budget"))
  NEW test files:
  └── BudgetBreakerAutoConfigurationTest.kt

kore-observability
  MODIFIED files:
  └── EventBusSpanObserver.kt  + SkillActivated branch (stub removed)

kore-storage
  MODIFIED files:
  └── build.gradle.kts         + integrationTest task registration

.github/workflows/ci.yml
  MODIFIED:
  └── + integration-test job (needs: build; :kore-storage:integrationTest)

settings.gradle.kts
  MODIFIED:
  └── + include(":kore-budget")
```

---

## Data Flow Diagrams

### budget-breaker adapter

```
AgentLoop.run(task)
  → eventBus.emit(AgentStarted)
      → BudgetBreakerAdapter coroutine: opens withBudget(agentId) scope

AgentLoop iteration:
  → budgetEnforcer.checkBudget(agentId)
      → agentId !in breachedAgents → true (continue)
  → [LLM call completes]
  → budgetEnforcer.recordUsage(agentId, tokenUsage)
      → budgetScopes[agentId].trackCall(input, output)
      → if BudgetHardLimitException: breachedAgents += agentId
  → budgetEnforcer.checkBudget(agentId)
      → agentId in breachedAgents → false
  → AgentResult.BudgetExceeded returned

  → eventBus.emit(AgentCompleted)
      → BudgetBreakerAdapter signals scope to close
      → BudgetCircuitBreaker.reports[agentId] = finalReport
```

### hierarchical agent

```
Parent AgentLoop.run(task)
  → LLM: { "tool": "child-agent:worker", "arguments": {...} }
  → AgentLoop calls childAgentProvider.spawn("worker", childTask, parentRunId)
      → ChildAgentDispatcher.childRunners["worker"].run(childTask).await()
          → child runs in CoroutineScope(parentScope.job + SupervisorJob(parentScope.job))
          → child cancels if parent's Job is cancelled
          → child returns AgentResult
      → childTask.metadata["parent_run_id"] = parentRunId  (audit trail)
  → child AgentResult → ToolResult in parent history
  → parent loop continues with child output in context
```

### OBSV-03 skill activation span

```
AgentLoop.runLoop()
  → span = tracer?.spanBuilder("kore.skill.activate")
              ?.setParent(Context.current())
              ?.setAttribute("kore.agent.id", agentId)
              ?.startSpan()
  → activatedPrompts = skillRegistry.activateFor(...)
  → span?.setAttribute("kore.skill.count", count)
  → span?.end()    (in finally)
  → eventBus.emit(AgentEvent.SkillActivated(agentId, skillCount))
      → EventBusSpanObserver: no-op (span already closed)
      → EventBusMetricsObserver: kore.skill.activations counter += 1
```

---

## Suggested Build Order

| Step | Scope | Rationale |
|------|-------|-----------|
| 1 | kore-storage: add `integrationTest` task | Smallest change; unblocks CI immediately; no kore-core touch |
| 2 | ci.yml: add `integration-test` job | Follows from step 1; validates the task works on CI |
| 3 | kore-core: add `AgentEvent.SkillActivated` | Isolated sealed class change; no downstream breakage |
| 4 | kore-core `AgentLoop`: OBSV-03 span attrs + emit | Depends on step 3; contained in one method |
| 5 | kore-observability `EventBusSpanObserver`: SkillActivated branch | Depends on steps 3–4 |
| 6 | kore-budget: new module + `BudgetBreakerAdapter` | Independent of steps 1–5; can run in parallel |
| 7 | kore-spring: `BudgetBreakerAutoConfiguration` | Depends on step 6 |
| 8 | kore-core: `ChildAgentProvider` port + `NoOpChildAgentProvider` | Can start after step 3; zero-risk addition |
| 9 | kore-core `AgentRunner`: optional scope param | Depends on step 8 |
| 10 | kore-core `ChildAgentDispatcher` | Depends on steps 8–9 |
| 11 | kore-core `AgentBuilder`: `child { }` DSL + wiring | Depends on steps 9–10; final kore-core change |

Steps 1–2 and 6–7 can proceed in parallel with steps 3–5 and 8–11. Steps 8–11 are the highest-risk block (most kore-core surface area) and should be done last to avoid rebase conflicts.

---

## Anti-Patterns to Avoid

### Putting adapter logic in kore-spring

`BudgetBreakerAdapter` must live in `kore-budget`, not inline in `KoreAutoConfiguration`. kore-spring's `build.gradle.kts` uses `compileOnly(project(...))` for all adapter modules. Inlining adapter code breaks this boundary and makes kore-spring's runtime classpath depend on budget-breaker when the jar is absent.

### Class literal in @ConditionalOnClass

```kotlin
// WRONG — eager classloading crashes context when kore-budget is absent:
@ConditionalOnClass(BudgetBreakerAdapter::class)

// CORRECT — string form, resolved only when class is present:
@ConditionalOnClass(name = ["io.github.unityinflow.kore.budget.BudgetBreakerAdapter"])
```

This pitfall is already documented in `KoreAutoConfiguration.kt` comments and applies equally to the new budget-breaker inner class.

### Independent CoroutineScope per child AgentRunner

```kotlin
// WRONG — child scope is independent; parent cancellation does not propagate:
val childScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

// CORRECT — child Job is a child of parent Job:
val childScope = CoroutineScope(
    parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job)
)
```

### Registering integrationTest in the kore.publishing convention plugin

`kore.publishing.gradle.kts` handles Maven publishing only. Adding `integrationTest` there applies it to kore-core, kore-observability, and every other publishable module — none of which have Testcontainers tests. Register per-module where tests actually exist.

### Adding budget-breaker as a runtime dependency of kore-core

`BudgetEnforcer` is the port. `BudgetBreakerAdapter` is the adapter. Port and adapter are in different modules (kore-core vs kore-budget). The dependency graph flows one way: `kore-budget -> kore-core`. Any inversion breaks the hexagonal architecture.

---

## Confidence Assessment

| Area | Confidence | Basis |
|------|------------|-------|
| budget-breaker adapter module placement | HIGH | Read kore-kafka/kore-rabbitmq structure; matches established pattern exactly |
| Auto-config triple-gate (`@ConditionalOnClass` string + `@ConditionalOnProperty` + `@ConditionalOnMissingBean`) | HIGH | Read full `KoreAutoConfiguration.kt`; pattern applied verbatim |
| `BudgetBreakerAdapter` using `withBudget` scope via EventBus subscription | MEDIUM | budget-breaker `BudgetCircuitBreaker` and `BudgetScope` read; lifecycle bridging requires careful implementation |
| Hierarchical agent structured concurrency via child scope | HIGH | `AgentRunner.kt` read; standard coroutines `SupervisorJob(parent.job)` pattern |
| OBSV-03 in-loop span + bus event split | HIGH | `AgentLoop.kt` and `EventBusSpanObserver.kt` read; existing nullable tracer path already in place |
| kore-storage `integrationTest` Gradle task | HIGH | kore-kafka `build.gradle.kts` provides identical template |
| CI `integration-test` job | HIGH | `ci.yml` read; arc-runner-unityinflow confirmed for Docker workloads |

---

*Integration architecture research for: kore-runtime v0.0.2 Hardening & Hierarchy*
*Researched: 2026-06-12*
