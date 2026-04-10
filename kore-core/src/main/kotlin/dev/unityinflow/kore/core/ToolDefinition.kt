package dev.unityinflow.kore.core

/**
 * A tool that can be invoked by the LLM during an agent run.
 * [inputSchema] is a JSON Schema string describing the tool's parameters.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: String,
)
