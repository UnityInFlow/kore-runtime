package dev.unityinflow.kore.core.internal

import dev.unityinflow.kore.core.ConversationMessage
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.ToolDefinition
import dev.unityinflow.kore.core.port.LLMBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Decorates a primary [LLMBackend] with retry + fallback chain.
 * Usage: `claude() fallbackTo gpt() fallbackTo ollama()`
 *
 * Semaphore: NOT managed here — each underlying backend manages its own semaphore.
 * Retry delay: OUTSIDE the semaphore permit scope to avoid holding permits during backoff.
 */
class ResilientLLMBackend(
    internal val primary: LLMBackend,
    internal val fallbacks: List<LLMBackend> = emptyList(),
    internal val retryPolicy: RetryPolicy = RetryPolicy(),
) : LLMBackend {
    override val name: String = primary.name

    override fun call(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig,
    ): Flow<LLMChunk> =
        flow {
            val backends = listOf(primary) + fallbacks
            var lastException: Throwable? = null

            for (backend in backends) {
                for (attempt in 0 until retryPolicy.maxAttempts) {
                    try {
                        backend.call(messages, tools, config).collect { chunk -> emit(chunk) }
                        return@flow // Success — done
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        lastException = e
                        if (attempt < retryPolicy.maxAttempts - 1) {
                            delay(retryPolicy.delayFor(attempt)) // delay OUTSIDE semaphore
                        }
                    }
                }
            }

            throw lastException ?: RuntimeException("All LLM backends failed")
        }
}

/** Infix function enabling: `claude() fallbackTo gpt() fallbackTo ollama()` — per D-20, CORE-07. */
infix fun LLMBackend.fallbackTo(fallback: LLMBackend): ResilientLLMBackend =
    if (this is ResilientLLMBackend) {
        ResilientLLMBackend(
            primary = this.primary,
            fallbacks = this.fallbacks + fallback,
            retryPolicy = this.retryPolicy,
        )
    } else {
        ResilientLLMBackend(primary = this, fallbacks = listOf(fallback))
    }
