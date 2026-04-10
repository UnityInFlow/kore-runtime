package dev.unityinflow.kore.llm

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import kotlinx.coroutines.sync.Semaphore

/**
 * DSL factory functions for LLM backends.
 *
 * Usage in the `agent { }` DSL:
 * ```kotlin
 * val runner = agent("my-agent") {
 *     model = claude(apiKey = System.getenv("ANTHROPIC_API_KEY"))
 * }
 * ```
 *
 * API keys are passed explicitly — callers are responsible for retrieving keys from
 * environment variables. Keys are NEVER read from the environment inside these functions.
 * Keys are NEVER logged (T-05-01).
 */

/**
 * Creates a [ClaudeBackend] using the Anthropic Java SDK 0.1.0.
 *
 * @param apiKey Anthropic API key — retrieve from environment: `System.getenv("ANTHROPIC_API_KEY")`
 * @param model Default model identifier (default: claude-3-5-sonnet-20241022)
 * @param maxConcurrency Maximum concurrent requests to Anthropic per [Semaphore] (T-05-04)
 */
fun claude(
    apiKey: String,
    model: String = "claude-3-5-sonnet-20241022",
    maxConcurrency: Int = 10,
): ClaudeBackend {
    val client = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()
    return ClaudeBackend(
        client = client,
        defaultModel = model,
        semaphore = Semaphore(maxConcurrency),
    )
}

/**
 * Creates an [OpenAiBackend] using the OpenAI Java SDK 4.30.0.
 *
 * @param apiKey OpenAI API key — retrieve from environment: `System.getenv("OPENAI_API_KEY")`
 * @param model Default model identifier (default: gpt-4o)
 * @param maxConcurrency Maximum concurrent requests to OpenAI per [Semaphore] (T-05-04)
 */
fun gpt(
    apiKey: String,
    model: String = "gpt-4o",
    maxConcurrency: Int = 10,
): OpenAiBackend {
    val client = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .build()
    return OpenAiBackend(
        client = client,
        defaultModel = model,
        semaphore = Semaphore(maxConcurrency),
    )
}

/**
 * Creates an [OllamaBackend] using LangChain4j 0.26.1 as HTTP transport.
 *
 * @param baseUrl Ollama server base URL (default: http://localhost:11434)
 * @param model Model name as registered in Ollama (default: llama3)
 * @param maxConcurrency Maximum concurrent requests — default 2 (local model, limited parallelism)
 */
fun ollama(
    baseUrl: String = "http://localhost:11434",
    model: String = "llama3",
    maxConcurrency: Int = 2,
): OllamaBackend {
    val chatModel = OllamaChatModel.builder()
        .baseUrl(baseUrl)
        .modelName(model)
        .build()
    return OllamaBackend(
        chatModel = chatModel,
        semaphore = Semaphore(maxConcurrency),
    )
}

/**
 * Creates a [GeminiBackend] using LangChain4j 0.36.1 as HTTP transport.
 *
 * @param apiKey Google AI Studio API key — retrieve from env: `System.getenv("GEMINI_API_KEY")`
 * @param model Gemini model name (default: gemini-1.5-flash)
 * @param maxConcurrency Maximum concurrent requests per [Semaphore] (T-05-04)
 */
fun gemini(
    apiKey: String,
    model: String = "gemini-1.5-flash",
    maxConcurrency: Int = 10,
): GeminiBackend {
    val chatModel = GoogleAiGeminiChatModel.builder()
        .apiKey(apiKey)
        .modelName(model)
        .build()
    return GeminiBackend(
        chatModel = chatModel,
        semaphore = Semaphore(maxConcurrency),
    )
}
