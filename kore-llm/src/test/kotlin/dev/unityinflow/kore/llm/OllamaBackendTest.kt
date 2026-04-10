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

class OllamaBackendTest {

    private val mockChatModel = mockk<ChatLanguageModel>()

    private fun makeBackend() = OllamaBackend(
        chatModel = mockChatModel,
        semaphore = Semaphore(1),
    )

    @Test
    fun `name is ollama`() {
        makeBackend().name shouldBe "ollama"
    }

    @Test
    fun `emits Text chunk for text response`() = runTest {
        val aiMessage = AiMessage.from("Hello from Ollama!")
        val response = Response.from(aiMessage, TokenUsage(10, 5))
        every { mockChatModel.generate(any<List<dev.langchain4j.data.message.ChatMessage>>()) } returns response

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
            tools = emptyList(),
            config = LLMConfig(model = "llama3"),
        ).toList()

        chunks.filterIsInstance<LLMChunk.Text>().first().content shouldBe "Hello from Ollama!"
    }

    @Test
    fun `emits ToolCall chunk for tool execution request`() = runTest {
        val toolRequest = ToolExecutionRequest.builder()
            .id("req_1")
            .name("search")
            .arguments("""{"query": "kotlin"}""")
            .build()
        val aiMessage = AiMessage.from(listOf(toolRequest))
        val response = Response.from(aiMessage, TokenUsage(20, 10))
        every {
            mockChatModel.generate(
                any<List<dev.langchain4j.data.message.ChatMessage>>(),
                any<List<dev.langchain4j.agent.tool.ToolSpecification>>(),
            )
        } returns response

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Search")),
            tools = listOf(ToolDefinition("search", "Search the web", "{}")),
            config = LLMConfig(model = "llama3"),
        ).toList()

        val toolCall = chunks.filterIsInstance<LLMChunk.ToolCall>().first()
        toolCall.id shouldBe "req_1"
        toolCall.name shouldBe "search"
        // arguments must be a String — no LangChain4j types escape (Pitfall 5)
        toolCall.arguments.shouldBeInstanceOf<String>()
        toolCall.arguments shouldBe """{"query": "kotlin"}"""
    }

    @Test
    fun `emits Usage chunk with token counts`() = runTest {
        val aiMessage = AiMessage.from("Response")
        val response = Response.from(aiMessage, TokenUsage(25, 8))
        every { mockChatModel.generate(any<List<dev.langchain4j.data.message.ChatMessage>>()) } returns response

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
            tools = emptyList(),
            config = LLMConfig(model = "llama3"),
        ).toList()

        val usageChunk = chunks.filterIsInstance<LLMChunk.Usage>().first()
        usageChunk.inputTokens shouldBe 25
        usageChunk.outputTokens shouldBe 8
    }

    @Test
    fun `last chunk is Done`() = runTest {
        val aiMessage = AiMessage.from("OK")
        val response = Response.from(aiMessage)
        every { mockChatModel.generate(any<List<dev.langchain4j.data.message.ChatMessage>>()) } returns response

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
            tools = emptyList(),
            config = LLMConfig(model = "llama3"),
        ).toList()

        chunks.last() shouldBe LLMChunk.Done
    }

    @Test
    fun `no LangChain4j types leak into LLMChunk`() = runTest {
        val aiMessage = AiMessage.from("Text response")
        val response = Response.from(aiMessage, TokenUsage(5, 3))
        every { mockChatModel.generate(any<List<dev.langchain4j.data.message.ChatMessage>>()) } returns response

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
            tools = emptyList(),
            config = LLMConfig(model = "llama3"),
        ).toList()

        // All chunks must be sealed LLMChunk subtypes — no LangChain4j types
        chunks.forEach { chunk ->
            chunk.shouldBeInstanceOf<LLMChunk>()
        }
    }
}
