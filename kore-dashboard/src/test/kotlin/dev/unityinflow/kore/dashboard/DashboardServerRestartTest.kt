package dev.unityinflow.kore.dashboard

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.internal.InMemoryAuditLog
import dev.unityinflow.kore.core.internal.InProcessEventBus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Unit regression for HI-01 (03-REVIEW.md).
 *
 * Proves the [DashboardServer] SmartLifecycle start → stop → start contract:
 * the second start must install a NEW [kotlinx.coroutines.CoroutineScope]
 * instance and a NEW [EventBusDashboardObserver], so the collector runs on
 * a live (non-cancelled) scope. Pre-fix the single `private val scope` was
 * cancelled by `stop()` and the next `start()` launched collect{} on a dead
 * scope, producing silently empty active-agents snapshots.
 *
 * Entirely unit level — no HTTP, no `testApplication`, no static port.
 * All assertions go through the internal [DashboardServer.scopeForTest] /
 * [DashboardServer.observerForTest] accessors and the observer's `snapshot()`.
 * Port 0 is used so the ephemeral binding cannot collide with a static
 * dashboard port on the CI runner.
 */
class DashboardServerRestartTest {
    private fun newServer() =
        DashboardServer(
            eventBus = InProcessEventBus(),
            auditLog = InMemoryAuditLog(),
            properties =
                DashboardServer.DefaultDashboardProperties(
                    port = 0,
                    path = "/kore",
                    enabled = true,
                ),
        )

    @Test
    fun `start after stop recreates scope and observer and the new observer receives events`(): Unit =
        runBlocking {
            val bus = InProcessEventBus()
            val server =
                DashboardServer(
                    eventBus = bus,
                    auditLog = InMemoryAuditLog(),
                    properties =
                        DashboardServer.DefaultDashboardProperties(
                            port = 0,
                            path = "/kore",
                            enabled = true,
                        ),
                )
            try {
                // Phase 1: first start — scope and observer installed.
                server.start()
                val scope1 = server.scopeForTest()
                val observer1 = server.observerForTest()
                scope1.shouldNotBeNull()
                observer1.shouldNotBeNull()
                scope1.isActive shouldBe true
                server.isRunning() shouldBe true

                // Phase 2: stop — scope cancelled, refs cleared.
                server.stop()
                server.scopeForTest() shouldBe null
                server.observerForTest() shouldBe null
                server.isRunning() shouldBe false
                scope1.isActive shouldBe false

                // Phase 3: second start — MUST install a new scope + observer pair.
                server.start()
                val scope2 = server.scopeForTest()
                val observer2 = server.observerForTest()
                scope2.shouldNotBeNull()
                observer2.shouldNotBeNull()
                scope2.isActive shouldBe true
                // Identity check: the new references are distinct from gen 1.
                (scope2 === scope1) shouldBe false
                (observer2 === observer1) shouldBe false

                // Phase 4: emit via retry-until-observed loop (SharedFlow has no replay).
                repeat(50) {
                    bus.emit(AgentEvent.AgentStarted(agentId = "restart-agent", taskId = "t1"))
                    if (observer2.snapshot().containsKey("restart-agent")) return@repeat
                    delay(20)
                }
                // Final assertion: the NEW observer in the NEW scope received the event.
                observer2.snapshot().containsKey("restart-agent") shouldBe true
            } finally {
                runCatching { server.stop() }
            }
        }

    @Test
    fun `double stop is idempotent`() {
        val server = newServer()
        server.start()
        server.stop()
        server.stop() // must not throw
        server.isRunning() shouldBe false
        server.scopeForTest() shouldBe null
        server.observerForTest() shouldBe null
    }

    @Test
    fun `double start without intervening stop is idempotent`() {
        val server = newServer()
        try {
            server.start()
            val scope1 = server.scopeForTest()
            scope1.shouldNotBeNull()
            server.start() // second call — engine.get() != null early-return
            val scope2 = server.scopeForTest()
            (scope2 === scope1) shouldBe true
            scope1.isActive shouldBe true
            server.isRunning() shouldBe true
        } finally {
            runCatching { server.stop() }
        }
    }
}
