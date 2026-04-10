package dev.unityinflow.kore.core

/**
 * A single chunk emitted by an LLM backend during streaming.
 *
 * All backends emit [Text] chunks followed by zero or more [ToolCall] chunks,
 * then a [Usage] chunk, then [Done]. Non-streaming backends emit one [Text]
 * chunk containing the full response.
 */
sealed class LLMChunk {
    /** A text fragment from the model's response. */
    data class Text(
        val content: String,
    ) : LLMChunk()

    /**
     * A tool call requested by the model.
     * [arguments] is always a JSON object string — never a provider-specific type.
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
    ) : LLMChunk()

    /** Token usage reported at the end of a streaming response. */
    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
    ) : LLMChunk()

    /** Signals the end of the streaming response. Always the final chunk. */
    data object Done : LLMChunk()
}
