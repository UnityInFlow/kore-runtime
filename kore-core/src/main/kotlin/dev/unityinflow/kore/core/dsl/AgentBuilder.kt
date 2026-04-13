package dev.unityinflow.kore.core.dsl

import dev.unityinflow.kore.core.AgentLoop
import dev.unityinflow.kore.core.AgentRunner
import dev.unityinflow.kore.core.LLMConfig
import dev.unityinflow.kore.core.internal.InMemoryAuditLog
import dev.unityinflow.kore.core.internal.InMemoryBudgetEnforcer
import dev.unityinflow.kore.core.internal.InProcessEventBus
import dev.unityinflow.kore.core.internal.ResilientLLMBackend
import dev.unityinflow.kore.core.internal.RetryPolicy
import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.core.port.BudgetEnforcer
import dev.unityinflow.kore.core.port.EventBus
import dev.unityinflow.kore.core.port.LLMBackend
import dev.unityinflow.kore.core.port.NoOpSkillRegistry
import dev.unityinflow.kore.core.port.SkillRegistry
import dev.unityinflow.kore.core.port.ToolProvider

/**
 * DSL builder for configuring and creating an [AgentRunner].
 *
 * All methods annotated with [@KoreDsl] so nested blocks cannot accidentally
 * access the outer [AgentBuilder] receiver (Pitfall 10 prevention).
 *
 * Usage:
 * ```kotlin
 * val runner = agent("my-agent") {
 *     model = claude()
 *     tools(mcp("github"))
 *     budget(maxTokens = 10_000)
 *     retry(maxAttempts = 3)
 * }
 * ```
 */
@KoreDsl
class AgentBuilder(
    val agentName: String,
) {
    /** The primary LLM backend. Use [model] property assignment or [fallbackTo] chain. */
    var model: LLMBackend? = null

    private val toolProviders = mutableListOf<ToolProvider>()
    private var budgetEnforcer: BudgetEnforcer = InMemoryBudgetEnforcer()
    private var eventBus: EventBus = InProcessEventBus()
    private var auditLog: AuditLog = InMemoryAuditLog()
    private var retryPolicy: RetryPolicy = RetryPolicy()
    private var llmConfig: LLMConfig = LLMConfig(model = "default")

    // Single-element array acts as a val cell whose element can be swapped
    // without declaring a `var` (CLAUDE.md rule). Only one registry is ever
    // kept, at index 0.
    private val skillRegistryCell: Array<SkillRegistry> = arrayOf(NoOpSkillRegistry)

    /** Add one or more tool providers. */
    @KoreDsl
    fun tools(vararg providers: ToolProvider) {
        toolProviders.addAll(providers)
    }

    /** Configure token budget limit. */
    @KoreDsl
    fun budget(maxTokens: Long) {
        budgetEnforcer = InMemoryBudgetEnforcer(defaultLimitPerAgent = maxTokens)
    }

    /** Configure retry policy for LLM calls. */
    @KoreDsl
    fun retry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500L,
        maxDelayMs: Long = 30_000L,
    ) {
        retryPolicy =
            RetryPolicy(
                maxAttempts = maxAttempts,
                initialDelayMs = initialDelayMs,
                maxDelayMs = maxDelayMs,
            )
    }

    /** Override the default event bus implementation. */
    @KoreDsl
    fun eventBus(bus: EventBus) {
        eventBus = bus
    }

    /** Override the default audit log implementation. */
    @KoreDsl
    fun auditLog(log: AuditLog) {
        auditLog = log
    }

    /** Override the LLM configuration (model name, maxTokens, temperature, maxHistoryMessages). */
    @KoreDsl
    fun config(block: LLMConfigBuilder.() -> Unit) {
        llmConfig = LLMConfigBuilder().apply(block).build()
    }

    /**
     * Override the [SkillRegistry] for this agent (D-09 / D-10).
     *
     * Default is [NoOpSkillRegistry] — kore-skills consumers pass a
     * `SkillRegistryAdapter` here.
     */
    @KoreDsl
    fun skillRegistry(registry: SkillRegistry) {
        skillRegistryCell[0] = registry
    }

    /** Build and return the configured [AgentRunner]. */
    fun build(): AgentRunner {
        val backend =
            requireNotNull(model) {
                "Agent '$agentName': model must be configured. Use model = claude() or model = gpt()"
            }
        val resilientBackend =
            ResilientLLMBackend(
                primary = backend,
                retryPolicy = retryPolicy,
            )
        val loop =
            AgentLoop(
                llmBackend = resilientBackend,
                toolProviders = toolProviders,
                budgetEnforcer = budgetEnforcer,
                eventBus = eventBus,
                auditLog = auditLog,
                skillRegistry = skillRegistryCell[0],
                config = llmConfig,
            )
        return AgentRunner(loop = loop)
    }
}

/**
 * Builder for [LLMConfig] inside the [AgentBuilder] DSL.
 */
@KoreDsl
class LLMConfigBuilder {
    var model: String = "default"
    var maxTokens: Int = 4096
    var temperature: Double = 0.0
    var maxHistoryMessages: Int = 50

    internal fun build() =
        LLMConfig(
            model = model,
            maxTokens = maxTokens,
            temperature = temperature,
            maxHistoryMessages = maxHistoryMessages,
        )
}
