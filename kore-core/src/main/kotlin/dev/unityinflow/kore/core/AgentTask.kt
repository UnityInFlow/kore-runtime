package dev.unityinflow.kore.core

/** A unit of work submitted to an agent for execution. */
data class AgentTask(
    val id: String,
    val input: String,
    val metadata: Map<String, String> = emptyMap(),
)
