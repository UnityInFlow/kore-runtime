package io.github.unityinflow.kore.observability

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.withContext

/** Kore-namespaced OTel span name constants (D-02). Do NOT use GenAI semantic conventions (D-06). */
object KoreSpans {
    const val AGENT_RUN = "kore.agent.run"
    const val LLM_CALL = "kore.llm.call"
    const val TOOL_USE = "kore.tool.use"

    /** Phase 3: emitted when kore-skills activates a skill. Constant defined here for completeness. */
    const val SKILL_ACTIVATE = "kore.skill.activate"
}

/** Kore-namespaced OTel attribute key constants (D-03, D-04, D-05). */
object KoreAttrs {
    // Common (D-03)
    const val AGENT_NAME = "kore.agent.name"
    const val AGENT_ID = "kore.agent.id"

    // LLM (D-04)
    const val LLM_MODEL = "kore.llm.model"
    const val LLM_TOKENS_IN = "kore.llm.tokens.in"
    const val LLM_TOKENS_OUT = "kore.llm.tokens.out"
    const val LLM_BACKEND = "kore.llm.backend"
    const val LLM_DURATION_MS = "kore.llm.duration_ms"

    // Tool (D-05)
    const val TOOL_NAME = "kore.tool.name"
    const val TOOL_MCP_SERVER = "kore.tool.mcp_server"
    const val TOOL_DURATION_MS = "kore.tool.duration_ms"

    // Skill (D-03). Single source of truth for the kore.skill.* attribute keys.
    // AgentLoop (kore-core) sets these inline with the same literal strings because kore-core
    // cannot depend on kore-observability (A4 / Open-Q 1) — keep these in lockstep with Plan 05-02.
    const val SKILL_NAMES = "kore.skill.names"
    const val SKILL_COUNT = "kore.skill.count"
    const val SKILL_DURATION_MS = "kore.skill.duration_ms"
}

/**
 * Thin wrapper around [Tracer] providing [withSpan] ergonomics for kore span creation.
 * Inject via constructor from kore-spring or kore-observability tests.
 */
class KoreTracer(
    val tracer: Tracer,
)

/**
 * Runs [block] inside an OTel span named [name], parented to the current coroutine context's OTel context.
 *
 * Context propagation (OBSV-04 / Pitfall 3): stores the span in the coroutine context via
 * [asContextElement] so the OTel ThreadLocal context is restored after every suspension point.
 *
 * Always calls [Span.end] in a finally block.
 */
suspend fun <T> KoreTracer.withSpan(
    name: String,
    kind: SpanKind = SpanKind.INTERNAL,
    attrs: Map<String, Any> = emptyMap(),
    block: suspend (Span) -> T,
): T {
    val span =
        tracer
            .spanBuilder(name)
            .setParent(Context.current()) // critical: inherit trace context from coroutine
            .setSpanKind(kind)
            .startSpan()
    attrs.forEach { (key, value) ->
        when (value) {
            is String -> span.setAttribute(key, value)
            is Long -> span.setAttribute(key, value)
            is Int -> span.setAttribute(key, value.toLong())
            is Double -> span.setAttribute(key, value)
            is Boolean -> span.setAttribute(key, value)
            // D-03: a List value becomes a native OTel string-array (queryable per-element),
            // never a comma-joined String. filterIsInstance drops non-String elements safely
            // (no @Suppress("UNCHECKED_CAST"), no !!).
            is List<*> -> span.setAttribute(AttributeKey.stringArrayKey(key), value.filterIsInstance<String>())
        }
    }
    return withContext(span.storeInContext(Context.current()).asContextElement()) {
        try {
            block(span)
        } finally {
            span.end()
        }
    }
}
