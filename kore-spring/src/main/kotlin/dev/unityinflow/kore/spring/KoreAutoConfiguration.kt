package dev.unityinflow.kore.spring

import dev.unityinflow.kore.core.internal.InMemoryAuditLog
import dev.unityinflow.kore.core.internal.InMemoryBudgetEnforcer
import dev.unityinflow.kore.core.internal.InProcessEventBus
import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.core.port.BudgetEnforcer
import dev.unityinflow.kore.core.port.EventBus
import dev.unityinflow.kore.core.port.NoOpSkillRegistry
import dev.unityinflow.kore.core.port.SkillRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring Boot 4 auto-configuration entry point for kore-runtime.
 *
 * Registers four always-present default beans (event bus, budget enforcer,
 * audit log, skill registry) backed by the in-memory implementations from
 * kore-core, plus a graph of conditional inner @Configuration blocks that
 * activate when their corresponding optional kore module is on the classpath:
 *
 *  - kore-llm  → ClaudeBackend / OpenAiBackend / OllamaBackend / GeminiBackend (D-15)
 *  - kore-storage → PostgresAuditLogAdapter (D-18)
 *  - kore-observability → KoreTracer (D-19)
 *  - kore-skills → SkillRegistryAdapter (D-20)
 *  - kore-dashboard → DashboardServer (D-21, wired in plan 03-03)
 *
 * Every conditional uses `@ConditionalOnClass(name=[...])` string form to
 * avoid the eager classloading pitfall (RESEARCH.md Pattern 6 / Pitfall 12) —
 * Spring will not resolve the gating class symbol until it has confirmed the
 * class is present on the runtime classpath.
 *
 * Every default bean uses `@ConditionalOnMissingBean(<port>::class)` so that a
 * user-supplied `@Bean` of the same port wins over the kore-spring default.
 *
 * `proxyBeanMethods = false` on every nested @Configuration matches the
 * Spring Boot starter convention — these classes contain no @Bean methods that
 * call each other, so CGLIB proxying is wasted overhead.
 */
@AutoConfiguration
@EnableConfigurationProperties(KoreProperties::class)
class KoreAutoConfiguration {
    // ───────────────────────────────────────────────────────────────────────
    // Always-present default beans (in-memory, zero-dep)
    // ───────────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(EventBus::class)
    fun inProcessEventBus(): EventBus = InProcessEventBus()

    @Bean
    @ConditionalOnMissingBean(BudgetEnforcer::class)
    fun inMemoryBudgetEnforcer(properties: KoreProperties): BudgetEnforcer =
        InMemoryBudgetEnforcer(defaultLimitPerAgent = properties.budget.defaultMaxTokens)

    @Bean
    @ConditionalOnMissingBean(AuditLog::class)
    fun inMemoryAuditLog(): AuditLog = InMemoryAuditLog()

    @Bean
    @ConditionalOnMissingBean(SkillRegistry::class)
    fun noOpSkillRegistry(): SkillRegistry = NoOpSkillRegistry

    // ───────────────────────────────────────────────────────────────────────
    // LLM backends (D-15) — gated on kore-llm classpath presence + api-key set
    //
    // The real constructors take provider client objects (AnthropicClient,
    // OpenAIClient, ChatLanguageModel, ...) so we delegate to the public
    // top-level factory functions in dev.unityinflow.kore.llm.LlmBackends.kt
    // which build the clients from API keys exactly as the DSL does.
    // ───────────────────────────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.llm.ClaudeBackend"])
    @ConditionalOnProperty(prefix = "kore.llm.claude", name = ["api-key"], matchIfMissing = false)
    class ClaudeLlmAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = ["claudeBackend"])
        fun claudeBackend(properties: KoreProperties): dev.unityinflow.kore.llm.ClaudeBackend =
            dev.unityinflow.kore.llm.claude(
                apiKey = properties.llm.claude.apiKey,
                model = properties.llm.claude.model,
            )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.llm.OpenAiBackend"])
    @ConditionalOnProperty(prefix = "kore.llm.openai", name = ["api-key"], matchIfMissing = false)
    class OpenAiLlmAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = ["openAiBackend"])
        fun openAiBackend(properties: KoreProperties): dev.unityinflow.kore.llm.OpenAiBackend =
            dev.unityinflow.kore.llm.gpt(
                apiKey = properties.llm.openai.apiKey,
                model = properties.llm.openai.model,
            )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.llm.OllamaBackend"])
    @ConditionalOnProperty(
        prefix = "kore.llm.ollama",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    class OllamaLlmAutoConfiguration {
        // Ollama uses a local endpoint — no api-key required, but we still
        // gate auto-wiring on an explicit `kore.llm.ollama.enabled=true` opt-in
        // so that adding kore-llm to the classpath does NOT eagerly construct
        // an OllamaChatModel (which would fail at startup if the LangChain4j
        // ollama adapter has a transitive version skew with another adapter
        // on the same classpath, e.g. langchain4j-google-ai-gemini).
        @Bean
        @ConditionalOnMissingBean(name = ["ollamaBackend"])
        fun ollamaBackend(properties: KoreProperties): dev.unityinflow.kore.llm.OllamaBackend =
            dev.unityinflow.kore.llm.ollama(
                baseUrl = properties.llm.ollama.baseUrl,
                model = properties.llm.ollama.model,
            )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.llm.GeminiBackend"])
    @ConditionalOnProperty(prefix = "kore.llm.gemini", name = ["api-key"], matchIfMissing = false)
    class GeminiLlmAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = ["geminiBackend"])
        fun geminiBackend(properties: KoreProperties): dev.unityinflow.kore.llm.GeminiBackend =
            dev.unityinflow.kore.llm.gemini(
                apiKey = properties.llm.gemini.apiKey,
                model = properties.llm.gemini.model,
            )
    }

    // ───────────────────────────────────────────────────────────────────────
    // Storage (D-18) — kore-storage compileOnly + @ConditionalOnClass guard
    //
    // PostgresAuditLogAdapter takes a R2dbcDatabase that the host application
    // must supply as its own @Bean (via Spring Boot R2DBC auto-configuration
    // or a manual bean). When kore-storage is on classpath but no R2dbcDatabase
    // bean exists, this @Bean method is skipped and InMemoryAuditLog wins.
    // ───────────────────────────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.storage.PostgresAuditLogAdapter"])
    class StorageAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(AuditLog::class)
        fun postgresAuditLog(database: org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase): AuditLog =
            dev.unityinflow.kore.storage
                .PostgresAuditLogAdapter(database)
    }

    // ───────────────────────────────────────────────────────────────────────
    // Observability (D-19) — kore-observability compileOnly + class guard
    //
    // KoreTracer wraps the host application's io.opentelemetry.api.trace.Tracer
    // bean. If the host doesn't expose a Tracer, the @Bean method is skipped.
    // ───────────────────────────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
        name = [
            "dev.unityinflow.kore.observability.KoreTracer",
            "io.opentelemetry.api.trace.Tracer",
        ],
    )
    class ObservabilityAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(dev.unityinflow.kore.observability.KoreTracer::class)
        fun koreTracer(tracer: io.opentelemetry.api.trace.Tracer): dev.unityinflow.kore.observability.KoreTracer =
            dev.unityinflow.kore.observability
                .KoreTracer(tracer)
    }

    // ───────────────────────────────────────────────────────────────────────
    // Skills (D-20) — kore-skills compileOnly + class guard.
    //
    // SkillRegistryAdapter loads YAML skills from `kore.skills.directory`
    // (filesystem) merged with classpath META-INF/kore/skills entries.
    // ───────────────────────────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.skills.SkillRegistryAdapter"])
    class SkillsAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(SkillRegistry::class)
        fun skillRegistry(properties: KoreProperties): SkillRegistry =
            dev.unityinflow.kore.skills
                .SkillRegistryAdapter(properties.skills.directory)
    }

    // ───────────────────────────────────────────────────────────────────────
    // Dashboard (D-21) — wired in plan 03-03.
    //
    // Until the kore-dashboard module exists we use a reflective construction
    // path so kore-spring continues to compile without a hard kore-dashboard
    // dependency. The full direct-constructor wiring is added in plan 03-03
    // when DashboardServer is created.
    // ───────────────────────────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.dashboard.DashboardServer"])
    @ConditionalOnProperty(
        prefix = "kore.dashboard",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    class DashboardAutoConfiguration {
        @Bean
        fun dashboardServer(
            eventBus: EventBus,
            auditLog: AuditLog,
            properties: KoreProperties,
        ): Any {
            // Reflective bridge to be replaced by direct constructor in plan 03-03.
            // @ConditionalOnClass guarantees DashboardServer is on classpath here.
            val clazz = Class.forName("dev.unityinflow.kore.dashboard.DashboardServer")
            return clazz
                .getConstructor(
                    EventBus::class.java,
                    AuditLog::class.java,
                    KoreProperties.DashboardProperties::class.java,
                ).newInstance(eventBus, auditLog, properties.dashboard)
        }
    }
}
