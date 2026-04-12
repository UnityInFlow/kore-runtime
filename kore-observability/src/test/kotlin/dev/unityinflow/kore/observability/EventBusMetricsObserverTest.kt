package dev.unityinflow.kore.observability

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.core.port.EventBus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class EventBusMetricsObserverTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = KoreMetrics(registry)
    private val scope = TestScope()

    private fun makeObserver(eventFlow: MutableSharedFlow<AgentEvent>): EventBusMetricsObserver {
        val eventBus = mockk<EventBus>()
        every { eventBus.subscribe() } returns eventFlow
        return EventBusMetricsObserver(
            eventBus = eventBus,
            metrics = metrics,
            scope = scope,
        )
    }

    @Test
    fun `AgentStarted increments agentsActive to 1`() =
        runTest {
            val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
            val observer = makeObserver(flow)
            observer.start()

            flow.emit(AgentEvent.AgentStarted(agentId = "agent-1", taskId = "task-1"))
            advanceUntilIdle()

            metrics.agentsActive.get() shouldBe 1
        }

    @Test
    fun `AgentStarted then AgentCompleted with Success resets agentsActive and increments run counter`() =
        runTest {
            val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
            val observer = makeObserver(flow)
            observer.start()

            flow.emit(AgentEvent.AgentStarted(agentId = "agent-1", taskId = "task-1"))
            flow.emit(
                AgentEvent.AgentCompleted(
                    agentId = "agent-1",
                    result = AgentResult.Success(output = "done", tokenUsage = TokenUsage(10, 5)),
                ),
            )
            advanceUntilIdle()

            metrics.agentsActive.get() shouldBe 0
            val count =
                registry
                    .find("kore.agent.runs")
                    .tag("result_type", "success")
                    .counter()!!
                    .count()
            count shouldBe 1.0
        }

    @Test
    fun `AgentCompleted with LLMError increments kore_errors counter with llm_error tag`() =
        runTest {
            val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
            val observer = makeObserver(flow)
            observer.start()

            flow.emit(AgentEvent.AgentStarted(agentId = "agent-1", taskId = "task-1"))
            flow.emit(
                AgentEvent.AgentCompleted(
                    agentId = "agent-1",
                    result = AgentResult.LLMError(backend = "claude", cause = RuntimeException("llm fail")),
                ),
            )
            advanceUntilIdle()

            val errorCounter =
                registry
                    .find("kore.errors")
                    .tag("error_type", "llm_error")
                    .counter()
            errorCounter shouldNotBe null
            errorCounter!!.count() shouldBe 1.0
        }

    @Test
    fun `LLMCallCompleted with TokenUsage increments tokens_used counters with correct direction`() =
        runTest {
            val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
            val observer = makeObserver(flow)
            observer.start()

            flow.emit(AgentEvent.AgentStarted(agentId = "agent-1", taskId = "task-1"))
            flow.emit(
                AgentEvent.LLMCallCompleted(
                    agentId = "agent-1",
                    tokenUsage = TokenUsage(inputTokens = 10, outputTokens = 5),
                ),
            )
            advanceUntilIdle()

            val inCount =
                registry
                    .find("kore.tokens.used")
                    .tag("direction", "in")
                    .counter()!!
                    .count()
            val outCount =
                registry
                    .find("kore.tokens.used")
                    .tag("direction", "out")
                    .counter()!!
                    .count()
            inCount shouldBe 10.0
            outCount shouldBe 5.0
        }
}
