package io.github.unityinflow.kore.budget

import io.github.unityinflow.budget.AgentBudget
import io.github.unityinflow.budget.BudgetException
import io.github.unityinflow.budget.TokenTracker
import io.github.unityinflow.kore.core.TokenUsage
import io.github.unityinflow.kore.core.port.BudgetEnforcer
import java.util.concurrent.ConcurrentHashMap

/**
 * Real [BudgetEnforcer] backed by budget-breaker's [TokenTracker] (Tool 05).
 *
 * One [TokenTracker] per agentId (= `AgentTask.id`, a per-run UUID) is held in a
 * [ConcurrentHashMap], giving concurrent-agent isolation for free (BUDG-07): exhausting one
 * agent's budget never affects another, and ids never collide.
 *
 * [TokenTracker.add] and [TokenTracker.isAboveHardLimit] are synchronous and non-throwing, so
 * the `suspend` port methods run without blocking — no dispatcher juggling, no `Thread.sleep` —
 * and `io.github.unityinflow.budget.BudgetHardLimitException` never escapes the port (BUDG-06).
 *
 * No eviction (D-05): tracker state lives for the process lifetime, bounded by the running-agent
 * count. This mirrors `InMemoryBudgetEnforcer`'s accepted T-03-04 tradeoff — the port has no
 * run-end hook and D-00 forbids adding one.
 *
 * @param defaultHardLimitTokens the single global hard token limit applied to every agent run.
 * @param model the budget-breaker model label (informational only; cost/soft limits are unused).
 */
class BudgetBreakerAdapter(
    private val defaultHardLimitTokens: Long,
    private val model: String = "kore-agent",
) : BudgetEnforcer {
    private val trackers = ConcurrentHashMap<String, TokenTracker>()

    private fun trackerFor(agentId: String): TokenTracker =
        trackers.computeIfAbsent(agentId) {
            TokenTracker(
                agentId = agentId,
                budget =
                    AgentBudget(
                        model = model,
                        hardLimitTokens = defaultHardLimitTokens,
                        // soft <= hard required by AgentBudget init validation; soft is never read (D-02/D-03).
                        softLimitTokens = defaultHardLimitTokens,
                    ),
            )
        }

    override suspend fun recordUsage(
        agentId: String,
        usage: TokenUsage,
    ) {
        try {
            trackerFor(agentId).add(usage.inputTokens.toLong(), usage.outputTokens.toLong())
        } catch (_: BudgetException) {
            // Belt-and-braces (BUDG-06): TokenTracker.add does not throw, but the port contract
            // is "no budget-breaker exception escapes". State is already recorded in the tracker;
            // checkBudget() reads isAboveHardLimit() on the next loop iteration.
        }
    }

    override suspend fun checkBudget(agentId: String): Boolean = !(trackers[agentId]?.isAboveHardLimit() ?: false)

    override suspend fun getUsage(agentId: String): TokenUsage =
        trackers[agentId]?.let {
            TokenUsage(it.promptTokens.toInt(), it.completionTokens.toInt())
        } ?: TokenUsage(0, 0)
}
