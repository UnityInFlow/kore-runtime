package dev.unityinflow.kore.storage

import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.core.ToolResult
import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.storage.tables.AgentRunsTable
import dev.unityinflow.kore.storage.tables.LlmCallsTable
import dev.unityinflow.kore.storage.tables.ToolCallsTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.OffsetDateTime
import java.util.UUID

/**
 * PostgreSQL-backed implementation of the [AuditLog] port.
 *
 * Append-only: only INSERT operations. No UPDATE or DELETE (D-16).
 * Uses Exposed R2DBC [suspendTransaction] — NOT Spring @Transactional (D-18, Pitfall 7).
 * Schema created by Flyway migration V1__init_schema.sql before this adapter is used.
 *
 * NOTE (T-02-05): task.input may contain user PII. Callers must sanitize PII before
 * creating AgentTask if audit retention is enabled. A future kore-spring property
 * kore.storage.mask-task-content=true can truncate/hash this column.
 */
class PostgresAuditLogAdapter(
    private val database: R2dbcDatabase,
) : AuditLog {
    override suspend fun recordAgentRun(
        agentId: String,
        task: AgentTask,
        result: AgentResult,
    ) {
        suspendTransaction(database) {
            AgentRunsTable.insert { stmt ->
                stmt[id] = UUID.fromString(agentId)
                stmt[agentName] = task.metadata["agent_name"] ?: "unknown"
                stmt[AgentRunsTable.task] = task.input
                stmt[resultType] = result.typeName()
                stmt[startedAt] = OffsetDateTime.now()
                stmt[finishedAt] = OffsetDateTime.now()
                stmt[metadata] = "{}" // base metadata; enrichment via kore-spring in Phase 3
            }
        }
    }

    override suspend fun recordLLMCall(
        agentId: String,
        backend: String,
        usage: TokenUsage,
    ) {
        suspendTransaction(database) {
            LlmCallsTable.insert { stmt ->
                stmt[runId] = UUID.fromString(agentId)
                stmt[model] = backend
                stmt[tokensIn] = usage.inputTokens
                stmt[tokensOut] = usage.outputTokens
                stmt[durationMs] = 0 // duration tracked at observability layer (OTel span)
                stmt[metadata] = "{}"
            }
        }
    }

    override suspend fun recordToolCall(
        agentId: String,
        call: ToolCall,
        result: ToolResult,
    ) {
        suspendTransaction(database) {
            ToolCallsTable.insert { stmt ->
                stmt[runId] = UUID.fromString(agentId)
                stmt[llmCallId] = null // correlated in Phase 3 with span context linkage
                stmt[toolName] = call.name
                stmt[mcpServer] = null // MCP server tracking added in Phase 3 via context
                stmt[durationMs] = 0
                stmt[arguments] = call.arguments // already a JSON string per kore-core design
                stmt[ToolCallsTable.result] = result.toJsonString()
            }
        }
    }
}

/** Maps [AgentResult] to a stable string for the result_type column. */
private fun AgentResult.typeName(): String =
    when (this) {
        is AgentResult.Success -> "success"
        is AgentResult.BudgetExceeded -> "budget_exceeded"
        is AgentResult.ToolError -> "tool_error"
        is AgentResult.LLMError -> "llm_error"
        is AgentResult.Cancelled -> "cancelled"
    }

/** Serializes [ToolResult] to a JSON string without requiring kotlinx.serialization. */
private fun ToolResult.toJsonString(): String {
    val escapedContent = content.replace("\\", "\\\\").replace("\"", "\\\"")
    return """{"content":"$escapedContent","isError":$isError}"""
}
