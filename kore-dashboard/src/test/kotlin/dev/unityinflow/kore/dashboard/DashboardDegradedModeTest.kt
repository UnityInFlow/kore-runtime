package dev.unityinflow.kore.dashboard

import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.core.ToolResult
import dev.unityinflow.kore.core.internal.InMemoryAuditLog
import dev.unityinflow.kore.core.port.AgentCostRecord
import dev.unityinflow.kore.core.port.AgentRunRecord
import dev.unityinflow.kore.core.port.AuditLog
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit regression for HI-02 (03-VERIFICATION.md).
 *
 * Pins [DashboardDataService.hasStorage] behaviour at the boundary between
 * the service and the [AuditLog] port. The pre-fix implementation returned
 * `true` for any non-null audit log, so the default Spring wiring (which
 * supplies [InMemoryAuditLog]) produced empty tables instead of the
 * UI-SPEC degraded notice. The fix reads [AuditLog.isPersistent] so only
 * adapters that actually persist rows (e.g. PostgresAuditLogAdapter) report
 * storage as configured.
 */
class DashboardDegradedModeTest {
    @Test
    fun `hasStorage returns false for InMemoryAuditLog (default Spring wiring)`() {
        val service = DashboardDataService(InMemoryAuditLog())
        service.hasStorage() shouldBe false
    }

    @Test
    fun `hasStorage returns true for AuditLog impl with isPersistent true`() {
        val service = DashboardDataService(PersistentStubAuditLog())
        service.hasStorage() shouldBe true
    }

    @Test
    fun `hasStorage returns false for null AuditLog (explicit degraded construction)`() {
        val service = DashboardDataService(null)
        service.hasStorage() shouldBe false
    }
}

/**
 * Test double for [AuditLog] that reports `isPersistent = true` to simulate
 * a kore-storage-backed adapter (PostgresAuditLogAdapter) without pulling in
 * a real database dependency.
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
