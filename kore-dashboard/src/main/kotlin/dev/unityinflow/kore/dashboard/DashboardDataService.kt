package dev.unityinflow.kore.dashboard

import dev.unityinflow.kore.core.port.AgentCostRecord
import dev.unityinflow.kore.core.port.AgentRunRecord
import dev.unityinflow.kore.core.port.AuditLog

/**
 * Thin facade over the optional [AuditLog] read methods consumed by the
 * recent-runs and cost-summary HTMX fragments.
 *
 * The [auditLog] parameter is nullable AND filtered by [AuditLog.isPersistent]: when
 * `null` OR when the bound adapter reports `isPersistent=false` (e.g. the default
 * InMemoryAuditLog supplied by KoreAutoConfiguration), both queries effectively
 * return an empty list for rendering purposes and [hasStorage] returns `false`, so
 * the fragments render the "History unavailable — kore-storage not configured"
 * degraded-mode notice (D-27, UI-SPEC.md, 03-VERIFICATION HI-02).
 */
class DashboardDataService(
    private val auditLog: AuditLog?,
) {
    suspend fun getRecentRuns(limit: Int = 20): List<AgentRunRecord> = auditLog?.queryRecentRuns(limit) ?: emptyList()

    suspend fun getCostSummary(): List<AgentCostRecord> = auditLog?.queryCostSummary() ?: emptyList()

    /**
     * `true` when an [AuditLog] adapter is configured AND it persists data durably
     * (i.e. kore-storage's PostgresAuditLogAdapter is on the classpath). In-memory
     * stubs (InMemoryAuditLog, InertAuditLog) inherit `isPersistent=false` and so
     * report the degraded-mode state here — the HI-02 fix.
     */
    fun hasStorage(): Boolean = auditLog?.isPersistent == true
}
