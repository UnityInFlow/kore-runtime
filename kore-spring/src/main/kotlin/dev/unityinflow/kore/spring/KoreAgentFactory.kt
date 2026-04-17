package dev.unityinflow.kore.spring

import dev.unityinflow.kore.core.AgentRunner
import dev.unityinflow.kore.core.dsl.AgentBuilder
import dev.unityinflow.kore.core.dsl.agent
import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.core.port.EventBus
import dev.unityinflow.kore.core.port.SkillRegistry

/**
 * Spring-aware agent factory that pre-wires the application context's [EventBus],
 * [AuditLog], and [SkillRegistry] into every agent built through this factory.
 *
 * Without this factory, agents built via the raw `agent("name") { }` DSL create
 * their own private [dev.unityinflow.kore.core.internal.InProcessEventBus] —
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
 */
class KoreAgentFactory(
    private val eventBus: EventBus,
    private val auditLog: AuditLog,
    private val skillRegistry: SkillRegistry,
) {
    fun agent(
        name: String,
        block: AgentBuilder.() -> Unit,
    ): AgentRunner =
        agent(name) {
            eventBus(eventBus)
            auditLog(auditLog)
            skillRegistry(skillRegistry)
            block()
        }
}
