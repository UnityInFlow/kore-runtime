package dev.unityinflow.kore.dashboard

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.port.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Subscribes to [EventBus] and maintains a [ConcurrentHashMap] of active
 * agents for the `/kore/fragments/active-agents` HTMX fragment (D-25).
 *
 * **Memory safety (T-03-11):** the active-agents map is bounded by
 * [maxTrackedAgents]. If `AgentCompleted` is never emitted (e.g. due to
 * `DROP_OLDEST` event-bus overflow), the oldest entries are evicted on the
 * next `AgentStarted` to prevent unbounded growth.
 *
 * **Lifecycle:** an external [CoroutineScope] is injected so callers control
 * cancellation. [DashboardServer] passes its own application scope; tests pass
 * `runTest`'s `backgroundScope` so the infinite collect loop is cancelled at
 * test end. Mirrors the `EventBusMetricsObserver` pattern from Phase 02-03 —
 * no internal `var Job?` field needed.
 *
 * @param eventBus the [EventBus] to subscribe to.
 * @param scope the [CoroutineScope] in which the subscription is launched.
 * @param maxTrackedAgents upper bound on the active-agents map (T-03-11).
 */
class EventBusDashboardObserver(
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val maxTrackedAgents: Int = 10_000,
) {
    private val activeAgents = ConcurrentHashMap<String, AgentState>()

    // ME-06: idempotency guard — startCollecting() must not launch a second
    // collector on the same scope. Double-collection double-counts tokens.
    private val started = AtomicBoolean(false)

    /**
     * Launches the EventBus subscription in [scope]. Non-blocking — returns
     * immediately. Call once at startup before the dashboard starts serving
     * requests so the first `/kore/fragments/active-agents` poll has data.
     *
     * Idempotent (ME-06): a second call on the same instance is a no-op.
     */
    fun startCollecting() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            eventBus.subscribe().collect { event ->
                when (event) {
                    is AgentEvent.AgentStarted -> {
                        // ME-02: evict OLDEST entries deterministically by
                        // AgentState.startedAt. ConcurrentHashMap.keys has no
                        // defined order, so the previous `.keys.take(N)` could
                        // evict arbitrary (even newly-inserted) agents while
                        // long-running zombies stuck around forever.
                        // O(N log N) sort only fires when at capacity.
                        if (activeAgents.size >= maxTrackedAgents) {
                            val victims =
                                activeAgents.entries
                                    .sortedBy { it.value.startedAt }
                                    .take(activeAgents.size - maxTrackedAgents + 1)
                                    .map { it.key }
                            victims.forEach { activeAgents.remove(it) }
                        }
                        activeAgents[event.agentId] =
                            AgentState(
                                name = event.agentId,
                                status = AgentStatus.RUNNING,
                                startedAt = Instant.now(),
                            )
                    }

                    is AgentEvent.AgentCompleted -> {
                        activeAgents.remove(event.agentId)
                    }

                    is AgentEvent.LLMCallCompleted -> {
                        activeAgents.compute(event.agentId) { _, state ->
                            state?.copy(
                                tokensUsed = state.tokensUsed + event.tokenUsage.totalTokens.toLong(),
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    /** Immutable snapshot of currently active agents. Safe to read from any thread. */
    fun snapshot(): Map<String, AgentState> = activeAgents.toMap()
}
