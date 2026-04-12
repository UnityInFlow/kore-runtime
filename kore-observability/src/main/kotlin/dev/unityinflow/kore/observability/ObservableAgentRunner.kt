package dev.unityinflow.kore.observability

import dev.unityinflow.kore.core.AgentLoop
import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job

/**
 * Decorator wrapping [AgentLoop] with an OTel-aware CoroutineScope (OBSV-04, D-07, D-08).
 *
 * Uses decorator pattern — does NOT subclass [AgentLoop] which is a concrete class.
 * [Context.current().asContextElement()] ensures the OTel ThreadLocal context is restored
 * after every coroutine suspension (Pitfall 3 prevention, D-09).
 *
 * Produces spans: kore.agent.run (root per task) → relies on [EventBusSpanObserver]
 * for LLM call and tool use child spans emitted from the EventBus.
 */
class ObservableAgentRunner(
    private val loop: AgentLoop,
    private val tracer: KoreTracer,
) {
    // OTel context element injected at scope creation — all launched coroutines inherit it (D-08)
    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                Context.current().asContextElement(),
        )

    /**
     * Runs [task] asynchronously.
     * Opens a "kore.agent.run" root span. Sets kore.agent.id and kore.agent.name.
     * Closes the span when the Deferred completes (success or error).
     * Returns [Deferred]<[AgentResult]> — never throws.
     */
    fun run(task: AgentTask): Deferred<AgentResult> =
        scope.async {
            tracer.withSpan(
                name = KoreSpans.AGENT_RUN,
                attrs =
                    mapOf(
                        KoreAttrs.AGENT_ID to task.id,
                        KoreAttrs.AGENT_NAME to (task.metadata["agent_name"] ?: "unknown"),
                    ),
            ) { span ->
                val result = loop.run(task)
                // Record result type on the span before it closes
                span.setAttribute("kore.result_type", result.typeName())
                result
            }
        }

    /** Cancel all running agents in this runner's scope. */
    fun shutdown() {
        scope.coroutineContext.job.cancel()
    }
}

/** Maps [AgentResult] to a stable string for OTel attribute value and audit log result_type. */
fun AgentResult.typeName(): String =
    when (this) {
        is AgentResult.Success -> "success"
        is AgentResult.BudgetExceeded -> "budget_exceeded"
        is AgentResult.ToolError -> "tool_error"
        is AgentResult.LLMError -> "llm_error"
        is AgentResult.Cancelled -> "cancelled"
    }
