package dev.unityinflow.kore.dashboard

import dev.unityinflow.kore.core.internal.InMemoryAuditLog
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
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test

/**
 * Routing regression for HI-02 (03-VERIFICATION.md).
 *
 * Pins the recent-runs and cost-summary fragments to the UI-SPEC degraded
 * copy when the [DashboardDataService] is constructed against the default
 * [InMemoryAuditLog] Spring wiring. The existing [DashboardRoutingTest]
 * uses `auditLog = null`, which never exercised the bug — the pre-fix
 * `hasStorage()` short-circuited on non-null and rendered empty tables.
 *
 * This file deliberately feeds a NON-null [InMemoryAuditLog] through the
 * real route layer so the fix (reading [dev.unityinflow.kore.core.port.AuditLog.isPersistent])
 * is pinned end-to-end.
 */
class DashboardDegradedModeRoutingTest {
    private fun emptyObserver(): EventBusDashboardObserver {
        val bus = InProcessEventBus()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return EventBusDashboardObserver(bus, scope)
    }

    @Test
    fun `recent-runs fragment with non-null InMemoryAuditLog still renders degraded notice`() =
        testApplication {
            val observer = emptyObserver()
            val service = DashboardDataService(InMemoryAuditLog())
            routing { configureDashboardRoutes(observer, service, DashboardConfig()) }

            val response = client.get("/kore/fragments/recent-runs")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "Recent Runs"
            body shouldContain "History unavailable — kore-storage not configured"
            body shouldContain "kore.storage.url"
            body shouldNotContain "<table"
        }

    @Test
    fun `cost-summary fragment with non-null InMemoryAuditLog still renders degraded notice`() =
        testApplication {
            val observer = emptyObserver()
            val service = DashboardDataService(InMemoryAuditLog())
            routing { configureDashboardRoutes(observer, service, DashboardConfig()) }

            val response = client.get("/kore/fragments/cost-summary")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "Token Cost Summary"
            body shouldContain "Cost history unavailable — kore-storage not configured"
            body shouldNotContain "<table"
        }
}
