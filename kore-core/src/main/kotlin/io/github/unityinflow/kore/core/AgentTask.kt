package io.github.unityinflow.kore.core

/** A unit of work submitted to an agent for execution. */
data class AgentTask(
    val id: String,
    val input: String,
    val metadata: Map<String, String> = emptyMap(),
    /** Recursion depth in the agent hierarchy; root tasks are depth 0 (D-07). Defaulted for binary compat (criterion #5). */
    val depth: Int = 0,
    /** Run id of the parent agent that spawned this task, or null for root tasks (D-09). Defaulted for binary compat (criterion #5). */
    val parentRunId: String? = null,
)
