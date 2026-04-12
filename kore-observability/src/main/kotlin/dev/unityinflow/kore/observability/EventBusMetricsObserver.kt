package dev.unityinflow.kore.observability

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.port.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Subscribes to [EventBus] and drives [KoreMetrics] increments from agent lifecycle events (OBSV-05).
 *
 * **agentName lookup:** [AgentEvent.AgentStarted] does not carry the agent name — it has only agentId.
 * [EventBusMetricsObserver] maintains a [ConcurrentHashMap] populated on [AgentEvent.AgentStarted]
 * using the optional [agentNameResolver] (defaults to "unknown").
 * Full agent_name enrichment can be wired in Phase 3 via Spring context injection.
 *
 * **LLM model/backend tags:** [AgentEvent.LLMCallCompleted] does not carry model or backend name.
 * Tags default to "unknown" for now; Phase 3 adds OTel span context correlation.
 *
 * **Memory safety (T-02-10):** [agentNames] is bounded by [maxTrackedAgents]. If the map reaches
 * the limit, the oldest entries are evicted to prevent unbounded growth when [AgentEvent.AgentCompleted]
 * is never received (e.g. due to event bus DROP_OLDEST overflow or cancelled agents).
 *
 * @param eventBus The [EventBus] to subscribe to.
 * @param metrics The [KoreMetrics] instance to drive.
 * @param scope The [CoroutineScope] in which to launch the subscription. Non-blocking — [start] returns immediately.
 * @param agentNameResolver Optional function to resolve a configured agent name from an agentId. Defaults to "unknown".
 * @param maxTrackedAgents Maximum number of concurrent agent entries in the name lookup map before eviction (T-02-10).
 */
class EventBusMetricsObserver(
    private val eventBus: EventBus,
    private val metrics: KoreMetrics,
    private val scope: CoroutineScope,
    private val agentNameResolver: (agentId: String) -> String = { "unknown" },
    private val maxTrackedAgents: Int = 10_000,
) {
    // Tracks agentId -> agentName for event correlation (populated on AgentStarted, removed on AgentCompleted)
    private val agentNames = ConcurrentHashMap<String, String>()

    /**
     * Starts the EventBus subscription in [scope]. Non-blocking — returns immediately.
     * Call once at startup before agents start running.
     */
    fun start() {
        scope.launch {
            eventBus.subscribe().collect { event ->
                when (event) {
                    is AgentEvent.AgentStarted -> {
                        // Evict oldest entries if map is at capacity (T-02-10 DoS mitigation)
                        if (agentNames.size >= maxTrackedAgents) {
                            agentNames.keys.take(agentNames.size - maxTrackedAgents + 1).forEach { agentNames.remove(it) }
                        }
                        val name = agentNameResolver(event.agentId)
                        agentNames[event.agentId] = name
                        metrics.agentsActive.incrementAndGet()
                    }

                    is AgentEvent.AgentCompleted -> {
                        val agentName = agentNames.remove(event.agentId) ?: "unknown"
                        metrics.agentsActive.decrementAndGet()
                        metrics.agentRunCounter(agentName, event.result.typeName()).increment()
                        if (event.result is AgentResult.LLMError || event.result is AgentResult.ToolError) {
                            metrics.errorCounter(agentName, event.result.typeName()).increment()
                        }
                    }

                    is AgentEvent.LLMCallCompleted -> {
                        // agentId present — look up configured name. model/backend unknown until Phase 3.
                        val agentName = agentNames[event.agentId] ?: "unknown"
                        metrics.llmCallCounter(agentName, model = "unknown", backend = "unknown").increment()
                        metrics
                            .tokensUsedCounter(agentName, model = "unknown", direction = "in")
                            .increment(event.tokenUsage.inputTokens.toDouble())
                        metrics
                            .tokensUsedCounter(agentName, model = "unknown", direction = "out")
                            .increment(event.tokenUsage.outputTokens.toDouble())
                    }

                    // ToolCallStarted, ToolCallCompleted, LLMCallStarted: no Micrometer action (D-25)
                    // Per-tool metrics live in OTel spans only — no kore.tool.* counters here.
                    else -> Unit
                }
            }
        }
    }
}
