# Phase 6: Real Budget Enforcement - Pattern Map

**Mapped:** 2026-06-21
**Files analyzed:** 7 (3 new source, 1 new module build, 2 modified config, 3 new tests — grouped below)
**Analogs found:** 7 / 7 (all exact or strong role-matches; every new file mirrors an in-repo precedent)

> Phase 6 is an **integration phase**, not an algorithm phase. Every new file has a direct in-repo analog. The planner should copy structure verbatim and change only the budget-breaker-specific bits. No file in this phase invents a new pattern.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `kore-budget/src/main/kotlin/io/github/unityinflow/kore/budget/BudgetBreakerAdapter.kt` | adapter (port impl) | transform / poll-and-record | `kore-core/.../internal/InMemoryBudgetEnforcer.kt` | exact (same port, same keying) |
| `kore-budget/build.gradle.kts` | build config (new module) | — | `kore-rabbitmq/build.gradle.kts` / `kore-storage/build.gradle.kts` | exact (library module template) |
| `settings.gradle.kts` (MODIFIED) | build config | — | existing `include(...)` block | exact |
| `kore-spring/.../KoreProperties.kt` → `BudgetProperties` (MODIFIED) | config props | — | existing `BudgetProperties` + `DashboardProperties.enabled` | exact |
| `kore-spring/.../KoreAutoConfiguration.kt` → `BudgetBreakerAutoConfiguration` inner class (MODIFIED) | auto-config | conditional bean wiring | `KoreAutoConfiguration.KafkaEventBusAutoConfiguration` (lines 284-302) | exact |
| `kore-budget/src/test/.../BudgetBreakerAdapterTest.kt` (BUDG-06 + BUDG-07) | test (unit + concurrency) | event-driven (coroutine) | `kore-core/.../port/EventBusConcurrencyTest.kt` | role + flow match |
| `kore-spring/src/test/.../BudgetBreakerAutoConfigurationTest.kt` (BUDG-05) | test (Spring integration) | request-response | `kore-spring/.../KafkaEventBusAutoConfigurationTest.kt` + `KoreAutoConfigurationTest.kt` | exact |

---

## Pattern Assignments

### `kore-budget/.../BudgetBreakerAdapter.kt` (adapter, port impl)

**Analog:** `kore-core/src/main/kotlin/io/github/unityinflow/kore/core/internal/InMemoryBudgetEnforcer.kt`
**Also informs:** `kore-core/.../port/BudgetEnforcer.kt` (the contract — byte-identical per D-00), `kore-core/.../TokenUsage.kt` (the usage value type).

**The full port the adapter implements** (`BudgetEnforcer.kt` lines 11-29) — three `suspend` methods, do NOT change them (D-00):
```kotlin
interface BudgetEnforcer {
    suspend fun recordUsage(agentId: String, usage: TokenUsage)
    suspend fun checkBudget(agentId: String): Boolean   // true = continue, false = budget exceeded
    suspend fun getUsage(agentId: String): TokenUsage
}
```

**Keying + no-eviction tradeoff to mirror** (`InMemoryBudgetEnforcer.kt` lines 17-35) — copy the class shape exactly; swap the value type from `TokenUsage` to budget-breaker `TokenTracker`:
```kotlin
class InMemoryBudgetEnforcer(
    private val defaultLimitPerAgent: Long = Long.MAX_VALUE,
) : BudgetEnforcer {
    private val usageMap = ConcurrentHashMap<String, TokenUsage>()      // ← adapter uses ConcurrentHashMap<String, TokenTracker>

    override suspend fun recordUsage(agentId: String, usage: TokenUsage) {
        usageMap.merge(agentId, usage) { existing, new -> existing + new }   // ← adapter: trackerFor(id).add(...)
    }

    override suspend fun checkBudget(agentId: String): Boolean {
        val current = usageMap[agentId]?.totalTokens?.toLong() ?: 0L
        return current < defaultLimitPerAgent                                // ← adapter: !(trackers[id]?.isAboveHardLimit() ?: false)
    }

    override suspend fun getUsage(agentId: String): TokenUsage = usageMap[agentId] ?: TokenUsage(0, 0)
}
```
Key facts to carry over verbatim:
- `private val ...Map = ConcurrentHashMap<String, _>()` — same field idiom (line 20).
- Per-`agentId` keying = BUDG-07 isolation for free. `agentId` is `AgentTask.id` (a UUID; `AgentTask` has **no `name` field** — see `AgentTask.kt` line 5 — which is why D-01 forbids per-name overrides).
- No eviction (D-05): the InMemory class accepts the same "state lives for process lifetime, bounded by running-agent count" tradeoff — see its KDoc lines 14-15 (`T-03-04 accepted`). Carry that KDoc note into the adapter.
- The `getUsage` fallback `?: TokenUsage(0, 0)` (line 34) is the exact null-handling the adapter reproduces.

**TokenUsage value type** (`TokenUsage.kt` lines 7-18) — `inputTokens`/`outputTokens` are `Int`; `totalTokens` is the derived sum. The adapter feeds `usage.inputTokens.toLong()` / `usage.outputTokens.toLong()` into `TokenTracker.add(prompt, completion)` (which takes `Long`), and reads `tracker.promptTokens.toInt()` back out for `getUsage`.

**The ready-to-adapt body** is in `06-RESEARCH.md` Pattern 1 (lines 217-278) — byte-accurate against the budget-breaker 0.0.1 jar. The planner should use that body, anchored to the InMemory shape above. Critical adapter-specific points from research:
- `AgentBudget(model, hardLimitTokens, softLimitTokens)` validates `soft ≤ hard` at construction — pass `softLimitTokens = defaultHardLimitTokens` (Pitfall 4).
- `TokenTracker.add` / `isAboveHardLimit` are **synchronous, non-throwing** — no `withContext(Dispatchers.IO)`, no blocking, no `Thread.sleep` (CLAUDE.md compliant).
- Keep a defensive `try/catch (e: BudgetException)` in `recordUsage` so the BUDG-06 "no budget-breaker exception escapes the port" invariant is explicit and self-documenting.

**CLAUDE.md constraints to honor** (same as the analog): `val` only, no `!!` (use `?.` with fallback as the analog does on line 30/34), KDoc on the public class.

---

### `kore-budget/build.gradle.kts` (new module build config)

**Analog:** `kore-rabbitmq/build.gradle.kts` (cleanest minimal-dep library module) — prefer it over `kore-kafka` (which carries Kafka/Jackson exclusions) and `kore-storage` (which carries DB + integrationTest scaffolding the budget module does NOT need).

**Plugin block + group + toolchain to copy** (mirrors `kore-storage/build.gradle.kts` lines 6-13, 35-37):
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinter)
    id("kore.publishing")
}

group = "io.github.unityinflow"

// ...dependencies...

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
```

**Dependency block** — use the research-pinned set (`06-RESEARCH.md` lines 84-97), which matches the `kore-kafka` testImplementation idiom (`kore-kafka/build.gradle.kts` lines 19-26):
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

**Publishing pom block to copy** (`kore-storage/build.gradle.kts` lines 97-108 / `kore-kafka` lines 47-58) — every library module declares one:
```kotlin
publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                name.set("kore-runtime — Budget enforcement adapter")
                description.set(
                    "budget-breaker (Tool 05) implementation of the kore-runtime BudgetEnforcer port.",
                )
            }
        }
    }
}
```

Notes: kore-budget is **Spring-free** (D-06 resolution / research Open Question 1) — do NOT add Spring, `kotlin.plugin.spring`, or the BOM here. Only `kore-storage` adds `jacoco`; the budget module does not need it. No `serialization` plugin needed (the adapter has no `@Serializable` types of its own).

---

### `settings.gradle.kts` (MODIFIED — module registration)

**Analog:** the existing `include(...)` block (lines 3-14). Add `"kore-budget"` as the final entry, exactly as `06-RESEARCH.md` lines 366-382 shows:
```kotlin
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

---

### `kore-spring/.../KoreProperties.kt` → `BudgetProperties` (MODIFIED — config props)

**Analog:** the existing `BudgetProperties` (lines 94-97) + the `enabled` field pattern already on `DashboardProperties` (line 91, `val enabled: Boolean = true`).

**Current shape** (`KoreProperties.kt` lines 94-97):
```kotlin
/** In-memory budget enforcer defaults — replaced by budget-breaker in v2. */
data class BudgetProperties(
    val defaultMaxTokens: Long = 100_000L,
)
```

**Target shape** (add `enabled`, default `false` to keep InMemory as the zero-config default — `06-RESEARCH.md` lines 384-393):
```kotlin
/** Budget enforcement config. `enabled` opts into the budget-breaker adapter (BUDG-05). */
data class BudgetProperties(
    val defaultMaxTokens: Long = 100_000L,
    val enabled: Boolean = false, // NEW — gates BudgetBreakerAutoConfiguration; default false keeps InMemory stub
    // Future (deferred): agents: Map<String, AgentBudgetOverride> = emptyMap()  ← keep shape extensible (D-01)
)
```
The `@ConditionalOnProperty("kore.budget.enabled")` gate fires on the raw property regardless, but adding the typed field surfaces it in `spring-boot-configuration-processor` metadata and documents the surface (D-01 extensibility). Reuse `defaultMaxTokens` as the single global limit — do NOT add a new limit key.

---

### `kore-spring/.../KoreAutoConfiguration.kt` → `BudgetBreakerAutoConfiguration` inner class (MODIFIED — auto-config)

**Analog:** `KoreAutoConfiguration.KafkaEventBusAutoConfiguration` (lines 284-302) — the closest triple-conditional inner-class precedent. Also reference `StorageAutoConfiguration` (lines 164-172) for the `@ConditionalOnClass` + `@ConditionalOnMissingBean(<port>::class)` pairing that lets a real adapter beat an in-memory default.

**The exact inner-class pattern to mirror** (`KafkaEventBusAutoConfiguration`, lines 284-302):
```kotlin
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["io.github.unityinflow.kore.kafka.KafkaEventBus"])   // ← string form (Pitfall 3), NOT class literal
@ConditionalOnProperty(
    prefix = "kore.event-bus",
    name = ["type"],
    havingValue = "kafka",
)
class KafkaEventBusAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(EventBus::class)
    fun kafkaEventBus(
        properties: KoreProperties,
        @Qualifier("koreEventBusScope") scope: CoroutineScope,
    ): EventBus =
        io.github.unityinflow.kore.kafka.KafkaEventBus(...)
}
```

**The default bean the new adapter must beat** (`inMemoryBudgetEnforcer`, lines 62-65) — leave it UNCHANGED; the triple-gate adapter wins via `@ConditionalOnMissingBean` ordering:
```kotlin
@Bean
@ConditionalOnMissingBean(BudgetEnforcer::class)
fun inMemoryBudgetEnforcer(properties: KoreProperties): BudgetEnforcer =
    InMemoryBudgetEnforcer(defaultLimitPerAgent = properties.budget.defaultMaxTokens)
```

**Target inner class to add** (`06-RESEARCH.md` Pattern 2, lines 284-302 — mirrors the Kafka block, drops the `@Qualifier` scope param since the adapter needs no `CoroutineScope`):
```kotlin
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["io.github.unityinflow.kore.budget.BudgetBreakerAdapter"]) // string form (Pitfall 3)
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
Mirror conventions exactly (the file's class-level KDoc, lines 39-49, mandates these): `proxyBeanMethods = false`, `@ConditionalOnClass(name=[...])` **string form** (NOT a class literal — Pitfall 3, eager-load crash), fully-qualified adapter reference in the body (same as Storage line 170-171 and the LLM blocks lines 98-99).

**Build wiring this requires** in `kore-spring/build.gradle.kts` — mirror the `compileOnly(project(...))` + `testImplementation(project(...))` pairing used for every optional module (lines 26-29 compileOnly, lines 60-73 testImplementation):
```kotlin
compileOnly(project(":kore-budget"))        // adapter constructor reference resolves at compile time
testImplementation(project(":kore-budget")) // fires the @ConditionalOnClass gate in the auto-config test
```

---

### `kore-budget/src/test/.../BudgetBreakerAdapterTest.kt` (BUDG-06 + BUDG-07)

**Analog:** `kore-core/src/test/kotlin/io/github/unityinflow/kore/core/port/EventBusConcurrencyTest.kt` — the in-repo `runTest` concurrency precedent.

**Framework + imports to mirror** (lines 1-14, 27-31):
```kotlin
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class BudgetBreakerAdapterTest {
    @Test
    fun `...`() = runTest { ... }
}
```

**Concurrent-fan-out idiom to copy** (lines 40-48) — `(range).map { async { ... } }` then `.awaitAll()` for BUDG-07's many-agent stress variant:
```kotlin
val producers =
    (0 until 8).map { p ->
        async {
            repeat(1000) { i -> bus.emit(...) }
        }
    }
producers.awaitAll()
```
Per `06-RESEARCH.md` Validation Architecture (lines 471-472), the planner writes:
- **BUDG-06 (no-escape):** `BudgetBreakerAdapter(defaultHardLimitTokens = 10L)`; in `runTest`, `recordUsage("a", TokenUsage(100, 100))` must NOT throw (`shouldNotThrowAny { ... }`); then `checkBudget("a") shouldBe false`; `getUsage("a").totalTokens shouldBe 200`. Assert no `BudgetHardLimitException`/`BudgetException` propagates.
- **BUDG-07 (isolation):** two distinct ids (`"agent-A"` over limit, `"agent-B"` under); assert `checkBudget("agent-A") == false` AND `checkBudget("agent-B") == true`, and `getUsage("agent-B")` reflects only B's tokens. Stress variant: N concurrent ids via `(1..N).map { async { ... } }.awaitAll()`, assert per-id totals and that no `IllegalArgumentException` (the `withBudget` guard) is ever raised — proving the adapter never touches `withBudget`.

Use Kotest assertions (`shouldBe`, `shouldNotThrowAny`) and `kotlinx-coroutines-test` `runTest`, exactly as the analog. MockK is available if a `MockLLMBackend`-driven `AgentLoop` variant of BUDG-06 is added, but the direct-adapter test needs no mocking.

---

### `kore-spring/src/test/.../BudgetBreakerAutoConfigurationTest.kt` (BUDG-05)

**Analog:** `kore-spring/src/test/kotlin/io/github/unityinflow/kore/spring/KafkaEventBusAutoConfigurationTest.kt` (the per-feature auto-config test shape) + `KoreAutoConfigurationTest.kt` (the `ApplicationContextRunner` + `shouldBeInstanceOf` bean-type assertion idiom).

**ContextRunner setup to copy** (`KafkaEventBusAutoConfigurationTest.kt` lines 22-27):
```kotlin
class BudgetBreakerAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoreAutoConfiguration::class.java))
            .withPropertyValues("kore.dashboard.enabled=false") // keep: stops Ktor binding port 8090 in tests
}
```

**Bean-type assertion idiom to copy** (`KoreAutoConfigurationTest.kt` lines 66-70 — the InMemory default assertion is the exact inverse of scenario 1):
```kotlin
@Test
fun `auto-configures InMemoryBudgetEnforcer as the default BudgetEnforcer bean`() {
    contextRunner.run { context ->
        context.getBean(BudgetEnforcer::class.java).shouldBeInstanceOf<InMemoryBudgetEnforcer>()
    }
}
```

**The 4-scenario matrix** (`06-RESEARCH.md` lines 465-470) — each scenario asserts exactly one `BudgetEnforcer` bean and its concrete type via `getBean(BudgetEnforcer::class.java).shouldBeInstanceOf<...>()`:
1. `enabled=true` + kore-budget on classpath → `BudgetBreakerAdapter`. Use `.withPropertyValues("kore.budget.enabled=true")`.
2. `enabled` unset → `InMemoryBudgetEnforcer`.
3. `enabled=false` → `InMemoryBudgetEnforcer`. Use `.withPropertyValues("kore.budget.enabled=false")`.
4. kore-budget absent → `InMemoryBudgetEnforcer`. Simulate via `.withClassLoader(FilteredClassLoader(BudgetBreakerAdapter::class.java))` on the `ApplicationContextRunner` (kore-budget is on the test classpath, so "absent" must be simulated by filtering the class).

`kore-budget` must be on the test classpath for scenarios 1-3 → add `testImplementation(project(":kore-budget"))` to `kore-spring/build.gradle.kts` (see auto-config build wiring above). The `assertThat(ctx).hasBean(...)` definition-level idiom (Kafka test lines 36-40) is NOT needed here — `BudgetBreakerAdapter` opens no socket, so resolving the bean is safe; prefer `getBean(...).shouldBeInstanceOf<...>()`.

---

## Shared Patterns

### Conditional-bean wiring (auto-config)
**Source:** `kore-spring/.../KoreAutoConfiguration.kt` lines 39-49 (class KDoc stating the conventions), 284-302 (`KafkaEventBusAutoConfiguration`), 164-172 (`StorageAutoConfiguration`).
**Apply to:** `BudgetBreakerAutoConfiguration`.
```kotlin
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["<adapter FQCN as a STRING>"])  // never a class literal — Pitfall 3 eager-load crash
@ConditionalOnProperty(prefix = "...", name = ["..."], havingValue = "true", matchIfMissing = false)
class XxxAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(<Port>::class)   // lets the real adapter beat the in-memory default
    fun xxx(properties: KoreProperties): <Port> = fully.qualified.Adapter(...)
}
```

### Per-agentId ConcurrentHashMap state (no eviction)
**Source:** `kore-core/.../internal/InMemoryBudgetEnforcer.kt` lines 17-35 (KDoc lines 14-15 document the accepted tradeoff).
**Apply to:** `BudgetBreakerAdapter`.
```kotlin
private val <name>Map = ConcurrentHashMap<String, _>()   // keyed by agentId (= AgentTask.id, a per-run UUID)
// reads use `map[id]?.… ?: <fallback>` — no `!!`, per CLAUDE.md
```

### Library-module build template
**Source:** `kore-rabbitmq/build.gradle.kts` / `kore-storage/build.gradle.kts` lines 6-13, 35-37, 97-108.
**Apply to:** `kore-budget/build.gradle.kts`.
Plugins (`kotlin.jvm`, `kotlinter`, `id("kore.publishing")`) + `group = "io.github.unityinflow"` + `kotlin { jvmToolchain(21) }` + `tasks.test { useJUnitPlatform() }` + a `publishing { ... pom { ... } }` block. Test deps: `junit5`, `kotest.assertions`, `coroutines.test`, `mockk`, `junit-platform-launcher`.

### Coroutine test scaffolding
**Source:** `kore-core/.../port/EventBusConcurrencyTest.kt` lines 27-61.
**Apply to:** `BudgetBreakerAdapterTest`.
`@Test fun ...() = runTest { ... }`, `(range).map { async { ... } }.awaitAll()` for concurrency, Kotest `shouldBe` assertions.

### Spring auto-config test scaffolding
**Source:** `kore-spring/.../KoreAutoConfigurationTest.kt` lines 44-52, 66-70; `KafkaEventBusAutoConfigurationTest.kt` lines 22-27.
**Apply to:** `BudgetBreakerAutoConfigurationTest`.
`ApplicationContextRunner().withConfiguration(AutoConfigurations.of(KoreAutoConfiguration::class.java)).withPropertyValues("kore.dashboard.enabled=false")`, then per-scenario `.withPropertyValues(...)` / `.withClassLoader(FilteredClassLoader(...))` + `getBean(BudgetEnforcer::class.java).shouldBeInstanceOf<...>()`.

---

## No Analog Found

None. Every new file in this phase has a direct in-repo analog. This is the expected outcome for an additive integration phase — the only genuinely new surface is the budget-breaker `TokenTracker` API, whose byte-accurate signatures are documented in `06-RESEARCH.md` lines 115-158 (verified from the published jar), not in this repo.

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| — | — | — | All 7 files map to existing patterns. |

---

## Metadata

**Analog search scope:** `kore-core/` (port, stub, value types, concurrency test), `kore-spring/` (auto-config, properties, auto-config tests, build), `kore-kafka/`, `kore-storage/`, `kore-rabbitmq/` (module build templates), `settings.gradle.kts`.
**Files scanned:** 13 (read in full or in targeted ranges).
**Key cross-cutting insight:** `BudgetBreakerAutoConfiguration` and `inMemoryBudgetEnforcer` BOTH carry `@ConditionalOnMissingBean(BudgetEnforcer::class)`; only one gate can be satisfied per app (kore-budget present + `enabled=true` ⇒ adapter wins; otherwise ⇒ default wins) — the same mechanism proven by `StorageAutoConfiguration` vs `inMemoryAuditLog` in the same file. No bean-ordering annotations needed.
**Pattern extraction date:** 2026-06-21
