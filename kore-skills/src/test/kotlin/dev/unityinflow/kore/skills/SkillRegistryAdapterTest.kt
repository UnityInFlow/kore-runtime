package dev.unityinflow.kore.skills

import dev.unityinflow.kore.skills.internal.PatternMatcher
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class SkillRegistryAdapterTest {
    // ---------- PatternMatcher unit tests (Tests 5-7) ----------

    @Test
    fun `Test 5 - PatternMatcher matches case-insensitive review code pattern`() {
        val matcher = PatternMatcher(listOf("(?i)review.*code"))
        matcher.matches("Please review this code") shouldBe true
    }

    @Test
    fun `Test 6 - PatternMatcher returns false when nothing matches`() {
        val matcher = PatternMatcher(listOf("(?i)review.*code"))
        matcher.matches("Hello world") shouldBe false
    }

    @Test
    fun `Test 7 - PatternMatcher returns true when any of multiple patterns matches`() {
        val matcher = PatternMatcher(listOf("(?i)foo", "(?i)bar"))
        matcher.matches("this has bar in it") shouldBe true
        matcher.matches("this has foo in it") shouldBe true
        matcher.matches("this has neither") shouldBe false
    }

    // ---------- SkillRegistryAdapter tests (Tests 8-11) ----------

    private fun adapterOverriding(
        @TempDir tempDir: Path,
        yaml: String,
    ): SkillRegistryAdapter {
        tempDir.resolve("code-review.yaml").writeText(yaml)
        return SkillRegistryAdapter(skillsDirectory = tempDir.toString())
    }

    @Test
    fun `Test 8 - activateFor returns non-empty list when task and tools match bundled code-review`() =
        runTest {
            val adapter = SkillRegistryAdapter(skillsDirectory = "nonexistent-dir")
            val prompts =
                adapter.activateFor(
                    taskContent = "review this code please",
                    availableTools = listOf("github_get_pr", "github_list_files"),
                )
            prompts.any { it.contains("expert code reviewer") } shouldBe true
        }

    @Test
    fun `Test 9 - activateFor returns empty for bundled code-review on non-matching task`(
        @TempDir tempDir: Path,
    ) = runTest {
        // Override the bundled skill so requires_tools is empty, isolating the task match logic.
        val adapter =
            adapterOverriding(
                tempDir,
                """
                name: code-review
                description: "test"
                version: "1.0.0"
                activation:
                  task_matches:
                    - "(?i)review.*code"
                  requires_tools: []
                prompt: |
                  code review prompt
                """.trimIndent(),
            )
        val prompts = adapter.activateFor("hello world", emptyList())
        prompts.shouldBeEmpty()
    }

    @Test
    fun `Test 10 - activateFor activates when ALL required tools are present`() =
        runTest {
            val adapter = SkillRegistryAdapter(skillsDirectory = "nonexistent-dir")
            val prompts =
                adapter.activateFor(
                    taskContent = "review code",
                    availableTools = listOf("github_get_pr", "github_list_files"),
                )
            prompts shouldHaveSize 1
            prompts.first() shouldContain "expert code reviewer"
        }

    @Test
    fun `Test 11 - activateFor does NOT activate when only some required tools present (AND semantics)`() =
        runTest {
            val adapter = SkillRegistryAdapter(skillsDirectory = "nonexistent-dir")
            val prompts =
                adapter.activateFor(
                    taskContent = "review code",
                    availableTools = listOf("github_get_pr"),
                )
            prompts.shouldBeEmpty()
        }
}
