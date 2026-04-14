package dev.unityinflow.kore.spring

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.internal.InMemoryAuditLog
import dev.unityinflow.kore.core.internal.InMemoryBudgetEnforcer
import dev.unityinflow.kore.core.internal.InProcessEventBus
import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.core.port.BudgetEnforcer
import dev.unityinflow.kore.core.port.EventBus
import dev.unityinflow.kore.core.port.NoOpSkillRegistry
import dev.unityinflow.kore.core.port.SkillRegistry
import dev.unityinflow.kore.spring.actuator.KoreActuatorEndpoint
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Integration tests for [KoreAutoConfiguration].
 *
 * Two complementary patterns are used:
 *
 *  1. [ApplicationContextRunner] — a fast in-process Spring context built
 *     directly from `KoreAutoConfiguration` without scanning. Used for the
 *     bean-presence / @ConditionalOnMissingBean / @ConditionalOnProperty
 *     scenarios that don't need component scanning.
 *
 *  2. [SpringBootTest] — a full Spring Boot test context that picks up the
 *     META-INF/spring/...AutoConfiguration.imports SPI file AND scans for
 *     [KoreActuatorEndpoint] (which is annotated @Component, so it requires
 *     a real @SpringBootApplication scan root). Used for the actuator
 *     end-to-end test (status="UP", agentsActive present).
 */
class KoreAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoreAutoConfiguration::class.java))
            // kore-dashboard is on the test classpath (so the integration test
            // can wire it). Disable the dashboard SmartLifecycle for these
            // unit tests so nothing tries to bind to port 8090 — DashboardServer
            // honours `enabled=false` via isAutoStartup() and never starts the
            // Ktor CIO engine.
            .withPropertyValues("kore.dashboard.enabled=false")

    // ───────────────────────────────────────────────────────────────────
    // Default beans (Tests 1-4)
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `auto-configures InProcessEventBus as the default EventBus bean`() {
        contextRunner.run { context ->
            context.getBean(EventBus::class.java).shouldBeInstanceOf<InProcessEventBus>()
        }
    }

    @Test
    fun `auto-configures InMemoryBudgetEnforcer as the default BudgetEnforcer bean`() {
        contextRunner.run { context ->
            context.getBean(BudgetEnforcer::class.java).shouldBeInstanceOf<InMemoryBudgetEnforcer>()
        }
    }

    @Test
    fun `auto-configures InMemoryAuditLog as the default AuditLog bean when kore-storage is absent`() {
        contextRunner.run { context ->
            context.getBean(AuditLog::class.java).shouldBeInstanceOf<InMemoryAuditLog>()
        }
    }

    @Test
    fun `auto-configures SkillRegistryAdapter as the SkillRegistry bean when kore-skills is on classpath`() {
        // kore-skills is on the test classpath (testImplementation), so the
        // @ConditionalOnClass gate on SkillsAutoConfiguration fires and the
        // adapter beats the default NoOpSkillRegistry. The "kore-skills absent"
        // path is exercised at runtime only — it cannot be tested from inside
        // the same module that declares kore-skills as a test dep.
        contextRunner.run { context ->
            val bean = context.getBean(SkillRegistry::class.java)
            (bean === NoOpSkillRegistry) shouldBe false
            bean.shouldBeInstanceOf<dev.unityinflow.kore.skills.SkillRegistryAdapter>()
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // @ConditionalOnMissingBean override (Test 5)
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `user-supplied EventBus bean overrides the auto-configured InProcessEventBus`() {
        contextRunner
            .withUserConfiguration(UserEventBusConfig::class.java)
            .run { context ->
                val bean = context.getBean(EventBus::class.java)
                bean.shouldNotBe(null)
                // The user bean is a recording stand-in, NOT InProcessEventBus
                (bean is InProcessEventBus) shouldBe false
                bean.shouldBeInstanceOf<RecordingEventBus>()
            }
    }

    // ───────────────────────────────────────────────────────────────────
    // LLM backend conditional (Test 8 — D-15)
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `ClaudeBackend bean is registered when kore-llm is on classpath and api-key is set`() {
        contextRunner
            .withPropertyValues(
                "kore.llm.claude.api-key=sk-test-not-a-real-key",
            ).run { context ->
                // ClaudeBackend is on the test classpath because kore-spring tests
                // bring in kore-llm transitively via testRuntime — verify the bean
                // is registered when the api-key property is present.
                val backendBeanNames =
                    context.getBeanNamesForType(dev.unityinflow.kore.llm.ClaudeBackend::class.java)
                backendBeanNames.toList() shouldContain "claudeBackend"
            }
    }

    @Test
    fun `ClaudeBackend bean is NOT registered when api-key is missing`() {
        contextRunner.run { context ->
            // No kore.llm.claude.api-key in the environment → @ConditionalOnProperty
            // gate is closed → bean must not exist.
            context.getBeanNamesForType(dev.unityinflow.kore.llm.ClaudeBackend::class.java).size shouldBe 0
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Actuator endpoint (Tests 6-7)
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `actuator endpoint reports status UP and agentsActive`() {
        // Construct the endpoint directly with default beans — same wiring
        // Spring would do, but without spinning up a full @SpringBootTest
        // (kore-spring is a library, not an application).
        val eventBus = InProcessEventBus()
        val budgetEnforcer = InMemoryBudgetEnforcer()
        val endpoint = KoreActuatorEndpoint(eventBus, budgetEnforcer)
        endpoint.start()
        try {
            val health = endpoint.health()
            // Map<String, Any?> values are nullable, so use containsKey() rather
            // than Kotest's shouldContainKey (which constrains V : Any).
            health.containsKey("status") shouldBe true
            health.containsKey("agentsActive") shouldBe true
            health.containsKey("version") shouldBe true
            health["status"] shouldBe "UP"
            (health["agentsActive"] as Int) shouldBe 0
        } finally {
            endpoint.stop()
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Test fixtures
    // ───────────────────────────────────────────────────────────────────

    /** Spring @Configuration registering a user-supplied EventBus bean for the override test. */
    @Configuration(proxyBeanMethods = false)
    class UserEventBusConfig {
        @Bean
        fun userEventBus(): EventBus = RecordingEventBus()
    }

    /** Minimal EventBus stand-in to verify @ConditionalOnMissingBean precedence. */
    class RecordingEventBus : EventBus {
        private val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)

        override suspend fun emit(event: AgentEvent) {
            flow.emit(event)
        }

        override fun subscribe(): Flow<AgentEvent> = flow
    }
}

/**
 * Full @SpringBootTest scan that boots the actuator @Component and verifies
 * autowiring through the Spring Boot 4 test context (SPI-driven loading of
 * KoreAutoConfiguration via META-INF/spring/...AutoConfiguration.imports).
 */
@SpringBootTest(
    classes = [KoreAutoConfigurationTestApp::class],
    properties = [
        // Disable Spring's web environment — kore-spring is not a web app on its own.
        "spring.main.web-application-type=none",
        // kore-dashboard is on the test classpath. Disable the dashboard
        // SmartLifecycle so the Ktor CIO engine does not bind to port 8090
        // during this Spring context boot (port collision with the integration
        // test would otherwise flake CI).
        "kore.dashboard.enabled=false",
    ],
)
class KoreAutoConfigurationSpringContextTest {
    @Autowired
    private lateinit var endpoint: KoreActuatorEndpoint

    @Autowired
    private lateinit var eventBus: EventBus

    @Test
    fun `Spring Boot test context wires KoreActuatorEndpoint and EventBus`() {
        eventBus.shouldBeInstanceOf<InProcessEventBus>()
        val health = endpoint.health()
        health["status"] shouldBe "UP"
    }
}

@org.springframework.boot.autoconfigure.SpringBootApplication
class KoreAutoConfigurationTestApp
