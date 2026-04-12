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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

class EventBusMetricsObserverTest {
    /**
     * Creates an [EventBusMetricsObserver] with a mocked [EventBus] backed by [eventFlow].
     *
     * Uses [backgroundScope] from [runTest] so the infinite [collect] loop is cancelled
     * when the test ends. This prevents [runTest] from hanging waiting for it to finish.
     * [yield] after [EventBusMetricsObserver.start] lets the launched coroutine reach its
     * suspension point in [collect] before test events are emitted.
     */
    private fun makeObserver(
        eventFlow: MutableSharedFlow<AgentEvent>,
        metrics: KoreMetrics,
        scope: CoroutineScope,
    ): EventBusMetricsObserver {
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
            val registry = SimpleMeterRegistry()
            val metrics = KoreMetrics(registry)
            val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
            // backgroundScope is cancelled at test end — collect{} loop never finishes but that is fine
            val observer = makeObserver(flow, metrics, backgroundScope)
            observer.start()
            yield() // let collect{} coroutine reach its suspension point

            flow.emit(AgentEvent.AgentStarted(agentId = "agent-1", taskId = "task-1"))
            runCurrent()

            metrics.agentsActive.get() shouldBe 1
        }

    @Test
    fun `AgentStarted then AgentCompleted with Success resets agentsActive and increments run counter`() =
        runTest {
            val registry = SimpleMeterRegistry()
            val metrics = KoreMetrics(registry)
            val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
            val observer = makeObserver(flow, metrics, backgroundScope)
            observer.start()
            yield()

            flow.emit(AgentEvent.AgentStarted(agentId = "agent-1", taskId = "task-1"))
            flow.emit(
                AgentEvent.AgentCompleted(
                    agentId = "agent-1",
                    result = AgentResult.Success(output = "done", tokenUsage = TokenUsage(10, 5)),
                ),
            )
            runCurrent()

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
            val registry = SimpleMeterRegistry()
            val metrics = KoreMetrics(registry)
            val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
            val observer = makeObserver(flow, metrics, backgroundScope)
            observer.start()
            yield()

            flow.emit(AgentEvent.AgentStarted(agentId = "agent-1", taskId = "task-1"))
            flow.emit(
                AgentEvent.AgentCompleted(
                    agentId = "agent-1",
                    result = AgentResult.LLMError(backend = "claude", cause = RuntimeException("llm fail")),
                ),
            )
            runCurrent()

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
            val registry = SimpleMeterRegistry()
            val metrics = KoreMetrics(registry)
            val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
            val observer = makeObserver(flow, metrics, backgroundScope)
            observer.start()
            yield()

            flow.emit(AgentEvent.AgentStarted(agentId = "agent-1", taskId = "task-1"))
            flow.emit(
                AgentEvent.LLMCallCompleted(
                    agentId = "agent-1",
                    tokenUsage = TokenUsage(inputTokens = 10, outputTokens = 5),
                ),
            )
            runCurrent()

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
