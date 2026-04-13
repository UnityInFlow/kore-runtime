package dev.unityinflow.kore.skills

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class SkillLoaderTest {
    @Test
    fun `Test 1 - loadAll parses classpath skill code-review yaml`() {
        val loader = SkillLoader(skillsDirectory = "nonexistent-dir-for-classpath-only-test")

        val skills = loader.loadAll()

        val codeReview = skills.firstOrNull { it.name == "code-review" }
        codeReview shouldNotBe null
        codeReview!!.version shouldBe "1.0.0"
        codeReview.activation.taskMatches.any { it.contains("review") } shouldBe true
        codeReview.prompt shouldContain "expert code reviewer"
    }

    @Test
    fun `Test 2 - loadAll with filesystem directory returns that skill`(
        @TempDir tempDir: Path,
    ) {
        val yamlFile = tempDir.resolve("my-custom-skill.yaml")
        yamlFile.writeText(
            """
            name: my-custom-skill
            description: "Local filesystem skill"
            version: "0.0.1"
            activation:
              task_matches:
                - "hello"
              requires_tools: []
            prompt: |
              Respond politely.
            """.trimIndent(),
        )

        val loader = SkillLoader(skillsDirectory = tempDir.toString())
        val skills = loader.loadAll()

        skills.any { it.name == "my-custom-skill" } shouldBe true
    }

    @Test
    fun `Test 3 - filesystem skill with same name wins over classpath skill`(
        @TempDir tempDir: Path,
    ) {
        // Override the bundled code-review skill with a user filesystem version.
        val yamlFile = tempDir.resolve("code-review.yaml")
        yamlFile.writeText(
            """
            name: code-review
            description: "USER OVERRIDE"
            version: "9.9.9"
            activation:
              task_matches:
                - "always match"
              requires_tools: []
            prompt: |
              This is the user override prompt.
            """.trimIndent(),
        )

        val loader = SkillLoader(skillsDirectory = tempDir.toString())
        val skills = loader.loadAll()

        val codeReview = skills.first { it.name == "code-review" }
        codeReview.version shouldBe "9.9.9"
        codeReview.description shouldBe "USER OVERRIDE"
        codeReview.prompt shouldContain "user override"
    }

    @Test
    fun `Test 4 - empty filesystem directory returns only classpath skills no error`(
        @TempDir tempDir: Path,
    ) {
        val loader = SkillLoader(skillsDirectory = tempDir.toString())
        val skills = loader.loadAll()

        // The bundled code-review skill must still be present; filesystem was empty.
        skills.any { it.name == "code-review" } shouldBe true
    }
}
