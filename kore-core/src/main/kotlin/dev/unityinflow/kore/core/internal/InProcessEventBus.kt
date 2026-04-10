package dev.unityinflow.kore.core.internal

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.port.EventBus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Default in-process [EventBus] using [MutableSharedFlow].
 *
 * Buffer: 64 events with DROP_OLDEST overflow.
 * Dashboard lag MUST NOT backpressure the agent loop — hence DROP_OLDEST.
 * [extraBufferCapacity] and [onBufferOverflow] are explicit to prevent Pitfall 9
 * (missing buffer config causes suspend under load).
 */
class InProcessEventBus : EventBus {
    private val _events =
        MutableSharedFlow<AgentEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override suspend fun emit(event: AgentEvent) {
        _events.emit(event)
    }

    override fun subscribe(): Flow<AgentEvent> = _events.asSharedFlow()
}
