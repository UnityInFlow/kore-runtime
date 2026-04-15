package dev.unityinflow.kore.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// Sentinel used when deserializing ToolError/LLMError across a process boundary
// — the original stack trace does not survive the JSON wire format. Declared at
// file scope (not in a companion object) because a private companion with a
// default value referenced from a sealed-class data class triggers a Kotlin
// 2.3.0 IR lowering bug (SyntheticAccessorLowering cross-file assertion).
private val DESERIALIZED_CAUSE: Throwable = RuntimeException("cause unavailable (deserialized)")

/**
 * The result of a single agent execution run.
 *
 * The agent loop NEVER throws — all outcomes are modelled as sealed class variants.
 * The only exception that escapes is [kotlinx.coroutines.CancellationException].
 *
 * Serialization: `@Serializable` on every subclass supports polymorphic encoding
 * inside [AgentEvent.AgentCompleted]. `Throwable` fields are `@Transient` with a
 * default null because JVM exceptions are not serializable across process
 * boundaries — observers receive the tool/backend name and the error flag, which
 * is enough for metrics and dashboard rendering.
 */
@Serializable
sealed class AgentResult {
    /** The agent completed successfully and produced an output. */
    @Serializable
    @SerialName("Success")
    data class Success(
        val output: String,
        val tokenUsage: TokenUsage,
    ) : AgentResult()

    /** The agent was stopped because it exceeded its configured token budget. */
    @Serializable
    @SerialName("BudgetExceeded")
    data class BudgetExceeded(
        val spent: TokenUsage,
        val limit: Long,
    ) : AgentResult()

    /** A tool invocation failed and retries were exhausted. */
    @Serializable
    @SerialName("ToolError")
    data class ToolError(
        val toolName: String,
        @Transient val cause: Throwable = DESERIALIZED_CAUSE,
    ) : AgentResult()

    /** The LLM backend failed and all fallbacks were exhausted. */
    @Serializable
    @SerialName("LLMError")
    data class LLMError(
        val backend: String,
        @Transient val cause: Throwable = DESERIALIZED_CAUSE,
    ) : AgentResult()

    /** The agent was cancelled by the caller (structured concurrency cancellation). */
    @Serializable
    @SerialName("Cancelled")
    data class Cancelled(
        val reason: String,
    ) : AgentResult()
}
