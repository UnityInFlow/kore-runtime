package dev.unityinflow.kore.spring.actuator

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.port.BudgetEnforcer
import dev.unityinflow.kore.core.port.EventBus
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Spring Actuator endpoint exposed at `/actuator/kore`.
 *
 * Read-only health surface (D-34 / SPRN-04). Returns aggregate counts only —
 * never task content, LLM responses, or API keys (T-03-05). Authorization is
 * the host application's responsibility via Spring Security.
 *
 * The endpoint subscribes to [EventBus] in a background coroutine to maintain
 * an in-memory active-agent count. The subscription is started in
 * [PostConstruct] (after Spring DI is complete) and cancelled in [PreDestroy]
 * to honour structured concurrency on context shutdown.
 */
@Component
@Endpoint(id = "kore")
class KoreActuatorEndpoint(
    private val eventBus: EventBus,
    private val budgetEnforcer: BudgetEnforcer,
) {
    private val activeAgents = ConcurrentHashMap.newKeySet<String>()
    private val supervisor: Job = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)

    @PostConstruct
    fun start() {
        eventBus
            .subscribe()
            .onEach { event ->
                when (event) {
                    is AgentEvent.AgentStarted -> activeAgents.add(event.agentId)
                    is AgentEvent.AgentCompleted -> activeAgents.remove(event.agentId)
                    else -> Unit
                }
            }.launchIn(scope)
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    /**
     * Aggregate health snapshot. Stable key set (status, agentsActive,
     * budgetEnforcer, version) so dashboards and alerts can rely on the shape.
     */
    @ReadOperation
    fun health(): Map<String, Any?> =
        mapOf(
            "status" to "UP",
            "agentsActive" to activeAgents.size,
            "budgetEnforcer" to budgetEnforcer::class.simpleName,
            "version" to "0.0.1",
        )
}
