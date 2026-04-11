package dev.unityinflow.kore.mcp

import dev.unityinflow.kore.mcp.client.McpClientAdapter
import dev.unityinflow.kore.mcp.client.McpConnectionManager
import dev.unityinflow.kore.mcp.client.McpServerConfig
import dev.unityinflow.kore.mcp.client.McpTransportType

/**
 * DSL factory function for stdio-based MCP servers.
 *
 * Returns an [McpClientAdapter] that connects lazily — connection happens on the first
 * tool call, not at construction time (per D-10).
 *
 * Usage in agent DSL:
 * ```kotlin
 * agent("analyst") {
 *     model = claude(apiKey)
 *     tools = mcp("github", "npx", "-y", "@github/github-mcp-server")
 * }
 * ```
 *
 * @param name Logical name for this server (used in logging and error messages).
 * @param command The subprocess command. E.g., `"npx", "-y", "@github/github-mcp-server"`.
 */
fun mcp(
    name: String,
    vararg command: String,
): McpClientAdapter {
    val config =
        McpServerConfig(
            name = name,
            transportType = McpTransportType.STDIO,
            command = command.toList(),
        )
    return McpClientAdapter(McpConnectionManager(config))
}

/**
 * DSL factory function for SSE (HTTP)-based MCP servers.
 *
 * Returns an [McpClientAdapter] that connects lazily on first tool call (per D-10).
 * SSE transport implements Last-Event-ID resumability — disconnect does not cancel
 * in-flight operations (per Pitfall 6, T-06-03).
 *
 * Usage:
 * ```kotlin
 * agent("search-agent") {
 *     model = claude(apiKey)
 *     tools = mcpSse("search-server", url = "https://mcp.example.com/sse")
 * }
 * ```
 *
 * @param name Logical name for this server.
 * @param url The SSE endpoint URL of the MCP server.
 */
fun mcpSse(
    name: String,
    url: String,
): McpClientAdapter {
    val config =
        McpServerConfig(
            name = name,
            transportType = McpTransportType.SSE,
            sseUrl = url,
        )
    return McpClientAdapter(McpConnectionManager(config))
}
