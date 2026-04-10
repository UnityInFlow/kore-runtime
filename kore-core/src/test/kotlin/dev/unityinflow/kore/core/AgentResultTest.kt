package dev.unityinflow.kore.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AgentResultTest {
    @Test
    fun `Success carries output and token usage`() {
        val usage = TokenUsage(inputTokens = 10, outputTokens = 5)
        val result = AgentResult.Success(output = "done", tokenUsage = usage)
        result.output shouldBe "done"
        result.tokenUsage.totalTokens shouldBe 15
    }

    @Test
    fun `BudgetExceeded carries spent usage and limit`() {
        val spent = TokenUsage(inputTokens = 1000, outputTokens = 200)
        val result = AgentResult.BudgetExceeded(spent = spent, limit = 500L)
        result.spent.totalTokens shouldBe 1200
        result.limit shouldBe 500L
    }

    @Test
    fun `AgentResult when expression is exhaustive`() {
        val result: AgentResult = AgentResult.Cancelled(reason = "test")
        val label =
            when (result) {
                is AgentResult.Success -> "success"
                is AgentResult.BudgetExceeded -> "budget"
                is AgentResult.ToolError -> "tool"
                is AgentResult.LLMError -> "llm"
                is AgentResult.Cancelled -> "cancelled"
            }
        label shouldBe "cancelled"
    }

    @Test
    fun `TokenUsage addition accumulates correctly`() {
        val a = TokenUsage(inputTokens = 100, outputTokens = 50)
        val b = TokenUsage(inputTokens = 200, outputTokens = 30)
        val total = a + b
        total.inputTokens shouldBe 300
        total.outputTokens shouldBe 80
        total.totalTokens shouldBe 380
    }
}
