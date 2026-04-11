package dev.unityinflow.kore.mcp.client

import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.core.ToolDefinition
import dev.unityinflow.kore.core.ToolResult
import dev.unityinflow.kore.core.port.ToolProvider
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Implements [ToolProvider] using an MCP server connection.
 *
 * Handles tools, resources, and prompt templates via the MCP protocol.
 * Connection is lazy — established on first [listTools] or [callTool] call (per D-10).
 *
 * On [callTool] failure the exception is caught and returned as [ToolResult.isError] = true
 * rather than propagating — satisfies T-06-02 (malformed MCP server response).
 *
 * @param connectionManager manages the lifecycle of the underlying [Client].
 */
class McpClientAdapter(
    private val connectionManager: McpConnectionManager,
) : ToolProvider {
    /** List all tools from the MCP server. Triggers lazy connection on first call. */
    override suspend fun listTools(): List<ToolDefinition> {
        val client = connectionManager.getClient()
        return client.listTools().tools.map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description ?: "",
                inputSchema = tool.inputSchema.toString(),
            )
        }
    }

    /**
     * Call a tool on the MCP server.
     *
     * Returns [ToolResult.isError] = true (and never throws) if the SDK raises an
     * exception — satisfies T-06-02: malformed or error response from external MCP server.
     */
    override suspend fun callTool(call: ToolCall): ToolResult {
        val client = connectionManager.getClient()
        return try {
            val request =
                CallToolRequest(
                    params =
                        CallToolRequestParams(
                            name = call.name,
                            arguments = parseArguments(call.arguments),
                        ),
                )
            val result = client.callTool(request)
            val content = result.content.joinToString("\n") { it.toString() }
            ToolResult(
                toolCallId = call.id,
                content = content,
                isError = result.isError == true,
            )
        } catch (e: Exception) {
            ToolResult(
                toolCallId = call.id,
                content = "MCP tool call failed: ${e.message}",
                isError = true,
            )
        }
    }

    /**
     * Read a resource from the MCP server (MCP-03).
     * Resources include files, database records, or any data the server exposes by URI.
     */
    suspend fun readResource(uri: String): String {
        val client = connectionManager.getClient()
        val result =
            client.readResource(
                ReadResourceRequest(
                    params = ReadResourceRequestParams(uri = uri),
                ),
            )
        return result.contents.joinToString("\n") { it.toString() }
    }

    /**
     * Get a prompt template from the MCP server (MCP-04).
     * Returns the prompt messages joined as a single string.
     */
    suspend fun getPrompt(
        name: String,
        arguments: Map<String, String> = emptyMap(),
    ): String {
        val client = connectionManager.getClient()
        val result =
            client.getPrompt(
                GetPromptRequest(
                    params =
                        GetPromptRequestParams(
                            name = name,
                            arguments = arguments,
                        ),
                ),
            )
        return result.messages.joinToString("\n") { it.content.toString() }
    }

    private fun parseArguments(json: String): JsonObject =
        try {
            Json.decodeFromString<JsonObject>(json)
        } catch (e: Exception) {
            buildJsonObject { put("raw", JsonPrimitive(json)) }
        }
}
