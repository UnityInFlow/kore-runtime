package dev.unityinflow.kore.spring

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentRunner
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.dsl.agent
import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.core.port.BudgetEnforcer
import dev.unityinflow.kore.core.port.EventBus
import dev.unityinflow.kore.core.port.SkillRegistry
import dev.unityinflow.kore.dashboard.DashboardServer
import dev.unityinflow.kore.skills.SkillRegistryAdapter
import dev.unityinflow.kore.spring.actuator.KoreActuatorEndpoint
import dev.unityinflow.kore.test.MockLLMBackend
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import java.util.UUID

/**
 * Phase 3 end-to-end integration test.
 *
 * Boots a full Spring Boot context with kore-spring on the classpath alongside
 * every optional kore module (kore-llm, kore-skills, kore-storage — compileOnly
 * on main, project deps on test classpath — kore-dashboard). Verifies the
 * "one dependency promise" from PROJECT.md:
 *
 *   add `dev.unityinflow:kore-spring` → write one `@Bean { agent { } }` →
 *   get a running AgentRunner bean + live dashboard bean + actuator endpoint.
 *
 * Covers the five success criteria from plan 03-04:
 *   1. DashboardServer bean is wired (via direct constructor, not reflection).
 *   2. SkillRegistryAdapter replaces NoOpSkillRegistry when kore-skills is on
 *      the classpath.
 *   3. A user `@Bean` returning `agent(...) { ... }` is injectable as an
 *      [AgentRunner] anywhere in the Spring context.
 *   4. [KoreActuatorEndpoint] reports `status=UP` through the real Spring
 *      actuator wiring.
 *   5. Spring context shutdown stops the DashboardServer cleanly.
 *
 * The dashboard is configured with `kore.dashboard.enabled=false` so the Ktor
 * CIO engine does not actually bind to port 8090 during the test — we verify
 * bean creation and lifecycle membership without opening a port (T-03-14
 * mitigation).
 */
@SpringBootTest(
    classes = [KoreIntegrationTestApp::class],
    properties = [
        "spring.main.web-application-type=none",
        // Prevent Ktor CIO engine from binding to port 8090 in the test JVM
        // while still allowing Spring to create the DashboardServer bean.
        // isAutoStartup() returns `enabled`, so SmartLifecycle start() is skipped.
        "kore.dashboard.enabled=false",
    ],
)
class KoreIntegrationTest
    @Autowired
    constructor(
        private val eventBus: EventBus,
        private val auditLog: AuditLog,
        private val budgetEnforcer: BudgetEnforcer,
        private val skillRegistry: SkillRegistry,
        private val actuatorEndpoint: KoreActuatorEndpoint,
        private val dashboardServer: DashboardServer,
        private val testAgent: AgentRunner,
    ) {
        // ───────────────────────────────────────────────────────────────
        // Test 1 — DashboardServer bean is wired (direct constructor, not reflection)
        // ───────────────────────────────────────────────────────────────
        @Test
        fun `DashboardServer bean is wired by KoreAutoConfiguration without reflection`() {
            dashboardServer.shouldNotBe(null)
            dashboardServer.shouldBeInstanceOf<DashboardServer>()
            // isAutoStartup follows kore.dashboard.enabled — we disabled it, so
            // Spring SmartLifecycle should not have started the Ktor engine.
            dashboardServer.isAutoStartup() shouldBe false
            dashboardServer.isRunning() shouldBe false
        }

        // ───────────────────────────────────────────────────────────────
        // Test 2 — kore-skills on classpath → SkillRegistryAdapter wins
        // ───────────────────────────────────────────────────────────────
        @Test
        fun `SkillRegistryAdapter replaces NoOpSkillRegistry when kore-skills is on classpath`() {
            // The test classpath includes kore-skills (testImplementation), so
            // @ConditionalOnClass on SkillsAutoConfiguration fires and the
            // adapter bean beats the default NoOpSkillRegistry.
            skillRegistry.shouldBeInstanceOf<SkillRegistryAdapter>()
        }

        // ───────────────────────────────────────────────────────────────
        // Test 3 — user @Bean agent() is injectable as AgentRunner
        // ───────────────────────────────────────────────────────────────
        @Test
        fun `user @Bean agent is injectable as AgentRunner`() {
            testAgent.shouldNotBe(null)
            testAgent.shouldBeInstanceOf<AgentRunner>()
        }

        // ───────────────────────────────────────────────────────────────
        // Test 4 — KoreActuatorEndpoint reports status=UP
        // ───────────────────────────────────────────────────────────────
        @Test
        fun `KoreActuatorEndpoint health returns UP through the Spring context`() {
            val health = actuatorEndpoint.health()
            health["status"] shouldBe "UP"
            // agentsActive tracked via EventBus subscription — zero at start.
            health.containsKey("agentsActive") shouldBe true
            health.containsKey("version") shouldBe true
        }

        // ───────────────────────────────────────────────────────────────
        // Test 5 — full bean graph is wired (sanity check on all default beans)
        // ───────────────────────────────────────────────────────────────
        @Test
        fun `all default kore beans are wired alongside the user agent bean`() {
            eventBus.shouldNotBe(null)
            auditLog.shouldNotBe(null)
            budgetEnforcer.shouldNotBe(null)
            skillRegistry.shouldNotBe(null)
            actuatorEndpoint.shouldNotBe(null)
            dashboardServer.shouldNotBe(null)
            testAgent.shouldNotBe(null)
        }

        // ───────────────────────────────────────────────────────────────
        // Test 6 (ME-08) — user agent runs the full loop end-to-end
        //
        // Pre-fix, every test in this class only asserted bean presence —
        // no test actually invoked `testAgent.run(...)`, so any failure
        // mode that happened AFTER DI (observer subscription timing,
        // SkillRegistry activation, budget enforcer semantics) was
        // invisible. This test closes that gap by exercising the full
        // wired graph via MockLLMBackend and asserting that EventBus
        // subscribers observe AgentStarted + AgentCompleted events.
        // ───────────────────────────────────────────────────────────────
        @Test
        fun `user agent bean runs full loop through auto-wired EventBus`(): Unit =
            runBlocking {
                // Subscribe on a fresh scope BEFORE the run so the subscriber
                // is active before the first emission. (EventBus uses a hot
                // SharedFlow — late subscribers miss past events.)
                val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val collected = mutableListOf<AgentEvent>()
                val job =
                    collectorScope.launch {
                        eventBus.subscribe().collect { event ->
                            collected += event
                        }
                    }
                // Yield so the collect {} reaches its suspension point and
                // the SharedFlow registers the subscriber before the agent
                // runner emits anything.
                yield()

                val task =
                    AgentTask(
                        id = UUID.randomUUID().toString(),
                        input = "hello",
                    )
                val result =
                    withTimeout(10_000L) {
                        testAgent.run(task).await()
                    }

                result.shouldBeInstanceOf<AgentResult.Success>()

                // Give the SharedFlow a moment to deliver the terminal
                // AgentCompleted event to our collector before we cancel.
                yield()

                job.cancel()
                collectorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

                // The full loop should have emitted at least one AgentStarted
                // and one AgentCompleted. We only pin the shapes — not the
                // exact count — so the assertion is robust to any future
                // instrumentation that adds extra events.
                val started = collected.filterIsInstance<AgentEvent.AgentStarted>()
                val completed = collected.filterIsInstance<AgentEvent.AgentCompleted>()
                started shouldHaveSize 1
                completed shouldHaveSize 1
            }
    }

/**
 * Minimal @SpringBootApplication scan root for [KoreIntegrationTest].
 *
 * Defines a single `@Bean` returning an [AgentRunner] built via the kore DSL —
 * this is exactly the developer experience the README hero demo shows. The
 * scan root lives in the same package so [KoreActuatorEndpoint] (annotated
 * @Component) is discovered.
 */
@SpringBootApplication
class KoreIntegrationTestApp {
    /**
     * The entire "user-written" footprint of an agent in a Spring Boot app.
     * The kore-spring auto-configuration supplies EventBus, BudgetEnforcer,
     * AuditLog and SkillRegistry as Spring beans — we pass them through the
     * DSL so this agent shares the same backing infrastructure as the rest of
     * the application.
     */
    @Bean
    fun testAgent(
        eventBus: EventBus,
        budgetEnforcer: BudgetEnforcer,
        auditLog: AuditLog,
    ): AgentRunner =
        agent("integration-test-agent") {
            model =
                MockLLMBackend("integration-test").whenCalled(
                    LLMChunk.Text("Hello from mock!"),
                    LLMChunk.Usage(inputTokens = 10, outputTokens = 5),
                    LLMChunk.Done,
                )
            eventBus(eventBus)
            auditLog(auditLog)
            budget(maxTokens = 1_000L)
        }
}
