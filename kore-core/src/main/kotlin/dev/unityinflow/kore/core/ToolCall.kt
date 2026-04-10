package dev.unityinflow.kore.core

/** A tool invocation requested by the LLM. [arguments] is a JSON object string. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
