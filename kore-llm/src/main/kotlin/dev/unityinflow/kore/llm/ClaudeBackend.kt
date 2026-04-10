package dev.unityinflow.kore.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.models.MessageCreateParams
import com.anthropic.models.MessageParam
import com.anthropic.models.Model
import com.anthropic.models.Tool
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
 * LLM backend adapter for Anthropic Claude using the official Anthropic Java SDK 0.1.0.
 *
 * API keys MUST be provided via constructor — never logged or included in LLMChunk content (T-05-01).
 * All SDK calls are wrapped in [withContext](Dispatchers.IO) to prevent thread pool starvation (T-05-03).
 * [semaphore] is per-backend (not shared with other backends) — per D-18 and Pitfall 11 (T-05-04).
 *
 * No provider-specific types (AnthropicClient, ContentBlock, etc.) escape this adapter
 * into LLMChunk — per Pitfall 5 and T-05-05.
 */
class ClaudeBackend(
    private val client: AnthropicClient,
    private val defaultModel: String = "claude-3-5-sonnet-20241022",
    private val semaphore: Semaphore = Semaphore(10),
) : LLMBackend {

    override val name: String = "claude"

    override fun call(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig,
    ): Flow<LLMChunk> = flow {
        val systemPrompt = messages
            .filter { it.role == ConversationMessage.Role.System }
            .joinToString("\n") { it.content }

        val anthropicMessages = messages
            .filter { it.role != ConversationMessage.Role.System }
            .map { it.toAnthropicMessageParam() }

        val anthropicTools = tools.map { tool ->
            Tool.builder()
                .name(tool.name)
                .description(tool.description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .type(Tool.InputSchema.Type.OBJECT)
                        .build(),
                )
                .build()
        }

        // Semaphore acquired only around the HTTP call — not around retry delay (Pitfall 11)
        val response = semaphore.withPermit {
            // All SDK I/O inside Dispatchers.IO to prevent thread starvation (Pitfall 1, T-05-03)
            withContext(Dispatchers.IO) {
                val paramsBuilder = MessageCreateParams.builder()
                    .model(Model.of(config.model.ifBlank { defaultModel }))
                    .maxTokens(config.maxTokens.toLong())
                    .messages(anthropicMessages)

                if (systemPrompt.isNotBlank()) paramsBuilder.system(systemPrompt)
                if (anthropicTools.isNotEmpty()) paramsBuilder.tools(anthropicTools)

                client.messages().create(paramsBuilder.build())
            }
        }

        // Translate provider-specific response to canonical LLMChunk sequence (Pitfall 5, T-05-05)
        // Using isText()/isToolUse() pattern as required by SDK 0.1.0 (not instanceof)
        for (block in response.content()) {
            when {
                block.isText() -> emit(LLMChunk.Text(block.asText().text()))
                block.isToolUse() -> {
                    val toolUse = block.asToolUse()
                    emit(
                        LLMChunk.ToolCall(
                            id = toolUse.id(),
                            name = toolUse.name(),
                            // _input() returns JsonValue — convert to JSON string (Pitfall 5)
                            arguments = toolUse._input().toString(),
                        ),
                    )
                }
                // Unknown content block types are silently ignored per SDK guidance
            }
        }

        val usage = response.usage()
        emit(
            LLMChunk.Usage(
                inputTokens = usage.inputTokens().toInt(),
                outputTokens = usage.outputTokens().toInt(),
            ),
        )
        emit(LLMChunk.Done)
    }

    private fun ConversationMessage.toAnthropicMessageParam(): MessageParam {
        val role = when (this.role) {
            ConversationMessage.Role.User, ConversationMessage.Role.Tool -> MessageParam.Role.USER
            ConversationMessage.Role.Assistant -> MessageParam.Role.ASSISTANT
            // System messages filtered out before this is called
            ConversationMessage.Role.System -> MessageParam.Role.USER
        }
        return MessageParam.builder()
            .role(role)
            .content(content)
            .build()
    }
}
