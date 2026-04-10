package dev.unityinflow.kore.test

import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.core.ToolDefinition
import dev.unityinflow.kore.core.ToolResult
import dev.unityinflow.kore.core.port.ToolProvider

/**
 * Scripted [ToolProvider] for use in unit tests.
 *
 * Usage:
 * ```kotlin
 * val mock = MockToolProvider()
 *     .withTool("search", description = "Search the web", schema = "{}")
 *     .returnsFor("search") { call -> ToolResult(call.id, "search result") }
 * ```
 */
class MockToolProvider : ToolProvider {
    private val tools = mutableListOf<ToolDefinition>()
    private val handlers = mutableMapOf<String, suspend (ToolCall) -> ToolResult>()

    /** Register a tool definition. */
    fun withTool(
        name: String,
        description: String,
        schema: String = "{}",
    ): MockToolProvider {
        tools.add(ToolDefinition(name = name, description = description, inputSchema = schema))
        return this
    }

    /** Register a scripted handler for a tool. */
    fun returnsFor(
        toolName: String,
        handler: suspend (ToolCall) -> ToolResult,
    ): MockToolProvider {
        handlers[toolName] = handler
        return this
    }

    /** Register a fixed response for a tool (convenience overload). */
    fun returnsFor(
        toolName: String,
        content: String,
    ): MockToolProvider = returnsFor(toolName) { call -> ToolResult(toolCallId = call.id, content = content) }

    override suspend fun listTools(): List<ToolDefinition> = tools.toList()

    override suspend fun callTool(call: ToolCall): ToolResult {
        val handler =
            handlers[call.name]
                ?: error(
                    "MockToolProvider: no handler registered for tool '${call.name}'. " +
                        "Available: ${handlers.keys}",
                )
        return handler(call)
    }
}
