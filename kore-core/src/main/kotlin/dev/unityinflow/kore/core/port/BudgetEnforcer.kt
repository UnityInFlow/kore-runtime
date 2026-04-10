package dev.unityinflow.kore.core.port

import dev.unityinflow.kore.core.TokenUsage

/**
 * Port interface for token budget enforcement.
 *
 * Phase 1 implementation: [InMemoryBudgetEnforcer] stub.
 * Future: budget-breaker adapter (Tool 05) — v2 requirement.
 */
interface BudgetEnforcer {
    /**
     * Records token usage for the given agent run.
     * Called after each LLM call in the agent loop.
     */
    suspend fun recordUsage(
        agentId: String,
        usage: TokenUsage,
    )

    /**
     * Checks whether the agent has exceeded its configured token budget.
     * Returns true if the agent SHOULD continue, false if budget is exceeded.
     */
    suspend fun checkBudget(agentId: String): Boolean

    /** Returns accumulated token usage for an agent run. */
    suspend fun getUsage(agentId: String): TokenUsage
}
