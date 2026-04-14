package dev.unityinflow.kore.dashboard

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.core.internal.InProcessEventBus
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusDashboardObserverTest {
    @Test
    fun `AgentStarted adds agent to snapshot with RUNNING status`() =
        runTest {
            val bus = InProcessEventBus()
            val observer = EventBusDashboardObserver(bus, backgroundScope)
            observer.startCollecting()
            yield() // let collect{} reach its suspension point

            bus.emit(AgentEvent.AgentStarted(agentId = "a1", taskId = "t1"))
            runCurrent()

            val snapshot = observer.snapshot()
            snapshot shouldContainKey "a1"
            snapshot["a1"]!!.status shouldBe AgentStatus.RUNNING
            snapshot["a1"]!!.name shouldBe "a1"
        }

    @Test
    fun `AgentStarted then AgentCompleted removes agent from snapshot`() =
        runTest {
            val bus = InProcessEventBus()
            val observer = EventBusDashboardObserver(bus, backgroundScope)
            observer.startCollecting()
            yield()

            bus.emit(AgentEvent.AgentStarted(agentId = "a1", taskId = "t1"))
            bus.emit(
                AgentEvent.AgentCompleted(
                    agentId = "a1",
                    result = AgentResult.Success(output = "done", tokenUsage = TokenUsage(10, 5)),
                ),
            )
            runCurrent()

            observer.snapshot() shouldNotContainKey "a1"
        }

    @Test
    fun `LLMCallCompleted accumulates tokensUsed on the active agent`() =
        runTest {
            val bus = InProcessEventBus()
            val observer = EventBusDashboardObserver(bus, backgroundScope)
            observer.startCollecting()
            yield()

            bus.emit(AgentEvent.AgentStarted(agentId = "a1", taskId = "t1"))
            bus.emit(
                AgentEvent.LLMCallCompleted(
                    agentId = "a1",
                    tokenUsage = TokenUsage(inputTokens = 100, outputTokens = 50),
                ),
            )
            runCurrent()

            observer.snapshot()["a1"]!!.tokensUsed shouldBe 150L
        }

    @Test
    fun `LLMCallCompleted accumulates across multiple calls`() =
        runTest {
            val bus = InProcessEventBus()
            val observer = EventBusDashboardObserver(bus, backgroundScope)
            observer.startCollecting()
            yield()

            bus.emit(AgentEvent.AgentStarted(agentId = "a1", taskId = "t1"))
            bus.emit(AgentEvent.LLMCallCompleted(agentId = "a1", tokenUsage = TokenUsage(10, 5)))
            bus.emit(AgentEvent.LLMCallCompleted(agentId = "a1", tokenUsage = TokenUsage(20, 8)))
            runCurrent()

            observer.snapshot()["a1"]!!.tokensUsed shouldBe (10L + 5L + 20L + 8L)
        }

    @Test
    fun `DashboardDataService getRecentRuns with null AuditLog returns emptyList`() =
        runTest {
            val service = DashboardDataService(auditLog = null)
            service.getRecentRuns() shouldBe emptyList()
            service.hasStorage() shouldBe false
        }

    @Test
    fun `DashboardDataService getCostSummary with null AuditLog returns emptyList`() =
        runTest {
            val service = DashboardDataService(auditLog = null)
            service.getCostSummary() shouldBe emptyList()
        }

    @Test
    fun `ME-02 capacity eviction bounds the active-agents map to maxTrackedAgents`() =
        runTest {
            val bus = InProcessEventBus()
            // Small cap so we can hit it in the test.
            val observer = EventBusDashboardObserver(bus, backgroundScope, maxTrackedAgents = 3)
            observer.startCollecting()
            yield()

            // Emit 5 AgentStarted events; map must not exceed 3.
            // The most-recently-emitted one (a5) must always be present because
            // eviction happens BEFORE the new insert, so the newest entry cannot
            // be evicted in the same step.
            bus.emit(AgentEvent.AgentStarted(agentId = "a1", taskId = "t1"))
            bus.emit(AgentEvent.AgentStarted(agentId = "a2", taskId = "t2"))
            bus.emit(AgentEvent.AgentStarted(agentId = "a3", taskId = "t3"))
            bus.emit(AgentEvent.AgentStarted(agentId = "a4", taskId = "t4"))
            bus.emit(AgentEvent.AgentStarted(agentId = "a5", taskId = "t5"))
            runCurrent()

            val snapshot = observer.snapshot()
            // Cap is 3 — must not exceed.
            (snapshot.size <= 3) shouldBe true
            snapshot shouldContainKey "a5"
        }

    @Test
    fun `ME-06 startCollecting is idempotent — second call is a no-op`() =
        runTest {
            val bus = InProcessEventBus()
            val observer = EventBusDashboardObserver(bus, backgroundScope)
            observer.startCollecting()
            // Second call MUST NOT launch a second collector.
            observer.startCollecting()
            yield()

            // Emit one LLMCallCompleted — if two collectors were running,
            // tokens would be double-counted.
            bus.emit(AgentEvent.AgentStarted(agentId = "a1", taskId = "t1"))
            bus.emit(
                AgentEvent.LLMCallCompleted(
                    agentId = "a1",
                    tokenUsage = TokenUsage(inputTokens = 10, outputTokens = 5),
                ),
            )
            runCurrent()

            observer.snapshot()["a1"]!!.tokensUsed shouldBe 15L
        }

    @Test
    fun `cancelling observer scope stops processing further events`() =
        runTest {
            val bus = InProcessEventBus()
            // Use backgroundScope and cancel its children explicitly between emits.
            val observer = EventBusDashboardObserver(bus, backgroundScope)
            observer.startCollecting()
            yield()

            bus.emit(AgentEvent.AgentStarted(agentId = "a1", taskId = "t1"))
            runCurrent()
            observer.snapshot() shouldContainKey "a1"

            // Cancel all collectors. After cancellation, new events must not mutate the map.
            backgroundScope.coroutineContext[Job]!!.cancelChildren()
            yield()

            bus.emit(AgentEvent.AgentStarted(agentId = "a2", taskId = "t2"))
            runCurrent()

            // a2 was never observed because the collector was cancelled.
            observer.snapshot() shouldNotContainKey "a2"
        }
}
