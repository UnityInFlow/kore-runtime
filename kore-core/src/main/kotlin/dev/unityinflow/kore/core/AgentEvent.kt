package dev.unityinflow.kore.core

/** Events emitted by the agent loop to the [port.EventBus]. */
sealed class AgentEvent {
    data class AgentStarted(
        val agentId: String,
        val taskId: String,
    ) : AgentEvent()

    data class LLMCallStarted(
        val agentId: String,
        val backend: String,
    ) : AgentEvent()

    data class LLMCallCompleted(
        val agentId: String,
        val tokenUsage: TokenUsage,
    ) : AgentEvent()

    data class ToolCallStarted(
        val agentId: String,
        val toolName: String,
    ) : AgentEvent()

    data class ToolCallCompleted(
        val agentId: String,
        val toolName: String,
        val isError: Boolean,
    ) : AgentEvent()

    data class AgentCompleted(
        val agentId: String,
        val result: AgentResult,
    ) : AgentEvent()
}
