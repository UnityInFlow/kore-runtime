package dev.unityinflow.kore.mcp

import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentRunner
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.mcp.server.McpServerAdapter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class McpServerAdapterTest {
    @Test
    fun `registerAgent registers an agent by tool name`() {
        val adapter = McpServerAdapter()
        val runner = mockk<AgentRunner>()

        adapter.registerAgent("my-agent", runner)

        adapter.registeredAgentNames() shouldBe listOf("my-agent")
    }

    @Test
    fun `AgentResult Success maps to non-error CallToolResult`() =
        runTest {
            val adapter = McpServerAdapter()
            val runner = mockk<AgentRunner>()
            val deferred = CompletableDeferred<AgentResult>()
            deferred.complete(AgentResult.Success(output = "agent output", tokenUsage = TokenUsage(100, 50)))
            every { runner.run(any<AgentTask>()) } returns deferred

            adapter.registerAgent("summariser", runner)

            val result: CallToolResult = adapter.invokeAgent("summariser", "{}")

            result.isError shouldBe false
            result.content.joinToString { it.toString() } shouldContain "agent output"
        }

    @Test
    fun `AgentResult BudgetExceeded maps to isError=true CallToolResult`() =
        runTest {
            val adapter = McpServerAdapter()
            val runner = mockk<AgentRunner>()
            val deferred = CompletableDeferred<AgentResult>()
            deferred.complete(AgentResult.BudgetExceeded(spent = TokenUsage(1000, 500), limit = 1000))
            every { runner.run(any<AgentTask>()) } returns deferred

            adapter.registerAgent("budget-agent", runner)

            val result: CallToolResult = adapter.invokeAgent("budget-agent", "{}")

            result.isError shouldBe true
            result.content.joinToString { it.toString() } shouldContain "Budget exceeded"
        }

    @Test
    fun `AgentResult ToolError maps to isError=true CallToolResult with tool name`() =
        runTest {
            val adapter = McpServerAdapter()
            val runner = mockk<AgentRunner>()
            val deferred = CompletableDeferred<AgentResult>()
            deferred.complete(AgentResult.ToolError(toolName = "file_read", cause = RuntimeException("file not found")))
            every { runner.run(any<AgentTask>()) } returns deferred

            adapter.registerAgent("file-agent", runner)

            val result: CallToolResult = adapter.invokeAgent("file-agent", "{}")

            result.isError shouldBe true
            result.content.joinToString { it.toString() } shouldContain "Tool error"
            result.content.joinToString { it.toString() } shouldContain "file_read"
        }

    @Test
    fun `AgentResult LLMError maps to isError=true CallToolResult with backend name`() =
        runTest {
            val adapter = McpServerAdapter()
            val runner = mockk<AgentRunner>()
            val deferred = CompletableDeferred<AgentResult>()
            deferred.complete(AgentResult.LLMError(backend = "claude", cause = RuntimeException("rate limit")))
            every { runner.run(any<AgentTask>()) } returns deferred

            adapter.registerAgent("llm-agent", runner)

            val result: CallToolResult = adapter.invokeAgent("llm-agent", "{}")

            result.isError shouldBe true
            result.content.joinToString { it.toString() } shouldContain "LLM error"
            result.content.joinToString { it.toString() } shouldContain "claude"
        }

    @Test
    fun `AgentResult Cancelled maps to isError=true CallToolResult with reason`() =
        runTest {
            val adapter = McpServerAdapter()
            val runner = mockk<AgentRunner>()
            val deferred = CompletableDeferred<AgentResult>()
            deferred.complete(AgentResult.Cancelled(reason = "user requested cancellation"))
            every { runner.run(any<AgentTask>()) } returns deferred

            adapter.registerAgent("cancellable-agent", runner)

            val result: CallToolResult = adapter.invokeAgent("cancellable-agent", "{}")

            result.isError shouldBe true
            result.content.joinToString { it.toString() } shouldContain "cancelled"
        }
}
