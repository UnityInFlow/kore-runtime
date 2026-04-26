package dev.unityinflow.kore.core.integration

import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.ConversationMessage
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.ToolDefinition
import dev.unityinflow.kore.core.dsl.agent
import dev.unityinflow.kore.core.internal.fallbackTo
import dev.unityinflow.kore.core.port.LLMBackend
import dev.unityinflow.kore.test.MockLLMBackend
import dev.unityinflow.kore.test.MockToolProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * End-to-end integration test for Phase 1.
 *
 * KEEP IN SYNC WITH README.md hero demo. The syntax in this test IS the README example.
 * If this test passes, the hero demo works. If the hero demo in README changes,
 * update this test first to keep it green.
 *
 * T-07-01 mitigation: HeroDemoTest uses identical syntax to the README example.
 */
class HeroDemoTest {
    @Test
    fun `hero demo - simple text response`() =
        runTest {
            // This is the README hero demo pattern
            val runner =
                agent("demo-agent") {
                    model =
                        MockLLMBackend("mock")
                            .whenCalled(
                                LLMChunk.Text("Hello from kore!"),
                                LLMChunk.Usage(inputTokens = 20, outputTokens = 10),
                                LLMChunk.Done,
                            )
                    budget(maxTokens = 1_000)
                }

            val result = runner.run(AgentTask(id = "demo-1", input = "say hello")).await()

            val success = result.shouldBeInstanceOf<AgentResult.Success>()
            success.output shouldBe "Hello from kore!"
            success.tokenUsage.inputTokens shouldBe 20
            success.tokenUsage.outputTokens shouldBe 10
        }

    @Test
    fun `hero demo - tool calling loop`() =
        runTest {
            val toolProvider =
                MockToolProvider()
                    .withTool("echo", "Echoes the input", "{}")
                    .returnsFor("echo", "echoed!")

            var llmCallNumber = 0
            val mockBackend =
                object : LLMBackend {
                    override val name = "mock"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow {
                        llmCallNumber++
                        if (llmCallNumber == 1) {
                            emit(LLMChunk.ToolCall(id = "c1", name = "echo", arguments = """{"text":"hi"}"""))
                            emit(LLMChunk.Done)
                        } else {
                            emit(LLMChunk.Text("Tool returned: echoed!"))
                            emit(LLMChunk.Done)
                        }
                    }
                }

            val runner =
                agent("tool-agent") {
                    model = mockBackend
                    tools(toolProvider)
                }

            val result = runner.run(AgentTask(id = "tool-1", input = "echo hi")).await()

            val success = result.shouldBeInstanceOf<AgentResult.Success>()
            success.output shouldBe "Tool returned: echoed!"
            llmCallNumber shouldBe 2
        }

    @Test
    fun `fallback chain compiles and wraps backends`() =
        runTest {
            val primary = MockLLMBackend("primary")
            // No .whenCalled() on primary → throws on first call → fallback activates
            val fallback =
                MockLLMBackend("fallback")
                    .whenCalled(LLMChunk.Text("from fallback"), LLMChunk.Done)

            val resilient = primary fallbackTo fallback

            resilient.name shouldBe "primary"

            // Primary has no responses — will fail — fallback should be used
            val runner =
                agent("fallback-agent") {
                    model = resilient
                }
            val result = runner.run(AgentTask(id = "fb-1", input = "test")).await()
            // primary fails (no scripted response) → fallback returns "from fallback"
            val success = result.shouldBeInstanceOf<AgentResult.Success>()
            success.output shouldBe "from fallback"
        }

    @Test
    fun `budget exceeded stops the agent loop`() =
        runTest {
            val backend =
                MockLLMBackend("mock")
                    .whenCalled(LLMChunk.Text("response"), LLMChunk.Done)

            val runner =
                agent("budget-agent") {
                    model = backend
                    budget(maxTokens = 0L) // zero budget → BudgetExceeded immediately
                }

            val result = runner.run(AgentTask(id = "budget-1", input = "test")).await()
            result.shouldBeInstanceOf<AgentResult.BudgetExceeded>()
        }
}
