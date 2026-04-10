package dev.unityinflow.kore.core

/** The result of executing a tool call. [content] is a string representation. */
data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
)
