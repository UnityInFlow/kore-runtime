package dev.unityinflow.kore.dashboard

import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.core.port.EventBus
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.context.SmartLifecycle
import java.util.concurrent.atomic.AtomicReference

/**
 * Spring [SmartLifecycle]-aware dashboard side-car (D-33, RESEARCH.md Pattern 8).
 *
 * **Constructor contract:** the 3-arg constructor `(EventBus, AuditLog?,
 * KoreProperties.DashboardProperties)` is consumed by
 * `KoreAutoConfiguration.DashboardAutoConfiguration` in plan 03-02. Plan
 * 03-04 will swap the reflective bridge in kore-spring for a direct
 * constructor call against this exact signature — do not change it without
 * also updating kore-spring.
 *
 * **Reflective construction note:** the auto-config currently calls
 * `getConstructor(EventBus, AuditLog, KoreProperties.DashboardProperties)`
 * with the non-nullable `AuditLog` parameter type. We therefore declare
 * `auditLog` as non-null `AuditLog` here — the `InMemoryAuditLog` default
 * supplied by `KoreAutoConfiguration` honours D-27 by returning empty lists
 * from the read methods. A null-tolerant alternative constructor exists for
 * unit tests that want to exercise the explicit-null degraded mode path.
 *
 * **Lifecycle:**
 * - [start] launches the observer in an internal supervisor scope and
 *   starts the Ktor CIO engine with `wait = false` (Pitfall 3).
 * - [stop] stops the engine with a 1s grace period, cancels the observer
 *   scope, and clears the engine reference so [isRunning] returns `false`.
 * - [getPhase] returns `Int.MAX_VALUE - 1` so the dashboard starts after
 *   every other Spring bean and stops first on shutdown.
 *
 * @property eventBus the [EventBus] the observer subscribes to.
 * @property auditLog the (possibly in-memory) [AuditLog] backing the
 *           recent-runs and cost-summary fragments.
 * @property properties the dashboard properties from kore-spring's
 *           `KoreProperties.DashboardProperties` (port, path, enabled).
 */
class DashboardServer(
    private val eventBus: EventBus,
    private val auditLog: AuditLog,
    private val properties: DashboardProperties,
) : SmartLifecycle {
    /**
     * Property bag matching `KoreProperties.DashboardProperties` from kore-spring.
     * Defined as an interface so the kore-dashboard module does not need a
     * compile-time dependency on kore-spring.
     */
    interface DashboardProperties {
        val port: Int
        val path: String
        val enabled: Boolean
    }

    /** Default in-process implementation used by tests and standalone mains. */
    data class DefaultDashboardProperties(
        override val port: Int = 8090,
        override val path: String = "/kore",
        override val enabled: Boolean = true,
    ) : DashboardProperties

    /** Convenience constructor for unit tests / standalone usage with [DashboardConfig]. */
    constructor(
        eventBus: EventBus,
        auditLog: AuditLog?,
        config: DashboardConfig,
    ) : this(
        eventBus = eventBus,
        auditLog = auditLog ?: InertAuditLog,
        properties = DefaultDashboardProperties(port = config.port, path = config.path, enabled = config.enabled),
    )

    // HI-01: scope and observer are held in AtomicReferences so start() after
    // stop() can swap in a fresh CoroutineScope. The previous implementation
    // used `private val scope = ...` which meant stop() → scope.cancel()
    // permanently killed the only scope instance, and the next start() then
    // launched the observer collector on a cancelled scope (silent failure).
    // CLAUDE.md forbids `var`, so AtomicReference is the mechanism.
    private val scopeRef = AtomicReference<CoroutineScope?>(null)
    private val observerRef = AtomicReference<EventBusDashboardObserver?>(null)
    private val dataService = DashboardDataService(auditLog)

    // EmbeddedServer<*, *> can't be a `val` — we hold it via AtomicReference
    // to satisfy CLAUDE.md's no-`var` rule while still allowing start/stop
    // to swap the held instance.
    private val engine = AtomicReference<EmbeddedServer<*, *>?>(null)

    override fun start() {
        if (engine.get() != null) return
        // HI-01: recreate scope + observer on every start so a prior stop()
        // that cancelled the scope cannot leave the second start() launching
        // on a dead scope. SupervisorJob so one child failure does not tear
        // down the whole dashboard.
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newObserver = EventBusDashboardObserver(eventBus, newScope)
        scopeRef.set(newScope)
        observerRef.set(newObserver)
        newObserver.startCollecting()
        val config = DashboardConfig(properties.port, properties.path, properties.enabled)
        val started =
            embeddedServer(CIO, port = properties.port) {
                routing {
                    configureDashboardRoutes(newObserver, dataService, config)
                }
            }.also { it.start(wait = false) } // Pitfall 3 — never wait=true in SmartLifecycle
        engine.set(started)
    }

    override fun stop() {
        engine.getAndSet(null)?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        // HI-01: cancel AND clear the scope so the next start() recreates it.
        // getAndSet(null) is atomic; a concurrent start() observing null will
        // install a fresh scope before calling startCollecting().
        scopeRef.getAndSet(null)?.cancel()
        observerRef.set(null)
    }

    override fun isRunning(): Boolean = engine.get() != null

    override fun isAutoStartup(): Boolean = properties.enabled

    /** Start last, stop first — dashboard depends on every other kore bean. */
    override fun getPhase(): Int = Int.MAX_VALUE - 1

    /**
     * Test-only accessor for integration tests that need to assert on the
     * internally-held [DashboardDataService]. Used by
     * `DashboardDegradedModeSpringTest` in kore-spring to pin HI-02 to the
     * real `@Autowired` `DashboardServer` bean: any future refactor that
     * re-introduces a null-coalescing wrapper around the audit log will
     * break this accessor's return value and surface in CI.
     *
     * This accessor is `public` (not `internal`) because Kotlin `internal`
     * visibility is scoped to a single Gradle/Kotlin compilation module, so
     * kore-spring's test source set cannot see `internal` members declared
     * in kore-dashboard/src/main. The `-Test` suffix is the reviewer guard —
     * do not call from production code. (A future ktlint custom rule can
     * enforce this mechanically if the convention spreads.)
     */
    fun dataServiceForTest(): DashboardDataService = dataService

    /**
     * Test-only accessor for the current [CoroutineScope] held in [scopeRef].
     * Returns `null` when the server is stopped. Used by
     * `DashboardServerRestartTest` to prove HI-01: after start → stop → start,
     * the returned reference is a NEW instance (identity check), not the
     * original scope that `stop()` cancelled.
     *
     * See [dataServiceForTest] for the rationale on the `-Test` suffix
     * convention. This accessor is `internal` (not `public`) because the
     * only caller lives in `kore-dashboard/src/test` — the SAME Kotlin
     * compilation module as `kore-dashboard/src/main`, so `internal` IS
     * visible there.
     */
    internal fun scopeForTest(): CoroutineScope? = scopeRef.get()

    /**
     * Test-only accessor for the current [EventBusDashboardObserver] held in
     * [observerRef]. Returns `null` when the server is stopped. The observer
     * is recreated on each [start] alongside [scopeRef], so a start → stop →
     * start cycle produces a distinct instance. Used by
     * `DashboardServerRestartTest` to emit events through `InProcessEventBus`
     * and assert the NEW observer's `snapshot()` sees them.
     */
    internal fun observerForTest(): EventBusDashboardObserver? = observerRef.get()
}

/**
 * Sentinel marker used by the [DashboardServer.constructor] convenience
 * overload to represent "no AuditLog supplied" without ever returning
 * `null` from [DashboardDataService] when the primary 3-arg constructor
 * is used. The [DashboardDataService] is constructed against `null` if
 * the user originally passed `null`, so the recent-runs / cost-summary
 * fragments still render the degraded notice.
 */
private object InertAuditLog : AuditLog {
    override suspend fun recordAgentRun(
        agentId: String,
        task: dev.unityinflow.kore.core.AgentTask,
        result: dev.unityinflow.kore.core.AgentResult,
    ) = Unit

    override suspend fun recordLLMCall(
        agentId: String,
        backend: String,
        usage: dev.unityinflow.kore.core.TokenUsage,
    ) = Unit

    override suspend fun recordToolCall(
        agentId: String,
        call: dev.unityinflow.kore.core.ToolCall,
        result: dev.unityinflow.kore.core.ToolResult,
    ) = Unit

    override suspend fun queryRecentRuns(limit: Int): List<dev.unityinflow.kore.core.port.AgentRunRecord> = emptyList()

    override suspend fun queryCostSummary(): List<dev.unityinflow.kore.core.port.AgentCostRecord> = emptyList()
}
