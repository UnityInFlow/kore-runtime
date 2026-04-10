package dev.unityinflow.kore.test

import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.LLMConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionRecordReplayTest {
    @Test
    fun `record and replay produces identical chunks`(
        @TempDir tempDir: Path,
    ) = runTest {
        val delegate =
            MockLLMBackend("original")
                .whenCalled(LLMChunk.Text("hello"), LLMChunk.Usage(10, 5), LLMChunk.Done)
                .whenCalled(LLMChunk.ToolCall("c1", "search", "{}"), LLMChunk.Done)

        val sessionFile = tempDir.resolve("session.json")
        val recorder = SessionRecorder(delegate, sessionFile)

        // Record two calls
        val call1 = recorder.call(emptyList(), emptyList(), LLMConfig("test")).toList()
        val call2 = recorder.call(emptyList(), emptyList(), LLMConfig("test")).toList()
        recorder.save()

        // Replay
        val replayer = SessionReplayer(sessionFile)
        val replay1 = replayer.call(emptyList(), emptyList(), LLMConfig("test")).toList()
        val replay2 = replayer.call(emptyList(), emptyList(), LLMConfig("test")).toList()

        replay1 shouldBe call1
        replay2 shouldBe call2
    }

    @Test
    fun `replayer throws when calls exceed recorded sessions`(
        @TempDir tempDir: Path,
    ) = runTest {
        val delegate =
            MockLLMBackend("original")
                .whenCalled(LLMChunk.Done)

        val sessionFile = tempDir.resolve("one-session.json")
        val recorder = SessionRecorder(delegate, sessionFile)
        recorder.call(emptyList(), emptyList(), LLMConfig("test")).toList()
        recorder.save()

        val replayer = SessionReplayer(sessionFile)
        replayer.call(emptyList(), emptyList(), LLMConfig("test")).toList() // consume the one recording

        val ex =
            assertThrows<IllegalStateException> {
                replayer.call(emptyList(), emptyList(), LLMConfig("test")).toList()
            }
        ex.message shouldContain "no more recorded sessions"
    }

    @Test
    fun `session file is valid JSON containing modelName and chunks`(
        @TempDir tempDir: Path,
    ) = runTest {
        val delegate =
            MockLLMBackend("claude")
                .whenCalled(LLMChunk.Text("test response"), LLMChunk.Done)

        val sessionFile = tempDir.resolve("session.json")
        val recorder = SessionRecorder(delegate, sessionFile)
        recorder.call(emptyList(), emptyList(), LLMConfig("test")).toList()
        recorder.save()

        val content = sessionFile.toFile().readText()
        content shouldContain "modelName"
        content shouldContain "claude"
        content shouldContain "test response"
    }
}
