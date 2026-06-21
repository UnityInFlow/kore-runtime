package io.github.unityinflow.kore.budget

import io.github.unityinflow.kore.core.TokenUsage
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * BUDG-06 (no-escape / hard-stop) + BUDG-07 (isolation / concurrency) validation for
 * [BudgetBreakerAdapter]. Drives the adapter directly (no AgentLoop needed) — the unchanged
 * AgentLoop already returns AgentResult.BudgetExceeded on checkBudget()==false; here we assert
 * the adapter half: no budget-breaker exception escapes the port and per-agentId budgets are
 * fully isolated even under concurrent recordUsage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetBreakerAdapterTest {
    // --- BUDG-06: no-escape, hard-stop ---

    @Test
    fun `recordUsage past the hard limit does not throw and flips checkBudget to false`() =
        runTest {
            val adapter = BudgetBreakerAdapter(defaultHardLimitTokens = 10L)

            shouldNotThrowAny {
                adapter.recordUsage("a", TokenUsage(inputTokens = 100, outputTokens = 100))
            }

            adapter.checkBudget("a") shouldBe false
            adapter.getUsage("a").totalTokens shouldBe 200
        }

    @Test
    fun `a fresh agent under the limit and an unseen agent both continue`() =
        runTest {
            val adapter = BudgetBreakerAdapter(defaultHardLimitTokens = 10L)

            adapter.recordUsage("fresh", TokenUsage(inputTokens = 2, outputTokens = 3))
            adapter.checkBudget("fresh") shouldBe true

            // Never recorded: no tracker yet -> isAboveHardLimit fallback false -> checkBudget true.
            adapter.checkBudget("never-seen") shouldBe true
            adapter.getUsage("never-seen") shouldBe TokenUsage(0, 0)
        }

    // --- BUDG-07: isolation ---

    @Test
    fun `distinct agentIds have isolated budgets with no cross-contamination`() =
        runTest {
            val adapter = BudgetBreakerAdapter(defaultHardLimitTokens = 10L)

            // Drive agent-A over the limit; leave agent-B under it.
            adapter.recordUsage("agent-A", TokenUsage(inputTokens = 50, outputTokens = 50))
            adapter.recordUsage("agent-B", TokenUsage(inputTokens = 2, outputTokens = 1))

            adapter.checkBudget("agent-A") shouldBe false
            adapter.checkBudget("agent-B") shouldBe true

            // B reflects ONLY B's tokens.
            adapter.getUsage("agent-B") shouldBe TokenUsage(2, 1)
        }

    // --- BUDG-07: concurrency stress ---

    @Test
    fun `concurrent recordUsage across distinct agentIds never cross-talks and never throws`() =
        runTest {
            val adapter = BudgetBreakerAdapter(defaultHardLimitTokens = Long.MAX_VALUE)
            val agentCount = 16
            val callsPerAgent = 100

            shouldNotThrowAny {
                (1..agentCount)
                    .map { id ->
                        async {
                            repeat(callsPerAgent) {
                                adapter.recordUsage(
                                    "agent-$id",
                                    TokenUsage(inputTokens = 1, outputTokens = 1),
                                )
                            }
                        }
                    }.awaitAll()
            }

            // Each id's total equals exactly its own contributions — no cross-id leakage,
            // and no IllegalArgumentException (proving the adapter never calls withBudget).
            (1..agentCount).forEach { id ->
                adapter.getUsage("agent-$id").totalTokens shouldBe callsPerAgent * 2
            }
        }
}
