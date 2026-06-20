package io.github.unityinflow.kore.observability

import io.github.unityinflow.kore.core.AgentLoop
import io.github.unityinflow.kore.core.AgentResult
import io.github.unityinflow.kore.core.AgentTask
import io.github.unityinflow.kore.core.LLMChunk
import io.github.unityinflow.kore.core.LLMConfig
import io.github.unityinflow.kore.core.TokenUsage
import io.github.unityinflow.kore.core.internal.InMemoryAuditLog
import io.github.unityinflow.kore.core.internal.InMemoryBudgetEnforcer
import io.github.unityinflow.kore.core.internal.InProcessEventBus
import io.github.unityinflow.kore.core.port.ActivatedSkill
import io.github.unityinflow.kore.core.port.LLMBackend
import io.github.unityinflow.kore.core.port.SkillRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.flow.flow
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

    /** Minimal backend: emits a single text + usage chunk so the loop completes on the first call. */
    private class DoneBackend : LLMBackend {
        override val name = "done"

        override fun call(
            messages: List<io.github.unityinflow.kore.core.ConversationMessage>,
            tools: List<io.github.unityinflow.kore.core.ToolDefinition>,
            config: LLMConfig,
        ) = flow {
            emit(LLMChunk.Text("ok"))
            emit(LLMChunk.Usage(inputTokens = 1, outputTokens = 1))
            emit(LLMChunk.Done)
        }
    }

    /**
     * OBSV-03: the kore.skill.activate span must be parented under the kore.agent.run span when
     * the loop runs THROUGH ObservableAgentRunner (which establishes the agent-run span as the
     * current OTel context). A bare AgentLoop would emit a ROOT skill span instead (Pitfall 2), so
     * this test deliberately drives a REAL AgentLoop (not the mocked one) through the runner,
     * wiring the SAME SDK tracer into both the runner's KoreTracer and the loop so both spans land
     * in the one InMemorySpanExporter.
     */
    @Test
    fun `kore_skill_activate span is parented under kore_agent_run span through the runner`() =
        runTest {
            val sdkTracer = koreTracer.tracer
            val skillRegistry =
                object : SkillRegistry {
                    override suspend fun activateFor(
                        taskContent: String,
                        availableTools: List<String>,
                    ): List<ActivatedSkill> = listOf(ActivatedSkill(name = "code-review", prompt = "be thorough"))
                }
            val realLoop =
                AgentLoop(
                    llmBackend = DoneBackend(),
                    toolProviders = emptyList(),
                    budgetEnforcer = InMemoryBudgetEnforcer(),
                    eventBus = InProcessEventBus(),
                    auditLog = InMemoryAuditLog(),
                    skillRegistry = skillRegistry,
                    tracer = sdkTracer,
                    config = LLMConfig(model = "test-model"),
                )
            val realRunner = ObservableAgentRunner(realLoop, koreTracer)

            realRunner.run(AgentTask(id = "task-parent", input = "review code")).await()

            val agentRunSpan = exporter.finishedSpanItems.first { it.name == KoreSpans.AGENT_RUN }
            val skillSpan = exporter.finishedSpanItems.first { it.name == KoreSpans.SKILL_ACTIVATE }

            // Parented: the skill span's parent is the agent-run span, in the same trace.
            skillSpan.parentSpanContext.spanId shouldBe agentRunSpan.spanContext.spanId
            skillSpan.spanContext.traceId shouldBe agentRunSpan.spanContext.traceId

            // Tie OBSV-03 attrs + parenting together: the skill names array is present on the span.
            skillSpan.attributes.get(AttributeKey.stringArrayKey(KoreAttrs.SKILL_NAMES)) shouldBe
                listOf("code-review")
        }
}
