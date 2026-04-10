package dev.unityinflow.kore.core.dsl

import dev.unityinflow.kore.core.AgentRunner

/**
 * Entry point for the kore agent DSL.
 *
 * ```kotlin
 * val runner: AgentRunner = agent("my-agent") {
 *     model = claude()
 *     tools(mcp("github"))
 *     budget(maxTokens = 10_000)
 * }
 * ```
 *
 * @param name A unique name for this agent instance.
 * @param block Configuration block with receiver [AgentBuilder].
 * @return A configured [AgentRunner] ready to execute tasks.
 */
fun agent(
    name: String,
    block: AgentBuilder.() -> Unit,
): AgentRunner = AgentBuilder(name).apply(block).build()
