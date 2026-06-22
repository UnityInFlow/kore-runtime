package io.github.unityinflow.kore.spring

import io.github.unityinflow.kore.core.AgentRunner
import io.github.unityinflow.kore.core.dsl.AgentBuilder
import io.github.unityinflow.kore.core.dsl.agent
import io.github.unityinflow.kore.core.port.AuditLog
import io.github.unityinflow.kore.core.port.EventBus
import io.github.unityinflow.kore.core.port.SkillRegistry

/**
 * Spring-aware agent factory that pre-wires the application context's [EventBus],
 * [AuditLog], and [SkillRegistry] into every agent built through this factory.
 *
 * Without this factory, agents built via the raw `agent("name") { }` DSL create
 * their own private [io.github.unityinflow.kore.core.internal.InProcessEventBus] —
 * the dashboard, observability observers, and metrics never see their events.
 *
 * Usage in a Spring Boot app:
 * ```kotlin
 * @Bean
 * fun myAgent(kore: KoreAgentFactory) = kore.agent("my-agent") {
 *     model = claude(apiKey)
 * }
 * ```
 *
 * The `block` lambda still has full access to the [AgentBuilder] DSL — the
 * factory only pre-sets the infrastructure ports. User overrides in `block`
 * (e.g., `eventBus(customBus)`) take precedence because they run after the
 * factory's pre-wiring.
 *
 * In addition to the ports, the factory pre-wires the hierarchical-agent depth
 * ceiling: it calls `maxDepth([maxDepth])` before `block()` so the configured
 * `kore.hierarchy.max-depth` (D-08 / HIER-03) flows into every agent's loop. A
 * user `maxDepth(n)` inside `block` still wins, exactly like the port overrides,
 * because it runs after the factory's pre-wiring. The default of 5 preserves
 * behavior for existing callers that construct the factory without the parameter.
 */
class KoreAgentFactory(
    private val eventBus: EventBus,
    private val auditLog: AuditLog,
    private val skillRegistry: SkillRegistry,
    /** Hierarchical spawn ceiling pre-wired into every built agent (D-08 / HIER-03). */
    val maxDepth: Int = 5,
) {
    fun agent(
        name: String,
        block: AgentBuilder.() -> Unit,
    ): AgentRunner =
        io.github.unityinflow.kore.core.dsl.agent(name) {
            eventBus(this@KoreAgentFactory.eventBus)
            auditLog(this@KoreAgentFactory.auditLog)
            skillRegistry(this@KoreAgentFactory.skillRegistry)
            maxDepth(this@KoreAgentFactory.maxDepth)
            block()
        }
}
