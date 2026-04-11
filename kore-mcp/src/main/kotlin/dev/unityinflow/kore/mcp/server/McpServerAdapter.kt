package dev.unityinflow.kore.mcp.server

import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentRunner
import dev.unityinflow.kore.core.AgentTask
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.channels.Channel
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.util.UUID

/**
 * Exposes kore [AgentRunner] instances as MCP tools.
 *
 * External MCP clients can invoke kore agents via standard MCP tools/call requests.
 * Each registered agent appears as a named tool in the MCP server's tools/list.
 *
 * Per D-13 and D-14: uses MCP Kotlin SDK Server for protocol handling.
 * Transport: stdio via [StdioServerTransport] (see [startStdio]).
 *
 * CRITICAL: When running in stdio mode, ensure logging is routed to stderr
 * using logback-mcp.xml to prevent stdout contamination (Pitfall 4, T-06-01).
 * Activate with: -Dlogback.configurationFile=logback-mcp.xml
 *
 * Input from external MCP clients is passed directly to [AgentRunner.run] as [AgentTask.input]
 * and is NOT executed as code — AgentRunner enforces budget limits (T-06-04).
 */
class McpServerAdapter(
    private val serverName: String = "kore",
    private val serverVersion: String = "0.0.1",
) {
    // Using LinkedHashMap to preserve registration order for deterministic tool listing.
    // val not var — map is mutated in place; the reference itself never changes.
    private val agents: MutableMap<String, AgentRunner> = LinkedHashMap()

    /**
     * Register an [AgentRunner] as an MCP tool with [toolName] as the tool identifier.
     *
     * The tool description defaults to "kore agent: <toolName>" if not provided.
     * Called before [startStdio] — registration must be complete before transport starts.
     *
     * @param toolName MCP tool name visible to external clients.
     * @param runner The [AgentRunner] that handles incoming tool calls.
     * @param description Human-readable description for the MCP tools/list response.
     */
    fun registerAgent(
        toolName: String,
        runner: AgentRunner,
        description: String = "kore agent: $toolName",
    ) {
        agents[toolName] = runner
    }

    /**
     * Returns the names of all registered agents, in registration order.
     * Used for testing and introspection.
     */
    fun registeredAgentNames(): List<String> = agents.keys.toList()

    /**
     * Invoke a registered agent by [toolName] with the given [inputJson] arguments.
     *
     * This is extracted from the MCP server handler so it can be tested independently
     * without a real transport. The MCP tool handler calls this method.
     *
     * @param toolName The MCP tool name (must match a registered agent).
     * @param inputJson JSON string from the MCP tools/call request arguments.
     * @return [CallToolResult] representing the [AgentResult].
     */
    suspend fun invokeAgent(
        toolName: String,
        inputJson: String,
    ): CallToolResult {
        val runner =
            agents[toolName]
                ?: return CallToolResult(
                    content = listOf(TextContent(text = "No agent registered for tool: $toolName")),
                    isError = true,
                )
        val task =
            AgentTask(
                id = UUID.randomUUID().toString(),
                input = inputJson,
            )
        val result = runner.run(task).await()
        return result.toMcpToolResult()
    }

    /**
     * Start the MCP server on stdio transport.
     *
     * This function suspends until the connection is closed by the client.
     * Registers all previously [registerAgent] agents as MCP tools before accepting connections.
     *
     * IMPORTANT: Redirect all logging to stderr before calling this.
     * Use -Dlogback.configurationFile=logback-mcp.xml (see logback-mcp.xml in resources).
     * Any byte written to stdout that is not MCP JSON-RPC corrupts the transport (Pitfall 4).
     */
    suspend fun startStdio() {
        val server = buildServer()
        val transport =
            StdioServerTransport(
                inputStream = System.`in`.asSource().buffered(),
                outputStream = System.`out`.asSink().buffered(),
            )
        // createSession starts the protocol session and returns when transport closes.
        val closeChannel = Channel<Unit>(1)
        val session = server.createSession(transport)
        session.onClose {
            closeChannel.trySend(Unit)
        }
        // Suspend until the client disconnects or closes the transport.
        closeChannel.receive()
    }

    private fun buildServer(): Server {
        val server =
            Server(
                serverInfo = Implementation(name = serverName, version = serverVersion),
                options =
                    ServerOptions(
                        capabilities =
                            ServerCapabilities(
                                tools = ServerCapabilities.Tools(listChanged = false),
                            ),
                    ),
            )

        // Register each agent as an MCP tool (T-06-04: input is not executed as code)
        agents.forEach { (toolName, _) ->
            val tool =
                Tool(
                    name = toolName,
                    inputSchema = ToolSchema(),
                    description = "kore agent: $toolName",
                )
            server.addTool(tool) { request ->
                val inputJson = request.arguments?.toString() ?: "{}"
                invokeAgent(toolName, inputJson)
            }
        }

        return server
    }

    private fun AgentResult.toMcpToolResult(): CallToolResult =
        when (this) {
            is AgentResult.Success ->
                CallToolResult(
                    content = listOf(TextContent(text = output)),
                    isError = false,
                )

            is AgentResult.BudgetExceeded ->
                CallToolResult(
                    content = listOf(TextContent(text = "Budget exceeded: ${spent.totalTokens} tokens used, limit $limit")),
                    isError = true,
                )

            is AgentResult.ToolError ->
                CallToolResult(
                    content = listOf(TextContent(text = "Tool error in '$toolName': ${cause.message}")),
                    isError = true,
                )

            is AgentResult.LLMError ->
                CallToolResult(
                    content = listOf(TextContent(text = "LLM error in '$backend': ${cause.message}")),
                    isError = true,
                )

            is AgentResult.Cancelled ->
                CallToolResult(
                    content = listOf(TextContent(text = "Agent cancelled: $reason")),
                    isError = true,
                )
        }
}
