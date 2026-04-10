package dev.unityinflow.kore.core

/**
 * The mutable state of an agent during a single run.
 * Owned and accumulated by the agent loop — never exposed to callers directly.
 */
data class AgentContext(
    val agentId: String,
    val task: AgentTask,
    val history: List<ConversationMessage> = emptyList(),
    val accumulatedTokenUsage: TokenUsage = TokenUsage(0, 0),
)
