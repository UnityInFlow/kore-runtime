package dev.unityinflow.kore.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.output.Response
import dev.langchain4j.model.output.TokenUsage
import dev.unityinflow.kore.core.ConversationMessage
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.ToolDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GeminiBackendTest {
    private val mockChatModel = mockk<ChatLanguageModel>()

    private fun makeBackend() =
        GeminiBackend(
            chatModel = mockChatModel,
            semaphore = Semaphore(1),
        )

    @Test
    fun `name is gemini`() {
        makeBackend().name shouldBe "gemini"
    }

    @Test
    fun `emits Text chunk for text response`() =
        runTest {
            val aiMessage = AiMessage.from("Hello from Gemini!")
            val response = Response.from(aiMessage, TokenUsage(12, 6))
            every { mockChatModel.generate(any<List<dev.langchain4j.data.message.ChatMessage>>()) } returns response

            val chunks =
                makeBackend()
                    .call(
                        messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
                        tools = emptyList(),
                        config = LLMConfig(model = "gemini-1.5-flash"),
                    ).toList()

            chunks.filterIsInstance<LLMChunk.Text>().first().content shouldBe "Hello from Gemini!"
        }

    @Test
    fun `emits ToolCall chunk for tool execution request`() =
        runTest {
            val toolRequest =
                ToolExecutionRequest
                    .builder()
                    .id("gemini_req_1")
                    .name("calculator")
                    .arguments("""{"expression": "3*7"}""")
                    .build()
            val aiMessage = AiMessage.from(listOf(toolRequest))
            val response = Response.from(aiMessage, TokenUsage(15, 8))
            every {
                mockChatModel.generate(
                    any<List<dev.langchain4j.data.message.ChatMessage>>(),
                    any<List<dev.langchain4j.agent.tool.ToolSpecification>>(),
                )
            } returns response

            val chunks =
                makeBackend()
                    .call(
                        messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Calculate")),
                        tools = listOf(ToolDefinition("calculator", "Evaluate expressions", "{}")),
                        config = LLMConfig(model = "gemini-1.5-flash"),
                    ).toList()

            val toolCall = chunks.filterIsInstance<LLMChunk.ToolCall>().first()
            toolCall.id shouldBe "gemini_req_1"
            toolCall.name shouldBe "calculator"
            // arguments must be a String — no LangChain4j types escape (Pitfall 5)
            toolCall.arguments.shouldBeInstanceOf<String>()
            toolCall.arguments shouldBe """{"expression": "3*7"}"""
        }

    @Test
    fun `emits Usage chunk with token counts`() =
        runTest {
            val aiMessage = AiMessage.from("Gemini response")
            val response = Response.from(aiMessage, TokenUsage(18, 7))
            every { mockChatModel.generate(any<List<dev.langchain4j.data.message.ChatMessage>>()) } returns response

            val chunks =
                makeBackend()
                    .call(
                        messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
                        tools = emptyList(),
                        config = LLMConfig(model = "gemini-1.5-flash"),
                    ).toList()

            val usageChunk = chunks.filterIsInstance<LLMChunk.Usage>().first()
            usageChunk.inputTokens shouldBe 18
            usageChunk.outputTokens shouldBe 7
        }

    @Test
    fun `last chunk is Done`() =
        runTest {
            val aiMessage = AiMessage.from("Done")
            val response = Response.from(aiMessage)
            every { mockChatModel.generate(any<List<dev.langchain4j.data.message.ChatMessage>>()) } returns response

            val chunks =
                makeBackend()
                    .call(
                        messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
                        tools = emptyList(),
                        config = LLMConfig(model = "gemini-1.5-flash"),
                    ).toList()

            chunks.last() shouldBe LLMChunk.Done
        }

    @Test
    fun `no LangChain4j types leak into LLMChunk`() =
        runTest {
            val aiMessage = AiMessage.from("Gemini text")
            val response = Response.from(aiMessage, TokenUsage(4, 2))
            every { mockChatModel.generate(any<List<dev.langchain4j.data.message.ChatMessage>>()) } returns response

            val chunks =
                makeBackend()
                    .call(
                        messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
                        tools = emptyList(),
                        config = LLMConfig(model = "gemini-1.5-flash"),
                    ).toList()

            // All chunks must be sealed LLMChunk subtypes — no LangChain4j types
            chunks.forEach { chunk ->
                chunk.shouldBeInstanceOf<LLMChunk>()
            }
        }
}
