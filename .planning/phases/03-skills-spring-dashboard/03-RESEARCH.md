# Phase 3: Skills, Spring & Dashboard - Research

**Researched:** 2026-04-10
**Domain:** Kotlin skills engine (YAML), Spring Boot 4 auto-configuration, Ktor 3.2 HTMX dashboard
**Confidence:** HIGH (core stack verified against official docs; one ASSUMED claim for YAML parser choice)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Skills Engine

- **D-01:** Skills are 100% declarative YAML — no code, no Kotlin class references
- **D-02:** Required fields: `name`, `description`, `version`, `activation`, `prompt`
- **D-03:** `activation.task_matches` — list of regex patterns (with `(?i)` for case-insensitive). Skill activates if ANY pattern matches the task content
- **D-04:** `activation.requires_tools` (optional) — list of tool names. Skill only activates if ALL listed tools are available in the agent
- **D-05:** `prompt` — multi-line YAML string injected into LLM system message when skill activates
- **D-06:** Skills loaded from TWO sources: classpath (`META-INF/kore/skills/*.yaml`) AND filesystem (configurable directory, default `./kore-skills/`)
- **D-07:** Filesystem skills override classpath skills with the same name (user skills win)
- **D-08:** YAML parsing via `kotlinx.serialization` with YAML format adapter OR snakeyaml if needed
- **D-09:** `SkillRegistry` port interface in kore-core — kore-skills provides the adapter
- **D-10:** Agent loop calls `SkillRegistry.activateFor(task)` before the first LLM call to inject matching skill prompts into system message
- **D-11:** Skill activation emits `kore.skill.activate` span (wires up OBSV-03 from Phase 2)

#### Spring Boot Auto-Configuration

- **D-12:** Users register agents via `@Bean` functions returning `AgentRunner` from DSL — idiomatic Spring DI
- **D-13:** No central `KoreAgentRegistry` — Spring's own bean discovery IS the registry
- **D-14:** Users inject `AgentRunner` (or its specific bean) into controllers/services like any other Spring bean
- **D-15:** LLM backend beans from `application.yml` keys under `kore.llm.*`
- **D-16:** `McpConnectionManager` bean from `kore.mcp.servers` list
- **D-17:** `BudgetEnforcer` bean — `InMemoryBudgetEnforcer` by default
- **D-18:** `AuditLog` bean — `PostgresAuditLogAdapter` if kore-storage on classpath, else `InMemoryAuditLog`
- **D-19:** `ObservableAgentRunner` wrapper auto-activates if kore-observability on classpath
- **D-20:** `SkillRegistry` bean loading from classpath + `kore.skills.directory` config
- **D-21:** Dashboard router auto-registers at `kore.dashboard.path` (default `/kore`) if kore-dashboard on classpath
- **D-22:** All config lives under `kore.*` namespace in application.yml, bound via `@ConfigurationProperties("kore")`
- **D-23:** Core sections: `kore.llm.*`, `kore.mcp.servers[]`, `kore.skills.directory`, `kore.storage.*`, `kore.dashboard.*`, `kore.budget.*`

#### HTMX Dashboard

- **D-24:** Hybrid: in-memory for real-time state, PostgreSQL for historical data
- **D-25:** `EventBusDashboardObserver` subscribes to EventBus, maintains `ConcurrentHashMap<AgentId, AgentState>` for active agents
- **D-26:** Recent runs and cost summary query PostgreSQL via `AuditLog.queryRecentRuns()` and `AuditLog.queryCostSummary()` (new read methods added to port)
- **D-27:** If kore-storage absent, dashboard falls back to in-memory EventBus data only (degraded mode)
- **D-28:** Single page at `/kore` with three HTMX fragments (2s/5s/10s polling)
- **D-29:** No drill-down pages in v0.1
- **D-30:** HTMX polling via `hx-get` + `hx-trigger="every Ns"` — no WebSockets
- **D-31:** Ktor 3.2+ embedded server with `ktor-server-htmx` module
- **D-32:** Server-rendered HTML via `kotlinx.html` DSL
- **D-33:** Dashboard is a SEPARATE Ktor embedded server instance, not Spring MVC routes
- **D-34:** Actuator endpoint at `/actuator/kore` is a separate Spring Actuator bean

### Claude's Discretion

- Exact YAML parser choice (kotlinx-serialization-yaml vs snakeyaml vs jackson-dataformat-yaml)
- `AuditLog` port method additions for dashboard queries (exact signatures)
- Dashboard HTML/CSS styling — minimal, readable, no framework required
- Whether `SkillRegistry` is a new kore-core port or lives fully in kore-skills
- Internal structure of `EventBusDashboardObserver` (single observer vs separate per section)

### Deferred Ideas (OUT OF SCOPE)

- Skill hot-reload (filesystem watcher) — defer to v0.2
- Dashboard drill-down pages (per-agent trace view) — defer to v0.2
- WebSocket streaming for active agents (vs HTMX polling) — polling is simpler, defer switch
- Real budget-breaker integration (Tool 05) — stays stub in v0.1
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SKIL-01 | YAML skill definition format with name, description, activation patterns | D-01 through D-08 define the schema; jackson-dataformat-yaml chosen as YAML parser |
| SKIL-02 | Pattern-based activation context matching (task content, available tools, agent state) | Regex compilation with `(?i)` prefix per D-03; requires_tools list check per D-04 |
| SKIL-03 | Skill loader discovers and loads skills from configurable directory | ClassLoader.getResources() for classpath; File.walkTopDown() for filesystem; D-06/D-07 |
| SKIL-04 | Active skills injected into agent context before LLM calls | D-10 hook point in AgentLoop.run() before first LLM call; system message injection pattern |
| SPRN-01 | KoreAutoConfiguration for Spring Boot auto-configuration | META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports; @AutoConfiguration class |
| SPRN-02 | KoreProperties @ConfigurationProperties for application.yml config | @ConfigurationProperties("kore") data class; annotationProcessor for metadata |
| SPRN-03 | Spring Boot starter dependency: add one dependency, agents work | Starter POM declares all optional module deps; auto-config activates conditionally |
| SPRN-04 | Spring Actuator endpoints for agent health and metrics | @Endpoint(id = "kore") + @ReadOperation; requires spring-boot-starter-actuator |
| DASH-01 | HTMX dashboard showing list of active agents with status | EventBusDashboardObserver ConcurrentHashMap; fragment at /kore/fragments/active-agents |
| DASH-02 | HTMX dashboard showing recent agent runs with results | AuditLog.queryRecentRuns(); fragment at /kore/fragments/recent-runs |
| DASH-03 | HTMX dashboard showing token cost summary per agent and total | AuditLog.queryCostSummary(); fragment at /kore/fragments/cost-summary |
| DASH-04 | Ktor 3.2 embedded server with HTMX module (no frontend build step) | ktor-server-cio + ktor-server-htmx + ktor-server-html-builder; separate port from Spring |
</phase_requirements>

---

## Summary

Phase 3 delivers the developer experience layer: three new modules (kore-skills, kore-spring, kore-dashboard). The target is: add one Gradle dependency (`dev.unityinflow:kore-spring`), write one `@Bean` returning an `agent { }` block, and get a running agent with skills, observability, and a live dashboard at `/kore` — zero additional wiring.

The three technical domains are well-understood and proven against the existing codebase. The integration hooks are clear: `AgentLoop.run()` already calls `AuditLog` and `EventBus` at the right lifecycle points; Phase 3 adds `SkillRegistry.activateFor()` before the first LLM call. The Spring auto-configuration layer wraps all Phase 1/2 adapters with conditional beans. The Ktor dashboard is a standalone side-car running on a configurable port (default 8090) alongside Spring Boot (default 8080).

The one area requiring care is the Ktor embedded server lifecycle: it must be wired to `SmartLifecycle` so it starts after the Spring context is fully initialized (including `EventBusDashboardObserver` subscription) and stops cleanly on context shutdown. The `kaml` library (kotlinx.serialization YAML adapter) was archived in November 2025; `jackson-dataformat-yaml` with `jackson-module-kotlin` is the recommended replacement — it is actively maintained, already transitively available via Spring Boot 4, and Kotlin data class-friendly.

**Primary recommendation:** Use `jackson-dataformat-yaml` for YAML skill parsing. Wire the Ktor dashboard via Spring `SmartLifecycle`. Use `@ConditionalOnClass` nested in `@Configuration` blocks (not directly on `@Bean` methods) to prevent eager class loading failures.

---

## Standard Stack

### New Libraries for Phase 3

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `jackson-dataformat-yaml` | 2.x (via Spring Boot 4 BOM — `tools.jackson`) | YAML skill file parsing | Actively maintained; uses SnakeYAML 2 engine under the hood; integrates seamlessly with `jackson-module-kotlin` already on classpath via Spring Boot 4 |
| `ktor-server-cio` | 3.2.0 | CIO engine for Ktor embedded server | CIO is the recommended coroutine-native engine for embedded use; no Netty overhead |
| `ktor-server-htmx` | 3.2.0 | HTMX-aware routing DSL | `hx {}` DSL blocks, `HxResponseHeaders` constants, no manual header string handling |
| `ktor-server-html-builder` | 3.2.0 | Server-side HTML via kotlinx.html DSL | Type-safe HTML generation; no template engine; no build step |

All three Ktor artifacts share a single version pin and are already listed in STACK.md research.

### Existing Libraries Extended in Phase 3

| Library | Version | Extended Usage |
|---------|---------|----------------|
| `spring-boot-starter-actuator` | via Boot 4 BOM | New `@Endpoint(id = "kore")` bean |
| `spring-boot-starter-webflux` | via Boot 4 BOM | Auto-configuration bootstrap beans |
| `spring-boot-configuration-processor` | via Boot 4 BOM | Metadata generation for `KoreProperties` IDE support |
| `exposed-r2dbc` (Exposed 1.0) | 1.0.0 | New `selectAll().where {}` read queries for dashboard |

**Version verification note:** `jackson-dataformat-yaml` ships as part of the Spring Boot 4 BOM under the new group ID `tools.jackson.dataformat:jackson-dataformat-yaml`. No explicit version needed in `kore-skills/build.gradle.kts` when Spring Boot dependency management is applied. [VERIFIED: spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now]

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `jackson-dataformat-yaml` | `kaml` (kotlinx.serialization-yaml) | kaml archived Nov 2025; no new releases; requires `@Serializable` on data classes; jackson is the pragmatic choice for JVM-only parsing |
| `jackson-dataformat-yaml` | SnakeYAML directly | Raw SnakeYAML needs manual mapping; no data class binding without Constructor hacks |
| `ktor-server-cio` | `ktor-server-netty` | Netty adds ~4MB and is overkill for a dashboard side-car; CIO is coroutine-native and lighter |

**Installation (libs.versions.toml additions):**

```toml
[versions]
ktor = "3.2.0"
spring-boot = "4.0.5"

[libraries]
ktor-server-cio         = { module = "io.ktor:ktor-server-cio",          version.ref = "ktor" }
ktor-server-htmx        = { module = "io.ktor:ktor-server-htmx",         version.ref = "ktor" }
ktor-server-html-builder= { module = "io.ktor:ktor-server-html-builder",  version.ref = "ktor" }
# jackson-dataformat-yaml: no version needed — Spring Boot BOM manages it
# spring-boot-configuration-processor: annotation processor, no version needed

[plugins]
spring-boot     = { id = "org.springframework.boot",         version.ref = "spring-boot" }
spring-dep-mgmt = { id = "io.spring.dependency-management",  version = "1.1.7" }
```

---

## Architecture Patterns

### Module Dependency Graph for Phase 3

```
kore-skills
  └── depends on: kore-core (SkillRegistry port interface lives in kore-core per D-09)

kore-dashboard
  └── depends on: kore-core (EventBus, AgentEvent, AgentResult)
  └── depends on: kore-storage (optional, for AuditLog read queries — compileOnly or optional)

kore-spring
  └── depends on: kore-core, kore-llm, kore-mcp, kore-observability, kore-storage, kore-skills, kore-dashboard
  └── Spring Boot plugins applied here only
```

### Recommended Project Structure

```
kore-skills/
├── src/main/kotlin/dev/unityinflow/kore/skills/
│   ├── SkillYamlDef.kt          # @Serializable (or data class) matching YAML schema
│   ├── SkillLoader.kt           # loads classpath + filesystem skills
│   ├── SkillRegistryAdapter.kt  # implements SkillRegistry port
│   └── internal/
│       └── PatternMatcher.kt    # regex compilation + caching
├── src/main/resources/
│   └── META-INF/kore/skills/   # bundled example skill YAML files
└── build.gradle.kts

kore-dashboard/
├── src/main/kotlin/dev/unityinflow/kore/dashboard/
│   ├── DashboardServer.kt       # embeddedServer lifecycle wrapper (SmartLifecycle)
│   ├── DashboardRouting.kt      # Ktor routing: /kore, /kore/fragments/*
│   ├── EventBusDashboardObserver.kt  # subscribes to EventBus, maintains ConcurrentHashMap
│   ├── DashboardDataService.kt  # reads from observer + optional AuditLog
│   └── html/
│       ├── PageShell.kt         # koreDashboardPage() with <head> + HTMX script
│       ├── Fragments.kt         # activeAgentsFragment(), recentRunsFragment(), costSummaryFragment()
│       └── Components.kt        # statusBadge(), resultBadge(), dataTable(), emptyState()
└── build.gradle.kts

kore-spring/
├── src/main/kotlin/dev/unityinflow/kore/spring/
│   ├── KoreAutoConfiguration.kt     # @AutoConfiguration entry point
│   ├── KoreProperties.kt            # @ConfigurationProperties("kore") data class
│   ├── KoreLlmAutoConfiguration.kt  # @ConditionalOnProperty("kore.llm.*")
│   ├── KoreStorageAutoConfiguration.kt  # @ConditionalOnClass(PostgresAuditLogAdapter::class)
│   ├── KoreObservabilityAutoConfiguration.kt  # @ConditionalOnClass(ObservableAgentRunner::class)
│   ├── KoreSkillsAutoConfiguration.kt   # @ConditionalOnClass(SkillRegistryAdapter::class)
│   ├── KoreDashboardAutoConfiguration.kt  # @ConditionalOnClass(DashboardServer::class)
│   └── actuator/
│       └── KoreActuatorEndpoint.kt  # @Endpoint(id = "kore") @ReadOperation
├── src/main/resources/
│   └── META-INF/spring/
│       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── build.gradle.kts
```

### Pattern 1: SkillRegistry Port + Adapter

The `SkillRegistry` port lives in kore-core (per D-09), keeping the AgentLoop independent of the YAML parsing machinery.

```kotlin
// kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/SkillRegistry.kt
interface SkillRegistry {
    /** Return all skill prompts whose activation patterns match [taskContent] and whose
     *  required tools are all present in [availableTools]. */
    suspend fun activateFor(taskContent: String, availableTools: List<String>): List<String>
}

// kore-core default stub (no-op, used when kore-skills is not on classpath):
object NoOpSkillRegistry : SkillRegistry {
    override suspend fun activateFor(taskContent: String, availableTools: List<String>) = emptyList<String>()
}
```

### Pattern 2: AgentLoop Skill Injection Hook

AgentLoop currently has this sequence: history.add(userMessage) → eventBus.emit(AgentStarted) → runLoop(). The hook inserts before the first LLM call inside `runLoop()`. No change to the outer `run()` signature is needed; `SkillRegistry` is injected as a constructor param.

```kotlin
// kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentLoop.kt — diff
class AgentLoop(
    private val llmBackend: LLMBackend,
    private val toolProviders: List<ToolProvider>,
    private val budgetEnforcer: BudgetEnforcer,
    private val eventBus: EventBus,
    private val auditLog: AuditLog,
    private val skillRegistry: SkillRegistry = NoOpSkillRegistry, // NEW — default no-op
    private val config: LLMConfig,
) {
    private suspend fun runLoop(...): AgentResult {
        // NEW: inject skill prompts before first LLM call
        val activatedSkillPrompts = skillRegistry.activateFor(
            taskContent = history.first { it.role == ConversationMessage.Role.User }.content,
            availableTools = toolDefs.map { it.name }
        )
        if (activatedSkillPrompts.isNotEmpty()) {
            // Prepend as system message (or inject into existing system message)
            history.add(0, ConversationMessage(
                role = ConversationMessage.Role.System,
                content = activatedSkillPrompts.joinToString("\n\n")
            ))
        }
        // ... rest of loop unchanged
    }
}
```

**Note:** `AgentBuilder.build()` in kore-core/dsl/AgentBuilder.kt also needs updating to accept an optional `skillRegistry` parameter.

### Pattern 3: YAML Skill Definition Schema

```yaml
# META-INF/kore/skills/code-review.yaml
name: code-review
description: "Activates expert code review mode for PR analysis tasks"
version: "1.0.0"
activation:
  task_matches:
    - "(?i)review.*code"
    - "(?i)pull.?request"
    - "(?i)pr.*feedback"
  requires_tools:     # optional — omit if no tool requirement
    - github_get_pr
    - github_list_files
prompt: |
  You are an expert code reviewer. Focus on:
  - Correctness and logic errors
  - Security vulnerabilities
  - Performance implications
  Always provide specific line references and actionable suggestions.
```

### Pattern 4: YAML Skill Loader with Jackson

```kotlin
// kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt
// Source: [CITED: https://www.baeldung.com/jackson-yaml]
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

data class SkillYamlDef(
    val name: String,
    val description: String,
    val version: String,
    val activation: ActivationDef,
    val prompt: String,
)

data class ActivationDef(
    val task_matches: List<String> = emptyList(),
    val requires_tools: List<String> = emptyList(),
)

class SkillLoader(private val skillsDirectory: String = "./kore-skills") {
    private val mapper = YAMLMapper().apply { registerModule(kotlinModule()) }

    fun loadAll(): List<SkillYamlDef> {
        val classpathSkills = loadFromClasspath()
        val filesystemSkills = loadFromFilesystem()
        // D-07: filesystem wins on name collision
        val merged = classpathSkills.associateBy { it.name }.toMutableMap()
        filesystemSkills.forEach { merged[it.name] = it }
        return merged.values.toList()
    }

    private fun loadFromClasspath(): List<SkillYamlDef> {
        val urls = Thread.currentThread().contextClassLoader
            .getResources("META-INF/kore/skills/").toList()
        // ... enumerate YAML files from each URL
    }

    private fun loadFromFilesystem(): List<SkillYamlDef> {
        val dir = java.io.File(skillsDirectory)
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown()
            .filter { it.extension == "yaml" || it.extension == "yml" }
            .map { mapper.readValue<SkillYamlDef>(it) }
            .toList()
    }
}
```

### Pattern 5: Regex Compilation with Case-Insensitive Matching

Pre-compile regexes at startup to avoid per-call compilation cost. With `(?i)` embedded in the pattern string, `Pattern.compile()` picks it up automatically.

```kotlin
// kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/internal/PatternMatcher.kt
class PatternMatcher(private val patterns: List<String>) {
    // Pre-compile at construction time — patterns don't change after load
    private val compiled: List<Regex> = patterns.map { Regex(it) }

    fun matches(input: String): Boolean = compiled.any { it.containsMatchIn(input) }
}
```

**Performance note:** `Regex.containsMatchIn()` vs `Regex.matches()` — use `containsMatchIn()` since skill patterns should match anywhere in the task content, not require a full-string match.

**Pitfall:** Regex patterns with catastrophic backtracking (e.g., `(a+)+`) can lock threads. Validate patterns at load time with a timeout.

### Pattern 6: Spring Boot Auto-Configuration Registration

```
# kore-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
dev.unityinflow.kore.spring.KoreAutoConfiguration
```

Single entry point. `KoreAutoConfiguration` uses `@Import` or `@Bean` factory methods that delegate to sub-configurations for each module.

```kotlin
// Source: [CITED: https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html]
@AutoConfiguration
@EnableConfigurationProperties(KoreProperties::class)
class KoreAutoConfiguration {

    // Always-present beans (no classpath condition needed)
    @Bean
    @ConditionalOnMissingBean
    fun inProcessEventBus(): EventBus = InProcessEventBus()

    @Bean
    @ConditionalOnMissingBean
    fun inMemoryBudgetEnforcer(): BudgetEnforcer = InMemoryBudgetEnforcer()

    @Bean
    @ConditionalOnMissingBean
    fun inMemoryAuditLog(): AuditLog = InMemoryAuditLog()

    // Conditional beans — nested @Configuration class pattern to prevent eager class loading
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.storage.PostgresAuditLogAdapter"])
    inner class StorageAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(AuditLog::class)
        fun postgresAuditLog(properties: KoreProperties, database: R2dbcDatabase): AuditLog =
            PostgresAuditLogAdapter(database)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.observability.ObservableAgentRunner"])
    inner class ObservabilityAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean
        fun koreTracer(tracer: io.opentelemetry.api.trace.Tracer): KoreTracer = KoreTracer(tracer)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.skills.SkillRegistryAdapter"])
    inner class SkillsAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(SkillRegistry::class)
        fun skillRegistry(properties: KoreProperties): SkillRegistry =
            SkillRegistryAdapter(skillsDirectory = properties.skills.directory)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.dashboard.DashboardServer"])
    inner class DashboardAutoConfiguration {
        @Bean
        fun dashboardServer(
            eventBus: EventBus,
            auditLog: Optional<AuditLog>,
            properties: KoreProperties,
        ): DashboardServer = DashboardServer(eventBus, auditLog.orElse(null), properties.dashboard)
    }
}
```

**Critical:** Always use `@ConditionalOnClass(name = ["..."])` (string form, not `::class`) to avoid `ClassNotFoundException` during bean definition parsing when the referenced class is not on the classpath. [CITED: https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html]

### Pattern 7: KoreProperties @ConfigurationProperties

```kotlin
// Source: [CITED: https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html]
@ConfigurationProperties("kore")
data class KoreProperties(
    val llm: LlmProperties = LlmProperties(),
    val mcp: McpProperties = McpProperties(),
    val skills: SkillsProperties = SkillsProperties(),
    val storage: StorageProperties = StorageProperties(),
    val dashboard: DashboardProperties = DashboardProperties(),
    val budget: BudgetProperties = BudgetProperties(),
) {
    data class SkillsProperties(
        /** Directory to load user-defined skill YAML files from. */
        val directory: String = "./kore-skills",
    )
    data class DashboardProperties(
        /** Port for the Ktor embedded dashboard server. */
        val port: Int = 8090,
        /** Path prefix for dashboard routes (e.g., "/kore"). */
        val path: String = "/kore",
        /** Whether the dashboard is enabled. */
        val enabled: Boolean = true,
    )
    // ... other nested data classes
}
```

**Note:** Spring Boot 4 requires `var` (not `val`) or constructor-bound `@ConstructorBinding` for `@ConfigurationProperties`. Use `data class` with `var` fields, or annotate with `@ConstructorBinding` and use `val`. [ASSUMED — verify exact Spring Boot 4 behavior for Kotlin data classes with val vs var]

### Pattern 8: Ktor Embedded Server as Spring SmartLifecycle

The critical pattern: Ktor embedded server must not block Spring context startup. Run in a background coroutine, start after Spring is ready, stop when Spring shuts down.

```kotlin
// kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt
// Source: [CITED: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/SmartLifecycle.html]
// Source: [CITED: https://api.ktor.io/ktor-server/ktor-server-core/io.ktor.server.engine/-embedded-server/index.html]
class DashboardServer(
    private val eventBus: EventBus,
    private val auditLog: AuditLog?,
    private val config: KoreProperties.DashboardProperties,
) : SmartLifecycle {
    private var engine: EmbeddedServer<*, *>? = null
    private val observer = EventBusDashboardObserver(eventBus)

    override fun start() {
        observer.startCollecting()  // subscribe to EventBus before server accepts requests
        engine = embeddedServer(CIO, port = config.port) {
            install(HTMX)  // requires @OptIn(ExperimentalKtorApi::class)
            routing {
                get(config.path) { call.respondHtml { koreDashboardPage() } }
                get("${config.path}/fragments/active-agents") {
                    call.respondHtml { activeAgentsFragment(observer.snapshot()) }
                }
                get("${config.path}/fragments/recent-runs") {
                    val runs = auditLog?.queryRecentRuns(limit = 20) ?: emptyList()
                    call.respondHtml { recentRunsFragment(runs) }
                }
                get("${config.path}/fragments/cost-summary") {
                    val summary = auditLog?.queryCostSummary() ?: emptyList()
                    call.respondHtml { costSummaryFragment(summary) }
                }
            }
        }.also { it.start(wait = false) }  // wait = false — non-blocking
    }

    override fun stop() {
        engine?.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        observer.stopCollecting()
    }

    override fun isRunning(): Boolean = engine != null
    override fun isAutoStartup(): Boolean = config.enabled
    override fun getPhase(): Int = Int.MAX_VALUE - 1  // start last, stop first
}
```

**Key detail:** `wait = false` prevents blocking the Spring context thread. `getPhase() = Int.MAX_VALUE - 1` ensures the dashboard starts after all beans are wired.

### Pattern 9: EventBusDashboardObserver

```kotlin
// kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt
class EventBusDashboardObserver(private val eventBus: EventBus) {
    // Thread-safe map: agentId -> AgentState
    private val activeAgents = ConcurrentHashMap<String, AgentState>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun startCollecting() {
        job = scope.launch {
            eventBus.subscribe().collect { event ->
                when (event) {
                    is AgentEvent.AgentStarted -> activeAgents[event.agentId] =
                        AgentState(name = event.agentId, status = AgentStatus.RUNNING, startedAt = Instant.now())
                    is AgentEvent.AgentCompleted -> activeAgents.remove(event.agentId)
                    is AgentEvent.LLMCallCompleted -> activeAgents.compute(event.agentId) { _, state ->
                        state?.copy(tokensUsed = state.tokensUsed + event.tokenUsage.total)
                    }
                    else -> Unit
                }
            }
        }
    }

    fun snapshot(): Map<String, AgentState> = activeAgents.toMap()

    fun stopCollecting() { job?.cancel() }
}

data class AgentState(
    val name: String,
    val status: AgentStatus,
    val currentTask: String = "",
    val tokensUsed: Long = 0L,
    val startedAt: Instant,
)

enum class AgentStatus { RUNNING, IDLE, COMPLETED, ERROR }
```

### Pattern 10: AuditLog Port — Adding Read Methods

The port currently has only write methods. Phase 3 adds read methods for the dashboard. These belong in the `AuditLog` port (kore-core) since the dashboard depends on kore-core, not kore-storage directly.

```kotlin
// kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt — additions
interface AuditLog {
    // Existing write methods (unchanged)
    suspend fun recordAgentRun(agentId: String, task: AgentTask, result: AgentResult)
    suspend fun recordLLMCall(agentId: String, backend: String, usage: TokenUsage)
    suspend fun recordToolCall(agentId: String, call: ToolCall, result: ToolResult)

    // NEW read methods for dashboard (D-26)
    /** Returns the [limit] most recent agent runs, ordered by finishedAt DESC. */
    suspend fun queryRecentRuns(limit: Int = 20): List<AgentRunRecord>

    /** Returns token usage aggregated per agent name. */
    suspend fun queryCostSummary(): List<AgentCostRecord>
}

data class AgentRunRecord(
    val agentName: String,
    val resultType: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val durationMs: Long,
    val completedAt: Instant,
)

data class AgentCostRecord(
    val agentName: String,
    val totalRuns: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
)
```

Both `InMemoryAuditLog` (kore-core) and `PostgresAuditLogAdapter` (kore-storage) must implement the new read methods. `InMemoryAuditLog` returns empty lists (consistent with existing in-memory stub behavior). `PostgresAuditLogAdapter` implements real SQL queries.

### Pattern 11: Exposed R2DBC Select Queries (PostgresAuditLogAdapter extension)

```kotlin
// kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt — additions
// Source: [CITED: https://www.jetbrains.com/help/exposed/dsl-querying-data.html]
override suspend fun queryRecentRuns(limit: Int): List<AgentRunRecord> =
    suspendTransaction(database) {
        AgentRunsTable
            .innerJoin(LlmCallsTable, { AgentRunsTable.id }, { LlmCallsTable.runId })
            .selectAll()
            .orderBy(AgentRunsTable.finishedAt, SortOrder.DESC)
            .limit(limit)
            .map { row ->
                AgentRunRecord(
                    agentName = row[AgentRunsTable.agentName],
                    resultType = row[AgentRunsTable.resultType],
                    inputTokens = row[LlmCallsTable.tokensIn],
                    outputTokens = row[LlmCallsTable.tokensOut],
                    durationMs = row[LlmCallsTable.durationMs].toLong(),
                    completedAt = row[AgentRunsTable.finishedAt]?.toInstant() ?: Instant.now(),
                )
            }
    }

override suspend fun queryCostSummary(): List<AgentCostRecord> =
    suspendTransaction(database) {
        // GROUP BY agent_name using Exposed DSL aggregate functions
        AgentRunsTable
            .innerJoin(LlmCallsTable, { AgentRunsTable.id }, { LlmCallsTable.runId })
            .select(
                AgentRunsTable.agentName,
                AgentRunsTable.id.count(),
                LlmCallsTable.tokensIn.sum(),
                LlmCallsTable.tokensOut.sum(),
            )
            .groupBy(AgentRunsTable.agentName)
            .map { row ->
                AgentCostRecord(
                    agentName = row[AgentRunsTable.agentName],
                    totalRuns = row[AgentRunsTable.id.count()].toInt(),
                    totalInputTokens = row[LlmCallsTable.tokensIn.sum()] ?: 0L,
                    totalOutputTokens = row[LlmCallsTable.tokensOut.sum()] ?: 0L,
                )
            }
    }
```

**Note on join strategy:** The `agent_runs` table records per run; `llm_calls` is one row per LLM call within a run. For dashboard purposes, sum `tokens_in`/`tokens_out` across all LLM calls for a run. This requires a join — the queries above use `innerJoin`.

### Pattern 12: Ktor HTMX Integration

```kotlin
// Source: [CITED: https://ktor.io/docs/htmx-integration.html]
@OptIn(ExperimentalKtorApi::class)
fun Application.dashboardRouting(
    observer: EventBusDashboardObserver,
    auditLog: AuditLog?,
    dashboardPath: String,
) {
    install(HTMX)  // installs HTMX plugin — provides hx {} DSL and HxResponseHeaders
    routing {
        get(dashboardPath) {
            call.respondHtml { koreDashboardPage(dashboardPath) }
        }
        get("$dashboardPath/fragments/active-agents") {
            call.respondHtml { activeAgentsFragment(observer.snapshot()) }
        }
        get("$dashboardPath/fragments/recent-runs") {
            val runs = auditLog?.queryRecentRuns(20) ?: emptyList()
            call.respondHtml { recentRunsFragment(runs, degraded = auditLog == null) }
        }
        get("$dashboardPath/fragments/cost-summary") {
            val summary = auditLog?.queryCostSummary() ?: emptyList()
            call.respondHtml { costSummaryFragment(summary, degraded = auditLog == null) }
        }
    }
}
```

**The `hx { }` routing DSL (for request-header-based routing) is NOT needed here** — all three fragment endpoints must serve the initial HTML even without HTMX headers (on first page load, the fragments are loaded by the HTMX `hx-get` attribute on the outer page). Regular `get { }` routes are correct.

### Pattern 13: Spring Actuator Custom Endpoint

```kotlin
// kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/actuator/KoreActuatorEndpoint.kt
// Source: [CITED: https://docs.spring.io/spring-boot/reference/actuator/endpoints.html]
@Component
@Endpoint(id = "kore")
class KoreActuatorEndpoint(
    // Inject a list of all AgentRunner beans — Spring discovers them via bean type
    private val agentRunners: List<AgentRunner>,
    private val eventBus: EventBus,
) {
    @ReadOperation
    fun koreStatus(): Map<String, Any> = mapOf(
        "status" to "UP",
        "agents" to mapOf(
            "registered" to agentRunners.size,
        ),
        "version" to "0.0.1-SNAPSHOT",
    )
}
```

Endpoint auto-exposed at `/actuator/kore` when `spring-boot-starter-actuator` is on classpath and `management.endpoints.web.exposure.include=kore` (or `*`) is configured.

### Anti-Patterns to Avoid

- **`@ConditionalOnClass(PostgresAuditLogAdapter::class)`** directly on `@Bean` method: causes `ClassNotFoundException` at bean-scanning time when the class is absent. Always use the string form `@ConditionalOnClass(name = ["..."])` or wrap in a nested `@Configuration`.
- **`wait = true` in `embeddedServer(...).start(wait = true)`**: blocks the Spring context thread forever. Always use `wait = false`.
- **Sharing the Ktor `ApplicationEngine` coroutine scope** with Spring beans: Ktor manages its own scope; Spring beans get Spring's lifecycle management. Never mix.
- **Calling `observer.snapshot()` inside a coroutine** without the observer's `CoroutineScope` started: the dashboard will return empty fragments until `startCollecting()` is called. Wire `DashboardServer.start()` to call `observer.startCollecting()` first.
- **Blocking regex compilation per request**: always pre-compile `Regex` objects at `SkillLoader` init time, not inside `activateFor()`.
- **Not including `@OptIn(ExperimentalKtorApi::class)`** when using `install(HTMX)`: compiler will error. Annotate the file or the function.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| YAML parsing | Custom YAML tokenizer | `jackson-dataformat-yaml` + `jackson-module-kotlin` | SnakeYAML edge cases (anchors, multi-document, encoding); Jackson wraps SnakeYAML 2 with Kotlin data class binding |
| HTML generation | String interpolation for HTML | `kotlinx.html` DSL via `ktor-server-html-builder` | String interpolation has XSS risk; kotlinx.html is type-safe and already in the dependency graph |
| Auto-configuration discovery | Manual `@Import` lists | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Spring Boot's documented registration mechanism; manual `@Import` creates coupling between starter and consuming app |
| Spring bean lifecycle for Ktor | Custom thread management | `SmartLifecycle` implementation | Spring guarantees ordered start/stop with context shutdown; thread management is error-prone |
| Classpath resource enumeration | Manual classloader hacks | `ClassLoader.getResources("META-INF/kore/skills/")` | Standard Java API for classpath scanning; Spring's `PathMatchingResourcePatternResolver` is also available |

**Key insight:** For Phase 3, resist the urge to write custom solutions for YAML parsing and HTML rendering. The ecosystem has battle-tested solutions for both; the effort should focus on the integration wiring.

---

## Common Pitfalls

### Pitfall 1 (Inherited from PITFALLS.md): Eager Auto-Configuration (Pitfall 12)

**What goes wrong:** `KoreAutoConfiguration` activates all module beans regardless of whether the module JARs are present. A user with only `kore-spring` (no kore-storage) gets `DataSourceBeanCreationException`.

**Prevention:**
- All module-specific beans must be in nested `@Configuration` classes annotated with `@ConditionalOnClass(name = ["..."])`
- Write a Spring context test: boot with ONLY `kore-core` classes — no storage, no observability. Assert it starts in < 3s with no errors.
- The "minimal boot" test is a Phase 3 Acceptance Criterion equivalent for Spring integration.

### Pitfall 2: @ConditionalOnClass with Class Reference (Not String)

**What goes wrong:** `@ConditionalOnClass(PostgresAuditLogAdapter::class)` in an `@AutoConfiguration` causes a hard class load during bean scanning even when the class is absent — the JVM tries to load it to resolve the annotation attribute.

**Prevention:** Always use `@ConditionalOnClass(name = ["dev.unityinflow.kore.storage.PostgresAuditLogAdapter"])`. [CITED: https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html]

### Pitfall 3: Ktor wait=true Blocking Spring Context

**What goes wrong:** `embeddedServer(CIO, port = 8090) { ... }.start(wait = true)` is called inside a `@Bean` function or `SmartLifecycle.start()`. The call blocks indefinitely — Spring context never finishes starting.

**Prevention:** Always `wait = false`. The `SmartLifecycle` framework keeps the JVM alive; `wait = true` is only for standalone Ktor applications.

### Pitfall 4: kaml Archived — Do Not Add as Dependency

**What goes wrong:** Developer adds `com.charleskorn.kaml:kaml` for kotlinx.serialization YAML. The library was archived November 30, 2025. No security patches, no Kotlin 2.3 compatibility work going forward.

**Prevention:** Use `jackson-dataformat-yaml` (under the new `tools.jackson` group in Jackson 3.x) which ships via Spring Boot 4's BOM. No version pin needed. [VERIFIED: https://github.com/charleskorn/kaml — archived status confirmed]

### Pitfall 5: YAML Skill Validation Deferred to Runtime (Pitfall 15)

**What goes wrong:** Malformed skill YAML (wrong field name: `task_match` instead of `task_matches`) is not caught until first use in production.

**Prevention:**
- Validate all loaded `SkillYamlDef` objects at startup: check required fields are non-null/non-empty, validate each regex pattern compiles, emit a warning log for each invalid skill (do not fail startup — a broken skill should not block the agent loop).
- Test: load a skill YAML with an invalid regex pattern, assert the loader logs a warning and skips the skill rather than throwing.

### Pitfall 6: EventBusDashboardObserver Started After Server Accepts Requests

**What goes wrong:** Ktor server starts accepting `/kore` requests before `EventBusDashboardObserver.startCollecting()` is called. First 2-second poll to `/kore/fragments/active-agents` returns empty even if agents are running.

**Prevention:** Call `observer.startCollecting()` inside `DashboardServer.start()` BEFORE calling `embeddedServer(...).start()`.

### Pitfall 7: AuditLog Read Methods Missing from InMemoryAuditLog

**What goes wrong:** Adding read methods to the `AuditLog` port but only implementing them in `PostgresAuditLogAdapter`. When kore-storage is absent, `InMemoryAuditLog` is used — it does not implement the new methods — compile error.

**Prevention:** `InMemoryAuditLog` must implement `queryRecentRuns()` and `queryCostSummary()` returning empty lists. This also enables the degraded mode UX (D-27) for in-memory-only setups.

### Pitfall 8: ConversationMessage.Role.System Not in AgentLoop History

**What goes wrong:** Skill prompts injected as `ConversationMessage.Role.System` in the history list but the LLM backends don't handle system role messages sent mid-conversation correctly. Claude's API treats system as the initial prompt, not a user turn.

**Prevention:** Check existing `ConversationMessage.Role` enum — if it lacks a `System` variant, skill prompts must be prepended differently. The correct approach depends on how each LLM backend handles the conversation array. Two safe options:
1. Add a `System` role to `ConversationMessage` and have each LLM backend handle it appropriately in the adapter (recommended — already aligned with Claude's system prompt model).
2. Prepend skill prompts to the first user message content (simpler but loses separation of concerns).

**This is an open question — investigate the existing ConversationMessage.Role enum and LLM adapter conventions.**

---

## Code Examples

### Spring Boot User-Facing Agent Registration (D-12)

```kotlin
// What the user writes in their Spring Boot app
// Source: [CITED: .planning/phases/03-skills-spring-dashboard/03-CONTEXT.md, Specifics section]
@Configuration
class MyAgentsConfiguration {
    @Bean
    fun researchAgent(
        eventBus: EventBus,       // auto-configured by kore-spring
        auditLog: AuditLog,       // auto-configured by kore-spring
        skillRegistry: SkillRegistry,  // auto-configured if kore-skills on classpath
    ): AgentRunner = agent("research-agent") {
        model = claude()  // from kore-llm
        tools(mcp("github"))
        budget(maxTokens = 50_000)
        eventBus(eventBus)
        auditLog(auditLog)
        // skillRegistry injected via auto-config wrapping (see D-19 pattern)
    }
}
```

### Minimal application.yml

```yaml
kore:
  llm:
    claude:
      api-key: ${ANTHROPIC_API_KEY}
      model: claude-opus-4-5
  skills:
    directory: ./skills
  dashboard:
    port: 8090
    enabled: true
  storage:
    url: r2dbc:postgresql://localhost:5432/kore
```

### HTMX Page Shell (kotlinx.html DSL)

```kotlin
// Source: [CITED: https://ktor.io/docs/server-html-dsl.html]
@OptIn(ExperimentalKtorApi::class)
fun HTML.koreDashboardPage(dashboardPath: String = "/kore") {
    head {
        title { +"kore dashboard" }
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
        script(src = "https://unpkg.com/htmx.org@1.9.12/dist/htmx.min.js") {}
        style { unsafe { raw(KORE_CSS) } }  // embedded <style> block
    }
    body {
        nav(classes = "kore-nav") { ... }
        main(classes = "kore-page") {
            div {
                attributes["hx-get"] = "$dashboardPath/fragments/active-agents"
                attributes["hx-trigger"] = "load, every 2s"
                attributes["hx-swap"] = "innerHTML"
                attributes["aria-live"] = "assertive"
                id = "fragment-active-agents"
            }
            div {
                attributes["hx-get"] = "$dashboardPath/fragments/recent-runs"
                attributes["hx-trigger"] = "load, every 5s"
                attributes["hx-swap"] = "innerHTML"
                attributes["aria-live"] = "polite"
                id = "fragment-recent-runs"
            }
            div {
                attributes["hx-get"] = "$dashboardPath/fragments/cost-summary"
                attributes["hx-trigger"] = "load, every 10s"
                attributes["hx-swap"] = "innerHTML"
                attributes["aria-live"] = "polite"
                id = "fragment-cost-summary"
            }
        }
    }
}
```

**Note:** Use `hx-trigger="load, every 2s"` (with `load`) so the fragment populates on first page load, then polls every 2s thereafter. Without `load`, the fragment is empty until the first poll interval fires.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `spring.factories` (Spring Boot 1/2) | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Spring Boot 2.7/3.0 | Faster startup; no SpringFactoriesLoader overhead |
| `kaml` for kotlinx.serialization YAML | `jackson-dataformat-yaml` | Nov 2025 (kaml archived) | No functional regression; Jackson already on classpath via Spring Boot 4 |
| Ktor `ApplicationEngine` return type | `EmbeddedServer<*, *>` return type | Ktor 3.0 | API change — use `EmbeddedServer` type annotations, not `ApplicationEngine` |
| `ktor-server-netty` for embedded use | `ktor-server-cio` for coroutine-native embedded | Ktor 3.x | CIO is lighter; Netty still valid for high-throughput production use |

**Deprecated/outdated:**
- `spring.factories` key `org.springframework.boot.autoconfigure.EnableAutoConfiguration`: deprecated since Boot 2.7, removed in Boot 3.0. Do NOT use.
- `kaml` library: archived Nov 30, 2025. Do NOT add as a dependency.
- `@ConstructorBinding` on `@ConfigurationProperties` data classes: no longer needed in Spring Boot 3+ when the class has a single constructor — Boot 3+ infers constructor binding automatically. [ASSUMED — verify Spring Boot 4 behavior]

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Spring Boot 4 infers constructor binding for Kotlin `data class` with `val` fields without explicit `@ConstructorBinding` | Pattern 7 (KoreProperties) | If wrong: `KoreProperties` fields all read as null; fix by adding `@ConstructorBinding` or converting to `var` fields |
| A2 | `jackson-dataformat-yaml` group ID in Spring Boot 4 BOM is `tools.jackson.dataformat:jackson-dataformat-yaml` | Standard Stack | If wrong: dependency not managed by BOM; must pin version explicitly; safe fallback exists |
| A3 | HTMX CDN script `unpkg.com/htmx.org@1.9.12` is the latest 1.9.x version as of research date | Pattern 12 / UI-SPEC | If wrong: older version; update script src; no functional regression for the features used |

**If this table is empty:** All claims were verified. The three assumptions above are low-risk with clear fallbacks.

---

## Open Questions

1. **ConversationMessage.Role.System variant — does it exist?**
   - What we know: AgentLoop history uses `ConversationMessage.Role.User`, `Assistant`, `Tool`. Skill prompts need to be injected before the first LLM call.
   - What's unclear: Whether `Role.System` exists in the current model, and whether all four LLM backend adapters handle it correctly.
   - Recommendation: Read `ConversationMessage.kt` and all four LLM backend `call()` implementations before writing the skill injection code. If `System` role is absent, add it to `ConversationMessage` as part of Phase 3 Wave 0.

2. **AgentBuilder DSL: how should SkillRegistry be injected?**
   - What we know: Spring auto-configuration owns the `AgentRunner` bean; the DSL's `AgentBuilder.build()` creates the `AgentLoop`.
   - What's unclear: Should `SkillRegistry` be a DSL parameter (`skillRegistry(registry)`) or should `kore-spring` auto-config wrap `AgentRunner` post-creation (similar to how `ObservableAgentRunner` wraps `AgentLoop`)?
   - Recommendation: Add `skillRegistry` as a constructor param to `AgentLoop` with a default of `NoOpSkillRegistry`. Spring auto-config provides the real registry. No DSL change required unless the user wants to inject a custom registry — which is handled by exposing a `skillRegistry(registry: SkillRegistry)` method in `AgentBuilder`.

3. **Exposed aggregate functions in R2DBC mode — confirmed API?**
   - What we know: Exposed 1.0 R2DBC supports `selectAll().where {}` and `suspendTransaction`.
   - What's unclear: Whether `column.sum()`, `column.count()`, and `groupBy()` work in R2DBC mode without fallback to JDBC.
   - Recommendation: Write the `queryCostSummary()` integration test with Testcontainers in Wave 0 and verify the aggregation compiles and runs before writing the dashboard fragment.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|---------|
| Kotlin JVM | All modules | ✓ | 2.3.0 (from libs.versions.toml) | — |
| Gradle | Build | ✓ | 9.4.1 (from .gradle/) | — |
| Ktor 3.2.0 (ktor-server-cio/htmx/html-builder) | kore-dashboard | ✗ (not yet in libs.versions.toml) | — | Add to libs.versions.toml in Wave 0 |
| Spring Boot 4.0.5 plugins | kore-spring | ✗ (not yet in libs.versions.toml) | — | Add spring-boot and spring-dep-mgmt plugins in Wave 0 |
| jackson-dataformat-yaml | kore-skills | via Spring Boot BOM | managed by Boot 4 | — |
| PostgreSQL (Testcontainers) | kore-storage tests | ✓ (testcontainers 1.20.0 in catalog) | 1.20.0 | — |

**Missing dependencies with no fallback:**
- Ktor version entry in `libs.versions.toml` — must be added in Wave 0 before kore-dashboard can compile
- Spring Boot plugin entries — must be added in Wave 0 before kore-spring can compile

**Missing dependencies with fallback:**
- None — all other dependencies are either present or have clear BOM management paths

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Kotest assertions (established from Phases 1 and 2) |
| Config file | `kore-skills/build.gradle.kts`, `kore-spring/build.gradle.kts`, `kore-dashboard/build.gradle.kts` (new files) |
| Quick run command | `./gradlew :kore-skills:test :kore-spring:test :kore-dashboard:test` |
| Full suite command | `./gradlew test` (all modules) |

### Phase Requirements to Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SKIL-01 | YAML parsed into SkillYamlDef with all required fields | unit | `./gradlew :kore-skills:test --tests "*SkillLoaderTest*"` | Wave 0 |
| SKIL-02 | Pattern matching: task with matching content activates skill; task without match does not | unit | `./gradlew :kore-skills:test --tests "*SkillRegistryAdapterTest*"` | Wave 0 |
| SKIL-03 | Classpath skills loaded; filesystem skills override same-named classpath skills | unit | `./gradlew :kore-skills:test --tests "*SkillLoaderTest*"` | Wave 0 |
| SKIL-04 | AgentLoop calls SkillRegistry before first LLM call; skill prompt appears in history | unit | `./gradlew :kore-core:test --tests "*AgentLoopSkillTest*"` | Wave 0 |
| SPRN-01 | Spring context starts with only kore-spring on classpath (no storage, no observability) | integration | `./gradlew :kore-spring:test --tests "*MinimalBootTest*"` | Wave 0 |
| SPRN-02 | KoreProperties binds application.yml values correctly | unit | `./gradlew :kore-spring:test --tests "*KorePropertiesTest*"` | Wave 0 |
| SPRN-03 | Spring context with kore-spring + agent @Bean: context starts, AgentRunner bean exists | integration | `./gradlew :kore-spring:test --tests "*KoreAutoConfigurationTest*"` | Wave 0 |
| SPRN-04 | /actuator/kore returns 200 with status:UP when actuator enabled | integration | `./gradlew :kore-spring:test --tests "*KoreActuatorTest*"` | Wave 0 |
| DASH-01 | /kore/fragments/active-agents returns HTML with agent rows when observer has data | unit | `./gradlew :kore-dashboard:test --tests "*ActiveAgentsFragmentTest*"` | Wave 0 |
| DASH-02 | /kore/fragments/recent-runs returns HTML table with last 20 runs (degraded notice when no DB) | unit | `./gradlew :kore-dashboard:test --tests "*RecentRunsFragmentTest*"` | Wave 0 |
| DASH-03 | /kore/fragments/cost-summary returns HTML table aggregated by agent | unit | `./gradlew :kore-dashboard:test --tests "*CostSummaryFragmentTest*"` | Wave 0 |
| DASH-04 | Ktor server starts on configured port; /kore returns 200 with HTMX script tag in response | integration | `./gradlew :kore-dashboard:test --tests "*DashboardServerTest*"` | Wave 0 |

### Wave 0 Gaps

- [ ] `kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillLoaderTest.kt` — covers SKIL-01, SKIL-03
- [ ] `kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillRegistryAdapterTest.kt` — covers SKIL-02
- [ ] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentLoopSkillTest.kt` — covers SKIL-04
- [ ] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/MinimalBootTest.kt` — covers SPRN-01
- [ ] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KorePropertiesTest.kt` — covers SPRN-02
- [ ] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreAutoConfigurationTest.kt` — covers SPRN-03
- [ ] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreActuatorTest.kt` — covers SPRN-04
- [ ] `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardServerTest.kt` — covers DASH-04
- [ ] `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/ActiveAgentsFragmentTest.kt` — covers DASH-01
- [ ] `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/RecentRunsFragmentTest.kt` — covers DASH-02
- [ ] `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/CostSummaryFragmentTest.kt` — covers DASH-03
- [ ] `kore-skills/build.gradle.kts`, `kore-spring/build.gradle.kts`, `kore-dashboard/build.gradle.kts` — new module build files
- [ ] Update `settings.gradle.kts` to include `"kore-skills"`, `"kore-spring"`, `"kore-dashboard"`

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | Dashboard is read-only, no auth in v0.1 |
| V3 Session Management | no | No sessions; stateless fragment responses |
| V4 Access Control | partial | Dashboard should only be accessible from localhost by default; configure `kore.dashboard.host=127.0.0.1` |
| V5 Input Validation | yes | YAML skill file parsing — validate fields before use; regex patterns validated to prevent ReDoS |
| V6 Cryptography | no | No cryptographic operations in Phase 3 |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| ReDoS via malicious skill YAML regex | Tampering | Validate regex patterns with a timeout at load time; reject patterns that take > 100ms on a test string |
| YAML injection via skill files | Tampering | `jackson-dataformat-yaml` does not execute arbitrary code (unlike SnakeYAML with unsafe load); always use `YAMLMapper.readValue<T>()` (safe) not `Yaml().load()` |
| Dashboard exposes agent data on public interface | Information Disclosure | Bind Ktor server to `127.0.0.1` by default; require explicit `kore.dashboard.host=0.0.0.0` to expose externally |
| HTML injection in agent names/task content displayed in dashboard | Tampering/XSS | `kotlinx.html` DSL escapes content by default; never use `unsafe { raw(...) }` for agent-supplied data |

---

## Sources

### Primary (HIGH confidence)

- [CITED: https://ktor.io/docs/htmx-integration.html] — Ktor HTMX integration, `hx {}` DSL, `@OptIn(ExperimentalKtorApi::class)` requirement
- [CITED: https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html] — Auto-configuration file path, `@AutoConfiguration`, `@ConditionalOnClass` string-form requirement, nested `@Configuration` pattern
- [CITED: https://www.jetbrains.com/help/exposed/dsl-querying-data.html] — Exposed R2DBC `selectAll().where {}`, `suspendTransaction()` API
- [CITED: https://docs.spring.io/spring-boot/reference/actuator/endpoints.html] — `@Endpoint(id = "kore")`, `@ReadOperation` pattern
- [CITED: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/SmartLifecycle.html] — SmartLifecycle `start()`, `stop()`, `isAutoStartup()`, `getPhase()` contract
- [CITED: https://api.ktor.io/ktor-server/ktor-server-core/io.ktor.server.engine/-embedded-server/index.html] — `EmbeddedServer` type (replaces `ApplicationEngine` from Ktor 3.0)
- [VERIFIED: https://github.com/charleskorn/kaml] — archived November 30, 2025; read-only
- [VERIFIED: codebase — kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentEvent.kt] — AgentEvent sealed class variants
- [VERIFIED: codebase — kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt] — AuditLog port (write-only, needs read methods)
- [VERIFIED: codebase — kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/tables/AgentRunsTable.kt] — Table schema for read queries
- [VERIFIED: codebase — gradle/libs.versions.toml] — missing Ktor and Spring Boot plugin entries

### Secondary (MEDIUM confidence)

- [CITED: https://blog.jetbrains.com/kotlin/2025/06/ktor-3-2-0-is-now-available/] — Ktor 3.2.0 release with HTMX module announcement
- [CITED: https://www.baeldung.com/jackson-yaml] — jackson-dataformat-yaml usage pattern with Kotlin data classes
- [CITED: https://medium.com/@AlexanderObregon/managing-lifecycle-hooks-with-smartlifecycle-in-spring-boot-a85d3ae70360] — SmartLifecycle patterns for Spring Boot lifecycle management

### Tertiary (LOW confidence / ASSUMED)

- A1: Spring Boot 4 constructor binding behavior for Kotlin `val` data classes — needs verification in Spring Boot 4 docs
- A2: Jackson 3.x group ID under new Spring Boot 4 grouping — should verify BOM coordinates

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — Ktor 3.2 HTMX module verified from official blog post; Spring Boot 4 auto-configuration verified from official docs; jackson-dataformat-yaml verified as kaml replacement
- Architecture: HIGH — Integration hooks verified from existing codebase code reading; Spring SmartLifecycle pattern verified from official Spring docs
- Pitfalls: HIGH — Eager auto-configuration (Pitfall 12 from PITFALLS.md), @ConditionalOnClass string-form requirement, and kaml archived status all verified from primary sources

**Research date:** 2026-04-10
**Valid until:** 2026-06-01 (Ktor HTMX module is `@ExperimentalKtorApi` — API may change; Spring Boot 4 auto-config path is stable)
