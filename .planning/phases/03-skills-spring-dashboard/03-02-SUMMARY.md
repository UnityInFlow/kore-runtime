---
phase: 03-skills-spring-dashboard
plan: 02
subsystem: spring-auto-configuration
tags: [kotlin, spring-boot-4, auto-configuration, actuator, conditional-on-class, hexagonal]

requires:
  - phase: 01-core-runtime
    provides: kore-core ports (EventBus, BudgetEnforcer, AuditLog, SkillRegistry), in-memory defaults, kore-llm factory functions (claude/gpt/ollama/gemini)
  - phase: 02-observability-storage
    provides: KoreTracer, PostgresAuditLogAdapter, R2dbcDatabase consumer contract
  - phase: 03-skills-spring-dashboard plan 01
    provides: SkillRegistryAdapter, AuditLog read methods, libs.versions.toml Spring Boot 4.0.5 + Ktor 3.2.0 pins
provides:
  - kore-spring module: Spring Boot 4 starter library (no application plugin)
  - KoreProperties @ConfigurationProperties("kore") binding: llm.{claude,openai,ollama,gemini}, mcp, skills, storage, dashboard, budget
  - KoreAutoConfiguration: 4 always-present default beans + 8 conditional inner @Configuration blocks (Claude / OpenAI / Ollama / Gemini / Storage / Observability / Skills / Dashboard)
  - KoreActuatorEndpoint @Endpoint(id="kore") at /actuator/kore — read-only health snapshot
  - META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports SPI registration
affects: [03-03 kore-dashboard wiring (DashboardServer @Bean replaces reflective bridge)]

tech-stack:
  added:
    - org.jetbrains.kotlin.plugin.spring (kotlin-spring) — opens Spring-managed classes for proxying, version pinned to libs.versions.toml kotlin (2.3.0)
    - spring-boot-autoconfigure 4.0.5 (BOM-managed)
    - spring-boot-starter-actuator 4.0.5 (BOM-managed)
    - spring-boot-configuration-processor (annotationProcessor, BOM-managed)
    - spring-boot-starter-test (testImplementation, BOM-managed)
  patterns:
    - "@AutoConfiguration + @EnableConfigurationProperties root pattern (Spring Boot 4)"
    - "Nested @Configuration(proxyBeanMethods=false) inner classes with @ConditionalOnClass(name=[fqn-string]) — string form prevents eager classloading (RESEARCH.md Pitfall 12)"
    - "compileOnly project deps + direct constructor references — cleaner than reflection while still classpath-gated at runtime"
    - "compileOnly transitive dep redeclaration — kore-spring must redeclare exposed-r2dbc and opentelemetry-api as compileOnly because compileOnly project deps don't expose THEIR compileOnly transitives"
    - "Opt-in property gates for fragile LangChain4j adapters — kore.llm.ollama.enabled=true required (defaults false) so adding kore-llm to the classpath does not eagerly construct an OllamaChatModel"
    - "Actuator endpoint with structured-concurrency event bus subscription — @PostConstruct launches a coroutine, @PreDestroy cancels the supervisor scope"
    - "Reflective bridge for not-yet-existing modules — DashboardAutoConfiguration uses Class.forName + Constructor.newInstance until plan 03-03 creates kore-dashboard, then plan 03-03 swaps to direct constructor"

key-files:
  created:
    - kore-spring/build.gradle.kts
    - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt
    - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt
    - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/actuator/KoreActuatorEndpoint.kt
    - kore-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KorePropertiesTest.kt
    - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt
  modified:
    - settings.gradle.kts

key-decisions:
  - "Use kore-llm public factory functions (claude/gpt/ollama/gemini) in @Bean methods instead of direct ClaudeBackend/OpenAiBackend/etc constructors — the underlying SDK clients (AnthropicClient, OpenAIClient, ChatLanguageModel) are constructor-required and the factory functions in LlmBackends.kt already encapsulate the client wiring from an api key"
  - "Gate OllamaLlmAutoConfiguration on explicit kore.llm.ollama.enabled=true (defaults false) — adding kore-llm to the classpath must not eagerly construct an OllamaChatModel because langchain4j-ollama 0.26.1 and langchain4j-google-ai-gemini 0.36.1 resolve to incompatible langchain4j-core versions, and the higher gemini version removed ServiceHelper.loadFactoryService(Class, Supplier) which OllamaChatModel.builder still calls. The opt-in gate sidesteps the conflict in zero-config setups; the underlying classpath skew is a pre-existing kore-llm issue out of scope here"
  - "compileOnly transitive deps must be redeclared at the top level — `compileOnly(project(\":kore-storage\"))` exposes kore-storage's classes but NOT its own compileOnly transitives (org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase). Same for kore-observability → io.opentelemetry.api.trace.Tracer. We redeclare them as compileOnly in kore-spring/build.gradle.kts so the auto-config compiles against direct constructor references"
  - "Reflective bridge for DashboardAutoConfiguration — kore-dashboard does not exist yet (plan 03-03). Using Class.forName lets kore-spring compile and ship today; plan 03-03 will swap to a direct constructor. The @ConditionalOnClass(name=[\"...DashboardServer\"]) guard ensures the reflection only runs when the dashboard module is actually on the classpath"
  - "KoreActuatorEndpoint health() returns Map<String, Any?> not Map<String, Any> — version field is forward-extensible and budgetEnforcer simpleName might be null in pathological subclassing scenarios. Tests use containsKey() rather than Kotest's shouldContainKey because the latter constrains V : Any"
  - "@SpringBootTest test class set spring.main.web-application-type=none — kore-spring is a library, not a web application. Forces Spring Boot to skip web auto-config and avoids forcing Tomcat onto the test classpath"
  - "8 ApplicationContextRunner tests + 1 full SpringBootTest — ApplicationContextRunner is faster and isolates @Configuration; the SpringBootTest exercises the SPI imports file end to end and confirms KoreActuatorEndpoint is picked up by component scan from a real @SpringBootApplication"

requirements-completed: [SPRN-01, SPRN-02, SPRN-03, SPRN-04]

duration: ~6 min
completed: 2026-04-13
---

# Phase 3 Plan 2: kore-spring Module — Spring Boot Auto-Configuration Summary

**Spring Boot 4 starter library that wires the entire kore-runtime — event bus, budget enforcer, audit log, skill registry, four LLM backends, observability, storage and (reflectively, until plan 03-03) the dashboard — from a single `kore-spring` Gradle dependency, gated by classpath detection and `kore.*` configuration properties, with an Actuator endpoint at `/actuator/kore`.**

## Performance

- **Duration:** ~6 min
- **Started:** 2026-04-13T21:16:23Z
- **Completed:** 2026-04-13T21:22:00Z
- **Tasks:** 2 (both TDD, auto mode)
- **Files created:** 6
- **Files modified:** 1

## Accomplishments

- New `kore-spring` module added to settings.gradle.kts with Spring Boot 4 BOM imported via `io.spring.dependency-management` (no Spring Boot application plugin — kore-spring is a library, not an executable)
- `KoreProperties` data class hierarchy binds the entire `kore.*` namespace (llm.{claude,openai,ollama,gemini}, mcp, skills, storage, dashboard, budget) with documented zero-config defaults
- `KoreAutoConfiguration` registers four always-present default beans backed by the kore-core in-memory implementations (D-17 / D-18), each with `@ConditionalOnMissingBean(<port>::class)` so user-supplied beans win
- Eight nested `@Configuration(proxyBeanMethods = false)` inner classes for the optional kore modules, each gated by `@ConditionalOnClass(name=["fqn"])` string form (RESEARCH.md Pitfall 12 — string form prevents eager classloading)
- LLM backend conditionals (D-15): Claude / OpenAI / Gemini gated on the corresponding `api-key` property; Ollama gated on explicit `kore.llm.ollama.enabled=true` opt-in to avoid eager LangChain4j classpath conflicts
- Storage (D-18), Observability (D-19), Skills (D-20) and Dashboard (D-21) auto-configurations using compileOnly project deps + direct constructor references (Dashboard uses a reflective bridge until plan 03-03 creates kore-dashboard)
- `KoreActuatorEndpoint` at `@Endpoint(id="kore")` with `@PostConstruct` event-bus subscription (tracks active agents in a `ConcurrentHashMap.newKeySet`) and `@PreDestroy` supervisor cancellation
- META-INF/spring/...AutoConfiguration.imports SPI file registers `KoreAutoConfiguration` for Spring Boot 3/4 auto-discovery
- 12 tests pass (3 KorePropertiesTest + 8 KoreAutoConfigurationTest + 1 KoreAutoConfigurationSpringContextTest), `:kore-spring:lintKotlin` clean, `:kore-spring:build` produces a library JAR

## Task Commits

1. **Task 1: kore-spring Gradle scaffold + KoreProperties + Spring Boot SPI registration** — `a162058` (feat)
   - settings.gradle.kts adds kore-spring
   - kore-spring/build.gradle.kts with Spring Boot 4 BOM, no application plugin, compileOnly deps on kore-llm/skills/observability/storage
   - KoreProperties @ConfigurationProperties hierarchy with documented defaults
   - META-INF/spring/...AutoConfiguration.imports SPI file
   - KoreAutoConfiguration stub (full graph in Task 2)
   - KorePropertiesTest: 3 tests (defaults, custom override, SPI file content)
2. **Task 2: KoreAutoConfiguration with all @ConditionalOnClass nested configs + KoreActuatorEndpoint** — `9c15706` (feat)
   - 4 always-present default beans
   - 8 conditional inner @Configuration blocks (Claude/OpenAI/Ollama/Gemini/Storage/Observability/Skills/Dashboard)
   - KoreActuatorEndpoint with @PostConstruct/PreDestroy lifecycle
   - 8 KoreAutoConfigurationTest cases + 1 SpringBootTest context test
   - kore-spring build.gradle.kts: compileOnly opentelemetry-api + exposed-r2dbc, testImplementation kore-llm

**Plan metadata commit:** (this SUMMARY + STATE/ROADMAP updates, see final_commit step)

## Files Created/Modified

**Created:**
- `kore-spring/build.gradle.kts` — Spring Boot 4 BOM, no application plugin, compileOnly project deps + transitive symbol redeclarations
- `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt` — @ConfigurationProperties("kore") hierarchy
- `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt` — @AutoConfiguration entry point with full conditional bean graph
- `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/actuator/KoreActuatorEndpoint.kt` — @Endpoint(id="kore") health snapshot
- `kore-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — Spring Boot 3/4 SPI registration
- `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KorePropertiesTest.kt` — defaults / override / SPI file (3 tests)
- `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt` — ApplicationContextRunner tests + SpringBootTest context boot (9 tests)

**Modified:**
- `settings.gradle.kts` — adds kore-spring to include() list (kore-dashboard slot left for plan 03-03)

## Decisions Made

- **kore-llm factory functions instead of direct constructors** — The plan's snippet called `ClaudeBackend(apiKey=...)` and `OpenAiBackend(apiKey=...)` directly, but the real constructors take fully-built `AnthropicClient` / `OpenAIClient` / `ChatLanguageModel` instances (see `kore-llm/.../ClaudeBackend.kt` and `OpenAiBackend.kt`). The public factory functions `claude()`, `gpt()`, `ollama()`, `gemini()` in `kore-llm/.../LlmBackends.kt` already encapsulate the client wiring from an api key — calling them from `KoreAutoConfiguration` keeps the auto-config aligned with the DSL and avoids duplicating the client builder logic.
- **Ollama gated on explicit `enabled=true` opt-in** — Adding kore-llm to the classpath should NOT eagerly construct an OllamaChatModel. With the test classpath containing both `langchain4j-ollama:0.26.1` and `langchain4j-google-ai-gemini:0.36.1`, Gradle resolves `langchain4j-core` to the gemini-required version, and that version dropped `ServiceHelper.loadFactoryService(Class, Supplier)` which `OllamaChatModel.builder()` still calls. Without the gate, every test or app pulling kore-llm crashes at startup with a `NoSuchMethodError`. The gate is a Rule 2 design fix, not a workaround — it matches the intent of D-15 (LLM beans materialise from `application.yml` config, not from classpath presence alone). The pre-existing kore-llm classpath skew is logged for a future plan.
- **compileOnly transitive symbol redeclaration** — `compileOnly(project(":kore-storage"))` brings kore-storage's compiled classes into the compile classpath but NOT kore-storage's own `compileOnly` transitives (`R2dbcDatabase` from exposed-r2dbc). Same for `kore-observability` → `io.opentelemetry.api.trace.Tracer`. KoreAutoConfiguration references these symbols directly in its `@Bean` method signatures, so they must be redeclared as `compileOnly` at the top level of `kore-spring/build.gradle.kts`. At runtime the host application supplies them via the Spring Boot 4 BOM (otel) or its own dependency on Exposed.
- **Reflective bridge for DashboardAutoConfiguration** — kore-dashboard does not exist yet (plan 03-03). Rather than skip the dashboard wiring entirely, we use `Class.forName` + `Constructor.newInstance` reflected against the constructor `(EventBus, AuditLog, KoreProperties.DashboardProperties)`. Plan 03-03 will (a) create kore-dashboard, (b) add it as a `compileOnly(project(":kore-dashboard"))` dep here, and (c) swap the reflective bridge for a direct `DashboardServer(...)` constructor call. The `@ConditionalOnClass(name=["...DashboardServer"])` guard ensures the reflection only runs when the dashboard module is actually present at runtime.
- **`Map<String, Any?>` + `containsKey` over Kotest `shouldContainKey`** — The actuator health map needs to expose nullable values (e.g. `budgetEnforcer::class.simpleName` could theoretically be null for anonymous subclasses). Kotest's `shouldContainKey` constrains `V : Any` and rejects nullable maps. Using `health.containsKey(...) shouldBe true` is the simplest workaround.
- **`spring.main.web-application-type=none` in @SpringBootTest** — kore-spring is a library starter, not a web application. Forcing the test context to `none` skips Spring Web/WebFlux auto-config and avoids dragging Tomcat onto the test classpath. The HTMX dashboard runs as a separate Ktor side-car (D-33), not Spring MVC routes.
- **ApplicationContextRunner for unit-style auto-config tests** — `ApplicationContextRunner` builds an isolated Spring context per `.run { context -> ... }` block without component scanning. Eight of the nine auto-config tests use this — fast, deterministic, no `@SpringBootApplication` scan root needed. The single full `@SpringBootTest` exists to verify the SPI imports file is loaded end-to-end and the `@Component`-annotated `KoreActuatorEndpoint` is picked up by a real scan.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Plan's LLM backend constructors do not match the real signatures**
- **Found during:** Task 2, compiling KoreAutoConfiguration
- **Issue:** The plan's snippet called `ClaudeBackend(apiKey = ...)`, `OpenAiBackend(apiKey = ...)`, etc. The real constructors in `kore-llm` take fully-built provider clients (AnthropicClient, OpenAIClient, ChatLanguageModel) — there is no apiKey constructor parameter. Compiling the plan's snippet verbatim would fail with "no such constructor".
- **Fix:** Switched every LLM @Bean method to call the public top-level factory functions `dev.unityinflow.kore.llm.claude(apiKey, model)`, `dev.unityinflow.kore.llm.gpt(apiKey, model)`, `dev.unityinflow.kore.llm.ollama(baseUrl, model)`, `dev.unityinflow.kore.llm.gemini(apiKey, model)`. These functions already encapsulate client builder wiring and are the same entry points the DSL uses.
- **Files modified:** `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt`
- **Verification:** `./gradlew :kore-spring:compileKotlin` succeeds and the @Bean return types match (ClaudeBackend, OpenAiBackend, OllamaBackend, GeminiBackend).
- **Committed in:** `9c15706` (Task 2)

**2. [Rule 3 - Blocking] compileOnly transitive symbols not on the kore-spring compile classpath**
- **Found during:** Task 2, first compile attempt
- **Issue:** `compileOnly(project(":kore-storage"))` exposes kore-storage's compiled classes but NOT kore-storage's own `compileOnly` transitives. `KoreAutoConfiguration.StorageAutoConfiguration.postgresAuditLog(database: org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase)` references R2dbcDatabase directly, which kore-storage declares as a regular `implementation` dep — but kore-spring sees only the kore-storage classes themselves, not Exposed. Same problem for `io.opentelemetry.api.trace.Tracer` in `ObservabilityAutoConfiguration.koreTracer(...)`.
- **Fix:** Added two new compileOnly entries to `kore-spring/build.gradle.kts`:
    - `compileOnly(libs.exposed.r2dbc)`
    - `compileOnly("io.opentelemetry:opentelemetry-api:1.49.0")`
  These are pure compile-time symbol providers; at runtime the host application supplies them via the Spring Boot 4 BOM (otel) or its own Exposed dep, gated by `@ConditionalOnClass`.
- **Files modified:** `kore-spring/build.gradle.kts`
- **Verification:** `./gradlew :kore-spring:compileKotlin` succeeds.
- **Committed in:** `9c15706` (Task 2)

**3. [Rule 2 - Critical correctness gap] Eager Ollama wiring crashes on every Spring context startup whenever kore-llm is on the classpath**
- **Found during:** Task 2, running `./gradlew :kore-spring:test` for the first time
- **Issue:** The plan's `OllamaLlmAutoConfiguration` had no `@ConditionalOnProperty` gate (rationale: "Ollama uses a local endpoint — no api-key required"). With kore-llm on the test classpath, the @ConditionalOnClass check passed and Spring eagerly invoked `ollamaBackend()` on context startup, which calls `OllamaChatModel.builder()` → `ServiceHelper.loadFactoryService(Class, Supplier)` → `NoSuchMethodError`. Root cause: `langchain4j-ollama:0.26.1` and `langchain4j-google-ai-gemini:0.36.1` resolve to incompatible `langchain4j-core` versions; the higher gemini version removed the `(Class, Supplier)` overload of `loadFactoryService`. EVERY `KoreAutoConfigurationTest` failed with the same NoSuchMethodError because the context teardown happened in `getBean(EventBus::class.java)` once the OllamaBackend bean creation tripped.
- **Fix:** (a) Added `enabled: Boolean = false` to `KoreProperties.OllamaProperties`. (b) Annotated `OllamaLlmAutoConfiguration` with `@ConditionalOnProperty(prefix = "kore.llm.ollama", name = ["enabled"], havingValue = "true", matchIfMissing = false)`. The gate matches the intent of D-15 (LLM beans created from `application.yml` config) and is consistent with the api-key gates on the other three backends.
- **Files modified:** `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt`, `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt`
- **Verification:** All 12 :kore-spring tests pass.
- **Committed in:** `9c15706` (Task 2)

**4. [Rule 3 - Blocking] Kotest `shouldContainKey` rejects `Map<String, Any?>`**
- **Found during:** Task 2, compiling KoreAutoConfigurationTest
- **Issue:** Kotest's `Map<K, V>.shouldContainKey(key: K)` constrains `V : Any` (non-nullable). The actuator health map is intentionally `Map<String, Any?>` so callers can return null values for forward-compat fields. The test compiled but ktlint flagged it; fixing that flagged the type error.
- **Fix:** Replaced `health shouldContainKey "status"` with `health.containsKey("status") shouldBe true`. Same change for all three key assertions.
- **Files modified:** `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt`
- **Verification:** `./gradlew :kore-spring:test` compiles and runs.
- **Committed in:** `9c15706` (Task 2)

**5. [Rule 3 - Blocking] ktlint chain-method-continuation on inline FQN constructor calls**
- **Found during:** Task 2, after `:kore-spring:test` passed, running `:kore-spring:lintKotlin`
- **Issue:** ktlint's `standard:chain-method-continuation` rule rejected three inline FQN expressions inside `=>` method bodies, e.g. `dev.unityinflow.kore.storage.PostgresAuditLogAdapter(database)`. The rule wants a newline before the `.` when a chain spans multiple segments.
- **Fix:** Ran `./gradlew :kore-spring:formatKotlin` — the formatter automatically split each FQN call across lines. Three locations in StorageAutoConfiguration / ObservabilityAutoConfiguration / SkillsAutoConfiguration. Accepted the formatter output verbatim.
- **Files modified:** `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt`
- **Verification:** `./gradlew :kore-spring:lintKotlin :kore-spring:build` clean.
- **Committed in:** `9c15706` (Task 2)

---

**Total deviations:** 5 auto-fixed (1 Rule 1 bug, 1 Rule 2 critical correctness, 3 Rule 3 blocking)
**Impact on plan:** All five are necessary fixes for the plan to compile/run/lint cleanly. Output contracts (KoreProperties, KoreAutoConfiguration, KoreActuatorEndpoint, SPI file) match the plan exactly. The Ollama opt-in gate is a deliberate behavioural change from "always enabled when kore-llm present" to "enabled only when `kore.llm.ollama.enabled=true`" — documented above as a Rule 2 fix because the plan's "no gate" design crashes any context that pulls in kore-llm.

### Pre-existing issues out of scope

- **kore-llm langchain4j classpath skew** — `langchain4j-ollama:0.26.1` and `langchain4j-google-ai-gemini:0.36.1` resolve to incompatible `langchain4j-core` versions. Gradle picks the higher gemini version, which dropped `ServiceHelper.loadFactoryService(Class, Supplier)`. `OllamaChatModel.builder()` still calls it → `NoSuchMethodError` at runtime if both adapters are loaded together. Plan 03-02 sidesteps this with the Ollama opt-in gate, but the underlying conflict exists in `kore-llm/build.gradle.kts` and should be addressed in a future plan (either pin `langchain4j-core` or exclude the colliding transitive in one of the two adapters).

## Issues Encountered

- **First test run failed with `NoSuchMethodError`** — diagnosed via the build report XML (the gradle stdout truncated the cause). See deviation #3.
- **ktlint rejected the inline FQN constructor calls** — `formatKotlin` auto-fixed them.

## User Setup Required

None.

## Next Phase Readiness

Wave 2 plan **03-03 kore-dashboard** is unblocked:

- `settings.gradle.kts` has space for `kore-dashboard` (intentionally not added here so 03-03 can introduce its own module without merge conflict).
- `KoreAutoConfiguration.DashboardAutoConfiguration` already declares the @ConditionalOnClass guard against `dev.unityinflow.kore.dashboard.DashboardServer` and reflectively constructs it via `(EventBus, AuditLog, KoreProperties.DashboardProperties)`. Plan 03-03 should:
    1. Create `kore-dashboard/` with a `DashboardServer` class whose constructor matches `(EventBus, AuditLog, KoreProperties.DashboardProperties)`.
    2. Add `compileOnly(project(":kore-dashboard"))` to `kore-spring/build.gradle.kts`.
    3. Replace the reflective bridge in `DashboardAutoConfiguration` with a direct constructor call.
- `KoreProperties.DashboardProperties` already exposes `port`, `path`, `enabled` with the values 03-03 needs (8090 / `/kore` / true).
- `kore-dashboard` will consume `AuditLog.queryRecentRuns(...)` and `AuditLog.queryCostSummary()` via the kore-spring-injected `AuditLog` bean — works on either `InMemoryAuditLog` (returns empty lists per D-27 degraded mode) or `PostgresAuditLogAdapter`.

## Self-Check: PASSED

- [x] `kore-spring/build.gradle.kts` FOUND
- [x] `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt` FOUND
- [x] `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt` FOUND
- [x] `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/actuator/KoreActuatorEndpoint.kt` FOUND
- [x] `kore-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` FOUND
- [x] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KorePropertiesTest.kt` FOUND
- [x] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt` FOUND
- [x] Commit `a162058` FOUND in `git log`
- [x] Commit `9c15706` FOUND in `git log`
- [x] `@ConfigurationProperties("kore")` present in KoreProperties.kt
- [x] `@AutoConfiguration` present in KoreAutoConfiguration.kt
- [x] `@Endpoint(id = "kore")` present in KoreActuatorEndpoint.kt
- [x] `dev.unityinflow.kore.spring.KoreAutoConfiguration` present in SPI imports file
- [x] `./gradlew :kore-spring:test :kore-spring:lintKotlin :kore-spring:build` all green (12 tests pass)

---
*Phase: 03-skills-spring-dashboard*
*Completed: 2026-04-13*
