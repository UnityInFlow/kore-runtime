package dev.unityinflow.kore.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * D-03 validation: every AgentEvent subclass roundtrips through polymorphic JSON
 * with a `type` discriminator matching the subclass simple name. Proves the
 * Pattern 2 compileOnly kotlinx-serialization setup compiles and encodes
 * correctly without polluting kore-core's runtime classpath.
 */
@OptIn(ExperimentalSerializationApi::class)
class AgentEventSerializationTest {
    private val json =
        Json {
            classDiscriminator = "type"
            encodeDefaults = false
        }

    @Test
    fun `AgentStarted roundtrip preserves type discriminator`() {
        val original: AgentEvent = AgentEvent.AgentStarted(agentId = "a1", taskId = "t1")
        val encoded = json.encodeToString(AgentEvent.serializer(), original)
        encoded shouldContain "\"type\":\"AgentStarted\""
        json.decodeFromString(AgentEvent.serializer(), encoded) shouldBe original
    }

    @Test
    fun `LLMCallStarted roundtrip preserves type discriminator`() {
        val original: AgentEvent = AgentEvent.LLMCallStarted(agentId = "a1", backend = "claude")
        val encoded = json.encodeToString(AgentEvent.serializer(), original)
        encoded shouldContain "\"type\":\"LLMCallStarted\""
        json.decodeFromString(AgentEvent.serializer(), encoded) shouldBe original
    }

    @Test
    fun `LLMCallCompleted roundtrip preserves type discriminator`() {
        val original: AgentEvent =
            AgentEvent.LLMCallCompleted(
                agentId = "a1",
                tokenUsage = TokenUsage(inputTokens = 10, outputTokens = 20),
            )
        val encoded = json.encodeToString(AgentEvent.serializer(), original)
        encoded shouldContain "\"type\":\"LLMCallCompleted\""
        json.decodeFromString(AgentEvent.serializer(), encoded) shouldBe original
    }

    @Test
    fun `ToolCallStarted roundtrip preserves type discriminator`() {
        val original: AgentEvent = AgentEvent.ToolCallStarted(agentId = "a1", toolName = "github.read")
        val encoded = json.encodeToString(AgentEvent.serializer(), original)
        encoded shouldContain "\"type\":\"ToolCallStarted\""
        json.decodeFromString(AgentEvent.serializer(), encoded) shouldBe original
    }

    @Test
    fun `ToolCallCompleted roundtrip preserves type discriminator`() {
        val original: AgentEvent =
            AgentEvent.ToolCallCompleted(
                agentId = "a1",
                toolName = "github.read",
                isError = true,
            )
        val encoded = json.encodeToString(AgentEvent.serializer(), original)
        encoded shouldContain "\"type\":\"ToolCallCompleted\""
        json.decodeFromString(AgentEvent.serializer(), encoded) shouldBe original
    }

    @Test
    fun `AgentCompleted roundtrip preserves type discriminator`() {
        val original: AgentEvent =
            AgentEvent.AgentCompleted(
                agentId = "a1",
                result =
                    AgentResult.Success(
                        output = "done",
                        tokenUsage = TokenUsage(inputTokens = 1, outputTokens = 2),
                    ),
            )
        val encoded = json.encodeToString(AgentEvent.serializer(), original)
        encoded shouldContain "\"type\":\"AgentCompleted\""
        json.decodeFromString(AgentEvent.serializer(), encoded) shouldBe original
    }
}
