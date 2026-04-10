package dev.unityinflow.kore.core

/** Tracks token consumption for a single LLM call or accumulated over an agent run. */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
) {
    val totalTokens: Int get() = inputTokens + outputTokens

    operator fun plus(other: TokenUsage): TokenUsage =
        TokenUsage(
            inputTokens = this.inputTokens + other.inputTokens,
            outputTokens = this.outputTokens + other.outputTokens,
        )
}
