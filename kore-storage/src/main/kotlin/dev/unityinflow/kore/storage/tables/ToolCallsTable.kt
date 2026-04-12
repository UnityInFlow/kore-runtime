package dev.unityinflow.kore.storage.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object ToolCallsTable : UUIDTable("tool_calls") {
    val runId = reference("run_id", AgentRunsTable, onDelete = ReferenceOption.CASCADE)
    val llmCallId = reference("llm_call_id", LlmCallsTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val toolName = varchar("tool_name", 255)
    val mcpServer = varchar("mcp_server", 255).nullable()
    val durationMs = integer("duration_ms")
    val arguments = text("arguments") // JSON string
    val result = text("result") // JSON string
}
