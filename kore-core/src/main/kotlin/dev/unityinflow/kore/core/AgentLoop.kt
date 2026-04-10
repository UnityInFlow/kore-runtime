package dev.unityinflow.kore.core

import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.core.port.BudgetEnforcer
import dev.unityinflow.kore.core.port.EventBus
import dev.unityinflow.kore.core.port.LLMBackend
import dev.unityinflow.kore.core.port.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList

/**
 * The ReAct agent loop. Drives: task intake → LLM call → tool use → result → loop.
 *
 * INVARIANT: [run] NEVER throws. All failures are [AgentResult] variants.
 * Only [CancellationException] is re-thrown (structured concurrency requirement — T-03-03).
 *
 * Budget check (T-03-02): [BudgetEnforcer.checkBudget] is called before each LLM call.
 * History truncation (T-03-02): loop runs at most [LLMConfig.maxHistoryMessages] iterations.
 * Parallel tool dispatch (D-22): all tool calls in one LLM response dispatched concurrently.
 */
class AgentLoop(
    private val llmBackend: LLMBackend,
    private val toolProviders: List<ToolProvider>,
    private val budgetEnforcer: BudgetEnforcer,
    private val eventBus: EventBus,
    private val auditLog: AuditLog,
    private val config: LLMConfig,
) {
    /**
     * Run the agent loop for the given [task].
     *
     * @return [AgentResult] — never throws (except [CancellationException]).
     */
    suspend fun run(task: AgentTask): AgentResult {
        val agentId = task.id
        val history = mutableListOf<ConversationMessage>()
        var accumulatedUsage = TokenUsage(0, 0)

        // Build initial tool list from all providers
        val toolDefs: List<ToolDefinition> =
            buildList {
                toolProviders.forEach { provider ->
                    addAll(provider.listTools())
                }
            }

        // Add user message to history
        history.add(ConversationMessage(role = ConversationMessage.Role.User, content = task.input))

        eventBus.emit(AgentEvent.AgentStarted(agentId = agentId, taskId = task.id))

        return try {
            runLoop(agentId, history, toolDefs, accumulatedUsage)
        } catch (e: CancellationException) {
            throw e // ALWAYS re-throw CancellationException (T-03-03, D-21)
        } catch (e: Throwable) {
            AgentResult.LLMError(backend = llmBackend.name, cause = e)
        }.also { result ->
            auditLog.recordAgentRun(agentId, task, result)
            eventBus.emit(AgentEvent.AgentCompleted(agentId = agentId, result = result))
        }
    }

    private suspend fun runLoop(
        agentId: String,
        history: MutableList<ConversationMessage>,
        toolDefs: List<ToolDefinition>,
        initialUsage: TokenUsage,
    ): AgentResult {
        var accumulatedUsage = initialUsage

        repeat(config.maxHistoryMessages) {
            // Budget check before each LLM call (per D-25, BUDG-04, T-03-02)
            if (!budgetEnforcer.checkBudget(agentId)) {
                return AgentResult.BudgetExceeded(
                    spent = budgetEnforcer.getUsage(agentId),
                    limit = config.maxTokens.toLong(),
                )
            }

            eventBus.emit(AgentEvent.LLMCallStarted(agentId = agentId, backend = llmBackend.name))

            // Collect all chunks from the LLM streaming response
            val chunks: List<LLMChunk> =
                try {
                    llmBackend.call(history, toolDefs, config).toList()
                } catch (e: CancellationException) {
                    throw e // ALWAYS re-throw CancellationException
                } catch (e: Throwable) {
                    return AgentResult.LLMError(backend = llmBackend.name, cause = e)
                }

            // Extract usage and accumulate
            val usageChunk = chunks.filterIsInstance<LLMChunk.Usage>().firstOrNull()
            val callUsage =
                if (usageChunk != null) {
                    TokenUsage(usageChunk.inputTokens, usageChunk.outputTokens)
                } else {
                    TokenUsage(0, 0)
                }
            accumulatedUsage = accumulatedUsage + callUsage
            budgetEnforcer.recordUsage(agentId, callUsage)
            eventBus.emit(AgentEvent.LLMCallCompleted(agentId = agentId, tokenUsage = callUsage))

            // Extract text content for assistant message
            val textContent = chunks.filterIsInstance<LLMChunk.Text>().joinToString("") { it.content }

            // Extract tool calls
            val toolCallChunks = chunks.filterIsInstance<LLMChunk.ToolCall>()

            if (toolCallChunks.isEmpty()) {
                // No tool calls → agent is done
                history.add(
                    ConversationMessage(
                        role = ConversationMessage.Role.Assistant,
                        content = textContent,
                    ),
                )
                return AgentResult.Success(output = textContent, tokenUsage = accumulatedUsage)
            }

            // Add assistant message with tool calls indicator
            history.add(
                ConversationMessage(
                    role = ConversationMessage.Role.Assistant,
                    content = textContent,
                ),
            )

            // Parallel tool dispatch (per D-22, T-03-01: arguments passed as-is from LLM)
            val toolCalls =
                toolCallChunks.map { chunk ->
                    ToolCall(id = chunk.id, name = chunk.name, arguments = chunk.arguments)
                }

            val toolResults: List<ToolResult> =
                try {
                    coroutineScope {
                        toolCalls
                            .map { call ->
                                async {
                                    eventBus.emit(AgentEvent.ToolCallStarted(agentId = agentId, toolName = call.name))
                                    val provider =
                                        findProvider(call.name)
                                            ?: return@async ToolResult(
                                                toolCallId = call.id,
                                                content = "Tool '${call.name}' not found",
                                                isError = true,
                                            )
                                    try {
                                        val result = provider.callTool(call)
                                        eventBus.emit(AgentEvent.ToolCallCompleted(agentId, call.name, isError = result.isError))
                                        result
                                    } catch (e: CancellationException) {
                                        throw e // ALWAYS re-throw CancellationException
                                    } catch (e: Throwable) {
                                        eventBus.emit(AgentEvent.ToolCallCompleted(agentId, call.name, isError = true))
                                        ToolResult(
                                            toolCallId = call.id,
                                            content = e.message ?: "Tool error",
                                            isError = true,
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                } catch (e: CancellationException) {
                    throw e // ALWAYS re-throw CancellationException
                } catch (e: Throwable) {
                    return AgentResult.ToolError(toolName = "unknown", cause = e)
                }

            // Check if any tool call errored — return ToolError for the first failure
            toolResults.firstOrNull { it.isError }?.let { errorResult ->
                return AgentResult.ToolError(
                    toolName = toolCalls.find { it.id == errorResult.toolCallId }?.name ?: "unknown",
                    cause = RuntimeException(errorResult.content),
                )
            }

            // Append tool results to history
            toolResults.forEach { result ->
                history.add(
                    ConversationMessage(
                        role = ConversationMessage.Role.Tool,
                        content = result.content,
                        toolCallId = result.toolCallId,
                    ),
                )
            }
            // Continue loop
        }

        // Max iterations reached — treat as success with last text
        val lastAssistantMessage = history.lastOrNull { it.role == ConversationMessage.Role.Assistant }
        return AgentResult.Success(
            output = lastAssistantMessage?.content ?: "",
            tokenUsage = accumulatedUsage,
        )
    }

    private suspend fun findProvider(toolName: String): ToolProvider? =
        toolProviders.firstOrNull { provider ->
            provider.listTools().any { it.name == toolName }
        }
}
