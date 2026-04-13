package dev.unityinflow.kore.core.port

/**
 * Port interface for skill activation (D-09).
 *
 * A skill registry returns zero or more prompt strings to be injected as
 * [dev.unityinflow.kore.core.ConversationMessage.Role.System] messages before
 * the first LLM call in an agent loop. The decision of which prompts to return
 * is driven by pattern matching against [taskContent] and a tool-availability
 * check against [availableTools] (D-03 / D-04).
 *
 * kore-core ships [NoOpSkillRegistry] as the default — consumers who do not
 * depend on kore-skills get a zero-cost no-op. The real implementation lives
 * in the kore-skills module (`SkillRegistryAdapter`).
 */
interface SkillRegistry {
    /**
     * Return the prompts of all skills whose activation rules match the given
     * [taskContent] and whose [availableTools] requirement is satisfied.
     *
     * @param taskContent The user task content (typically `task.input`).
     * @param availableTools The tool names available in the current agent.
     * @return Matching skill prompt strings, in loader iteration order. Empty
     *   when no skills match — callers must be safe with an empty list.
     */
    suspend fun activateFor(
        taskContent: String,
        availableTools: List<String>,
    ): List<String>
}

/**
 * Default no-op [SkillRegistry] used when kore-skills is not on the classpath.
 *
 * Always returns an empty list, i.e. no skill prompts are injected into the
 * agent history. This is the default for [dev.unityinflow.kore.core.AgentLoop]
 * so that kore-core has zero runtime dependency on kore-skills.
 */
object NoOpSkillRegistry : SkillRegistry {
    override suspend fun activateFor(
        taskContent: String,
        availableTools: List<String>,
    ): List<String> = emptyList()
}
