package dev.unityinflow.kore.core

/**
 * The result of a single agent execution run.
 *
 * The agent loop NEVER throws — all outcomes are modelled as sealed class variants.
 * The only exception that escapes is [kotlinx.coroutines.CancellationException].
 */
sealed class AgentResult {
    /** The agent completed successfully and produced an output. */
    data class Success(
        val output: String,
        val tokenUsage: TokenUsage,
    ) : AgentResult()

    /** The agent was stopped because it exceeded its configured token budget. */
    data class BudgetExceeded(
        val spent: TokenUsage,
        val limit: Long,
    ) : AgentResult()

    /** A tool invocation failed and retries were exhausted. */
    data class ToolError(
        val toolName: String,
        val cause: Throwable,
    ) : AgentResult()

    /** The LLM backend failed and all fallbacks were exhausted. */
    data class LLMError(
        val backend: String,
        val cause: Throwable,
    ) : AgentResult()

    /** The agent was cancelled by the caller (structured concurrency cancellation). */
    data class Cancelled(
        val reason: String,
    ) : AgentResult()
}
