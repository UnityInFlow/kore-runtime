package dev.unityinflow.kore.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LLMChunkTest {
    @Test
    fun `LLMChunk when expression is exhaustive`() {
        val chunk: LLMChunk = LLMChunk.Text("hello")
        val label =
            when (chunk) {
                is LLMChunk.Text -> "text"
                is LLMChunk.ToolCall -> "tool"
                is LLMChunk.Usage -> "usage"
                is LLMChunk.Done -> "done"
            }
        label shouldBe "text"
    }

    @Test
    fun `ToolCall carries id, name, and JSON arguments`() {
        val chunk =
            LLMChunk.ToolCall(
                id = "call_1",
                name = "search",
                arguments = """{"query": "kotlin coroutines"}""",
            )
        chunk.id shouldBe "call_1"
        chunk.name shouldBe "search"
        chunk.arguments shouldBe """{"query": "kotlin coroutines"}"""
    }
}
