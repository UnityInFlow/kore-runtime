package dev.unityinflow.kore.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kore Micrometer metrics: 4 counters + 1 active-agents gauge (D-21 through D-25).
 *
 * Design rules:
 * - Low cardinality: tag values are configured names (agent_name, model, backend) not UUIDs (D-24)
 * - No per-tool counters: tool-level detail lives in OTel spans only (D-25)
 * - Gauge is a simple [AtomicInteger]: [EventBusMetricsObserver] increments/decrements it
 *
 * Counter methods call `.register(registry)` on every call.
 * Micrometer deduplicates by name+tag set — same name+tags always returns the same [Counter].
 *
 * @param registry The [MeterRegistry] to register all meters against (injected, not retrieved statically).
 */
class KoreMetrics(
    private val registry: MeterRegistry,
) {
    /**
     * Tracks currently active agents. Goes up on [dev.unityinflow.kore.core.AgentEvent.AgentStarted],
     * down on [dev.unityinflow.kore.core.AgentEvent.AgentCompleted]. (D-23 — no labels)
     */
    val agentsActive: AtomicInteger =
        AtomicInteger(0).also { active ->
            registry.gauge("kore.agents.active", active)
        }

    /**
     * Incremented once per completed agent run.
     * Tags: agent_name (configured name), result_type ("success" | "budget_exceeded" | etc.)
     */
    fun agentRunCounter(
        agentName: String,
        resultType: String,
    ): Counter =
        Counter
            .builder("kore.agent.runs")
            .tag("agent_name", agentName)
            .tag("result_type", resultType)
            .register(registry)

    /**
     * Incremented once per LLM call.
     * Tags: agent_name, model (e.g. "claude-opus-4-5"), backend (e.g. "claude")
     */
    fun llmCallCounter(
        agentName: String,
        model: String,
        backend: String,
    ): Counter =
        Counter
            .builder("kore.llm.calls")
            .tag("agent_name", agentName)
            .tag("model", model)
            .tag("backend", backend)
            .register(registry)

    /**
     * Incremented by token count per LLM call. Two increments per call: direction=in, direction=out.
     * Tags: agent_name, model, direction ("in" | "out")
     *
     * NOTE: tag values are configured names not UUIDs (D-24) — callers must pass
     * the agent's configured name (from [dev.unityinflow.kore.core.AgentTask.metadata])
     * not a runtime-generated agentId.
     * If agent names contain PII, that is a misconfiguration concern — see KDoc on KoreMetrics class (T-02-11).
     */
    fun tokensUsedCounter(
        agentName: String,
        model: String,
        direction: String,
    ): Counter =
        Counter
            .builder("kore.tokens.used")
            .tag("agent_name", agentName)
            .tag("model", model)
            .tag("direction", direction)
            .register(registry)

    /**
     * Incremented on [dev.unityinflow.kore.core.AgentResult.LLMError] and
     * [dev.unityinflow.kore.core.AgentResult.ToolError] results.
     * Tags: agent_name, error_type ("llm_error" | "tool_error")
     */
    fun errorCounter(
        agentName: String,
        errorType: String,
    ): Counter =
        Counter
            .builder("kore.errors")
            .tag("agent_name", agentName)
            .tag("error_type", errorType)
            .register(registry)
}
