package io.github.unityinflow.kore.core

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

    /**
     * Emitted once per agent run when at least one skill matched (D-05 / D-07).
     *
     * Carries the activated skill [skillNames] and the activation [durationMs];
     * the count is derivable from `skillNames.size`. Prompts are DELIBERATELY
     * excluded (D-06) — they can be KBs and may carry sensitive content
     * unsuitable for broker (Kafka/RabbitMQ) transport (threat T-05-03). The
     * always-emitted `kore.skill.activate` span covers the 0-match case (D-04);
     * this event is the bus-side, ≥1-match-only counterpart (deliberate
     * span-always / event-on-match asymmetry).
     */
    @Serializable
    @SerialName("SkillActivated")
    data class SkillActivated(
        val agentId: String,
        val skillNames: List<String>,
        val durationMs: Long,
    ) : AgentEvent()
}
