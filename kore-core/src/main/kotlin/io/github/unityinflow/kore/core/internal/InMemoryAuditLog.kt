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
