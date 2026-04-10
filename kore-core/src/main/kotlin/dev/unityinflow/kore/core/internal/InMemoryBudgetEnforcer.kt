package dev.unityinflow.kore.core.internal

import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.core.port.BudgetEnforcer
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory stub [BudgetEnforcer]. Tracks tokens per agent in a [ConcurrentHashMap].
 * Real implementation replaced by budget-breaker adapter (Tool 05) in v2.
 *
 * [defaultLimitPerAgent] is the maximum total tokens allowed per agent run.
 * Use [Long.MAX_VALUE] to disable budget enforcement.
 *
 * Thread-safe: uses [ConcurrentHashMap.merge] for atomic accumulation.
 * T-03-04 accepted: single JVM, bounded by running agent count.
 */
class InMemoryBudgetEnforcer(
    private val defaultLimitPerAgent: Long = Long.MAX_VALUE,
) : BudgetEnforcer {
    private val usageMap = ConcurrentHashMap<String, TokenUsage>()

    override suspend fun recordUsage(
        agentId: String,
        usage: TokenUsage,
    ) {
        usageMap.merge(agentId, usage) { existing, new -> existing + new }
    }

    override suspend fun checkBudget(agentId: String): Boolean {
        val current = usageMap[agentId]?.totalTokens?.toLong() ?: 0L
        return current < defaultLimitPerAgent
    }

    override suspend fun getUsage(agentId: String): TokenUsage = usageMap[agentId] ?: TokenUsage(0, 0)
}
