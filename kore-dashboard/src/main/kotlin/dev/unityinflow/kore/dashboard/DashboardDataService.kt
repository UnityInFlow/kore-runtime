package dev.unityinflow.kore.dashboard

import dev.unityinflow.kore.core.port.AgentCostRecord
import dev.unityinflow.kore.core.port.AgentRunRecord
import dev.unityinflow.kore.core.port.AuditLog

/**
 * Thin facade over the optional [AuditLog] read methods consumed by the
 * recent-runs and cost-summary HTMX fragments.
 *
 * The [auditLog] parameter is nullable: when `null`, both queries return an
 * empty list and [hasStorage] returns `false`, so the fragments render the
 * "History unavailable — kore-storage not configured" degraded-mode notice
 * (D-27, UI-SPEC.md).
 */
class DashboardDataService(
    private val auditLog: AuditLog?,
) {
    suspend fun getRecentRuns(limit: Int = 20): List<AgentRunRecord> = auditLog?.queryRecentRuns(limit) ?: emptyList()

    suspend fun getCostSummary(): List<AgentCostRecord> = auditLog?.queryCostSummary() ?: emptyList()

    /** `true` when an [AuditLog] adapter is configured (i.e. kore-storage is on the classpath). */
    fun hasStorage(): Boolean = auditLog != null
}
