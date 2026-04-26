package dev.unityinflow.kore.llm

import com.openai.client.OpenAIClient
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import dev.unityinflow.kore.core.ConversationMessage
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.ToolDefinition
import dev.unityinflow.kore.core.port.LLMBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * LLM backend adapter for OpenAI GPT models using the official OpenAI Java SDK 4.30.0.
 *
 * API keys MUST be provided via constructor — never logged or included in LLMChunk content (T-05-01).
 * All SDK calls are wrapped in [withContext](Dispatchers.IO) to prevent thread pool starvation (T-05-03).
 * [semaphore] is per-backend (not shared with other backends) — per D-18 and Pitfall 11 (T-05-04).
 *
 * No provider-specific types escape this adapter into LLMChunk — per Pitfall 5 and T-05-05.
 */
class OpenAiBackend(
    private val client: OpenAIClient,
    private val defaultModel: String = "gpt-4o",
    private val semaphore: Semaphore = Semaphore(10),
) : LLMBackend {
    override val name: String = "gpt"

    override fun call(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig,
    ): Flow<LLMChunk> =
        flow {
            val openAiMessages = messages.map { it.toChatCompletionMessageParam() }

            val openAiTools =
                tools.map { tool ->
                    ChatCompletionTool.ofFunction(
                        ChatCompletionFunctionTool
                            .builder()
                            .function(
                                FunctionDefinition
                                    .builder()
                                    .name(tool.name)
                                    .description(tool.description)
                                    .parameters(FunctionParameters.builder().build())
                                    .build(),
                            ).build(),
                    )
                }

            // Semaphore acquired only around the HTTP call — not around retry delay (Pitfall 11)
            val completion =
                semaphore.withPermit {
                    // All SDK I/O inside Dispatchers.IO to prevent thread starvation (Pitfall 1, T-05-03)
                    withContext(Dispatchers.IO) {
                        val paramsBuilder =
                            ChatCompletionCreateParams
                                .builder()
                                .model(config.model.ifBlank { defaultModel })
                                .maxCompletionTokens(config.maxTokens.toLong())
                                .messages(openAiMessages)

                        if (openAiTools.isNotEmpty()) paramsBuilder.tools(openAiTools)

                        client.chat().completions().create(paramsBuilder.build())
                    }
                }

            // Translate provider-specific response to canonical LLMChunk sequence (Pitfall 5, T-05-05)
            // Convert Optional to nullable Kotlin types before emitting — ifPresent() is not suspend-safe
            val choice = completion.choices().firstOrNull()
            if (choice != null) {
                val message = choice.message()

                // Emit text content if present
                val text = message.content().orElse(null)
                if (!text.isNullOrEmpty()) {
                    emit(LLMChunk.Text(text))
                }

                // Emit tool calls — arguments are already JSON strings in the OpenAI SDK
                val toolCalls = message.toolCalls().orElse(null)
                if (toolCalls != null) {
                    for (toolCall in toolCalls) {
                        if (toolCall.isFunction()) {
                            val fn = toolCall.asFunction()
                            emit(
                                LLMChunk.ToolCall(
                                    id = fn.id(),
                                    name = fn.function().name(),
                                    // arguments is already a JSON string — no conversion needed (Pitfall 5)
                                    arguments = fn.function().arguments(),
                                ),
                            )
                        }
                    }
                }
            }

            // Emit usage if present — convert Optional to nullable before emit
            val usage = completion.usage().orElse(null)
            if (usage != null) {
                emit(
                    LLMChunk.Usage(
                        inputTokens = usage.promptTokens().toInt(),
                        outputTokens = usage.completionTokens().toInt(),
                    ),
                )
            }

            emit(LLMChunk.Done)
        }

    private fun ConversationMessage.toChatCompletionMessageParam(): ChatCompletionMessageParam =
        when (role) {
            ConversationMessage.Role.System ->
                ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam
                        .builder()
                        .content(content)
                        .build(),
                )
            ConversationMessage.Role.User ->
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam
                        .builder()
                        .content(content)
                        .build(),
                )
            ConversationMessage.Role.Assistant ->
                ChatCompletionMessageParam.ofAssistant(
                    com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
                        .builder()
                        .content(content)
                        .build(),
                )
            ConversationMessage.Role.Tool ->
                ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam
                        .builder()
                        .content(content)
                        .toolCallId(toolCallId ?: "")
                        .build(),
                )
        }
}
