package io.github.unityinflow.kore.core

import io.github.unityinflow.kore.core.port.ActivatedSkill
import io.github.unityinflow.kore.core.port.AuditLog
import io.github.unityinflow.kore.core.port.BudgetEnforcer
import io.github.unityinflow.kore.core.port.EventBus
import io.github.unityinflow.kore.core.port.LLMBackend
import io.github.unityinflow.kore.core.port.NoOpSkillRegistry
import io.github.unityinflow.kore.core.port.SkillRegistry
import io.github.unityinflow.kore.core.port.ToolProvider
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
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
    /**
     * Skill registry called before the first LLM call to inject matching skill
     * prompts as a [ConversationMessage.Role.System] message (D-10). Defaults
     * to [NoOpSkillRegistry] so kore-core has zero runtime dependency on
     * kore-skills.
     */
    private val skillRegistry: SkillRegistry = NoOpSkillRegistry,
    /**
     * Optional OpenTelemetry [Tracer]. When non-null, skill activation is
     * wrapped in a `kore.skill.activate` span (D-11). When null — the default —
     * no span is created; activation still proceeds. Graceful degradation
     * ensures kore-core does not require kore-observability at runtime.
     */
    private val tracer: Tracer? = null,
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

        // D-10 skill injection hook: activate matching skills and prepend
        // their prompts as a System message BEFORE the first LLM call.
        // OBSV-03: when a tracer is supplied, wrap activation in a
        // "kore.skill.activate" span carrying kore.skill.names/count/duration_ms.
        // The span is ALWAYS emitted when a tracer is present, including count=0
        // and even when activateFor throws (D-04 / Pitfall 5). Graceful
        // degradation when tracer is null. kore-core cannot import KoreAttrs from
        // kore-observability, so the literal key strings are used here (KoreAttrs
        // is the mirror source-of-truth — Plan 05-04 adds those constants).
        val userMessage = history.first { it.role == ConversationMessage.Role.User }
        val span =
            tracer
                ?.spanBuilder(SKILL_ACTIVATE_SPAN)
                ?.setParent(Context.current()) // parents under kore.agent.run (OBSV-03)
                ?.startSpan()
        val startNanos = System.nanoTime()
        // Holder is read inside `finally` so attributes are computable even if
        // activateFor throws (default stays empty → count=0, names=[]). val-only.
        val activatedHolder = arrayOfNulls<List<ActivatedSkill>>(1)
        val activated: List<ActivatedSkill> =
            try {
                skillRegistry
                    .activateFor(
                        taskContent = userMessage.content,
                        availableTools = toolDefs.map { it.name },
                    ).also { activatedHolder[0] = it }
            } finally {
                val durMs = (System.nanoTime() - startNanos) / 1_000_000
                val names = activatedHolder[0].orEmpty().map { it.name }
                span?.apply {
                    setAttribute(AttributeKey.stringArrayKey("kore.skill.names"), names)
                    setAttribute(AttributeKey.longKey("kore.skill.count"), names.size.toLong())
                    setAttribute(AttributeKey.longKey("kore.skill.duration_ms"), durMs)
                    end()
                }
            }
        // Span always emits (observability/debuggability); the SkillActivated
        // event below emits only on ≥1 match (broker frugality) — D-07 asymmetry.
        if (activated.isNotEmpty()) {
            history.add(
                0,
                ConversationMessage(
                    role = ConversationMessage.Role.System,
                    content = activated.joinToString("\n\n") { it.prompt },
                ),
            )
            val durMs = (System.nanoTime() - startNanos) / 1_000_000
            eventBus.emit(
                AgentEvent.SkillActivated(
                    agentId = agentId,
                    skillNames = activated.map { it.name },
                    durationMs = durMs,
                ),
            )
        }

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

    private companion object {
        /**
         * Skill-activation span name. kore-core cannot depend on
         * kore-observability, so this literal mirrors `KoreSpans.SKILL_ACTIVATE`
         * (the observability-side source of truth).
         */
        const val SKILL_ACTIVATE_SPAN = "kore.skill.activate"
    }
}
