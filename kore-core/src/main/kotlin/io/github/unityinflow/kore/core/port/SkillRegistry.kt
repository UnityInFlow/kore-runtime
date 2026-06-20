package io.github.unityinflow.kore.core.port

/**
 * A single activated skill: its [name] and the [prompt] to inject (D-02).
 *
 * Returned by [SkillRegistry.activateFor]. [AgentLoop][io.github.unityinflow.kore.core.AgentLoop]
 * consumes [prompt] for [io.github.unityinflow.kore.core.ConversationMessage.Role.System]
 * message injection (existing behavior) and [name] for the `kore.skill.activate`
 * span attributes and the `AgentEvent.SkillActivated` event payload (OBSV-03 / OBSV-04).
 *
 * Deliberately a stdlib-only data class — NOT `@Serializable` (D-02). It never
 * crosses the event bus; only the skill *names* (not prompts) reach the
 * `AgentEvent.SkillActivated` payload (D-06). Richer metadata (description,
 * version, matched trigger) is deferred until a consumer needs it.
 */
data class ActivatedSkill(
    val name: String,
    val prompt: String,
)

/**
 * Port interface for skill activation (D-09).
 *
 * A skill registry returns zero or more [ActivatedSkill]s to be injected as
 * [io.github.unityinflow.kore.core.ConversationMessage.Role.System] messages before
 * the first LLM call in an agent loop. The decision of which skills to return
 * is driven by pattern matching against [taskContent] and a tool-availability
 * check against [availableTools] (D-03 / D-04).
 *
 * kore-core ships [NoOpSkillRegistry] as the default — consumers who do not
 * depend on kore-skills get a zero-cost no-op. The real implementation lives
 * in the kore-skills module (`SkillRegistryAdapter`).
 */
interface SkillRegistry {
    /**
     * Return the activated skills (name + prompt) of all skills whose activation
     * rules match the given [taskContent] and whose [availableTools] requirement
     * is satisfied.
     *
     * @param taskContent The user task content (typically `task.input`).
     * @param availableTools The tool names available in the current agent.
     * @return Matching [ActivatedSkill]s, in loader iteration order. Empty when
     *   no skills match — callers must be safe with an empty list.
     */
    suspend fun activateFor(
        taskContent: String,
        availableTools: List<String>,
    ): List<ActivatedSkill>
}

/**
 * Default no-op [SkillRegistry] used when kore-skills is not on the classpath.
 *
 * Always returns an empty list, i.e. no skill prompts are injected into the
 * agent history. This is the default for [io.github.unityinflow.kore.core.AgentLoop]
 * so that kore-core has zero runtime dependency on kore-skills.
 */
object NoOpSkillRegistry : SkillRegistry {
    override suspend fun activateFor(
        taskContent: String,
        availableTools: List<String>,
    ): List<ActivatedSkill> = emptyList()
}
