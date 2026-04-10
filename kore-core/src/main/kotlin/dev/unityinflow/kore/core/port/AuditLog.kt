package dev.unityinflow.kore.core.port

import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.core.ToolResult

/**
 * Port interface for append-only audit logging.
 *
 * Phase 1: InMemoryAuditLog stub (in kore-core).
 * Phase 2: PostgresAuditLogAdapter (in kore-storage).
 */
interface AuditLog {
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
}
