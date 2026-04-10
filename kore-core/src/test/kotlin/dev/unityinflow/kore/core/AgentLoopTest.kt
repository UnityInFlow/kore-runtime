package dev.unityinflow.kore.core

import dev.unityinflow.kore.core.internal.InMemoryAuditLog
import dev.unityinflow.kore.core.internal.InMemoryBudgetEnforcer
import dev.unityinflow.kore.core.internal.InProcessEventBus
import dev.unityinflow.kore.core.port.LLMBackend
import dev.unityinflow.kore.core.port.ToolProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AgentLoopTest {
    // Minimal inline stub LLMBackend for tests (does not depend on kore-test module)
    private fun scriptedBackend(vararg chunks: LLMChunk): LLMBackend =
        object : LLMBackend {
            override val name = "mock"

            override fun call(
                messages: List<ConversationMessage>,
                tools: List<ToolDefinition>,
                config: LLMConfig,
            ) = flow { chunks.forEach { emit(it) } }
        }

    private fun makeLoop(
        backend: LLMBackend,
        toolProviders: List<ToolProvider> = emptyList(),
        maxTokensBudget: Long = Long.MAX_VALUE,
    ): AgentLoop {
        val budgetEnforcer = InMemoryBudgetEnforcer(defaultLimitPerAgent = maxTokensBudget)
        return AgentLoop(
            llmBackend = backend,
            toolProviders = toolProviders,
            budgetEnforcer = budgetEnforcer,
            eventBus = InProcessEventBus(),
            auditLog = InMemoryAuditLog(),
            config = LLMConfig(model = "test-model"),
        )
    }

    @Test
    fun `returns Success when LLM emits text and done`() =
        runTest {
            val backend =
                scriptedBackend(
                    LLMChunk.Text("Hello world"),
                    LLMChunk.Usage(inputTokens = 10, outputTokens = 5),
                    LLMChunk.Done,
                )
            val loop = makeLoop(backend)
            val result = loop.run(AgentTask(id = "test-1", input = "say hello"))

            result.shouldBeInstanceOf<AgentResult.Success>()
            (result as AgentResult.Success).output shouldBe "Hello world"
            result.tokenUsage.inputTokens shouldBe 10
        }

    @Test
    fun `returns BudgetExceeded when budget check fails`() =
        runTest {
            val backend = scriptedBackend(LLMChunk.Text("response"), LLMChunk.Done)
            val loop = makeLoop(backend, maxTokensBudget = 0L) // zero budget → always exceeded
            val result = loop.run(AgentTask(id = "budget-test", input = "hello"))

            result.shouldBeInstanceOf<AgentResult.BudgetExceeded>()
        }

    @Test
    fun `returns LLMError when backend throws`() =
        runTest {
            val failingBackend =
                object : LLMBackend {
                    override val name = "failing"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow<LLMChunk> { throw RuntimeException("API error") }
                }
            val loop = makeLoop(failingBackend)
            val result = loop.run(AgentTask(id = "err-test", input = "hello"))

            result.shouldBeInstanceOf<AgentResult.LLMError>()
            (result as AgentResult.LLMError).backend shouldBe "failing"
        }

    @Test
    fun `calls tool and loops back to LLM`() =
        runTest {
            var llmCallCount = 0
            val backend =
                object : LLMBackend {
                    override val name = "mock"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow {
                        llmCallCount++
                        if (llmCallCount == 1) {
                            emit(LLMChunk.ToolCall(id = "call_1", name = "echo", arguments = """{"text":"hi"}"""))
                            emit(LLMChunk.Done)
                        } else {
                            emit(LLMChunk.Text("done"))
                            emit(LLMChunk.Done)
                        }
                    }
                }
            val echoProvider =
                object : ToolProvider {
                    override suspend fun listTools() =
                        listOf(
                            ToolDefinition(name = "echo", description = "echoes", inputSchema = "{}"),
                        )

                    override suspend fun callTool(call: ToolCall) =
                        ToolResult(
                            toolCallId = call.id,
                            content = "echo: hi",
                        )
                }
            val loop = makeLoop(backend, toolProviders = listOf(echoProvider))
            val result = loop.run(AgentTask(id = "tool-test", input = "call echo"))

            result.shouldBeInstanceOf<AgentResult.Success>()
            llmCallCount shouldBe 2
        }
}
