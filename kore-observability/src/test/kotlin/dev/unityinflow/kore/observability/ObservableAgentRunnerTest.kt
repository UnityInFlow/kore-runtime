package dev.unityinflow.kore.observability

import dev.unityinflow.kore.core.AgentLoop
import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.TokenUsage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservableAgentRunnerTest {
    private lateinit var exporter: InMemorySpanExporter
    private lateinit var koreTracer: KoreTracer
    private lateinit var loop: AgentLoop
    private lateinit var runner: ObservableAgentRunner

    @BeforeEach
    fun setUp() {
        exporter = InMemorySpanExporter.create()
        val provider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        koreTracer = KoreTracer(provider.get("kore-observability-test"))
        loop = mockk()
        runner = ObservableAgentRunner(loop, koreTracer)
    }

    @Test
    fun `run() produces a finished kore_agent_run span`() =
        runTest {
            coEvery { loop.run(any()) } returns AgentResult.Success("ok", TokenUsage(10, 5))
            val task = AgentTask(id = "task-1", input = "hello")

            runner.run(task).await()

            val spans = exporter.finishedSpanItems
            spans.any { it.name == KoreSpans.AGENT_RUN } shouldBe true
        }

    @Test
    fun `run() span has kore_agent_id attribute matching task id`() =
        runTest {
            coEvery { loop.run(any()) } returns AgentResult.Success("ok", TokenUsage(10, 5))
            val task = AgentTask(id = "task-42", input = "hello")

            runner.run(task).await()

            val span = exporter.finishedSpanItems.first { it.name == KoreSpans.AGENT_RUN }
            span.attributes.get(AttributeKey.stringKey(KoreAttrs.AGENT_ID)) shouldBe "task-42"
        }

    @Test
    fun `run() span has kore_result_type attribute set to success`() =
        runTest {
            coEvery { loop.run(any()) } returns AgentResult.Success("ok", TokenUsage(10, 5))
            val task = AgentTask(id = "task-1", input = "hello")

            runner.run(task).await()

            val span = exporter.finishedSpanItems.first { it.name == KoreSpans.AGENT_RUN }
            span.attributes.get(AttributeKey.stringKey("kore.result_type")) shouldBe "success"
        }

    @Test
    fun `run() span is ended after loop completes`() =
        runTest {
            coEvery { loop.run(any()) } returns AgentResult.Success("ok", TokenUsage(10, 5))
            val task = AgentTask(id = "task-1", input = "hello")

            runner.run(task).await()

            val spans = exporter.finishedSpanItems
            spans.any { it.name == KoreSpans.AGENT_RUN } shouldBe true
        }

    @Test
    fun `run() returns the AgentResult from the mocked loop`() =
        runTest {
            val expected = AgentResult.Success("result text", TokenUsage(10, 5))
            coEvery { loop.run(any()) } returns expected
            val task = AgentTask(id = "task-1", input = "hello")

            val result = runner.run(task).await()

            result shouldBe expected
        }

    @Test
    fun `run() span has kore_agent_name from task metadata`() =
        runTest {
            coEvery { loop.run(any()) } returns AgentResult.Success("ok", TokenUsage(10, 5))
            val task = AgentTask(id = "task-1", input = "hello", metadata = mapOf("agent_name" to "my-agent"))

            runner.run(task).await()

            val span = exporter.finishedSpanItems.first { it.name == KoreSpans.AGENT_RUN }
            span.attributes.get(AttributeKey.stringKey(KoreAttrs.AGENT_NAME)) shouldBe "my-agent"
        }

    @Test
    fun `typeName() maps all AgentResult variants correctly`() {
        AgentResult.Success("ok", TokenUsage(0, 0)).typeName() shouldBe "success"
        AgentResult.BudgetExceeded(TokenUsage(100, 0), 50L).typeName() shouldBe "budget_exceeded"
        AgentResult.ToolError("tool", RuntimeException()).typeName() shouldBe "tool_error"
        AgentResult.LLMError("claude", RuntimeException()).typeName() shouldBe "llm_error"
        AgentResult.Cancelled("user cancelled").typeName() shouldBe "cancelled"
    }

    @Test
    fun `ObservableAgentRunner span traceId is non-null`() =
        runTest {
            coEvery { loop.run(any()) } returns AgentResult.Success("ok", TokenUsage(10, 5))
            val task = AgentTask(id = "task-1", input = "hello")

            runner.run(task).await()

            val span = exporter.finishedSpanItems.first { it.name == KoreSpans.AGENT_RUN }
            span.traceId shouldNotBe null
        }
}
