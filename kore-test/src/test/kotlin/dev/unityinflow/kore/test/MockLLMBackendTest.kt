package dev.unityinflow.kore.test

import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.ToolCall
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MockLLMBackendTest {
    @Test
    fun `emits scripted chunks in order`() =
        runTest {
            val mock =
                MockLLMBackend()
                    .whenCalled(LLMChunk.Text("hello"), LLMChunk.Usage(10, 5), LLMChunk.Done)

            val chunks = mock.call(emptyList(), emptyList(), LLMConfig("test")).toList()

            chunks.size shouldBe 3
            (chunks[0] as LLMChunk.Text).content shouldBe "hello"
            (chunks[1] as LLMChunk.Usage).inputTokens shouldBe 10
            chunks[2] shouldBe LLMChunk.Done
        }

    @Test
    fun `throws when called more times than scripted`() =
        runTest {
            val mock =
                MockLLMBackend()
                    .whenCalled(LLMChunk.Text("once"), LLMChunk.Done)

            mock.call(emptyList(), emptyList(), LLMConfig("test")).toList()

            val ex =
                assertThrows<IllegalStateException> {
                    mock.call(emptyList(), emptyList(), LLMConfig("test")).toList()
                }
            ex.message shouldContain "more times than scripted"
        }

    @Test
    fun `assertCallCount passes when count matches`() =
        runTest {
            val mock =
                MockLLMBackend()
                    .whenCalled(LLMChunk.Done)
                    .whenCalled(LLMChunk.Done)

            mock.call(emptyList(), emptyList(), LLMConfig("test")).toList()
            mock.call(emptyList(), emptyList(), LLMConfig("test")).toList()

            mock.assertCallCount(2) // should not throw
        }

    @Test
    fun `MockToolProvider returns scripted content`() =
        runTest {
            val provider =
                MockToolProvider()
                    .withTool("echo", "echoes text")
                    .returnsFor("echo", "echoed!")

            val result = provider.callTool(ToolCall(id = "c1", name = "echo", arguments = "{}"))
            result.content shouldBe "echoed!"
            result.isError shouldBe false
        }

    @Test
    fun `MockToolProvider throws for unknown tool`() =
        runTest {
            val provider = MockToolProvider()

            val ex =
                assertThrows<IllegalStateException> {
                    provider.callTool(ToolCall(id = "c1", name = "unknown", arguments = "{}"))
                }
            ex.message shouldContain "no handler registered"
        }
}
