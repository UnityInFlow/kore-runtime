package dev.unityinflow.kore.skills

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Data-class binding for the YAML skill schema (D-01 / D-02).
 *
 * Required fields:
 *  - [name]         unique skill identifier; used for filesystem override (D-07)
 *  - [description]  one-line human description (surfaced in logs/registry)
 *  - [version]      semver-ish version string (not enforced)
 *  - [activation]   [ActivationDef] pattern + tool requirements
 *  - [prompt]       multi-line string injected as System message on activation
 */
data class SkillYamlDef(
    val name: String,
    val description: String,
    val version: String,
    val activation: ActivationDef,
    val prompt: String,
)

/**
 * Activation rules for a skill (D-03 / D-04).
 *
 *  - [taskMatches]   regex list (ANY match activates). Use `(?i)` for case-insensitive.
 *  - [requiresTools] tool name list (ALL must be present in the agent's tool set).
 *
 * YAML binds to snake_case keys `task_matches` / `requires_tools` via
 * [JsonProperty] so the public Kotlin API stays camelCase.
 */
data class ActivationDef(
    @param:JsonProperty("task_matches")
    val taskMatches: List<String> = emptyList(),
    @param:JsonProperty("requires_tools")
    val requiresTools: List<String> = emptyList(),
)
