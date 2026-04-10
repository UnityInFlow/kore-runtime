package dev.unityinflow.kore.core

/** Configuration for an LLM call. Passed with each [port.LLMBackend.call] invocation. */
data class LLMConfig(
    val model: String,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.0,
    val maxHistoryMessages: Int = 50,
)
