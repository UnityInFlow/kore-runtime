package dev.unityinflow.kore.spring

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure-Kotlin tests for [KoreProperties]: defaults, custom-value construction,
 * and the Spring Boot auto-configuration SPI registration file (META-INF/spring).
 *
 * No Spring context is started here — these are constructor-level tests against
 * the immutable data class hierarchy. Spring binding is exercised in
 * [KoreAutoConfigurationTest].
 */
class KorePropertiesTest {
    @Test
    fun `default constructor uses documented zero-config defaults`() {
        val props = KoreProperties()

        // Skills
        props.skills.directory shouldBe "./kore-skills"

        // Dashboard
        props.dashboard.port shouldBe 8090
        props.dashboard.path shouldBe "/kore"
        props.dashboard.enabled shouldBe true

        // Budget
        props.budget.defaultMaxTokens shouldBe 100_000L

        // LLM API keys default to blank — autoconfig will NOT create LLM beans
        // until the user populates `kore.llm.*.api-key`
        props.llm.claude.apiKey shouldBe ""
        props.llm.openai.apiKey shouldBe ""
        props.llm.gemini.apiKey shouldBe ""

        // Storage URLs default to blank — autoconfig falls back to InMemoryAuditLog
        props.storage.r2dbcUrl shouldBe ""
        props.storage.jdbcUrl shouldBe ""

        // MCP server list defaults to empty
        props.mcp.servers.shouldBeEmpty()

        // Event bus defaults to in-process with blank adapter credentials
        props.eventBus.type shouldBe "in-process"
        props.eventBus.kafka.bootstrapServers shouldBe ""
        props.eventBus.kafka.topic shouldBe "kore-agent-events"
        props.eventBus.kafka.groupIdPrefix shouldBe "kore"
        props.eventBus.rabbitmq.uri shouldBe ""
        props.eventBus.rabbitmq.exchange shouldBe "kore.agent-events"
        props.eventBus.rabbitmq.confirmTimeoutMillis shouldBe 5_000L
    }

    @Test
    fun `custom eventBus type and kafka config overrides defaults`() {
        val props =
            KoreProperties(
                eventBus =
                    KoreProperties.EventBusProperties(
                        type = "kafka",
                        kafka =
                            KoreProperties.KafkaProperties(
                                bootstrapServers = "broker.example.com:9092",
                                topic = "custom-topic",
                                groupIdPrefix = "prod",
                            ),
                    ),
            )

        props.eventBus.type shouldBe "kafka"
        props.eventBus.kafka.bootstrapServers shouldBe "broker.example.com:9092"
        props.eventBus.kafka.topic shouldBe "custom-topic"
        props.eventBus.kafka.groupIdPrefix shouldBe "prod"
    }

    @Test
    fun `KafkaProperties toAdapterConfig maps fields to KafkaEventBusConfig`() {
        val kafka =
            KoreProperties.KafkaProperties(
                bootstrapServers = "broker:9092",
                topic = "events",
                groupIdPrefix = "svc",
            )

        val adapter = kafka.toAdapterConfig()

        adapter.bootstrapServers shouldBe "broker:9092"
        adapter.topic shouldBe "events"
        adapter.groupIdPrefix shouldBe "svc"
    }

    @Test
    fun `RabbitMqProperties toAdapterConfig maps fields to RabbitMqEventBusConfig`() {
        val rabbit =
            KoreProperties.RabbitMqProperties(
                uri = "amqp://broker:5672",
                exchange = "events",
                confirmTimeoutMillis = 7_500L,
            )

        val adapter = rabbit.toAdapterConfig()

        adapter.uri shouldBe "amqp://broker:5672"
        adapter.exchange shouldBe "events"
        adapter.confirmTimeoutMillis shouldBe 7_500L
    }

    @Test
    fun `custom dashboard port overrides the default`() {
        val props =
            KoreProperties(
                dashboard = KoreProperties.DashboardProperties(port = 9090),
            )

        props.dashboard.port shouldBe 9090
        // unrelated defaults still hold
        props.dashboard.path shouldBe "/kore"
        props.dashboard.enabled shouldBe true
    }

    @Test
    fun `auto-configuration SPI imports file registers KoreAutoConfiguration`() {
        val resourcePath = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
        val stream =
            checkNotNull(this::class.java.classLoader.getResourceAsStream(resourcePath)) {
                "Spring Boot 3/4 auto-configuration SPI file is missing from kore-spring resources"
            }
        val content = stream.bufferedReader().use { it.readText() }.trim()

        // The file must contain exactly the FQN of KoreAutoConfiguration
        // (one entry per line, blank lines and comments allowed).
        val nonEmptyLines =
            content
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

        nonEmptyLines shouldBe listOf("dev.unityinflow.kore.spring.KoreAutoConfiguration")
    }

    private fun List<String>.shouldBeEmpty() {
        this.size shouldBe 0
    }
}
