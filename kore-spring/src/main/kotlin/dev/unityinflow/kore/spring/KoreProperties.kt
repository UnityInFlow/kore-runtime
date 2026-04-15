package dev.unityinflow.kore.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Root @ConfigurationProperties binding for everything under the `kore.*` namespace
 * in `application.yml` (D-22 / D-23).
 *
 * All sub-sections are required and default to safe zero-config values so that a
 * developer who adds `kore-spring` with no `kore.*` configuration still gets a
 * fully wired runtime (the "one dependency promise"). API key fields default to
 * blank strings — when blank the corresponding LLM backend bean is not created
 * (gated by `@ConditionalOnProperty` in [KoreAutoConfiguration], per D-15).
 *
 * Constructor binding is automatic in Spring Boot 4 for Kotlin data classes when
 * the class is registered via `@EnableConfigurationProperties`; no explicit
 * `@ConstructorBinding` annotation is required.
 */
@ConfigurationProperties("kore")
data class KoreProperties(
    val llm: LlmProperties = LlmProperties(),
    val mcp: McpProperties = McpProperties(),
    val skills: SkillsProperties = SkillsProperties(),
    val storage: StorageProperties = StorageProperties(),
    val dashboard: DashboardProperties = DashboardProperties(),
    val budget: BudgetProperties = BudgetProperties(),
    val eventBus: EventBusProperties = EventBusProperties(),
) {
    /** LLM backend configuration — one nested block per supported provider. */
    data class LlmProperties(
        val claude: ClaudeProperties = ClaudeProperties(),
        val openai: OpenAiProperties = OpenAiProperties(),
        val ollama: OllamaProperties = OllamaProperties(),
        val gemini: GeminiProperties = GeminiProperties(),
    )

    /** Anthropic Claude — `kore.llm.claude.api-key`. Empty disables auto-wiring. */
    data class ClaudeProperties(
        val apiKey: String = "",
        val model: String = "claude-3-5-sonnet-20241022",
    )

    /** OpenAI — `kore.llm.openai.api-key`. Empty disables auto-wiring. */
    data class OpenAiProperties(
        val apiKey: String = "",
        val model: String = "gpt-4o",
    )

    /**
     * Ollama — local model server, no API key required.
     *
     * [enabled] defaults to `false` so that adding kore-llm to the classpath
     * does not eagerly construct an OllamaChatModel when the user only wants
     * Claude or OpenAI. Set `kore.llm.ollama.enabled=true` to opt in.
     */
    data class OllamaProperties(
        val enabled: Boolean = false,
        val baseUrl: String = "http://localhost:11434",
        val model: String = "llama3",
    )

    /** Google Gemini — `kore.llm.gemini.api-key`. Empty disables auto-wiring. */
    data class GeminiProperties(
        val apiKey: String = "",
        val model: String = "gemini-1.5-flash",
    )

    /** MCP servers to connect to on startup (`kore.mcp.servers`). Empty by default. */
    data class McpProperties(
        val servers: List<String> = emptyList(),
    )

    /** Filesystem directory for user-supplied YAML skills (D-06 / D-07). */
    data class SkillsProperties(
        val directory: String = "./kore-skills",
    )

    /**
     * Storage configuration. When [r2dbcUrl] is blank, kore-spring falls back
     * to [dev.unityinflow.kore.core.internal.InMemoryAuditLog] (D-18).
     */
    data class StorageProperties(
        val r2dbcUrl: String = "",
        val jdbcUrl: String = "",
    )

    /** Dashboard side-car configuration. Defaults match RESEARCH.md Pattern 8. */
    data class DashboardProperties(
        val port: Int = 8090,
        val path: String = "/kore",
        val enabled: Boolean = true,
    )

    /** In-memory budget enforcer defaults — replaced by budget-breaker in v2. */
    data class BudgetProperties(
        val defaultMaxTokens: Long = 100_000L,
    )

    /**
     * Event bus selector and adapter-specific configuration (D-08).
     *
     * [type] controls which `EventBus` implementation kore-spring wires:
     *  - `in-process` (default): [dev.unityinflow.kore.core.internal.InProcessEventBus]
     *    — Kotlin Flows SharedFlow with DROP_OLDEST (EVNT-02).
     *  - `kafka`: `dev.unityinflow.kore.kafka.KafkaEventBus` — requires kore-kafka on
     *    the classpath (EVNT-03).
     *  - `rabbitmq`: `dev.unityinflow.kore.rabbitmq.RabbitMqEventBus` — requires
     *    kore-rabbitmq on the classpath (EVNT-04).
     *
     * Per D-08 and Pitfall 8, `havingValue` is always explicit on the auto-configuration
     * conditions — the string `type` must exactly match to activate.
     */
    data class EventBusProperties(
        val type: String = "in-process",
        val kafka: KafkaProperties = KafkaProperties(),
        val rabbitmq: RabbitMqProperties = RabbitMqProperties(),
    )

    /**
     * Kafka adapter configuration — bound under `kore.event-bus.kafka.*`.
     * Broadcast semantics: see kore-kafka README (consumer group
     * `${groupIdPrefix}-${hostname}-${pid}` per D-04).
     */
    data class KafkaProperties(
        val bootstrapServers: String = "",
        val topic: String = "kore-agent-events",
        val groupIdPrefix: String = "kore",
    )

    /** RabbitMQ adapter configuration — bound under `kore.event-bus.rabbitmq.*`. */
    data class RabbitMqProperties(
        val uri: String = "",
        val exchange: String = "kore.agent-events",
        val confirmTimeoutMillis: Long = 5_000L,
    )
}

/** Maps [KoreProperties.KafkaProperties] to kore-kafka's native config type. */
fun KoreProperties.KafkaProperties.toAdapterConfig(): dev.unityinflow.kore.kafka.KafkaEventBusConfig =
    dev.unityinflow.kore.kafka.KafkaEventBusConfig(
        bootstrapServers = this.bootstrapServers,
        topic = this.topic,
        groupIdPrefix = this.groupIdPrefix,
    )

/** Maps [KoreProperties.RabbitMqProperties] to kore-rabbitmq's native config type. */
fun KoreProperties.RabbitMqProperties.toAdapterConfig(): dev.unityinflow.kore.rabbitmq.RabbitMqEventBusConfig =
    dev.unityinflow.kore.rabbitmq.RabbitMqEventBusConfig(
        uri = this.uri,
        exchange = this.exchange,
        confirmTimeoutMillis = this.confirmTimeoutMillis,
    )
