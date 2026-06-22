package io.github.unityinflow.kore.core.dsl

import io.github.unityinflow.kore.core.AgentLoop
import io.github.unityinflow.kore.core.AgentRunner
import io.github.unityinflow.kore.core.LLMConfig
import io.github.unityinflow.kore.core.internal.InMemoryAuditLog
import io.github.unityinflow.kore.core.internal.InMemoryBudgetEnforcer
import io.github.unityinflow.kore.core.internal.InProcessEventBus
import io.github.unityinflow.kore.core.internal.ResilientLLMBackend
import io.github.unityinflow.kore.core.internal.RetryPolicy
import io.github.unityinflow.kore.core.port.AgentTool
import io.github.unityinflow.kore.core.port.AuditLog
import io.github.unityinflow.kore.core.port.BudgetEnforcer
import io.github.unityinflow.kore.core.port.EventBus
import io.github.unityinflow.kore.core.port.LLMBackend
import io.github.unityinflow.kore.core.port.NoOpSkillRegistry
import io.github.unityinflow.kore.core.port.SkillRegistry
import io.github.unityinflow.kore.core.port.ToolProvider
import io.opentelemetry.api.trace.Tracer

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

    // Spawn ceiling for child agents (D-08). val-cell (no `var`); default 5
    // mirrors AgentLoop.maxDepth. Set via [maxDepth]; threaded into buildLoop()
    // and each child AgentTool.
    private val maxDepthCell: IntArray = intArrayOf(5)

    // Parent tracer to inherit into children (A1/D-12). AgentBuilder has no
    // tracer affordance today (only AgentLoop does), so this val-cell adds one.
    // opentelemetry-api is compileOnly on kore-core — Tracer? stays optional.
    private val tracerCell: Array<Tracer?> = arrayOfNulls(1)

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

    /**
     * Set the maximum recursion depth for spawned child agents (D-08 / HIER-03).
     * Applies to every child registered via [child] AND threads into the built
     * [AgentLoop] so depth-limit refusals (D-03) are bounded consistently.
     */
    @KoreDsl
    fun maxDepth(n: Int) {
        maxDepthCell[0] = n
    }

    /**
     * Inherit the parent's OpenTelemetry [Tracer] into this builder (A1 / D-12).
     * Called by [child] so a child's spans parent under the same trace as the
     * parent agent. Internal — not part of the public DSL surface.
     */
    internal fun inheritTracer(tracer: Tracer?) {
        tracerCell[0] = tracer
    }

    /**
     * Register a child sub-agent that the parent LLM can invoke as a tool (the
     * spawn model — D-04). The child inherits the parent's [EventBus],
     * [AuditLog], and [Tracer] (D-12) so its runs land in the SAME audit
     * log/event stream as the parent (in-memory run-tree, HIER-04).
     *
     * @param name the child tool name advertised to the parent LLM — must be
     *   unique within the parent's tool surface (Pitfall 3).
     */
    @KoreDsl
    fun child(
        name: String,
        description: String = "Delegates a subtask to the '$name' sub-agent",
        block: AgentBuilder.() -> Unit,
    ) {
        val childBuilder = AgentBuilder(name).apply(block)
        // D-12: overwrite the child's throwaway InProcessEventBus/InMemoryAuditLog
        // defaults with the parent's ports AFTER the block runs, so inheritance
        // wins over anything the block set up by default (prevents the child's
        // parentRunId row landing in a discarded log).
        childBuilder.eventBus(eventBus)
        childBuilder.auditLog(auditLog)
        childBuilder.inheritTracer(tracerCell[0])
        toolProviders.add(
            AgentTool(
                childName = name,
                description = description,
                childLoop = childBuilder.buildLoop(),
                maxDepth = maxDepthCell[0],
            ),
        )
    }

    /**
     * Build the configured [AgentLoop]. Internal — children need the loop
     * directly (D-04: an [AgentTool] runs the child loop INLINE, not via an
     * [AgentRunner]).
     */
    internal fun buildLoop(): AgentLoop {
        val backend =
            requireNotNull(model) {
                "Agent '$agentName': model must be configured. Use model = claude() or model = gpt()"
            }
        val resilientBackend =
            ResilientLLMBackend(
                primary = backend,
                retryPolicy = retryPolicy,
            )
        return AgentLoop(
            llmBackend = resilientBackend,
            toolProviders = toolProviders,
            budgetEnforcer = budgetEnforcer,
            eventBus = eventBus,
            auditLog = auditLog,
            skillRegistry = skillRegistryCell[0],
            tracer = tracerCell[0],
            maxDepth = maxDepthCell[0],
            config = llmConfig,
        )
    }

    /** Build and return the configured [AgentRunner]. */
    fun build(): AgentRunner = AgentRunner(loop = buildLoop())
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
