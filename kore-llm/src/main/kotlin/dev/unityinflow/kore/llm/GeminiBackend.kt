package dev.unityinflow.kore.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatLanguageModel
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
 * LLM backend adapter for Google Gemini using LangChain4j 0.36.1 as HTTP transport only.
 *
 * LangChain4j is an implementation detail — no LangChain4j types (ChatLanguageModel,
 * AiMessage, ToolExecutionRequest) escape into LLMChunk (T-05-05, Pitfall 5).
 *
 * All LangChain4j blocking calls are wrapped in [withContext](Dispatchers.IO) (T-05-03, Pitfall 1).
 * API key MUST be provided via constructor — never logged (T-05-01).
 * [semaphore] is per-backend and injected as constructor parameter (T-05-04).
 *
 * The [chatModel] parameter is injectable for testing — use the [gemini] factory function
 * in production to build a GoogleAiGeminiChatModel with the correct apiKey and modelName.
 */
class GeminiBackend(
    internal val chatModel: ChatLanguageModel,
    private val semaphore: Semaphore = Semaphore(10),
) : LLMBackend {

    override val name: String = "gemini"

    override fun call(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig,
    ): Flow<LLMChunk> = flow {
        val langChainMessages = messages.toLangChain4jMessages()
        val langChainTools = tools.toLangChain4jToolSpecs()

        // Semaphore acquired only around the HTTP call — not around retry delay (Pitfall 11)
        val response = semaphore.withPermit {
            // All LangChain4j I/O inside Dispatchers.IO to prevent thread starvation (Pitfall 1)
            withContext(Dispatchers.IO) {
                if (langChainTools.isNotEmpty()) {
                    chatModel.generate(langChainMessages, langChainTools)
                } else {
                    chatModel.generate(langChainMessages)
                }
            }
        }

        // Translate LangChain4j response to canonical LLMChunk sequence (Pitfall 5, T-05-05)
        // No LangChain4j types (AiMessage, ToolExecutionRequest) escape this boundary
        val aiMessage: AiMessage = response.content()
        if (aiMessage.hasToolExecutionRequests()) {
            for (toolReq in aiMessage.toolExecutionRequests()) {
                emit(
                    LLMChunk.ToolCall(
                        id = toolReq.id() ?: "",
                        name = toolReq.name(),
                        // arguments() returns a JSON string already (Pitfall 5)
                        arguments = toolReq.arguments() ?: "{}",
                    ),
                )
            }
        } else {
            val text = aiMessage.text()
            if (!text.isNullOrEmpty()) {
                emit(LLMChunk.Text(text))
            }
        }

        // Emit token usage if available
        val tokenUsage = response.tokenUsage()
        if (tokenUsage != null) {
            emit(
                LLMChunk.Usage(
                    inputTokens = tokenUsage.inputTokenCount() ?: 0,
                    outputTokens = tokenUsage.outputTokenCount() ?: 0,
                ),
            )
        }

        emit(LLMChunk.Done)
    }

    private fun List<ConversationMessage>.toLangChain4jMessages(): List<ChatMessage> =
        map { msg ->
            when (msg.role) {
                ConversationMessage.Role.System -> SystemMessage.from(msg.content)
                ConversationMessage.Role.User -> UserMessage.from(msg.content)
                ConversationMessage.Role.Assistant -> AiMessage.from(msg.content)
                ConversationMessage.Role.Tool ->
                    ToolExecutionResultMessage.from(
                        msg.toolCallId ?: "",
                        "",
                        msg.content,
                    )
            }
        }

    private fun List<ToolDefinition>.toLangChain4jToolSpecs(): List<ToolSpecification> =
        map { tool ->
            ToolSpecification.builder()
                .name(tool.name)
                .description(tool.description)
                .build()
        }
}
