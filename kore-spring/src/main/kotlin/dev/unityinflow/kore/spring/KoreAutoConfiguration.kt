package dev.unityinflow.kore.spring

import dev.unityinflow.kore.core.internal.InMemoryAuditLog
import dev.unityinflow.kore.core.internal.InMemoryBudgetEnforcer
import dev.unityinflow.kore.core.internal.InProcessEventBus
import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.core.port.BudgetEnforcer
import dev.unityinflow.kore.core.port.EventBus
import dev.unityinflow.kore.core.port.NoOpSkillRegistry
import dev.unityinflow.kore.core.port.SkillRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.beans.factory.annotation.Qualifier
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
 *  - kore-kafka → KafkaEventBus (EVNT-03, D-08 — plan 04-04)
 *  - kore-rabbitmq → RabbitMqEventBus (EVNT-04, D-08 — plan 04-04)
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

    @Bean
    @ConditionalOnMissingBean(KoreAgentFactory::class)
    fun koreAgentFactory(
        eventBus: EventBus,
        auditLog: AuditLog,
        skillRegistry: SkillRegistry,
    ): KoreAgentFactory = KoreAgentFactory(eventBus, auditLog, skillRegistry)

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
    // Dashboard (D-21) — EXTRACTED to KoreDashboardAutoConfiguration.kt
    //
    // The inner class approach caused NoClassDefFoundError for
    // DashboardServer$DashboardProperties when kore-dashboard wasn't on the
    // classpath, because JVM resolves extends/implements eagerly at class load.
    // See KoreDashboardAutoConfiguration for the fix.
    // ───────────────────────────────────────────────────────────────────────

    // ───────────────────────────────────────────────────────────────────────
    // Event bus adapter scope (EVNT-03 / EVNT-04 / D-08 / plan 04-04)
    //
    // Shared CoroutineScope for Kafka and RabbitMQ consumer loops. The scope
    // is only created when an adapter is actively selected via
    // `kore.event-bus.type={kafka,rabbitmq}` — the in-process default does not
    // need it. Two parallel inner @Configuration classes are used because
    // @ConditionalOnProperty on @Bean definitions does not natively support an
    // `anyOf` disjunction (kafka OR rabbitmq); duplicating is simpler and
    // keeps every conditional explicit.
    //
    // The bean name is always "koreEventBusScope" so the adapter @Bean methods
    // below pick it up via @Qualifier regardless of which variant fires.
    // ───────────────────────────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "kore.event-bus",
        name = ["type"],
        havingValue = "kafka",
    )
    class KafkaEventBusScopeConfiguration {
        @Bean("koreEventBusScope", destroyMethod = "close")
        @ConditionalOnMissingBean(name = ["koreEventBusScope"])
        fun koreEventBusScope(): CoroutineScope =
            CloseableCoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "kore.event-bus",
        name = ["type"],
        havingValue = "rabbitmq",
    )
    class RabbitMqEventBusScopeConfiguration {
        @Bean("koreEventBusScope", destroyMethod = "close")
        @ConditionalOnMissingBean(name = ["koreEventBusScope"])
        fun koreEventBusScope(): CoroutineScope =
            CloseableCoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    }

    // ───────────────────────────────────────────────────────────────────────
    // Kafka EventBus (EVNT-03 / D-01 / D-08 / RESEARCH.md Pattern 6)
    //
    // Guarded by @ConditionalOnClass(name=[...]) string form (Pitfall 2) —
    // `dev.unityinflow.kore.kafka.KafkaEventBus` only resolves when the
    // opt-in kore-kafka module is present on the runtime classpath. Class
    // literal reference would eagerly load the symbol at scan time and crash
    // the Spring context when kore-kafka is absent.
    //
    // `havingValue = "kafka"` is explicit per Pitfall 8 — merely setting
    // `kore.event-bus.type` to any non-empty value (or leaving it blank) must
    // NOT activate Kafka. In-process stays the safe default.
    //
    // @Bean method is named `kafkaEventBus` — the Spring context test in
    // plan 04-04 asserts `assertThat(ctx).hasBean("kafkaEventBus")`. This is
    // a bean-definition-level assertion that does NOT invoke the factory
    // method, so no KafkaProducer/KafkaConsumer is constructed during the
    // test and no real broker socket is opened.
    //
    // `destroyMethod = "close"` so Spring calls AutoCloseable.close() at
    // context shutdown — graceful producer/consumer shutdown per D-01.
    // ───────────────────────────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.kafka.KafkaEventBus"])
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
            dev.unityinflow.kore.kafka.KafkaEventBus(
                config = properties.eventBus.kafka.toAdapterConfig(),
                scope = scope,
            )
    }

    // ───────────────────────────────────────────────────────────────────────
    // RabbitMQ EventBus (EVNT-04 / D-02 / D-08 / RESEARCH.md Pattern 6)
    //
    // RabbitMqEventBus uses lazy Connection + lazy publishChannel (Pitfall 7),
    // so bean resolution does not open a broker socket — the first emit() or
    // subscribe() does. That lets the Spring context refresh succeed even
    // when the broker is temporarily unavailable. The plan 04-04 test still
    // uses the same `assertThat(ctx).hasBean(...)` definition-level assertion
    // as the Kafka test for consistency and to stay resilient to future
    // refactors that might move the lazy boundary.
    //
    // @Bean method is named `rabbitMqEventBus` to match `hasBean("rabbitMqEventBus")`.
    // ───────────────────────────────────────────────────────────────────────

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["dev.unityinflow.kore.rabbitmq.RabbitMqEventBus"])
    @ConditionalOnProperty(
        prefix = "kore.event-bus",
        name = ["type"],
        havingValue = "rabbitmq",
    )
    class RabbitMqEventBusAutoConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(EventBus::class)
        fun rabbitMqEventBus(
            properties: KoreProperties,
            @Qualifier("koreEventBusScope") scope: CoroutineScope,
        ): EventBus =
            dev.unityinflow.kore.rabbitmq.RabbitMqEventBus(
                config = properties.eventBus.rabbitmq.toAdapterConfig(),
                scope = scope,
            )
    }
}
