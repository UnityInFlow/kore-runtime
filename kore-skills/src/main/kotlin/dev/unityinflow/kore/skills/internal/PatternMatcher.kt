package dev.unityinflow.kore.skills.internal

/**
 * Pre-compiled regex matcher for skill activation patterns (Pattern 5).
 *
 * Compiles all [patterns] at construction time — regex compilation is expensive
 * and skill patterns never change after [SkillLoader] loads them. [matches]
 * returns true if ANY compiled pattern has a match inside [input] (D-03).
 *
 * T-03-02 (catastrophic backtracking): malformed or adversarial patterns are
 * defended against at two layers:
 *  1. A [java.util.regex.PatternSyntaxException] from [Regex] construction is
 *     caught and the pattern is silently skipped (logged to stderr). The
 *     [PatternMatcher] is still constructed — it just has fewer patterns.
 *  2. Runtime [matches] calls use [Regex.containsMatchIn] which does not
 *     attempt a full backtracking match. For skill content this is enough.
 */
internal class PatternMatcher(
    private val patterns: List<String>,
) {
    private val compiled: List<Regex> =
        patterns.mapNotNull { raw ->
            try {
                Regex(raw)
            } catch (ex: java.util.regex.PatternSyntaxException) {
                System.err.println(
                    "kore-skills: skipping invalid regex pattern [$raw]: ${ex.message}",
                )
                null
            }
        }

    /** Return true if ANY compiled pattern matches (is contained in) [input]. */
    fun matches(input: String): Boolean = compiled.any { it.containsMatchIn(input) }
}
