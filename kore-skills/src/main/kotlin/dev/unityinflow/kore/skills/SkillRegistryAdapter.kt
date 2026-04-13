package dev.unityinflow.kore.skills

import dev.unityinflow.kore.core.port.SkillRegistry
import dev.unityinflow.kore.skills.internal.PatternMatcher

/**
 * [SkillRegistry] adapter that matches skill YAML definitions against task
 * content and the agent's available tool set.
 *
 * Activation logic (D-03 / D-04):
 *  - A skill activates when its [ActivationDef.taskMatches] list is empty OR
 *    ANY pattern matches [taskContent] (via [PatternMatcher]).
 *  - It also requires every tool in [ActivationDef.requiresTools] to be
 *    present in [availableTools] — ALL must match (AND semantics), NOT any.
 *  - Both conditions must be true (AND of the two checks).
 *
 * Skills are loaded once at construction via [SkillLoader] from the classpath
 * and the configured filesystem [skillsDirectory] (D-06 / D-07).
 */
class SkillRegistryAdapter(
    skillsDirectory: String = "./kore-skills",
) : SkillRegistry {
    private val skills: List<SkillYamlDef> = SkillLoader(skillsDirectory).loadAll()

    // Pre-compile matchers per skill at init time to avoid re-compiling on
    // every activateFor() call (Pitfall: compile-per-request is O(N*M)).
    private val matcherBySkill: Map<String, PatternMatcher> =
        skills.associate { skill ->
            skill.name to PatternMatcher(skill.activation.taskMatches)
        }

    override suspend fun activateFor(
        taskContent: String,
        availableTools: List<String>,
    ): List<String> =
        skills
            .filter { skill ->
                val taskMatches =
                    skill.activation.taskMatches.isEmpty() ||
                        matcherBySkill.getValue(skill.name).matches(taskContent)
                val toolsPresent =
                    skill.activation.requiresTools.isEmpty() ||
                        availableTools.containsAll(skill.activation.requiresTools)
                taskMatches && toolsPresent
            }.map { it.prompt }
}
