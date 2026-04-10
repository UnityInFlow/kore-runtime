package dev.unityinflow.kore.test

import dev.unityinflow.kore.core.ConversationMessage
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.ToolDefinition
import dev.unityinflow.kore.core.port.LLMBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scripted [LLMBackend] for use in unit tests.
 *
 * Each [whenCalled] invocation queues a sequence of [LLMChunk]s to be emitted
 * the next time [call] is invoked. Throws [IllegalStateException] if [call] is
 * invoked more times than scripted responses were provided.
 *
 * Usage:
 * ```kotlin
 * val mock = MockLLMBackend("mock")
 *     .whenCalled(LLMChunk.Text("hello"), LLMChunk.Usage(10, 5), LLMChunk.Done)
 * ```
 */
class MockLLMBackend(
    override val name: String = "mock",
) : LLMBackend {
    private val responses = ArrayDeque<List<LLMChunk>>()
    private val callCount = AtomicInteger(0)
    private val emittedChunks = mutableListOf<LLMChunk>()

    /** Queue a scripted response sequence for the next call(). */
    fun whenCalled(vararg chunks: LLMChunk): MockLLMBackend {
        responses.addLast(chunks.toList())
        return this
    }

    override fun call(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig,
    ): Flow<LLMChunk> =
        flow {
            callCount.incrementAndGet()
            val scripted =
                responses.removeFirstOrNull()
                    ?: error(
                        "MockLLMBackend '$name': call() invoked more times than scripted responses. " +
                            "Add more .whenCalled() sequences.",
                    )
            scripted.forEach { chunk ->
                emittedChunks.add(chunk)
                emit(chunk)
            }
        }

    /** Assert that call() was invoked exactly [n] times. */
    fun assertCallCount(n: Int) {
        val actual = callCount.get()
        check(actual == n) {
            "MockLLMBackend '$name': expected $n call(s) but was called $actual time(s)"
        }
    }

    /** Assert that at least one [LLMChunk.ToolCall] with [toolName] was emitted. */
    fun assertToolCallEmitted(toolName: String) {
        val found = emittedChunks.filterIsInstance<LLMChunk.ToolCall>().any { it.name == toolName }
        check(found) {
            "MockLLMBackend '$name': no ToolCall chunk with name '$toolName' was emitted. " +
                "Emitted tool calls: ${emittedChunks.filterIsInstance<LLMChunk.ToolCall>().map { it.name }}"
        }
    }
}
