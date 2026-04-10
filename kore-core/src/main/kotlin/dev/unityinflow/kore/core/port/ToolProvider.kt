package dev.unityinflow.kore.core.port

import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.core.ToolDefinition
import dev.unityinflow.kore.core.ToolResult

/**
 * Port interface for tool execution. Implementations include:
 * - McpClientAdapter (kore-mcp) — calls external MCP servers
 * - AgentTool (kore-core) — spawns child agents
 */
interface ToolProvider {
    /** Returns all tools available from this provider. */
    suspend fun listTools(): List<ToolDefinition>

    /** Executes a tool call and returns the result. */
    suspend fun callTool(call: ToolCall): ToolResult
}
