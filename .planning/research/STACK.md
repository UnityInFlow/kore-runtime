# Stack Research

**Domain:** kore-runtime v0.0.2 Hardening & Hierarchy (delta from v0.0.1)
**Researched:** 2026-06-12
**Confidence:** HIGH

> This document covers ONLY the four new v0.0.2 capabilities. The full v0.0.1 stack
> (Kotlin 2.3.0, Spring Boot 4.0.5, Ktor 3.2, Exposed 1.0, OTel, MCP SDK 0.11.0, etc.)
> is validated and unchanged. Do not alter versions pinned in `gradle/libs.versions.toml`
> except where noted below.

## Headline Findings

1. **`io.github.unityinflow:budget-breaker` — only `0.0.1` is on Maven Central** (verified
   directly against `repo1.maven.org` metadata, lastUpdated 2026-05-11). The local repo at
   `../05-budget-breaker` is at version `0.1.0` with tag `v0.1.0` created but **not published**.
   `budget-breaker-spring-boot-starter` returns **404 on Central — not published at any version**.
2. **Hierarchical agents need ZERO new dependencies** — `kotlinx-coroutines-core` (already
   pinned at 1.10.2) provides everything: `coroutineScope`, `supervisorScope`, `Job`
   hierarchy, cancellation propagation.
3. **OBSV-03 needs ZERO new dependencies — but the `SkillActivated` event does not exist yet.**
   Verified by grep: `kore-core`'s `AgentEvent` sealed class has no `SkillActivated` variant and
   `kore-skills` emits no events. The span constant `KoreSpans.SKILL_ACTIVATE = "kore.skill.activate"`
   already exists in `KoreTracer.kt`. OBSV-03 is therefore an event-plumbing task (kore-core +
   kore-skills + kore-observability), not a dependency task.
4. **integrationTest task needs ZERO new dependencies** — Testcontainers deps are already
   `testImplementation` on kore-storage; the 7 tests are already `@Tag("integration")`-ed.
   Recommend a tag-filtered second `Test` task (no source-set surgery). Bump Testcontainers
   1.20.0 → 1.21.4 (latest 1.x); do NOT move to Testcontainers 2.x in this milestone.

## Recommended Stack

### New Dependencies (the only ones)

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| `io.github.unityinflow:budget-breaker` | **0.0.1** (published) — see "0.1.0 decision" below | Real `BudgetEnforcer` adapter behind the existing port | Tool 05 core library. Coroutine-aware token circuit breaker with soft/hard limits, `SharedFlow<BudgetEvent>`, and `ModelPricing` cost estimation. Only-version-on-Central verified via repo1 maven-metadata.xml. |

That is the entire new-dependency list. Everything else in this milestone reuses pinned libraries.

### Version Bumps (optional but recommended)

| Library | Current pin | Bump to | Why |
|---------|-------------|---------|-----|
| `org.testcontainers:postgresql` / `:junit-jupiter` | 1.20.0 | **1.21.4** | Latest 1.x line (verified repo1 metadata `<release>1.21.4</release>`). Bug fixes + newer Docker engine compatibility — relevant because these tests will now actually run in CI on the ARC runners. Drop-in upgrade within 1.x. |
| `kotlinx-coroutines-core` / `-test` | 1.10.2 | 1.11.0 (optional) | 1.11.0 is the latest stable (verified repo1 `<release>1.11.0</release>`). **Not required** for hierarchical agents — 1.10.2 has every primitive needed. Bump only if you want to stay current; bump core and test together. budget-breaker 0.0.1 was built against coroutines 1.10.1 — binary compatible with both. |

### budget-breaker 0.0.1 — Actual API Surface (read from source at tag v0.0.1)

Package `io.github.unityinflow.budget`:

| Type | Members (0.0.1) | Notes for the adapter |
|------|-----------------|----------------------|
| `BudgetCircuitBreaker` | ctor`(defaultBudget: AgentBudget, pricing: ModelPricing, onSoftLimit: ((BudgetReport) -> Unit)?)`; `suspend fun <T> withBudget(agentId, budget = default, block: suspend BudgetScope.() -> T): T`; `fun getReport(agentId): BudgetReport?`; `val events: SharedFlow<BudgetEvent>` | `withBudget` wraps the block in `coroutineScope { }`; hard-limit breach throws `BudgetHardLimitException` out of the block |
| `BudgetScope` | `suspend fun trackCall(promptTokens: Long, completionTokens: Long)` — internal ctor, only obtainable inside `withBudget` | Emits `CallTracked`, then `HardLimitExceeded`+throw, then first-time `SoftLimitReached`+callback |
| `TokenTracker` | **public ctor**`(agentId: String, budget: AgentBudget)`; `add(prompt, completion)`; `isAboveSoftLimit()`; `isAboveHardLimit()`; `percentUsed()`; `promptTokens/completionTokens/totalTokens` | Key escape hatch — usable standalone without `withBudget` (see adapter strategy) |
| `AgentBudget` | `data class (model = "claude-sonnet-4-6", hardLimitTokens = 100_000, softLimitTokens = 80_000)` with init validation | Maps from kore agent config |
| `ModelPricing` | ctor`(overrides: Map<String, PriceConfig>)`; `estimateCost(model, promptTokens, completionTokens): Double`; built-in Claude/GPT/Gemini defaults | Unchanged between 0.0.1 and 0.1.0 (verified via git diff) |
| `BudgetEvent` (sealed) | `SoftLimitReached(agentId, tokensUsed, budgetTokens, percentUsed)` · `HardLimitExceeded(agentId, tokensUsed, budgetTokens, estimatedCostUsd)` · `CallTracked(agentId, tokensUsed, promptTokens, completionTokens)` | 0.0.1's `CallTracked` has **no `model` field** (added in 0.1.0) |
| `BudgetException` (sealed) | `BudgetSoftLimitException`, `BudgetHardLimitException(agentId, tokensUsed, budgetTokens, estimatedCostUsd)` | `BudgetHardLimitException` → map to `AgentResult.BudgetExceeded` |
| `BudgetReport` | data class: agentId, model, prompt/completion/total tokens, estimatedCostUsd, softLimitBreachCount, hardLimitBreached, durationMs, percentUsed | Feed the dashboard cost summary |

**What 0.1.0 adds (local HEAD, NOT yet on Central):** `getAllReports(): Map<String, BudgetSnapshot>`
(live in-flight snapshots), `subscriptions: StateFlow<Int>` (collector-ready synchronization),
`getActiveTrackerCount()`, `getTotalSoftBreaches()`, `getTotalHardBreaches()`, `modelOf()`,
`BudgetSnapshot` class, `CallTracked.model`, `TokenTracker.model/hardLimitTokens/softLimitTokens/softLimitBreachCount`,
and a fail-fast concurrency contract on duplicate `agentId` in concurrent `withBudget` calls.

**The 0.1.0 decision:** budget-breaker `v0.1.0` is tagged locally and release-ready (its v1.0
milestone was archived 2026-06-12). Pushing the tag publishes core via the existing nmcp CI.
**Recommendation: publish budget-breaker 0.1.0 core first, then build the kore adapter against
0.1.0.** The observability surface (`getAllReports`, `subscriptions`, live snapshots) is exactly
what kore's dashboard/metrics want, and the duplicate-agentId fail-fast matters once hierarchical
agents multiply concurrent runs. Fallback: if publishing is blocked, code the adapter strictly
against the 0.0.1 subset above — it compiles against both.

### Hierarchical Agents — Coroutine Primitives (no new deps)

All from `kotlinx-coroutines-core` already on kore-core's runtime classpath (the ONLY runtime
dep — zero-dep core constraint is preserved):

| Primitive | Use |
|-----------|-----|
| `coroutineScope { }` | Parent agent's body — children launched inside it; parent completes only when all children complete; any child failure cancels siblings + parent |
| `supervisorScope { }` | Alternative when one child agent's failure should NOT cancel sibling agents — parent cancellation still propagates down. Recommended default for supervisor/worker patterns; surface the choice in the DSL (e.g. `childFailure = CANCEL_SIBLINGS / ISOLATE`) |
| `launch` / `async` | Spawn child agents; `async` when the parent consumes child `AgentResult`s |
| `Job` hierarchy + `CancellationException` | Cancelling the parent's `Job` cancels all children automatically — this is the entire "cancelling parent cancels children" requirement; no bookkeeping needed if children are launched from the parent's scope |
| `joinAll` / `awaitAll` | Parent waits for child completion |

**The one rule that makes the feature work:** child agents MUST be launched from the parent
agent's own `CoroutineScope` (the scope `AgentLoop` already runs in), never from a fresh
`CoroutineScope(...)`, `GlobalScope`, or a scope with an unrelated `Job` — any of those detach
the child from the cancellation tree and silently break the requirement.

**Interaction with budget-breaker:** `withBudget`'s internal `coroutineScope` composes correctly
with this — a parent's hard-limit `BudgetHardLimitException` cancels the parent scope, which
cancels child agents. If each child gets its own budget, note 0.0.1 has no duplicate-agentId
guard (0.1.0 does) — derive child agentIds (`parent.child-1`) to avoid tracker collisions.

### OBSV-03 Skill-Activation Span (no new deps)

Existing pins suffice: `otel-extension-kotlin` 1.61.0, `otel-api` (compileOnly, version via
Spring Boot 4 BOM), decorator pattern in kore-observability.

What actually has to change (verified against source):

1. **kore-core:** add `AgentEvent.SkillActivated(agentId, skillName, …)` sealed variant —
   it does not exist today (`AgentEvent` has Agent/LLM/Tool Started/Completed variants only).
   Annotate `@Serializable` like siblings; remember kotlinx-serialization-core is `compileOnly`
   on kore-core, so downstream test modules touching the new variant need
   `testImplementation(libs.serialization.core)` (this exact gap caused the post-v0.0.1
   `NoClassDefFoundError`s — see knowledge base).
2. **kore-skills / agent loop:** publish the event at the activation site (where
   `PatternMatcher`/`SkillRegistryAdapter` resolves a skill into the run) via the existing
   `EventBus` port.
3. **kore-observability:** handle the new variant in `EventBusSpanObserver.start()`'s `when` —
   the `KoreSpans.SKILL_ACTIVATE` constant is already defined and KDoc in that file already
   marks the OBSV-03 stub. Skill activation is synchronous pattern matching, so emit ONE event
   and create a start+end span at handle time (no open-span map entry needed → no leak-guard
   changes). Add a `KoreAttrs.SKILL_NAME` attribute constant alongside the existing attrs.

### kore-storage integrationTest Task — Gradle 9 Pattern (no new deps)

Current state (verified): the 7 Testcontainers tests live in `src/test`, tagged
`"integration"`, excluded via `tasks.test { useJUnitPlatform { excludeTags("integration") } }`.

**Recommended: tag-filtered second `Test` task on the existing test source set** — zero file
moves, ~10 lines in `kore-storage/build.gradle.kts`:

```kotlin
val integrationTest by tasks.registering(Test::class) {
    description = "Runs the Testcontainers-backed integration tests (requires Docker)."
    group = "verification"
    useJUnitPlatform { includeTags("integration") }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.test)
}
// Deliberately NOT wired into `check` — CI invokes it as an explicit step;
// local `./gradlew build` stays Docker-free.
```

**Alternative considered — JVM Test Suite plugin** (`testing { suites { register<JvmTestSuite>("integrationTest") } }`):
the idiomatic Gradle 9 direction with a separate `src/integrationTest` source set, and the
Kotlin JVM plugin handles the extra source set fine. Rejected for this milestone because it
forces moving the 7 test files, re-declaring all Testcontainers/Exposed/serialization deps in
suite scope, and the API is still `@Incubating` in Gradle 9. Revisit if integration tests
spread to more modules (kore-kafka/kore-rabbitmq already have tagged container tests too).

**CI step** (`.github/workflows/ci.yml`):

```yaml
  integration-test:
    runs-on: [arc-runner-unityinflow]
    steps:
      - uses: actions/checkout@v4
      - run: ./gradlew :kore-storage:integrationTest
```

**Critical CI risk to verify FIRST:** Testcontainers needs a reachable Docker daemon. ARC
(Actions Runner Controller) runners run inside Kubernetes pods — Docker is only available if
the scale set is configured in `dind` mode (or with an external Docker host via `DOCKER_HOST`
+ `TESTCONTAINERS_HOST_OVERRIDE`). Confirm the `arc-runner-unityinflow` scale set's
`containerMode` before planning; if it is not `dind`, that infra change is a prerequisite
task for this milestone, not an afterthought. (Testcontainers' own docs call out ARC/dind as
the supported pattern for k8s-hosted runners.)

## Integration Points (hexagonal fit)

| New piece | Where it lives | How it plugs in |
|-----------|----------------|-----------------|
| `BudgetBreakerEnforcer` adapter | **New module `kore-budget`** (mirrors the kore-kafka/kore-rabbitmq adapter-module pattern) — `implementation("io.github.unityinflow:budget-breaker:<ver>")` | Implements the existing `BudgetEnforcer` port from kore-core. kore-core untouched. |
| Spring wiring | kore-spring | Same triple-gate as Kafka/RabbitMQ: `@ConditionalOnClass(name = ["io.github.unityinflow.budget.BudgetCircuitBreaker"])` + `@ConditionalOnMissingBean(BudgetEnforcer::class)` + explicit property (e.g. `kore.budget.type=budget-breaker`). Context tests assert at bean-definition level. |
| Child-agent API | kore-core DSL + AgentLoop | Pure kotlinx.coroutines; no port changes. New DSL surface (e.g. `spawn { agent(...) }` inside a parent) is a kore-core API addition. |
| `SkillActivated` span | kore-core (event) → kore-skills (emit) → kore-observability (span) | Existing `EventBus` port + existing `KoreSpans.SKILL_ACTIVATE` constant. |
| integrationTest | kore-storage build + CI | Build-logic only. |

**Adapter shape — the one real design decision.** kore's `BudgetEnforcer` port is
record/check style (`recordUsage` / `checkBudget` / `getUsage`), while budget-breaker's primary
API is scope-based (`withBudget { trackCall(...) }` — `BudgetScope` has an internal constructor
and is unreachable outside `withBudget`). Two honest strategies:

- **(a) Tracker-based adapter (recommended for this milestone):** the adapter keeps a
  `ConcurrentHashMap<String, TokenTracker>` (`TokenTracker` is public with a public
  constructor), maps `recordUsage` → `tracker.add(...)`, `checkBudget` →
  `!tracker.isAboveHardLimit()`, and uses `ModelPricing.estimateCost` for cost reporting.
  Fits the port exactly, never throws into the loop (`AgentResult.BudgetExceeded` flow
  preserved), drop-in replacement for `InMemoryBudgetEnforcer`. Downside: bypasses
  `BudgetCircuitBreaker.events` (no `SharedFlow` emissions).
- **(b) `withBudget`-wrapping at the loop boundary:** wrap each agent run in
  `breaker.withBudget(agentId) { ... }` to get hard-limit scope cancellation + the events
  Flow (bridgeable onto kore's `EventBus`). This is the deeper integration and composes
  beautifully with hierarchical cancellation, but it changes how `AgentLoop` is invoked
  (beyond the port) and turns `BudgetHardLimitException` into an exception path the loop must
  catch and convert to `AgentResult.BudgetExceeded`. Defer to v0.1.0 unless the planner wants
  the events Flow now.

## Installation

```kotlin
// gradle/libs.versions.toml additions
// [versions]
// budget-breaker = "0.0.1"   # or "0.1.0" once the v0.1.0 tag is pushed/published
// [libraries]
// budget-breaker = { module = "io.github.unityinflow:budget-breaker", version.ref = "budget-breaker" }

// kore-budget/build.gradle.kts
dependencies {
    implementation(project(":kore-core"))
    implementation(libs.budget.breaker)
    implementation(libs.coroutines.core)
}

// Version bump in libs.versions.toml
// testcontainers = "1.21.4"   (from 1.20.0)
```

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| `io.github.unityinflow:budget-breaker-spring-boot-starter` | **Not published to Maven Central (404 verified)** — finished locally 2026-06-12 but pending release. Also: kore-spring already owns its conditional-wiring idiom; depending on a second auto-config that registers its own `BudgetCircuitBreaker`, SLF4J logger, Actuator endpoint, and Micrometer binder beans would double-register observability kore already provides | Direct dep on `budget-breaker` core in `kore-budget`; kore-spring constructs the `BudgetCircuitBreaker`/`TokenTracker` wiring itself. When the starter ships, both use `@ConditionalOnMissingBean` so they coexist |
| Testcontainers 2.x (2.0.5 is latest) | Major-version migration: artifacts renamed (`postgresql` → `testcontainers-postgresql`), package relocations, JUnit 4 support removed, container constructors require explicit images. Pure scope creep for "make 7 existing tests run in CI" | Stay on 1.x, bump to 1.21.4 |
| `GlobalScope` / fresh `CoroutineScope(Job())` for child agents | Detaches children from the parent's Job — parent cancellation silently stops propagating, which is the entire feature | Launch children from the parent agent's existing scope (`coroutineScope`/`supervisorScope` inside the loop) |
| New OTel dependencies for OBSV-03 | `otel-api` (compileOnly) + `otel-extension-kotlin` 1.61.0 already cover span creation; the span-name constant already exists | Extend `EventBusSpanObserver`'s `when` block |
| JVM Test Suite plugin for this milestone | Still `@Incubating` in Gradle 9; forces moving the 7 tagged test files + duplicating dependency declarations into suite scope | Tag-filtered second `Test` task on the existing `test` source set |
| Custom parent/child Job bookkeeping (maps of child Jobs, manual cancel loops) | Structured concurrency already guarantees cancellation propagation; manual registries reintroduce leak bugs the runtime exists to avoid | The `Job` hierarchy itself |

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|-----------------|-------|
| budget-breaker 0.0.1 | kotlinx-coroutines 1.10.x and 1.11.0 | Built against 1.10.1; only uses stable coroutine/Flow APIs (verified from source) |
| budget-breaker 0.0.1 | JVM 21 / Kotlin 2.3 consumers | Built with Kotlin 2.1.21, toolchain 21; Kotlin 2.3 consumes 2.1-compiled libs fine (backward compat) |
| Testcontainers 1.21.4 | JUnit 5.12 (`org.testcontainers:junit-jupiter`) | Same artifact coordinates as 1.20.0 — drop-in |
| kotlinx-coroutines 1.11.0 (if bumped) | Kotlin 2.3.0 | 1.11.0 targets Kotlin 2.2+; bump `coroutines` and `coroutines-test` refs together |
| `SkillActivated` event addition | kore downstream test modules | New `@Serializable` sealed variant ⇒ any module's tests touching `AgentEvent` exhaustively need `testImplementation(libs.serialization.core)` (compileOnly-on-core propagation gotcha, already documented in debug knowledge base) |

## Sources

- `repo1.maven.org/maven2/io/github/unityinflow/budget-breaker/maven-metadata.xml` — only version 0.0.1, lastUpdated 2026-05-11 — **HIGH (authoritative)**
- `repo1.maven.org` 404 for `budget-breaker-spring-boot-starter` — starter unpublished — **HIGH**
- Local source `../05-budget-breaker` (tag `v0.0.1` vs HEAD `0.1.0` git diff): full API surface, starter auto-config beans, coroutines 1.10.1 dep — **HIGH (primary source)**
- Local source `08-kore-runtime`: `BudgetEnforcer` port, `EventBusSpanObserver` OBSV-03 stub + `KoreSpans.SKILL_ACTIVATE`, `AgentEvent` variants (no `SkillActivated`), kore-storage `excludeTags("integration")`, `libs.versions.toml` pins — **HIGH (primary source)**
- `repo1.maven.org` metadata: kotlinx-coroutines-core `<release>1.11.0</release>`, org.testcontainers:postgresql `<release>1.21.4</release>` — **HIGH**
- GitHub releases (kotlinx.coroutines, testcontainers-java) for 1.11.0 stable status and Testcontainers 2.x breaking-change summary — **MEDIUM** (fetch-model date attribution unreliable; version facts cross-checked against repo1)

---
*Stack research for: kore-runtime v0.0.2 Hardening & Hierarchy*
*Researched: 2026-06-12*
