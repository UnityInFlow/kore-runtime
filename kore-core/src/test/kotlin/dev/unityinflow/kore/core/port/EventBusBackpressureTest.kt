package dev.unityinflow.kore.core.port

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.internal.InProcessEventBus
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * EVNT-01 / EVNT-02 validation: InProcessEventBus emit() must never suspend the
 * producer even when a downstream consumer is slow. Closes Pitfall 1 (missing
 * buffer config causes suspend under load) from 04-RESEARCH.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventBusBackpressureTest {
    @Test
    fun `emit never suspends even with slow consumer`() =
        runTest {
            val bus = InProcessEventBus()
            val received = mutableListOf<AgentEvent>()
            val job =
                backgroundScope.launch {
                    bus.subscribe().collect {
                        delay(100.milliseconds)
                        received += it
                    }
                }
            runCurrent() // let collect start

            val producerStart = testScheduler.currentTime
            repeat(200) { i ->
                bus.emit(AgentEvent.AgentStarted(agentId = "a$i", taskId = "t$i"))
            }
            val producerElapsed = testScheduler.currentTime - producerStart
            producerElapsed shouldBe 0L // virtual time unchanged — producer never suspended

            advanceTimeBy(10.seconds)
            runCurrent()
            received.size shouldBeInRange (1..65) // 64 buffer + 1 in-flight, DROP_OLDEST
            job.cancel()
        }
}
