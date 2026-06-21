package io.github.unityinflow.kore.core.internal

import io.github.unityinflow.kore.core.AgentResult
import io.github.unityinflow.kore.core.AgentTask
import io.github.unityinflow.kore.core.TokenUsage
import io.github.unityinflow.kore.core.ToolCall
import io.github.unityinflow.kore.core.ToolResult
import io.github.unityinflow.kore.core.port.AgentCostRecord
import io.github.unityinflow.kore.core.port.AgentRunRecord
import io.github.unityinflow.kore.core.port.AuditLog

/**
 * In-memory [AuditLog] used as the zero-config default and in tests.
 *
 * [recordAgentRun] appends a [RunRecord] capturing the run's lineage
 * ([RunRecord.parentRunId], D-09) and outcome ([RunRecord.resultType]) so
 * in-memory run-tree assertions are possible. The LLM/tool/query members remain
 * no-op stubs — real persistence lives in PostgresAuditLogAdapter (kore-storage).
 */
class InMemoryAuditLog : AuditLog {
    /** A single recorded agent run: which agent, its parent (lineage), and the result variant name (D-11/D-12). */
    data class RunRecord(
        val agentId: String,
        val parentRunId: String?,
        val resultType: String,
    )

    private val runs = java.util.concurrent.CopyOnWriteArrayList<RunRecord>()

    /** All recorded runs in insertion order (snapshot copy). */
    val recordedRuns: List<RunRecord> get() = runs.toList()

    override suspend fun recordAgentRun(
        agentId: String,
        task: AgentTask,
        result: AgentResult,
    ) {
        runs.add(RunRecord(agentId, task.parentRunId, result::class.simpleName ?: "unknown"))
    }

    override suspend fun recordLLMCall(
        agentId: String,
        backend: String,
        usage: TokenUsage,
    ) { /* no-op stub */ }

    override suspend fun recordToolCall(
        agentId: String,
        call: ToolCall,
        result: ToolResult,
    ) { /* no-op stub */ }

    // in-memory stub; real data in PostgresAuditLogAdapter
    override suspend fun queryRecentRuns(limit: Int): List<AgentRunRecord> = emptyList()

    // in-memory stub; real data in PostgresAuditLogAdapter
    override suspend fun queryCostSummary(): List<AgentCostRecord> = emptyList()
}
