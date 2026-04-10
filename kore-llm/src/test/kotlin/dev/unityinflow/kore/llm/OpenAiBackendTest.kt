package dev.unityinflow.kore.llm

import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.completions.CompletionUsage
import com.openai.services.blocking.ChatService
import com.openai.services.blocking.chat.ChatCompletionService
import dev.unityinflow.kore.core.ConversationMessage
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.ToolDefinition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Optional

class OpenAiBackendTest {

    private val mockChatCompletionService = mockk<ChatCompletionService>()
    private val mockChatService = mockk<ChatService> {
        every { completions() } returns mockChatCompletionService
    }
    private val mockClient = mockk<OpenAIClient> {
        every { chat() } returns mockChatService
    }

    private fun makeBackend() = OpenAiBackend(
        client = mockClient,
        defaultModel = "gpt-4o",
        semaphore = Semaphore(1),
    )

    @Test
    fun `name is gpt`() {
        makeBackend().name shouldBe "gpt"
    }

    @Test
    fun `emits Text chunk for text content`() = runTest {
        val message = mockk<ChatCompletionMessage> {
            every { content() } returns Optional.of("Hello from GPT!")
            every { toolCalls() } returns Optional.empty()
        }
        val choice = mockk<ChatCompletion.Choice> {
            every { message() } returns message
        }
        val usage = mockk<CompletionUsage> {
            every { promptTokens() } returns 8L
            every { completionTokens() } returns 6L
        }
        val completion = mockk<ChatCompletion> {
            every { choices() } returns listOf(choice)
            every { usage() } returns Optional.of(usage)
        }
        every { mockChatCompletionService.create(any()) } returns completion

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
            tools = emptyList(),
            config = LLMConfig(model = "gpt-4o"),
        ).toList()

        chunks.filterIsInstance<LLMChunk.Text>().first().content shouldBe "Hello from GPT!"
    }

    @Test
    fun `emits ToolCall chunk for tool calls in response`() = runTest {
        val functionCall = mockk<ChatCompletionMessageFunctionToolCall.Function> {
            every { name() } returns "calculator"
            every { arguments() } returns """{"expression": "2+2"}"""
        }
        val functionToolCall = mockk<ChatCompletionMessageFunctionToolCall> {
            every { id() } returns "call_xyz789"
            every { function() } returns functionCall
        }
        val toolCallWrapper = mockk<ChatCompletionMessageToolCall> {
            every { isFunction() } returns true
            every { asFunction() } returns functionToolCall
        }
        val message = mockk<ChatCompletionMessage> {
            every { content() } returns Optional.empty()
            every { toolCalls() } returns Optional.of(listOf(toolCallWrapper))
        }
        val choice = mockk<ChatCompletion.Choice> {
            every { message() } returns message
        }
        val usage = mockk<CompletionUsage> {
            every { promptTokens() } returns 15L
            every { completionTokens() } returns 10L
        }
        val completion = mockk<ChatCompletion> {
            every { choices() } returns listOf(choice)
            every { usage() } returns Optional.of(usage)
        }
        every { mockChatCompletionService.create(any()) } returns completion

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Calculate 2+2")),
            tools = listOf(ToolDefinition("calculator", "Calculate expressions", "{}")),
            config = LLMConfig(model = "gpt-4o"),
        ).toList()

        val toolCall = chunks.filterIsInstance<LLMChunk.ToolCall>().first()
        toolCall.id shouldBe "call_xyz789"
        toolCall.name shouldBe "calculator"
        toolCall.arguments shouldBe """{"expression": "2+2"}"""
        // arguments must be a String — no provider-specific types (Pitfall 5)
        toolCall.arguments.shouldBeInstanceOf<String>()
    }

    @Test
    fun `emits Usage chunk when usage is present`() = runTest {
        val message = mockk<ChatCompletionMessage> {
            every { content() } returns Optional.of("Response")
            every { toolCalls() } returns Optional.empty()
        }
        val choice = mockk<ChatCompletion.Choice> {
            every { message() } returns message
        }
        val usage = mockk<CompletionUsage> {
            every { promptTokens() } returns 30L
            every { completionTokens() } returns 12L
        }
        val completion = mockk<ChatCompletion> {
            every { choices() } returns listOf(choice)
            every { usage() } returns Optional.of(usage)
        }
        every { mockChatCompletionService.create(any()) } returns completion

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
            tools = emptyList(),
            config = LLMConfig(model = "gpt-4o"),
        ).toList()

        val usageChunk = chunks.filterIsInstance<LLMChunk.Usage>().first()
        usageChunk.inputTokens shouldBe 30
        usageChunk.outputTokens shouldBe 12
    }

    @Test
    fun `last chunk is Done`() = runTest {
        val message = mockk<ChatCompletionMessage> {
            every { content() } returns Optional.of("OK")
            every { toolCalls() } returns Optional.empty()
        }
        val choice = mockk<ChatCompletion.Choice> {
            every { message() } returns message
        }
        val completion = mockk<ChatCompletion> {
            every { choices() } returns listOf(choice)
            every { usage() } returns Optional.empty()
        }
        every { mockChatCompletionService.create(any()) } returns completion

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
            tools = emptyList(),
            config = LLMConfig(model = "gpt-4o"),
        ).toList()

        chunks.last() shouldBe LLMChunk.Done
    }

    @Test
    fun `system messages are converted to system role params`() = runTest {
        val message = mockk<ChatCompletionMessage> {
            every { content() } returns Optional.of("Hi")
            every { toolCalls() } returns Optional.empty()
        }
        val choice = mockk<ChatCompletion.Choice> {
            every { message() } returns message
        }
        val completion = mockk<ChatCompletion> {
            every { choices() } returns listOf(choice)
            every { usage() } returns Optional.empty()
        }
        every { mockChatCompletionService.create(any()) } returns completion

        makeBackend().call(
            messages = listOf(
                ConversationMessage(ConversationMessage.Role.System, "Be helpful"),
                ConversationMessage(ConversationMessage.Role.User, "Hi"),
            ),
            tools = emptyList(),
            config = LLMConfig(model = "gpt-4o"),
        ).toList()

        verify { mockChatCompletionService.create(any()) }
    }
}
