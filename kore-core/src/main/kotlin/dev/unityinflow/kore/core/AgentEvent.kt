package dev.unityinflow.kore.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Events emitted by the agent loop to the [port.EventBus].
 *
 * Polymorphic JSON uses a `type` discriminator matching each subclass simple
 * name so the wire format stays human-readable in Kafka UI / RabbitMQ admin
 * consoles. The `@Serializable` + `@JsonClassDiscriminator` annotations are
 * compile-time only — kotlinx-serialization-core is declared `compileOnly` in
 * `kore-core/build.gradle.kts`, so the runtime classpath stays free of the
 * serialization runtime. Adapter modules (`kore-kafka`, `kore-rabbitmq`) bring
 * `kotlinx-serialization-json` at runtime.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class AgentEvent {
    @Serializable
    @SerialName("AgentStarted")
    data class AgentStarted(
        val agentId: String,
        val taskId: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("LLMCallStarted")
    data class LLMCallStarted(
        val agentId: String,
        val backend: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("LLMCallCompleted")
    data class LLMCallCompleted(
        val agentId: String,
        val tokenUsage: TokenUsage,
    ) : AgentEvent()

    @Serializable
    @SerialName("ToolCallStarted")
    data class ToolCallStarted(
        val agentId: String,
        val toolName: String,
    ) : AgentEvent()

    @Serializable
    @SerialName("ToolCallCompleted")
    data class ToolCallCompleted(
        val agentId: String,
        val toolName: String,
        val isError: Boolean,
    ) : AgentEvent()

    @Serializable
    @SerialName("AgentCompleted")
    data class AgentCompleted(
        val agentId: String,
        val result: AgentResult,
    ) : AgentEvent()
}
