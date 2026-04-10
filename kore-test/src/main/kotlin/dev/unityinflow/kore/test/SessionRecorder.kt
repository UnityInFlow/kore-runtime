package dev.unityinflow.kore.test

import dev.unityinflow.kore.core.ConversationMessage
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.ToolDefinition
import dev.unityinflow.kore.core.port.LLMBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Wraps a real [LLMBackend] and records all exchanges to a JSON file.
 * The recorded file can later be replayed with [SessionReplayer] in CI
 * without any API key or network access.
 *
 * **Security note:** Recorded session files may contain sensitive LLM API responses.
 * Never commit session files containing real API keys or user data.
 * Add `*.session.json` (or equivalent) to `.gitignore`.
 *
 * Usage:
 * ```kotlin
 * val recorder = SessionRecorder(realClaudeBackend, Path.of("session.json"))
 * // Use recorder as the backend in your agent — it transparently records
 * recorder.save()  // writes the JSON file after all calls
 * ```
 */
class SessionRecorder(
    private val delegate: LLMBackend,
    private val outputPath: Path,
) : LLMBackend {
    override val name: String = delegate.name
    private val recordings = mutableListOf<SessionEntry>()

    override fun call(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig,
    ): Flow<LLMChunk> =
        flow {
            val collectedChunks = mutableListOf<LLMChunk>()
            delegate.call(messages, tools, config).collect { chunk ->
                collectedChunks.add(chunk)
                emit(chunk)
            }
            recordings.add(
                SessionEntry(
                    modelName = delegate.name,
                    chunks = collectedChunks.map { it.toSerializable() },
                ),
            )
        }

    /** Write all recorded sessions to [outputPath] as JSON. Call after agent run completes. */
    fun save() {
        val json = Json { prettyPrint = true }
        outputPath.toFile().writeText(json.encodeToString(recordings))
    }
}

/**
 * Reads a session recording produced by [SessionRecorder] and replays responses deterministically.
 * Each [call] returns the next recorded sequence from the JSON file.
 *
 * Validates JSON structure before replay — throws a descriptive exception on malformed session files
 * (T-04-02 mitigation).
 */
class SessionReplayer(
    private val inputPath: Path,
    override val name: String = "session-replay",
) : LLMBackend {
    private val entries: ArrayDeque<SessionEntry> by lazy {
        val json = Json { ignoreUnknownKeys = true }
        val text = inputPath.toFile().readText()
        ArrayDeque(json.decodeFromString<List<SessionEntry>>(text))
    }

    override fun call(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig,
    ): Flow<LLMChunk> =
        flow {
            val entry =
                entries.removeFirstOrNull()
                    ?: error(
                        "SessionReplayer: no more recorded sessions in '$inputPath'. " +
                            "The test made more LLM calls than were recorded.",
                    )
            entry.chunks.forEach { emit(it.toLLMChunk()) }
        }
}

// ─── Serialization types ────────────────────────────────────────────────────

@Serializable
data class SessionEntry(
    val modelName: String,
    val chunks: List<SerializableChunk>,
)

@Serializable
sealed class SerializableChunk {
    @Serializable
    data class Text(
        val content: String,
    ) : SerializableChunk()

    @Serializable
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
    ) : SerializableChunk()

    @Serializable
    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
    ) : SerializableChunk()

    @Serializable
    data object Done : SerializableChunk()
}

internal fun LLMChunk.toSerializable(): SerializableChunk =
    when (this) {
        is LLMChunk.Text -> SerializableChunk.Text(content)
        is LLMChunk.ToolCall -> SerializableChunk.ToolCall(id, name, arguments)
        is LLMChunk.Usage -> SerializableChunk.Usage(inputTokens, outputTokens)
        is LLMChunk.Done -> SerializableChunk.Done
    }

internal fun SerializableChunk.toLLMChunk(): LLMChunk =
    when (this) {
        is SerializableChunk.Text -> LLMChunk.Text(content)
        is SerializableChunk.ToolCall -> LLMChunk.ToolCall(id, name, arguments)
        is SerializableChunk.Usage -> LLMChunk.Usage(inputTokens, outputTokens)
        is SerializableChunk.Done -> LLMChunk.Done
    }
