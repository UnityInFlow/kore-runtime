package dev.unityinflow.kore.core.port

import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.core.ToolResult
import java.time.Instant

/**
 * Port interface for append-only audit logging.
 *
 * Phase 1: InMemoryAuditLog stub (in kore-core).
 * Phase 2: PostgresAuditLogAdapter (in kore-storage).
 * Phase 3: Read methods ([queryRecentRuns], [queryCostSummary]) added to
 *          power the kore-dashboard HTMX fragments (D-26).
 */
interface AuditLog {
    /**
     * `true` when this adapter persists audit data durably across JVM restarts
     * (e.g. PostgreSQL via kore-storage), `false` for in-memory stubs used in
     * default Spring wiring when kore-storage is absent.
     *
     * Consumed by `kore-dashboard` → `DashboardDataService.hasStorage()` (D-27):
     * the recent-runs and cost-summary fragments render a "History unavailable"
     * degraded notice when this is `false`.
     *
     * Default is `false` so existing in-memory implementations (InMemoryAuditLog,
     * DashboardServer.InertAuditLog sentinel) inherit the degraded-mode semantics
     * without any change. The PostgresAuditLogAdapter in kore-storage overrides
     * this to `true`.
     */
    val isPersistent: Boolean get() = false

    suspend fun recordAgentRun(
        agentId: String,
        task: AgentTask,
        result: AgentResult,
    )

    suspend fun recordLLMCall(
        agentId: String,
        backend: String,
        usage: TokenUsage,
    )

    suspend fun recordToolCall(
        agentId: String,
        call: ToolCall,
        result: ToolResult,
    )

    /**
     * Return the [limit] most recent agent runs (newest first), flattened into
     * one row per (agent run × LLM call) join row.
     *
     * Consumed by `kore-dashboard` → `/kore/fragments/recent-runs` (D-26).
     * In-memory implementations may return an empty list (degraded mode per D-27).
     */
    suspend fun queryRecentRuns(limit: Int = 20): List<AgentRunRecord>

    /**
     * Return a per-agent-name aggregation of run counts and token totals.
     *
     * Consumed by `kore-dashboard` → `/kore/fragments/cost-summary` (D-26).
     * In-memory implementations may return an empty list (degraded mode per D-27).
     */
    suspend fun queryCostSummary(): List<AgentCostRecord>
}

/**
 * Projection of a single agent run joined with its LLM-call token counts.
 *
 * No task content is included — [T-03-03] accepts the PII-in-task risk by
 * omitting task_input from the dashboard read path entirely.
 */
data class AgentRunRecord(
    val agentName: String,
    val resultType: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val durationMs: Long,
    val completedAt: Instant,
)

/**
 * Per-agent-name aggregation of token usage and run counts.
 */
data class AgentCostRecord(
    val agentName: String,
    val totalRuns: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
)
