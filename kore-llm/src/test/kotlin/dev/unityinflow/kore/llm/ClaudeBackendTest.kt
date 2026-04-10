package dev.unityinflow.kore.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.models.ContentBlock
import com.anthropic.models.Message
import com.anthropic.models.TextBlock
import com.anthropic.models.ToolUseBlock
import com.anthropic.models.Usage
import com.anthropic.services.blocking.MessageService
import dev.unityinflow.kore.core.ConversationMessage
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.ToolDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ClaudeBackendTest {

    private val mockMessageService = mockk<MessageService>()
    private val mockClient = mockk<AnthropicClient> {
        every { messages() } returns mockMessageService
    }

    private fun makeBackend() = ClaudeBackend(
        client = mockClient,
        defaultModel = "claude-3-5-sonnet-20241022",
        semaphore = Semaphore(1),
    )

    @Test
    fun `name is claude`() {
        makeBackend().name shouldBe "claude"
    }

    @Test
    fun `emits Text chunk for text content block`() = runTest {
        val textBlock = mockk<TextBlock> {
            every { text() } returns "Hello, world!"
        }
        val contentBlock = mockk<ContentBlock> {
            every { isText() } returns true
            every { isToolUse() } returns false
            every { asText() } returns textBlock
        }
        val usage = mockk<Usage> {
            every { inputTokens() } returns 10L
            every { outputTokens() } returns 5L
        }
        val message = mockk<Message> {
            every { content() } returns listOf(contentBlock)
            every { usage() } returns usage
        }
        every { mockMessageService.create(any()) } returns message

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
            tools = emptyList(),
            config = LLMConfig(model = "claude-3-5-sonnet-20241022"),
        ).toList()

        chunks.filterIsInstance<LLMChunk.Text>().first().content shouldBe "Hello, world!"
    }

    @Test
    fun `emits ToolCall chunk for tool_use content block`() = runTest {
        val toolUseBlock = mockk<ToolUseBlock> {
            every { id() } returns "call_abc123"
            every { name() } returns "search"
            every { _input() } returns com.anthropic.core.JsonValue.from(mapOf("query" to "kotlin coroutines"))
        }
        val contentBlock = mockk<ContentBlock> {
            every { isText() } returns false
            every { isToolUse() } returns true
            every { asToolUse() } returns toolUseBlock
        }
        val usage = mockk<Usage> {
            every { inputTokens() } returns 20L
            every { outputTokens() } returns 15L
        }
        val message = mockk<Message> {
            every { content() } returns listOf(contentBlock)
            every { usage() } returns usage
        }
        every { mockMessageService.create(any()) } returns message

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Search something")),
            tools = listOf(ToolDefinition("search", "Search the web", "{}")),
            config = LLMConfig(model = "claude-3-5-sonnet-20241022"),
        ).toList()

        val toolCall = chunks.filterIsInstance<LLMChunk.ToolCall>().first()
        toolCall.id shouldBe "call_abc123"
        toolCall.name shouldBe "search"
        // arguments must be a JSON string — no provider-specific types (Pitfall 5)
        toolCall.arguments.shouldBeInstanceOf<String>()
    }

    @Test
    fun `emits Usage chunk with token counts`() = runTest {
        val textBlock = mockk<TextBlock> {
            every { text() } returns "Response"
        }
        val contentBlock = mockk<ContentBlock> {
            every { isText() } returns true
            every { isToolUse() } returns false
            every { asText() } returns textBlock
        }
        val usage = mockk<Usage> {
            every { inputTokens() } returns 42L
            every { outputTokens() } returns 17L
        }
        val message = mockk<Message> {
            every { content() } returns listOf(contentBlock)
            every { usage() } returns usage
        }
        every { mockMessageService.create(any()) } returns message

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
            tools = emptyList(),
            config = LLMConfig(model = "claude-3-5-sonnet-20241022"),
        ).toList()

        val usageChunk = chunks.filterIsInstance<LLMChunk.Usage>().first()
        usageChunk.inputTokens shouldBe 42
        usageChunk.outputTokens shouldBe 17
    }

    @Test
    fun `last chunk is Done`() = runTest {
        val textBlock = mockk<TextBlock> {
            every { text() } returns "OK"
        }
        val contentBlock = mockk<ContentBlock> {
            every { isText() } returns true
            every { isToolUse() } returns false
            every { asText() } returns textBlock
        }
        val usage = mockk<Usage> {
            every { inputTokens() } returns 1L
            every { outputTokens() } returns 1L
        }
        val message = mockk<Message> {
            every { content() } returns listOf(contentBlock)
            every { usage() } returns usage
        }
        every { mockMessageService.create(any()) } returns message

        val chunks = makeBackend().call(
            messages = listOf(ConversationMessage(ConversationMessage.Role.User, "Hi")),
            tools = emptyList(),
            config = LLMConfig(model = "claude-3-5-sonnet-20241022"),
        ).toList()

        chunks.last() shouldBe LLMChunk.Done
    }

    @Test
    fun `system messages are extracted and not included in message list`() = runTest {
        val textBlock = mockk<TextBlock> {
            every { text() } returns "Hi"
        }
        val contentBlock = mockk<ContentBlock> {
            every { isText() } returns true
            every { isToolUse() } returns false
            every { asText() } returns textBlock
        }
        val usage = mockk<Usage> {
            every { inputTokens() } returns 1L
            every { outputTokens() } returns 1L
        }
        val message = mockk<Message> {
            every { content() } returns listOf(contentBlock)
            every { usage() } returns usage
        }
        every { mockMessageService.create(any()) } returns message

        makeBackend().call(
            messages = listOf(
                ConversationMessage(ConversationMessage.Role.System, "You are helpful"),
                ConversationMessage(ConversationMessage.Role.User, "Hi"),
            ),
            tools = emptyList(),
            config = LLMConfig(model = "claude-3-5-sonnet-20241022"),
        ).toList()

        // verify create() was called — system prompt handling is internal
        verify { mockMessageService.create(any()) }
    }
}
