package dev.unityinflow.kore.core.internal

import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.core.ToolResult
import dev.unityinflow.kore.core.port.AgentCostRecord
import dev.unityinflow.kore.core.port.AgentRunRecord
import dev.unityinflow.kore.core.port.AuditLog

/**
 * No-op in-memory [AuditLog] stub.
 * Phase 2: replaced by PostgresAuditLogAdapter in kore-storage.
 */
class InMemoryAuditLog : AuditLog {
    override suspend fun recordAgentRun(
        agentId: String,
        task: AgentTask,
        result: AgentResult,
    ) { /* no-op stub */ }

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
