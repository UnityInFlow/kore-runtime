package dev.unityinflow.kore.spring

import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.core.ToolResult
import dev.unityinflow.kore.core.internal.InMemoryAuditLog
import dev.unityinflow.kore.core.port.AgentCostRecord
import dev.unityinflow.kore.core.port.AgentRunRecord
import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.core.port.EventBus
import dev.unityinflow.kore.dashboard.DashboardServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Spring integration regression for HI-02 (03-VERIFICATION.md).
 *
 * Pins the degraded-mode contract to the REAL `@Autowired` [DashboardServer]
 * bean. Any future refactor that re-introduces a null-coalescing wrapper
 * around the audit log will break
 * `dashboardServer.dataServiceForTest().hasStorage() shouldBe false` because
 * the wrapper would no longer read the live `dataService` field.
 *
 * Mirrors [KoreIntegrationTest] exactly: `spring.main.web-application-type=none`
 * and `kore.dashboard.enabled=false` so the Ktor CIO engine never binds a
 * port. This file is bean-introspection only — no ktor test host is added
 * to kore-spring's test classpath.
 */
@SpringBootTest(
    classes = [KoreIntegrationTestApp::class],
    properties = [
        "spring.main.web-application-type=none",
        "kore.dashboard.enabled=false",
    ],
)
class DashboardDegradedModeSpringTest
    @Autowired
    constructor(
        private val auditLog: AuditLog,
        private val dashboardServer: DashboardServer,
        private val eventBus: EventBus,
    ) {
        @Test
        fun `default Spring wiring binds InMemoryAuditLog with isPersistent false`() {
            auditLog.shouldBeInstanceOf<InMemoryAuditLog>()
            auditLog.isPersistent shouldBe false
        }

        @Test
        fun `dashboardServer dataServiceForTest hasStorage is false under default wiring`() {
            dashboardServer.dataServiceForTest().hasStorage() shouldBe false
        }

        @Test
        fun `dashboardServer dataServiceForTest hasStorage is true when persistent AuditLog injected`() {
            val freshServer =
                DashboardServer(
                    eventBus = eventBus,
                    auditLog = PersistentStubAuditLog(),
                    properties =
                        DashboardServer.DefaultDashboardProperties(
                            port = 0,
                            path = "/kore",
                            enabled = false,
                        ),
                )
            freshServer.dataServiceForTest().hasStorage() shouldBe true
        }
    }

/**
 * Test double for [AuditLog] that reports `isPersistent = true` to simulate
 * a kore-storage-backed adapter without introducing a real PostgreSQL
 * dependency on the kore-spring test classpath.
 */
private class PersistentStubAuditLog : AuditLog {
    override val isPersistent: Boolean = true

    override suspend fun recordAgentRun(
        agentId: String,
        task: AgentTask,
        result: AgentResult,
    ) = Unit

    override suspend fun recordLLMCall(
        agentId: String,
        backend: String,
        usage: TokenUsage,
    ) = Unit

    override suspend fun recordToolCall(
        agentId: String,
        call: ToolCall,
        result: ToolResult,
    ) = Unit

    override suspend fun queryRecentRuns(limit: Int): List<AgentRunRecord> = emptyList()

    override suspend fun queryCostSummary(): List<AgentCostRecord> = emptyList()
}
