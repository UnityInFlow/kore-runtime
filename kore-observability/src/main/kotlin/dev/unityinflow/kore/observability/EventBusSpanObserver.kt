package dev.unityinflow.kore.observability

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.port.EventBus
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Subscribes to [EventBus] and creates child OTel spans for LLM calls and tool uses.
 *
 * Implements the 3-level hierarchy: agent run (ObservableAgentRunner) → LLM call (here)
 * → tool use (here, child of LLM call span) — per D-01.
 *
 * Thread safety: [openLlmSpans], [openToolSpans] and start time maps use [ConcurrentHashMap]
 * to guard against concurrent access from coroutines on different threads (T-02-02).
 *
 * Span leak mitigation (T-02-02): AgentCompleted event closes any remaining open spans for
 * the agent, guarding against a LLMCallCompleted/ToolCallCompleted that is never received.
 *
 * OBSV-03 stub: SkillActivated event handling will be added in Phase 3 when kore-skills emits
 * the event. The "kore.skill.activate" span name constant is already defined in KoreSpans.
 */
class EventBusSpanObserver(
    private val eventBus: EventBus,
    private val tracer: KoreTracer,
    private val scope: CoroutineScope,
) {
    // Track open spans by agentId to correlate start/completed events
    private val openLlmSpans = ConcurrentHashMap<String, Span>()
    private val openToolSpans = ConcurrentHashMap<String, Span>()
    private val llmStartTimes = ConcurrentHashMap<String, Long>()
    private val toolStartTimes = ConcurrentHashMap<String, Long>()

    /** Begin collecting events from the [EventBus] and translating them into OTel child spans. */
    fun start() {
        scope.launch {
            eventBus.subscribe().collect { event ->
                when (event) {
                    is AgentEvent.LLMCallStarted -> {
                        llmStartTimes[event.agentId] = System.currentTimeMillis()
                        val span =
                            tracer.tracer
                                .spanBuilder(KoreSpans.LLM_CALL)
                                .setParent(Context.current())
                                .setAttribute(KoreAttrs.AGENT_ID, event.agentId)
                                .setAttribute(KoreAttrs.LLM_BACKEND, event.backend)
                                .startSpan()
                        openLlmSpans[event.agentId] = span
                    }

                    is AgentEvent.LLMCallCompleted -> {
                        val span = openLlmSpans.remove(event.agentId)
                        val startMs = llmStartTimes.remove(event.agentId) ?: 0L
                        span?.let {
                            it.setAttribute(KoreAttrs.LLM_TOKENS_IN, event.tokenUsage.inputTokens.toLong())
                            it.setAttribute(KoreAttrs.LLM_TOKENS_OUT, event.tokenUsage.outputTokens.toLong())
                            it.setAttribute(KoreAttrs.LLM_DURATION_MS, System.currentTimeMillis() - startMs)
                            it.end()
                        }
                    }

                    is AgentEvent.ToolCallStarted -> {
                        val key = event.agentId + event.toolName
                        toolStartTimes[key] = System.currentTimeMillis()
                        val span =
                            tracer.tracer
                                .spanBuilder(KoreSpans.TOOL_USE)
                                .setParent(Context.current())
                                .setAttribute(KoreAttrs.AGENT_ID, event.agentId)
                                .setAttribute(KoreAttrs.TOOL_NAME, event.toolName)
                                // MCP server tracking deferred to Phase 3 when AgentEvent carries it
                                .setAttribute(KoreAttrs.TOOL_MCP_SERVER, "")
                                .startSpan()
                        openToolSpans[key] = span
                    }

                    is AgentEvent.ToolCallCompleted -> {
                        val key = event.agentId + event.toolName
                        val span = openToolSpans.remove(key)
                        val startMs = toolStartTimes.remove(key) ?: 0L
                        span?.let {
                            it.setAttribute(KoreAttrs.TOOL_DURATION_MS, System.currentTimeMillis() - startMs)
                            it.end()
                        }
                    }

                    is AgentEvent.AgentCompleted -> {
                        // Leak guard (T-02-02): close any remaining open spans for this agent
                        openLlmSpans.remove(event.agentId)?.end()
                        llmStartTimes.remove(event.agentId)
                        // Tool spans keyed by agentId+toolName — collect all matching keys
                        openToolSpans.keys
                            .filter { it.startsWith(event.agentId) }
                            .forEach { key ->
                                openToolSpans.remove(key)?.end()
                                toolStartTimes.remove(key)
                            }
                    }

                    else -> Unit // AgentStarted handled by ObservableAgentRunner
                }
            }
        }
    }
}
