package dev.unityinflow.kore.core.port

import dev.unityinflow.kore.core.AgentEvent
import kotlinx.coroutines.flow.Flow

/**
 * Port interface for agent lifecycle event publishing.
 *
 * Default implementation: InProcessEventBus using MutableSharedFlow
 * with `extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST`.
 * Dashboard lag MUST NOT backpressure the agent loop — hence DROP_OLDEST.
 */
interface EventBus {
    /** Emit an event to all subscribers. Never suspends if buffer is configured correctly. */
    suspend fun emit(event: AgentEvent)

    /** Subscribe to all events. Returns a cold Flow for each subscriber. */
    fun subscribe(): Flow<AgentEvent>
}
