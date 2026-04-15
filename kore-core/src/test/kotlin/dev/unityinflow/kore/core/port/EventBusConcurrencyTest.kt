package dev.unityinflow.kore.core.port

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.internal.InProcessEventBus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * EVNT-02 validation: 8 concurrent producers × 1000 events each. A fast consumer
 * under runTest virtual time should see at least (8*1000 - 8*64) = 7488 events
 * (DROP_OLDEST worst-case envelope) with no duplicates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventBusConcurrencyTest {
    @Test
    fun `concurrent producers lose no more than 8 times 64 events`() =
        runTest {
            val bus = InProcessEventBus()
            val received = mutableListOf<AgentEvent>()
            val job =
                backgroundScope.launch {
                    bus.subscribe().collect { received += it }
                }
            runCurrent()

            val producers =
                (0 until 8).map { p ->
                    async {
                        repeat(1000) { i ->
                            bus.emit(AgentEvent.AgentStarted(agentId = "p$p-a$i", taskId = "t$i"))
                        }
                    }
                }
            producers.awaitAll()
            advanceUntilIdle()

            // Fast in-memory consumer collects everything; DROP_OLDEST only drops when
            // the buffer is genuinely full. Assert worst-case envelope from D-09.
            received.size shouldBeGreaterThanOrEqual (8 * 1000 - 8 * 64)
            // Assert no duplicates (proves no race producing phantom replays)
            received.map { (it as AgentEvent.AgentStarted).agentId }.toSet().size shouldBe received.size
            job.cancel()
        }
}
