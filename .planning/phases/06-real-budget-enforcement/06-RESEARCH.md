# Phase 6: Real Budget Enforcement - Research

**Researched:** 2026-06-21
**Domain:** JVM library integration (budget-breaker Tool 05) + Spring Boot 4 auto-configuration + coroutine adapter
**Confidence:** HIGH

## Summary

This phase adds a new `kore-budget` Gradle module whose adapter implements the **existing, byte-identical** `BudgetEnforcer` port using `io.github.unityinflow:budget-breaker:0.0.1` (Tool 05). The headline research question — *does budget-breaker expose a non-throwing pre-check?* — is answered **YES, decisively**. The published `0.0.1` jar exposes a `public final class TokenTracker` with synchronous, non-throwing methods `add(prompt, completion)`, `isAboveHardLimit(): Boolean`, `isAboveSoftLimit(): Boolean`, and `totalTokens: Long`. This means D-04's "internal-tally pre-check + catch-on-record" strategy maps onto `TokenTracker` cleanly and the adapter never needs to enter a `withBudget {}` scope or catch `BudgetHardLimitException` on the happy path.

The API surface was verified two ways this session: (1) live `maven-metadata.xml` from `repo1.maven.org` confirms **only `0.0.1` is published** and `budget-breaker-spring-boot-starter` returns **404** (not published — matches STATE.md and the v0.0.2 "Out of Scope" line); (2) the `0.0.1` jar was downloaded and its bytecode inspected with `javap`, giving byte-accurate constructor and method signatures for `TokenTracker`, `AgentBudget`, and `BudgetHardLimitException`. All classes live in package `io.github.unityinflow.budget`.

**Important supersession note for the planner:** the milestone-level `.planning/research/ARCHITECTURE.md` proposed an EventBus-subscription + `withBudget`-scope adapter design (subscribe to `AgentStarted`/`AgentCompleted`, open a `withBudget` block per run, store the live `BudgetScope`). **That design is superseded by CONTEXT.md D-04.** D-04 locks the simpler `TokenTracker`-direct approach, which `.planning/research/PITFALLS.md` Pitfall 1 (step 4) independently recommends as the correct strategy for a boolean-poll port. The simpler approach has no EventBus dependency, needs no `CoroutineScope` bean, sidesteps the `withBudget` duplicate-agentId guard entirely (→ BUDG-07 isolation for free), and is what the planner must plan against.

**Primary recommendation:** Build `BudgetBreakerAdapter` in a new `kore-budget` module as a `ConcurrentHashMap<String, TokenTracker>`-backed implementation of `BudgetEnforcer`; `recordUsage` → `tracker.add(...)` (non-throwing); `checkBudget` → `!tracker.isAboveHardLimit()`; `getUsage` → read tracker totals. Wire it via a `BudgetBreakerAutoConfiguration` triple-gate (`@ConditionalOnClass(name=[adapter FQCN])` + `@ConditionalOnProperty("kore.budget.enabled", "true")` + `@ConditionalOnMissingBean(BudgetEnforcer::class)`) placed in `kore-spring`, mirroring the kore-kafka pattern.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-00 (anchor):** The `BudgetEnforcer` port stays **byte-identical** — no new methods, no signature changes. The three existing methods (`recordUsage(agentId, usage)`, `checkBudget(agentId): Boolean`, `getUsage(agentId): TokenUsage`) are the entire contract the adapter implements. Per-agent-name overrides and run-end eviction were deliberately dropped to preserve this. Rationale: zero `AgentLoop` ripple, no coordination cost with Phase 7.
- **D-01 (config surface):** **Single global hard token limit** via the existing `kore.budget.default-max-tokens` property (already on `KoreProperties.budget`, consumed today by the `InMemoryBudgetEnforcer` default bean). NO per-agent-name overrides this phase. Keep the `KoreProperties.budget` shape **extensible** so a future `agents.<name>.max-tokens` map can be added without a breaking change.
- **D-02 (scope):** **Hard token-count stop only.** budget-breaker's cost (USD), rate limits, and soft-warning thresholds are NOT wired in Phase 6.
- **D-03 (exceed behavior):** Hitting the hard limit ends the run with `AgentResult.BudgetExceeded` and nothing more — no soft-warning event, no threshold knob this phase.
- **D-04 (adapter bridge):** **Internal-tally pre-check + catch-on-record.** The adapter keeps per-`agentId` accumulated spend. `checkBudget(agentId)` returns whether tallied spend is still under the configured limit (loop stops *before* overshooting). `recordUsage(agentId, usage)` feeds budget-breaker and **catches `BudgetHardLimitException` internally so it NEVER escapes the port** (BUDG-06). Budgets are keyed by `agentId` (= `AgentTask.id`, a per-run UUID) — concurrent agents are isolated, `withBudget` ids never collide (BUDG-07).
- **D-05 (lifecycle):** **No eviction** — the adapter mirrors `InMemoryBudgetEnforcer`'s accepted tradeoff (per-`agentId` state lives for the process lifetime, bounded by running-agent count). The port has no run-end hook and D-00 forbids adding one. Documented limitation.
- **D-06 (module & auto-config):** New `kore-budget` Gradle module (added to `settings.gradle.kts`) provides a `BudgetEnforcer` `@Bean` gated by `@ConditionalOnClass(name=[budget-breaker class])` **and** `@ConditionalOnProperty("kore.budget.enabled", havingValue="true")`. It wins over the kore-spring `InMemoryBudgetEnforcer` default via the existing `@ConditionalOnMissingBean(BudgetEnforcer::class)` ordering. Mirror `KoreAutoConfiguration.kt` conventions. **(Open: whether the auto-config class lives in `kore-budget` or `kore-spring` — Claude's discretion / research; gating semantics above are locked.)**

### Claude's Discretion
- Exact Gradle coordinates/version of `io.github.unityinflow:budget-breaker` and whether kore-budget needs Spring on its compile classpath (vs. a Spring-free adapter + auto-config registered via `kore-spring`).
- Where the auto-config class physically lives (D-06 open clause).
- The precise internal tally structure in the adapter (e.g. `ConcurrentHashMap` mirroring the stub) and how it reconciles its tally with budget-breaker's own accounting so the two never disagree.

### Deferred Ideas (OUT OF SCOPE)
- **Per-agent-name (and per-model) budget overrides** — needs the port to carry agent identity or a side-channel registry; deferred. Config shape kept extensible (D-01) so it slots in later.
- **Cost (USD) budgets** — needs per-model pricing tables; deferred (D-02).
- **Rate limits** from budget-breaker — deferred (D-02).
- **Soft-warning thresholds + `AgentEvent.BudgetWarning` + `kore.budget.warn-at`** — deferred (D-03).
- **Run-end budget eviction / a `release(agentId)` port lifecycle hook** — deferred (D-05).
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| BUDG-05 | Developer enables real enforcement by adding `kore-budget` + `kore.budget.enabled=true` (Spring Boot auto-configured; `InMemoryBudgetEnforcer` remains default when absent) | Triple-gate auto-config (see Pattern 2); `@ConditionalOnMissingBean(BudgetEnforcer::class)` ordering verified in existing `KoreAutoConfiguration.kt`; `ApplicationContextRunner` test matrix in Validation Architecture |
| BUDG-06 | Agent run hitting hard limit ends with `AgentResult.BudgetExceeded`; `BudgetHardLimitException` never escapes the port (test drives adapter to hard limit) | `TokenTracker` non-throwing API means the happy path never throws; defensive catch documented in Pattern 1; `AgentLoop` already returns `BudgetExceeded` on `checkBudget()==false` (lines 158-163, unchanged) |
| BUDG-07 | Concurrent agents have isolated budgets keyed by `AgentTask.id` (UUID), not agent name — no cross-agent interference or `withBudget` id collision | `agentId = task.id` (per-run UUID) is what `AgentLoop` already passes; `ConcurrentHashMap<String,TokenTracker>` per-agentId keying; `TokenTracker`-direct approach never calls `withBudget` so the duplicate-agentId guard is irrelevant; coroutine concurrency test in Validation Architecture |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Token tally per agent run | `kore-budget` adapter (port impl) | budget-breaker `TokenTracker` | Adapter owns the per-agentId map; `TokenTracker` is the per-agent counter + limit check |
| Hard-limit decision | budget-breaker `TokenTracker.isAboveHardLimit()` | `kore-budget` adapter | Library owns the limit comparison; adapter translates to the port's boolean contract |
| Stopping the run on exceed | `kore-core` `AgentLoop` (UNCHANGED) | — | `AgentLoop` already returns `AgentResult.BudgetExceeded` when `checkBudget()==false` (D-00: not modified) |
| Bean selection (real vs stub) | `kore-spring` auto-config | — | Spring conditional-bean ordering picks adapter when present + enabled, else InMemory default |
| Configuration binding | `kore-spring` `KoreProperties.budget` (REUSED) | — | `defaultMaxTokens` already exists; only new key is `kore.budget.enabled` |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `io.github.unityinflow:budget-breaker` | **0.0.1** | Token budget enforcement (`TokenTracker`, `AgentBudget`) | First-party Tool 05; the ONLY published version on Maven Central. Coroutine-aware. `TokenTracker` is public + synchronous + non-throwing — exact fit for D-04. [VERIFIED: repo1.maven.org maven-metadata.xml + jar bytecode] |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | `suspend` port methods | Already pinned in the version catalog (`libs.coroutines.core`). budget-breaker 0.0.1 was built against coroutines 1.10.1 — binary compatible. [VERIFIED: gradle/libs.versions.toml + .planning/research/STACK.md] |
| `project(":kore-core")` | (in-repo) | `BudgetEnforcer` port + `TokenUsage` | The port the adapter implements; `TokenUsage(inputTokens, outputTokens)` is the usage type. [VERIFIED: codebase] |

### Supporting (Spring auto-config — lives in kore-spring, see D-06 resolution)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.springframework.boot:spring-boot-autoconfigure` | 4.0.5 (via BOM) | `@ConditionalOnClass/Property/MissingBean` | Already a dependency of kore-spring; the new `BudgetBreakerAutoConfiguration` inner class uses it |
| `project(":kore-budget")` (compileOnly in kore-spring) | (in-repo) | Lets auto-config reference `BudgetBreakerAdapter` constructor directly | Mirrors how kore-spring declares kore-kafka/kore-rabbitmq `compileOnly` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `TokenTracker` direct (D-04) | `BudgetCircuitBreaker.withBudget {}` scope DSL | `withBudget` requires the agent body to run *inside* its lambda; `AgentLoop` is not entered inside such a lambda, forcing the EventBus-subscription workaround from the milestone ARCHITECTURE.md. D-04 rejects this. `withBudget` also enforces a duplicate-agentId guard (Pitfall 2) that the direct approach avoids. |
| budget-breaker 0.0.1 | budget-breaker 0.1.0 (local tag, richer observability) | 0.1.0 is tagged locally but **NOT on Maven Central**. Phase scope (D-02 hard-stop only) needs none of 0.1.0's additions (`getAllReports`, live snapshots). Use 0.0.1 — it is what's published and it compiles the entire D-04 adapter. |
| Auto-config in `kore-spring` | Auto-config in `kore-budget` | See D-06 Resolution below — kore-spring is recommended. |

**Installation (kore-budget/build.gradle.kts):**
```kotlin
dependencies {
    implementation(project(":kore-core"))
    implementation("io.github.unityinflow:budget-breaker:0.0.1")
    implementation(libs.coroutines.core)

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

**Version verification (performed this session):**
- `curl https://repo1.maven.org/maven2/io/github/unityinflow/budget-breaker/maven-metadata.xml` → `<release>0.0.1</release>`, single version, `lastUpdated 20260511132548`. [VERIFIED]
- `budget-breaker-spring-boot-starter/maven-metadata.xml` → HTTP **404** (not published). [VERIFIED]
- Jar `budget-breaker-0.0.1.jar` (31.5 KB) downloaded; all `io.github.unityinflow.budget.*` classes present. [VERIFIED]

## Package Legitimacy Audit

| Package | Registry | Age | Source Repo | Verdict | Disposition |
|---------|----------|-----|-------------|---------|-------------|
| `io.github.unityinflow:budget-breaker:0.0.1` | Maven Central | published 2026-05-11 | github.com/UnityInFlow/budget-breaker (first-party, Tool 05) | OK | Approved |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none.

This is a **first-party UnityInFlow ecosystem package** authored by the same maintainer, published under the Sonatype-verified `io.github.unityinflow` namespace. Legitimacy was established directly this session: live Maven Central metadata + jar download + `javap` bytecode inspection. No third-party/registry-heuristic risk applies. The `gsd-tools` legitimacy seam was not reachable on PATH in this session; direct evidence supersedes the heuristic.

## budget-breaker 0.0.1 — Byte-Accurate API (from published jar bytecode)

> All types in package `io.github.unityinflow.budget`. Signatures from `javap -p` on `budget-breaker-0.0.1.jar`. [VERIFIED: jar bytecode]

### `TokenTracker` — THE class the D-04 adapter uses
```
public final class TokenTracker {
    public TokenTracker(String agentId, AgentBudget budget)   // public ctor — usable standalone
    public final void    add(long prompt, long completion)     // SYNCHRONOUS, non-suspend, void, does NOT throw
    public final boolean isAboveHardLimit()                    // non-throwing pre-check  ← answers the headline question
    public final boolean isAboveSoftLimit()
    public final double  percentUsed()
    public final long    getPromptTokens()
    public final long    getCompletionTokens()
    public final long    getTotalTokens()
    public final long    getHardLimitTokens()
    public final long    getSoftLimitTokens()
    public final String  getModel()
    public final String  getAgentId()
}
```
**Key facts for the adapter:**
- `add` and `isAboveHardLimit` are **not `suspend`** (no `Continuation` parameter) and **do not throw** — the entire D-04 happy path is exception-free. The port methods are `suspend` only to satisfy the interface; the adapter body is synchronous and needs no dispatcher juggling, no `Thread.sleep`, no blocking.
- `TokenTracker` is **`public final`** (no `internal` modifier) in the published artifact — calling it from kore-budget is supported, not a visibility hack.

### `AgentBudget` — config the tracker needs
```
public final class AgentBudget {
    public AgentBudget(String model, long hardLimitTokens, long softLimitTokens)
    public AgentBudget()   // no-arg default ctor (defaults: model="claude-sonnet-4-6", hard=100_000, soft=80_000)
}
```
Construct as `AgentBudget(model = <any>, hardLimitTokens = properties.budget.defaultMaxTokens, softLimitTokens = <anything ≤ hard>)`. Per D-02/D-03 the soft limit is unused by the adapter (never read `isAboveSoftLimit`), but `AgentBudget`'s init validates `softLimit ≤ hardLimit`, so pass a soft value `≤ defaultMaxTokens` (e.g. equal to hard, or hard/2) to pass construction. **Pin this in the plan** — a soft > hard value throws at construction.

### `BudgetHardLimitException` — caught for BUDG-06 belt-and-braces
```
public final class BudgetHardLimitException extends BudgetException {   // BudgetException extends java.lang.Exception
    public BudgetHardLimitException(String agentId, long tokensUsed, long budgetTokens, double estimatedCostUsd)
}
```
Thrown only out of `BudgetScope.trackCall` (the `withBudget` path). The D-04 `TokenTracker`-direct adapter never triggers it on the happy path, but BUDG-06's contract is "never escapes the port," so the adapter still wraps any budget-breaker call site in a `try/catch (e: BudgetHardLimitException)` and converts to "hard limit reached" state. Catching `BudgetException` (the sealed base) is acceptable and future-proofs against `BudgetSoftLimitException` ever surfacing.

### Types NOT used this phase (deferred per D-02/D-03)
`BudgetCircuitBreaker` (`withBudget` scope DSL, `getReport`, `events: SharedFlow<BudgetEvent>`), `BudgetScope.trackCall` (suspend), `ModelPricing` (USD cost), `BudgetEvent` (`SoftLimitReached/HardLimitExceeded/CallTracked`), `BudgetReport`, `BudgetSoftLimitException`. Listed so the planner knows what to leave untouched.

## Architecture Patterns

### System Architecture Diagram
```
                     application.yml: kore.budget.enabled=true, kore.budget.default-max-tokens=N
                                              │
                                              ▼
        ┌─────────────────────────  kore-spring (Spring Boot 4 auto-config) ─────────────────────────┐
        │  BudgetBreakerAutoConfiguration  (triple-gate)                                              │
        │   @ConditionalOnClass(name=["io.github.unityinflow.kore.budget.BudgetBreakerAdapter"])       │
        │   @ConditionalOnProperty("kore.budget.enabled","true")                                       │
        │   @Bean @ConditionalOnMissingBean(BudgetEnforcer::class) -> BudgetBreakerAdapter(maxTokens)   │
        │                                  │ wins over ▼                                                │
        │   KoreAutoConfiguration.inMemoryBudgetEnforcer()  @ConditionalOnMissingBean(BudgetEnforcer)   │
        └──────────────────────────────────┬──────────────────────────────────────────────────────────┘
                                            │  Spring injects ONE BudgetEnforcer bean
                                            ▼
   kore-core  AgentLoop.runLoop  (UNCHANGED, D-00)
        repeat:
          if (!budgetEnforcer.checkBudget(agentId))  ──► return AgentResult.BudgetExceeded(spent, limit)   (BUDG-06)
          ... LLM call ...
          budgetEnforcer.recordUsage(agentId, callUsage)                                                   (per call)
                                            │
                                            ▼
   kore-budget  BudgetBreakerAdapter : BudgetEnforcer
        trackers = ConcurrentHashMap<String /*agentId=AgentTask.id*/, TokenTracker>            (BUDG-07 isolation)
          recordUsage(id,u): trackers.computeIfAbsent(id){TokenTracker(id, AgentBudget(.., maxTokens, ..))}
                              .add(u.inputTokens, u.outputTokens)        // synchronous, non-throwing
          checkBudget(id)  : !(trackers[id]?.isAboveHardLimit() ?: false)
          getUsage(id)     : trackers[id]?.let{ TokenUsage(it.promptTokens.toInt(), it.completionTokens.toInt()) }
                              ?: TokenUsage(0,0)
```

### Recommended Project Structure
```
kore-budget/                                  # NEW module (settings.gradle.kts include)
├── build.gradle.kts                          # kore-core + budget-breaker:0.0.1 + coroutines
└── src/
    ├── main/kotlin/io/github/unityinflow/kore/budget/
    │   └── BudgetBreakerAdapter.kt           # implements BudgetEnforcer via TokenTracker
    └── test/kotlin/io/github/unityinflow/kore/budget/
        └── BudgetBreakerAdapterTest.kt       # BUDG-06 (no-escape) + BUDG-07 (isolation)

kore-spring/                                  # MODIFIED
├── build.gradle.kts                          # + compileOnly(project(":kore-budget")); + testImplementation(...)
└── src/
    ├── main/kotlin/.../KoreAutoConfiguration.kt   # + BudgetBreakerAutoConfiguration inner class
    └── test/kotlin/.../BudgetBreakerAutoConfigurationTest.kt  # BUDG-05 ApplicationContextRunner matrix

settings.gradle.kts                           # + "kore-budget" in include(...)
KoreProperties.kt                             # + enabled: Boolean = false on BudgetProperties (D-01 extensible)
```

### Pattern 1: TokenTracker-direct adapter (D-04 — the locked strategy)
**What:** A `BudgetEnforcer` impl backed by a `ConcurrentHashMap<String, TokenTracker>`, one tracker per `agentId`. No `withBudget`, no EventBus, no `CoroutineScope`.
**When to use:** This is THE strategy for Phase 6 (D-04). It mirrors `InMemoryBudgetEnforcer`'s keying and no-eviction tradeoff (D-05).
**Example:**
```kotlin
// Source: synthesised from BudgetEnforcer.kt (port), InMemoryBudgetEnforcer.kt (reference),
// and budget-breaker 0.0.1 TokenTracker bytecode [VERIFIED: jar]
package io.github.unityinflow.kore.budget

import io.github.unityinflow.budget.AgentBudget
import io.github.unityinflow.budget.BudgetException
import io.github.unityinflow.budget.TokenTracker
import io.github.unityinflow.kore.core.TokenUsage
import io.github.unityinflow.kore.core.port.BudgetEnforcer
import java.util.concurrent.ConcurrentHashMap

/**
 * Real [BudgetEnforcer] backed by budget-breaker's [TokenTracker] (Tool 05).
 *
 * One [TokenTracker] per agentId (= AgentTask.id, a per-run UUID) gives concurrent-agent
 * isolation for free (BUDG-07). [TokenTracker.add] / [TokenTracker.isAboveHardLimit] are
 * synchronous and non-throwing, so the suspend port methods run without blocking and
 * [io.github.unityinflow.budget.BudgetHardLimitException] never escapes (BUDG-06).
 *
 * No eviction (D-05): tracker state lives for the process lifetime, bounded by running-agent
 * count — same accepted tradeoff as InMemoryBudgetEnforcer.
 */
class BudgetBreakerAdapter(
    private val defaultHardLimitTokens: Long,
    private val model: String = "kore-agent",
) : BudgetEnforcer {
    private val trackers = ConcurrentHashMap<String, TokenTracker>()

    private fun trackerFor(agentId: String): TokenTracker =
        trackers.computeIfAbsent(agentId) {
            TokenTracker(
                agentId = agentId,
                budget = AgentBudget(
                    model = model,
                    hardLimitTokens = defaultHardLimitTokens,
                    // soft ≤ hard required by AgentBudget init validation; soft is unused (D-02/D-03).
                    softLimitTokens = defaultHardLimitTokens,
                ),
            )
        }

    override suspend fun recordUsage(agentId: String, usage: TokenUsage) {
        try {
            trackerFor(agentId).add(usage.inputTokens.toLong(), usage.outputTokens.toLong())
        } catch (e: BudgetException) {
            // Belt-and-braces (BUDG-06): TokenTracker.add does not throw, but the port contract
            // is "no budget-breaker exception escapes". State is already in the tracker;
            // checkBudget() reads isAboveHardLimit() on the next iteration.
        }
    }

    override suspend fun checkBudget(agentId: String): Boolean =
        !(trackers[agentId]?.isAboveHardLimit() ?: false)

    override suspend fun getUsage(agentId: String): TokenUsage =
        trackers[agentId]?.let {
            TokenUsage(it.promptTokens.toInt(), it.completionTokens.toInt())
        } ?: TokenUsage(0, 0)
}
```
*Note for planner:* `add` is non-throwing so the `try/catch` is defensive only; keep it to make the BUDG-06 invariant explicit and self-documenting. `it.promptTokens` / `it.completionTokens` are `Long` (Kotlin property accessors over the `getPromptTokens()`/`getCompletionTokens()` JVM getters) — `.toInt()` matches `TokenUsage`'s `Int` fields (token counts per run are well within Int range at a 100k default).

### Pattern 2: Auto-config triple-gate (BUDG-05 — mirror kore-kafka)
**What:** An inner `@Configuration` class in `KoreAutoConfiguration.kt` that registers the adapter bean only when (a) the adapter class is on the classpath, (b) `kore.budget.enabled=true`, and (c) no other `BudgetEnforcer` bean already exists.
**When to use:** This is the wiring for BUDG-05. Placement: **kore-spring** (see D-06 Resolution).
**Example:**
```kotlin
// Source: mirror of KafkaEventBusAutoConfiguration in KoreAutoConfiguration.kt [VERIFIED: codebase]
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["io.github.unityinflow.kore.budget.BudgetBreakerAdapter"]) // string form (Pitfall 1)
@ConditionalOnProperty(
    prefix = "kore.budget",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class BudgetBreakerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BudgetEnforcer::class)
    fun budgetBreakerAdapter(properties: KoreProperties): BudgetEnforcer =
        io.github.unityinflow.kore.budget.BudgetBreakerAdapter(
            defaultHardLimitTokens = properties.budget.defaultMaxTokens,
        )
}
```
**Why this wins over the InMemory default:** the existing default bean
`KoreAutoConfiguration.inMemoryBudgetEnforcer()` carries `@ConditionalOnMissingBean(BudgetEnforcer::class)`. When the triple-gate's three conditions all pass, `budgetBreakerAdapter` is registered first (or the adapter wins by being present), and the InMemory `@ConditionalOnMissingBean` then sees a `BudgetEnforcer` already exists and backs off. When the gate fails (no `kore-budget` on classpath, or `enabled` unset/false), only the InMemory default remains. This is the same mechanism the StorageAutoConfiguration uses against `inMemoryAuditLog()` — proven in the codebase. **No bean ordering annotations are needed** because both candidates use `@ConditionalOnMissingBean(BudgetEnforcer::class)` and only one gate can be satisfied at a time in a given app (gate present ⇒ adapter wins; gate absent ⇒ default wins). The plan's `ApplicationContextRunner` test must assert exactly one `BudgetEnforcer` bean and its concrete type in each of the four scenarios.

### Anti-Patterns to Avoid
- **Using `BudgetCircuitBreaker.withBudget {}` as a decorator around `AgentLoop.run`** — the loop is not executed inside the lambda, so the milestone ARCHITECTURE.md had to invent an EventBus-subscription bridge. D-04 forbids this; use `TokenTracker` directly.
- **Class-literal `@ConditionalOnClass(BudgetBreakerAdapter::class)`** — eager classloading crashes the context when kore-budget is absent. Use the `name=[...]` string form (every existing conditional in `KoreAutoConfiguration.kt` does).
- **Keying budgets by agent name** — `AgentTask` has no `name` field; the port receives `agentId = task.id` (UUID). Keying by anything else breaks BUDG-07 isolation.
- **Sharing one `TokenTracker` across agents / using `withBudget` with a shared id** — budget-breaker's `withBudget` throws `IllegalArgumentException` on duplicate concurrent agentId (Pitfall 2). The per-agentId-`TokenTracker` map avoids this entirely.
- **Making the adapter `suspend`-block on a dispatcher** — `TokenTracker.add`/`isAboveHardLimit` are synchronous; no `withContext(Dispatchers.IO)` needed. Do not introduce blocking or `Thread.sleep`.
- **`softLimitTokens > hardLimitTokens` in `AgentBudget`** — fails the data class init validation at construction. Set soft ≤ hard.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Per-agent token counting + hard-limit comparison | A bespoke `AtomicLong` counter with manual limit math in the adapter | budget-breaker `TokenTracker` (`add` + `isAboveHardLimit`) | That IS Tool 05's job; re-implementing it forfeits the whole point of Phase 6 (replace the stub with the real library) and diverges from budget-breaker's accounting |
| Spring conditional bean selection | Manual `if (enabled) ... else ...` factory logic | `@ConditionalOnClass` + `@ConditionalOnProperty` + `@ConditionalOnMissingBean` | The codebase already standardises on this triple-gate (kore-kafka, storage); Spring evaluates it correctly and the `ApplicationContextRunner` test harness validates it |
| Config property binding | A custom env-var reader for the token limit | Reuse `KoreProperties.budget.defaultMaxTokens` | Already bound, already used by the InMemory default; D-01 mandates reuse |

**Key insight:** Phase 6 is an *integration* phase, not an algorithm phase. The "logic" (counting, limit comparison) belongs to budget-breaker; kore-budget is a thin port-adapter + Spring wiring. The risk is in the *boundary* (no exception escape, correct keying, correct bean selection), not in any computation.

## Runtime State Inventory

> This is a greenfield additive phase (new module + new bean + one new config key). It is NOT a rename/refactor/migration. No existing stored data, live service config, OS-registered state, secrets, or build artifacts embed a string that changes.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — adapter state is in-memory `ConcurrentHashMap`, no datastore | none |
| Live service config | None — no external service, no UI-stored config | none |
| OS-registered state | None | none |
| Secrets/env vars | None — `kore.budget.*` are non-secret tunables; budget-breaker needs no credentials | none |
| Build artifacts | New `kore-budget` module compiles to a new jar; no stale artifact from a rename | Publish coords `io.github.unityinflow:kore-budget` added to release flow (new module, not a rename) |

**Nothing found in categories 1-4** — verified: the adapter holds only in-process state and the phase adds one boolean property (`kore.budget.enabled`) plus a new module.

## Common Pitfalls

### Pitfall 1: `BudgetHardLimitException` escaping the port (BUDG-06)
**What goes wrong:** If the adapter ever routed through `BudgetCircuitBreaker.withBudget`/`BudgetScope.trackCall`, a hard breach throws `BudgetHardLimitException` out of the suspend call. If uncaught, it propagates through `recordUsage` → `AgentLoop` → `AgentRunner.async {}` and surfaces as a failed `Deferred` on `await()`, silently breaking the "AgentLoop never throws" invariant — callers get an exception instead of `AgentResult.BudgetExceeded`.
**Why it happens:** `InMemoryBudgetEnforcer` never throws; a developer porting to budget-breaker may not realise the library *can* throw.
**How to avoid:** Use `TokenTracker` directly (D-04) — `add` is non-throwing, so the happy path can't throw. Keep a defensive `try/catch (e: BudgetException)` in `recordUsage` regardless, so the port-level invariant is explicit. `checkBudget` then reports exhaustion via `isAboveHardLimit()`, and `AgentLoop`'s existing `return AgentResult.BudgetExceeded(...)` (lines 158-163, unchanged) fires.
**Warning signs:** A test that drives the adapter to its limit sees `AgentRunner.run(task).await()` *throw* instead of returning `BudgetExceeded`.

### Pitfall 2: `withBudget` duplicate-agentId guard (BUDG-07)
**What goes wrong:** `BudgetCircuitBreaker.withBudget` does `activeTrackers.putIfAbsent(agentId, ...)` and throws `IllegalArgumentException("Agent X is already running inside withBudget")` on a duplicate concurrent agentId.
**Why it happens:** Only relevant if you adopt the `withBudget` scope approach. The D-04 `TokenTracker`-direct approach never calls `withBudget`, so this guard is never hit.
**How to avoid:** Stay on `TokenTracker`-direct + per-agentId map. Because `agentId = AgentTask.id` (fresh UUID per run), even hierarchical/concurrent runs get distinct keys → no collision (BUDG-07).
**Warning signs:** `IllegalArgumentException: Agent '...' is already running inside withBudget` in a concurrency test — a signal that someone reintroduced `withBudget`.

### Pitfall 3: `@ConditionalOnClass` class-literal eager-load crash
**What goes wrong:** `@ConditionalOnClass(BudgetBreakerAdapter::class)` (literal) forces the JVM to resolve the symbol at config-scan time; when kore-budget is absent the Spring context fails with `NoClassDefFoundError`.
**Why it happens:** JVM resolves class literals eagerly; string FQCNs are resolved lazily by Spring's condition evaluator.
**How to avoid:** `@ConditionalOnClass(name = ["io.github.unityinflow.kore.budget.BudgetBreakerAdapter"])` — string form, exactly as every existing conditional in `KoreAutoConfiguration.kt`.
**Warning signs:** Context fails to start in apps that include kore-spring but NOT kore-budget.

### Pitfall 4: `AgentBudget` soft-limit validation
**What goes wrong:** `AgentBudget(model, hardLimitTokens, softLimitTokens)` validates `softLimitTokens ≤ hardLimitTokens` in its init block; a soft value above hard throws at construction time, inside `computeIfAbsent`.
**How to avoid:** Set `softLimitTokens = defaultHardLimitTokens` (or any value ≤ hard). The adapter never reads the soft limit (D-02/D-03), so the value is immaterial beyond passing validation.
**Warning signs:** An exception thrown from the first `recordUsage` call for a fresh agentId.

## Code Examples

### Adding the new module (settings.gradle.kts)
```kotlin
// Source: kore settings.gradle.kts [VERIFIED: codebase]
include(
    "kore-core",
    "kore-mcp",
    "kore-llm",
    "kore-test",
    "kore-observability",
    "kore-storage",
    "kore-skills",
    "kore-spring",
    "kore-dashboard",
    "kore-kafka",
    "kore-rabbitmq",
    "kore-budget", // NEW
)
```

### Extending KoreProperties.budget with `enabled` (D-01 extensible)
```kotlin
// Source: KoreProperties.kt BudgetProperties [VERIFIED: codebase]
/** Budget enforcement config. `enabled` opts into the budget-breaker adapter (BUDG-05). */
data class BudgetProperties(
    val defaultMaxTokens: Long = 100_000L,
    val enabled: Boolean = false, // NEW — gates BudgetBreakerAutoConfiguration; default false keeps InMemory stub
    // Future (deferred): agents: Map<String, AgentBudgetOverride> = emptyMap()
)
```
*Note:* `@ConditionalOnProperty("kore.budget.enabled","true")` reads the same key regardless of whether it is also a typed property; adding the field here makes it appear in `spring-boot-configuration-processor` metadata and documents it. The conditional fires on the raw property, so even without this field the gate works — but adding it is cleaner and keeps the shape extensible per D-01.

### kore-spring build.gradle.kts additions
```kotlin
// Source: mirror of kore-kafka/kore-rabbitmq compileOnly pattern [VERIFIED: codebase]
compileOnly(project(":kore-budget"))
// fire the @ConditionalOnClass gate in the auto-config context test:
testImplementation(project(":kore-budget"))
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `InMemoryBudgetEnforcer` stub (`ConcurrentHashMap<String,TokenUsage>`, simple `<` compare) | budget-breaker `TokenTracker`-backed adapter | Phase 6 | Real hard-stop enforcement; stub remains the zero-config default |
| Milestone ARCHITECTURE.md: EventBus-subscription + `withBudget` scope adapter | CONTEXT.md D-04: `TokenTracker`-direct, no EventBus, no scope | Phase 6 discuss (2026-06-21) | Simpler adapter, no `CoroutineScope` bean, no `withBudget` guard exposure |

**Deprecated/outdated for THIS phase:**
- The EventBus-subscription/`withBudget`-scope adapter design in `.planning/research/ARCHITECTURE.md` lines 41-70 and the `koreBudgetScope` `CoroutineScope` bean in lines 99-102 — **superseded by D-04**. The planner should NOT plan a `koreBudgetScope` bean, an `EventBus` constructor param, or a `withBudget` subscriber coroutine. The auto-config bean takes only `KoreProperties`.
- budget-breaker 0.1.0 features (`getAllReports`, `subscriptions`, live snapshots) — out of scope (D-02) and not on Maven Central.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `it.promptTokens`/`it.completionTokens` (Long) fit in `Int` for `TokenUsage` at the 100k default limit | Pattern 1 | Negligible — 100k ≪ Int.MAX (2.1B). If a user sets a multi-billion-token limit, prefer `Math.toIntExact` or keep the InMemory pattern's `Int` truncation. Plan can add a clamp; not blocking. |
| A2 | budget-breaker 0.0.1's `kotlinx-coroutines-core:1.10.1` transitive resolves cleanly to the repo's pinned 1.10.2 with no exclusion | Standard Stack | LOW — STACK.md verified binary compatibility; if a conflict surfaces, Gradle picks the higher (1.10.2). Plan a smoke build. |
| A3 | The two `@ConditionalOnMissingBean(BudgetEnforcer::class)` candidates never both register (gate satisfied ⇒ adapter wins; gate unsatisfied ⇒ default wins) | Pattern 2 | LOW — proven by the analogous StorageAutoConfiguration vs inMemoryAuditLog in the same file. The `ApplicationContextRunner` matrix verifies all four scenarios. |

All other claims are `[VERIFIED]` (jar bytecode / live Maven Central / codebase read) or `[CITED]` from in-repo research docs.

## Open Questions

1. **D-06 open clause — auto-config in kore-budget vs kore-spring?**
   - What we know: kore-spring already owns ALL auto-config (LLM, storage, observability, skills, kafka, rabbitmq) via `compileOnly(project(...))` + `@ConditionalOnClass(name=...)`. kore-budget itself needs NO Spring on its compile classpath if the auto-config lives in kore-spring.
   - **Resolution (recommended):** Put `BudgetBreakerAutoConfiguration` as an inner class in `kore-spring`'s `KoreAutoConfiguration.kt`, exactly like `KafkaEventBusAutoConfiguration`. kore-budget stays **Spring-free** (pure adapter + budget-breaker), which keeps it usable by standalone DSL users (the same reason kore-kafka has no Spring deps). Add `compileOnly(project(":kore-budget"))` + `testImplementation(project(":kore-budget"))` to kore-spring. This is the lowest-friction, most-consistent placement. The planner should adopt this unless a reason to keep kore-budget Spring-aware emerges.
   - Registration: no new `AutoConfiguration.imports` entry needed — the inner class is nested under the already-registered `KoreAutoConfiguration` (same as Kafka/RabbitMQ). (The standalone `KoreDashboardAutoConfiguration` is the only top-level extra, and only because of an eager-load issue specific to it.)

2. **Should the adapter expose `getUsage` from the tracker totals or keep a parallel tally?**
   - What we know: `TokenTracker` already tracks `promptTokens`/`completionTokens`; reading them keeps the adapter's view and budget-breaker's view identical (Claude's-discretion item: "never disagree"). A parallel `ConcurrentHashMap<String,TokenUsage>` would risk drift.
   - Recommendation: read `getUsage` straight off the tracker (Pattern 1). Single source of truth = no reconciliation needed.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| `io.github.unityinflow:budget-breaker` | kore-budget adapter | ✓ (Maven Central) | 0.0.1 | none needed — published |
| `budget-breaker-spring-boot-starter` | (NOT used) | ✗ (404) | — | Not required — gate on core class only (D-06, REQUIREMENTS Out-of-Scope) |
| JDK 21 / Gradle 9 / Kotlin 2.x | build | ✓ (existing repo toolchain) | per `jvmToolchain(21)` | — |

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** `budget-breaker-spring-boot-starter` is unpublished and intentionally unused — the adapter is the Spring integration layer, gated on the core library class only.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (`libs.junit5` = 5.12.0) runner + Kotest assertions (`libs.kotest.assertions` = 6.1.11) + MockK (`libs.mockk` = 1.14.0) |
| Config file | per-module `build.gradle.kts` `tasks.test { useJUnitPlatform() }` |
| Quick run command | `./gradlew :kore-budget:test` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| BUDG-05 | Auto-config selects adapter when `kore-budget` on classpath AND `kore.budget.enabled=true`; InMemory otherwise | integration (Spring) | `./gradlew :kore-spring:test --tests "*BudgetBreakerAutoConfigurationTest"` | ❌ Wave 0 |
| BUDG-06 | Driving the adapter past the hard limit makes `checkBudget` return false and `BudgetHardLimitException` never escapes | unit (coroutine) | `./gradlew :kore-budget:test --tests "*BudgetBreakerAdapterTest"` | ❌ Wave 0 |
| BUDG-07 | Two agents with distinct `AgentTask.id`s have isolated tallies; no cross-interference, no `withBudget` collision | unit (concurrency) | `./gradlew :kore-budget:test --tests "*BudgetBreakerAdapterTest"` | ❌ Wave 0 |

### Test design notes (for VALIDATION.md / planner)
- **BUDG-05 — `ApplicationContextRunner` matrix (4 scenarios):**
  1. kore-budget on classpath + `kore.budget.enabled=true` ⇒ exactly one `BudgetEnforcer`, type `BudgetBreakerAdapter`.
  2. kore-budget on classpath + `enabled` unset/false ⇒ one `BudgetEnforcer`, type `InMemoryBudgetEnforcer`.
  3. kore-budget on classpath + `enabled=false` ⇒ type `InMemoryBudgetEnforcer`.
  4. (Optional, harder to simulate) kore-budget absent ⇒ `InMemoryBudgetEnforcer`. Since `testImplementation(project(":kore-budget"))` puts it on the test classpath, simulate "absent" via `FilteredClassLoader(BudgetBreakerAdapter::class.java)` on the `ApplicationContextRunner`, mirroring how Spring Boot tests exclude classes. Assert `InMemoryBudgetEnforcer`.
  Use `ApplicationContextRunner().withConfiguration(AutoConfigurations.of(KoreAutoConfiguration::class.java)).withPropertyValues("kore.budget.enabled=true")...run { ctx -> ctx.getBean(BudgetEnforcer::class.java) shouldBe instanceOf<BudgetBreakerAdapter>() }`.
- **BUDG-06 — no-escape:** With `BudgetBreakerAdapter(defaultHardLimitTokens = 10L)`, in a `runTest { }`: `recordUsage("a", TokenUsage(100, 100))` must NOT throw; then `checkBudget("a") shouldBe false`; `getUsage("a").totalTokens shouldBe 200`. Optionally drive a real `AgentLoop` with a `MockLLMBackend` emitting a `Usage` chunk over-limit and assert the result is `AgentResult.BudgetExceeded` (not a thrown exception). Assert no `BudgetHardLimitException`/`BudgetException` propagates (`shouldNotThrowAny { ... }`).
- **BUDG-07 — isolation/concurrency:** In `runTest { }`, launch two coroutines with distinct ids (`"agent-A"`, `"agent-B"`); `recordUsage("agent-A", overLimit)`, leave `"agent-B"` under limit; assert `checkBudget("agent-A") == false` AND `checkBudget("agent-B") == true`, and `getUsage("agent-B")` reflects only B's tokens. Stress variant: many concurrent `recordUsage` calls across N distinct ids (`(1..N).map { async { ... } }.awaitAll()`) — assert each id's total equals its own contributions (no cross-talk) and that no `IllegalArgumentException` (the `withBudget` guard) is ever raised, proving the adapter never touched `withBudget`. Use `kotlinx-coroutines-test` `runTest`.

### Sampling Rate
- **Per task commit:** `./gradlew :kore-budget:test` (and `:kore-spring:test` when touching auto-config)
- **Per wave merge:** `./gradlew test`
- **Phase gate:** `./gradlew build` (compile + ktlint + all tests) green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `kore-budget/src/test/kotlin/io/github/unityinflow/kore/budget/BudgetBreakerAdapterTest.kt` — BUDG-06 + BUDG-07
- [ ] `kore-spring/src/test/kotlin/io/github/unityinflow/kore/spring/BudgetBreakerAutoConfigurationTest.kt` — BUDG-05
- [ ] No new framework install — JUnit5/Kotest/MockK/coroutines-test all already in the version catalog

## Security Domain

> `security_enforcement` config key not located in this session; treated as enabled. This phase adds no authentication, session, access-control, cryptography, or untrusted-input-parsing surface.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | budget-breaker needs no credentials; no auth surface added |
| V3 Session Management | no | — |
| V4 Access Control | no | — |
| V5 Input Validation | minimal | The only external input is the `kore.budget.default-max-tokens`/`enabled` config (operator-supplied, Spring-bound, typed). `AgentBudget` validates `soft ≤ hard` internally |
| V6 Cryptography | no | No secrets, no crypto |

### Known Threat Patterns
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Resource exhaustion via unbounded token spend | Denial of Service | This phase IS the mitigation — a hard token-count stop bounding spend per agent run |
| Per-process tracker map growth (D-05 no-eviction) | Denial of Service (slow) | Documented accepted limitation, bounded by concurrent running-agent count; revisit if a lifecycle hook is added |

## Sources

### Primary (HIGH confidence)
- budget-breaker `0.0.1` jar bytecode — `javap -p` on `io.github.unityinflow.budget.{TokenTracker,AgentBudget,BudgetHardLimitException,BudgetException,BudgetScope,BudgetCircuitBreaker}` (downloaded from Maven Central this session) [VERIFIED]
- `https://repo1.maven.org/maven2/io/github/unityinflow/budget-breaker/maven-metadata.xml` — only `0.0.1` published; starter 404 [VERIFIED]
- kore codebase — `BudgetEnforcer.kt`, `InMemoryBudgetEnforcer.kt`, `AgentLoop.kt` (lines 156-186), `AgentTask.kt`, `TokenUsage.kt`, `AgentResult.kt`, `KoreAutoConfiguration.kt`, `KoreProperties.kt`, `settings.gradle.kts`, `kore-kafka/build.gradle.kts`, `kore-spring/build.gradle.kts`, `gradle/libs.versions.toml` [VERIFIED]
- `.planning/phases/06-real-budget-enforcement/06-CONTEXT.md` — locked decisions D-00..D-06 [CITED]

### Secondary (MEDIUM confidence)
- `.planning/research/STACK.md` — budget-breaker 0.0.1 API table (read from source at tag v0.0.1); 0.1.0-not-published note [CITED]
- `.planning/research/ARCHITECTURE.md` — kore-budget module placement; auto-config triple-gate (NOTE: its EventBus/withBudget adapter design is superseded by D-04) [CITED]
- `.planning/research/PITFALLS.md` — Pitfall 1 (exception boundary), Pitfall 2 (withBudget duplicate-id guard), `TokenTracker`-direct recommendation [CITED]
- `.planning/STATE.md` — budget-breaker:0.0.1 published-verified note; starter unpublished [CITED]

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — version + every signature verified from the published jar this session
- Architecture: HIGH — adapter shape verified against port source + budget-breaker bytecode; D-04 confirms strategy
- Auto-config: HIGH — pattern copied verbatim from working kore-kafka conditional in the same file
- Pitfalls: HIGH — cross-checked against jar bytecode and prior milestone PITFALLS.md

**Research date:** 2026-06-21
**Valid until:** 2026-07-21 (stable — budget-breaker 0.0.1 is a single immutable published artifact; re-check only if 0.1.0 ships to Central and the planner opts to bump)
