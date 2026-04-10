package dev.unityinflow.kore.core.port

import dev.unityinflow.kore.core.ConversationMessage
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * Port interface for all LLM backends (Claude, GPT, Ollama, Gemini).
 *
 * Designed simultaneously against all four providers — no provider-specific types
 * leak into this interface. Adapters translate from [LLMChunk] to provider formats internally.
 *
 * Implementations MUST be stateless — they receive the full [messages] list each call.
 * The agent loop accumulates message history in [dev.unityinflow.kore.core.AgentContext].
 */
interface LLMBackend {
    /** Unique name for this backend (e.g., "claude", "gpt-4o", "ollama/llama3"). */
    val name: String

    /**
     * Call the LLM with the given conversation history and available tools.
     *
     * Returns a [Flow] of [LLMChunk]s. Non-streaming backends emit:
     * [LLMChunk.Text] → [LLMChunk.Usage] → [LLMChunk.Done]
     *
     * Streaming backends emit multiple [LLMChunk.Text] chunks.
     * Tool-using LLMs emit [LLMChunk.ToolCall] chunks before [LLMChunk.Done].
     *
     * Implementations MUST wrap blocking HTTP calls in `withContext(Dispatchers.IO)`.
     * This method MUST NOT throw — emit [LLMChunk.Done] on error and propagate
     * via the flow's exception channel.
     */
    fun call(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig,
    ): Flow<LLMChunk>
}
