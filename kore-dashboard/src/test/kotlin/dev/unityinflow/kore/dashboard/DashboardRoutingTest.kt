package dev.unityinflow.kore.dashboard

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.internal.InProcessEventBus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardRoutingTest {
    private fun emptyObserver(): EventBusDashboardObserver {
        // No collecting — snapshot stays empty for the empty-state tests.
        val bus = InProcessEventBus()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return EventBusDashboardObserver(bus, scope)
    }

    @Test
    fun `GET kore returns 200 with three fragment containers wired to HTMX endpoints`() =
        testApplication {
            val observer = emptyObserver()
            val service = DashboardDataService(auditLog = null)
            routing {
                configureDashboardRoutes(observer, service, DashboardConfig())
            }

            val response = client.get("/kore")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()

            // Page shell sanity
            body shouldContain "kore dashboard"
            body shouldContain "https://unpkg.com/htmx.org"
            body shouldContain ".kore-table"
            body shouldContain ".kore-badge"
            body shouldContain ".kore-pulse"
            body shouldContain "@keyframes kore-pulse"

            // Three HTMX fragment containers
            body shouldContain """hx-get="/kore/fragments/active-agents""""
            body shouldContain """hx-get="/kore/fragments/recent-runs""""
            body shouldContain """hx-get="/kore/fragments/cost-summary""""
            body shouldContain """hx-trigger="every 2s""""
            body shouldContain """hx-trigger="every 5s""""
            body shouldContain """hx-trigger="every 10s""""
            body shouldContain """hx-swap="innerHTML""""
            body shouldContain """aria-live="assertive""""
            body shouldContain """aria-live="polite""""

            // Footer attribution
            body shouldContain "kore · UnityInFlow"
        }

    @Test
    fun `GET active-agents fragment with empty observer renders the No agents running empty state`() =
        testApplication {
            val observer = emptyObserver()
            val service = DashboardDataService(auditLog = null)
            routing {
                configureDashboardRoutes(observer, service, DashboardConfig())
            }

            val response = client.get("/kore/fragments/active-agents")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "Active Agents"
            body shouldContain "No agents running"
            body shouldContain "Agents appear here as soon as they start"
            // Empty state should not render a data table
            body shouldNotContain "<table"
        }

    @Test
    fun `GET active-agents fragment with one running agent renders the table with status badge`() =
        testApplication {
            val bus = InProcessEventBus()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val observer = EventBusDashboardObserver(bus, scope)
            observer.startCollecting()
            // SharedFlow has no replay buffer — wait for the collector to register
            // BEFORE emitting, otherwise the event is dropped on the floor.
            // Emit in a retry loop and check the snapshot until the agent appears.
            repeat(50) {
                bus.emit(AgentEvent.AgentStarted(agentId = "agent-alpha", taskId = "t1"))
                if (observer.snapshot().containsKey("agent-alpha")) return@repeat
                delay(20)
            }
            check(observer.snapshot().containsKey("agent-alpha")) { "observer never received AgentStarted" }

            val service = DashboardDataService(auditLog = null)
            routing {
                configureDashboardRoutes(observer, service, DashboardConfig())
            }

            val response = client.get("/kore/fragments/active-agents")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "agent-alpha"
            body shouldContain "kore-badge--running"
            body shouldContain "RUNNING"
            body shouldContain """aria-label="Status: Running""""
            body shouldContain "kore-pulse"

            scope.cancel()
        }

    @Test
    fun `GET recent-runs fragment with null AuditLog renders the degraded notice`() =
        testApplication {
            val observer = emptyObserver()
            val service = DashboardDataService(auditLog = null)
            routing {
                configureDashboardRoutes(observer, service, DashboardConfig())
            }

            val response = client.get("/kore/fragments/recent-runs")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "Recent Runs"
            body shouldContain "History unavailable — kore-storage not configured"
            body shouldContain "kore.storage.url"
            body shouldNotContain "<table"
        }

    @Test
    fun `GET cost-summary fragment with null AuditLog renders the degraded notice`() =
        testApplication {
            val observer = emptyObserver()
            val service = DashboardDataService(auditLog = null)
            routing {
                configureDashboardRoutes(observer, service, DashboardConfig())
            }

            val response = client.get("/kore/fragments/cost-summary")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "Token Cost Summary"
            body shouldContain "Cost history unavailable — kore-storage not configured"
            body shouldNotContain "<table"
        }

    @Test
    fun `result badges render correct CSS variants and human-readable labels`() =
        testApplication {
            val observer = emptyObserver()
            // Service returning seeded recent runs covering all five result types.
            val service =
                DashboardDataService(
                    auditLog =
                        SeededAuditLog(
                            runs =
                                listOf(
                                    fakeRun("a", "success"),
                                    fakeRun("b", "budget_exceeded"),
                                    fakeRun("c", "tool_error"),
                                    fakeRun("d", "llm_error"),
                                    fakeRun("e", "cancelled"),
                                ),
                            costs = emptyList(),
                        ),
                )

            routing {
                configureDashboardRoutes(observer, service, DashboardConfig())
            }
            val body = client.get("/kore/fragments/recent-runs").bodyAsText()

            body shouldContain "kore-badge--success"
            body shouldContain "SUCCESS"
            body shouldContain "kore-badge--warning"
            body shouldContain "BUDGET EXCEEDED"
            body shouldContain "kore-badge--error"
            body shouldContain "TOOL ERROR"
            body shouldContain "LLM ERROR"
            body shouldContain "kore-badge--neutral"
            body shouldContain "CANCELLED"
        }

    @Test
    fun `cost-summary fragment renders per-agent rows and a TOTAL footer row`() =
        testApplication {
            val observer = emptyObserver()
            val service =
                DashboardDataService(
                    auditLog =
                        SeededAuditLog(
                            runs = emptyList(),
                            costs =
                                listOf(
                                    dev.unityinflow.kore.core.port
                                        .AgentCostRecord("alpha", totalRuns = 3, totalInputTokens = 100, totalOutputTokens = 50),
                                    dev.unityinflow.kore.core.port
                                        .AgentCostRecord("beta", totalRuns = 2, totalInputTokens = 40, totalOutputTokens = 10),
                                ),
                        ),
                )

            routing {
                configureDashboardRoutes(observer, service, DashboardConfig())
            }
            val body = client.get("/kore/fragments/cost-summary").bodyAsText()

            body shouldContain "alpha"
            body shouldContain "beta"
            body shouldContain "TOTAL"
            // Grand totals: 3+2 runs = 5, 100+40 input = 140, 50+10 output = 60, total = 200
            body shouldContain ">5<"
            body shouldContain ">140<"
            body shouldContain ">60<"
            body shouldContain ">200<"
        }
}

/** Test double for [dev.unityinflow.kore.core.port.AuditLog] returning seeded reads. */
private class SeededAuditLog(
    private val runs: List<dev.unityinflow.kore.core.port.AgentRunRecord>,
    private val costs: List<dev.unityinflow.kore.core.port.AgentCostRecord>,
) : dev.unityinflow.kore.core.port.AuditLog {
    override suspend fun recordAgentRun(
        agentId: String,
        task: dev.unityinflow.kore.core.AgentTask,
        result: dev.unityinflow.kore.core.AgentResult,
    ) = Unit

    override suspend fun recordLLMCall(
        agentId: String,
        backend: String,
        usage: dev.unityinflow.kore.core.TokenUsage,
    ) = Unit

    override suspend fun recordToolCall(
        agentId: String,
        call: dev.unityinflow.kore.core.ToolCall,
        result: dev.unityinflow.kore.core.ToolResult,
    ) = Unit

    override suspend fun queryRecentRuns(limit: Int) = runs.take(limit)

    override suspend fun queryCostSummary() = costs
}

private fun fakeRun(
    name: String,
    resultType: String,
): dev.unityinflow.kore.core.port.AgentRunRecord =
    dev.unityinflow.kore.core.port.AgentRunRecord(
        agentName = name,
        resultType = resultType,
        inputTokens = 10,
        outputTokens = 5,
        durationMs = 123L,
        completedAt = java.time.Instant.parse("2026-04-13T20:00:00Z"),
    )
