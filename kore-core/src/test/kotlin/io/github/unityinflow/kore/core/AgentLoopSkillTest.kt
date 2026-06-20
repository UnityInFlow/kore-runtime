package io.github.unityinflow.kore.core

import io.github.unityinflow.kore.core.internal.InMemoryAuditLog
import io.github.unityinflow.kore.core.internal.InMemoryBudgetEnforcer
import io.github.unityinflow.kore.core.internal.InProcessEventBus
import io.github.unityinflow.kore.core.port.ActivatedSkill
import io.github.unityinflow.kore.core.port.EventBus
import io.github.unityinflow.kore.core.port.LLMBackend
import io.github.unityinflow.kore.core.port.NoOpSkillRegistry
import io.github.unityinflow.kore.core.port.SkillRegistry
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

class AgentLoopSkillTest {
    /**
     * Records the exact history snapshot the LLM saw on its first call.
     * Used to assert skill injection happens BEFORE the first LLM call.
     */
    private class RecordingBackend(
        private val responseChunks: List<LLMChunk>,
    ) : LLMBackend {
        override val name = "recording"
        val firstCallHistory = mutableListOf<ConversationMessage>()
        private var callCount = 0

        override fun call(
            messages: List<ConversationMessage>,
            tools: List<ToolDefinition>,
            config: LLMConfig,
        ) = flow {
            if (callCount == 0) {
                firstCallHistory.addAll(messages)
            }
            callCount++
            responseChunks.forEach { emit(it) }
        }
    }

    private fun doneResponse() =
        listOf(
            LLMChunk.Text("ok"),
            LLMChunk.Usage(inputTokens = 1, outputTokens = 1),
            LLMChunk.Done,
        )

    private fun makeLoop(
        backend: LLMBackend,
        skillRegistry: SkillRegistry = NoOpSkillRegistry,
        tracer: io.opentelemetry.api.trace.Tracer? = null,
        eventBus: EventBus = InProcessEventBus(),
    ): AgentLoop =
        AgentLoop(
            llmBackend = backend,
            toolProviders = emptyList(),
            budgetEnforcer = InMemoryBudgetEnforcer(),
            eventBus = eventBus,
            auditLog = InMemoryAuditLog(),
            skillRegistry = skillRegistry,
            tracer = tracer,
            config = LLMConfig(model = "test-model"),
        )

    @Test
    fun `Test 1 - NoOpSkillRegistry produces no System message in history on first LLM call`() =
        runTest {
            val backend = RecordingBackend(doneResponse())
            val loop = makeLoop(backend, skillRegistry = NoOpSkillRegistry)

            loop.run(AgentTask(id = "t1", input = "hello"))

            backend.firstCallHistory.any { it.role == ConversationMessage.Role.System } shouldBe false
        }

    @Test
    fun `Test 2 - SkillRegistry returning prompts inserts System message at history position 0`() =
        runTest {
            val backend = RecordingBackend(doneResponse())
            val skillRegistry =
                object : SkillRegistry {
                    override suspend fun activateFor(
                        taskContent: String,
                        availableTools: List<String>,
                    ): List<ActivatedSkill> = listOf(ActivatedSkill(name = "expert", prompt = "You are an expert"))
                }
            val loop = makeLoop(backend, skillRegistry = skillRegistry)

            loop.run(AgentTask(id = "t2", input = "review code"))

            backend.firstCallHistory[0].role shouldBe ConversationMessage.Role.System
            backend.firstCallHistory[0].content shouldBe "You are an expert"
        }

    @Test
    fun `Test 3 - SkillRegistry returning empty list does not insert System message`() =
        runTest {
            val backend = RecordingBackend(doneResponse())
            val skillRegistry =
                object : SkillRegistry {
                    override suspend fun activateFor(
                        taskContent: String,
                        availableTools: List<String>,
                    ): List<ActivatedSkill> = emptyList()
                }
            val loop = makeLoop(backend, skillRegistry = skillRegistry)

            loop.run(AgentTask(id = "t3", input = "hello world"))

            backend.firstCallHistory.any { it.role == ConversationMessage.Role.System } shouldBe false
        }

    @Test
    fun `Test 4 - InMemoryAuditLog queryRecentRuns returns empty list`() =
        runTest {
            val log = InMemoryAuditLog()
            log.queryRecentRuns(5) shouldContainExactly emptyList()
        }

    @Test
    fun `Test 5 - InMemoryAuditLog queryCostSummary returns empty list`() =
        runTest {
            val log = InMemoryAuditLog()
            log.queryCostSummary() shouldContainExactly emptyList()
        }

    @Test
    fun `Test 6 - tracer non-null emits kore_skill_activate span and tracer null emits no span`() =
        runTest {
            // Part A: tracer present -> span emitted
            val exporter = InMemorySpanExporter.create()
            val provider =
                SdkTracerProvider
                    .builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            val tracer = provider.get("kore-core-test")

            val skillRegistry =
                object : SkillRegistry {
                    override suspend fun activateFor(
                        taskContent: String,
                        availableTools: List<String>,
                    ): List<ActivatedSkill> = listOf(ActivatedSkill(name = "demo-skill", prompt = "skill prompt"))
                }

            val backendA = RecordingBackend(doneResponse())
            val loopA = makeLoop(backendA, skillRegistry = skillRegistry, tracer = tracer)
            val resultA = loopA.run(AgentTask(id = "t6a", input = "go"))
            resultA.shouldBeInstanceOf<AgentResult.Success>()

            val spans = exporter.finishedSpanItems
            spans.size shouldBeGreaterThanOrEqual 1
            spans.any { it.name == "kore.skill.activate" } shouldBe true

            // Part B: tracer null -> no span exporter interaction, activation still works
            val exporterB = InMemorySpanExporter.create()
            val backendB = RecordingBackend(doneResponse())
            val loopB = makeLoop(backendB, skillRegistry = skillRegistry, tracer = null)
            val resultB = loopB.run(AgentTask(id = "t6b", input = "go"))
            resultB.shouldBeInstanceOf<AgentResult.Success>()

            // Exporter B received no spans because no tracer was wired into loopB.
            exporterB.finishedSpanItems.size shouldBe 0
            // And skill prompt still got injected
            backendB.firstCallHistory[0].role shouldBe ConversationMessage.Role.System
            backendB.firstCallHistory[0].content shouldBe "skill prompt"
        }

    @Test
    fun `Test 7 - span carries names_count_duration attrs on at-least-one match`() =
        runTest {
            val exporter = InMemorySpanExporter.create()
            val provider =
                SdkTracerProvider
                    .builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            val tracer = provider.get("kore-core-test")

            val skillRegistry =
                object : SkillRegistry {
                    override suspend fun activateFor(
                        taskContent: String,
                        availableTools: List<String>,
                    ): List<ActivatedSkill> =
                        listOf(
                            ActivatedSkill(name = "code-review", prompt = "p1"),
                            ActivatedSkill(name = "security-audit", prompt = "p2"),
                        )
                }

            val loop = makeLoop(RecordingBackend(doneResponse()), skillRegistry = skillRegistry, tracer = tracer)
            loop.run(AgentTask(id = "t7", input = "review code")).shouldBeInstanceOf<AgentResult.Success>()

            val span = exporter.finishedSpanItems.first { it.name == "kore.skill.activate" }
            span.attributes.get(AttributeKey.stringArrayKey("kore.skill.names")) shouldContainExactly
                listOf("code-review", "security-audit")
            span.attributes.get(AttributeKey.longKey("kore.skill.count")) shouldBe 2L
            (span.attributes.get(AttributeKey.longKey("kore.skill.duration_ms")) ?: -1L) shouldBeGreaterThanOrEqual 0L
        }

    @Test
    fun `Test 8 - SkillActivated event emitted once on at-least-one match`() =
        runTest {
            val bus = InProcessEventBus()
            val collected = mutableListOf<AgentEvent>()
            val job = backgroundScope.launch { bus.subscribe().toList(collected) }
            runCurrent() // ensure the collector is subscribed before run() emits

            val skillRegistry =
                object : SkillRegistry {
                    override suspend fun activateFor(
                        taskContent: String,
                        availableTools: List<String>,
                    ): List<ActivatedSkill> = listOf(ActivatedSkill(name = "code-review", prompt = "p1"))
                }

            val loop = makeLoop(RecordingBackend(doneResponse()), skillRegistry = skillRegistry, eventBus = bus)
            loop.run(AgentTask(id = "t8", input = "review code"))
            yield()
            runCurrent()
            job.cancel()

            val skillEvents = collected.filterIsInstance<AgentEvent.SkillActivated>()
            skillEvents.size shouldBe 1
            skillEvents.first().agentId shouldBe "t8"
            skillEvents.first().skillNames shouldContainExactly listOf("code-review")
            skillEvents.first().durationMs shouldBeGreaterThanOrEqual 0L
        }

    @Test
    fun `Test 9 - no SkillActivated event on zero match but span still emitted with count zero`() =
        runTest {
            val exporter = InMemorySpanExporter.create()
            val provider =
                SdkTracerProvider
                    .builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            val tracer = provider.get("kore-core-test")

            val bus = InProcessEventBus()
            val collected = mutableListOf<AgentEvent>()
            val job = backgroundScope.launch { bus.subscribe().toList(collected) }
            runCurrent()

            val loop =
                makeLoop(
                    RecordingBackend(doneResponse()),
                    skillRegistry = NoOpSkillRegistry,
                    tracer = tracer,
                    eventBus = bus,
                )
            loop.run(AgentTask(id = "t9", input = "hello world"))
            yield()
            runCurrent()
            job.cancel()

            // D-07: no event emitted on 0-match.
            collected.filterIsInstance<AgentEvent.SkillActivated>().shouldBeEmpty()
            // D-04: span still emitted with count=0 and empty names.
            val span = exporter.finishedSpanItems.first { it.name == "kore.skill.activate" }
            span.attributes.get(AttributeKey.longKey("kore.skill.count")) shouldBe 0L
            span.attributes.get(AttributeKey.stringArrayKey("kore.skill.names")) shouldContainExactly emptyList()
        }

    @Test
    fun `Test 10 - span still finished when activateFor throws`() =
        runTest {
            val exporter = InMemorySpanExporter.create()
            val provider =
                SdkTracerProvider
                    .builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            val tracer = provider.get("kore-core-test")

            val throwingRegistry =
                object : SkillRegistry {
                    override suspend fun activateFor(
                        taskContent: String,
                        availableTools: List<String>,
                    ): List<ActivatedSkill> = throw IllegalStateException("boom")
                }

            val loop = makeLoop(RecordingBackend(doneResponse()), skillRegistry = throwingRegistry, tracer = tracer)
            // The loop never throws (INVARIANT); activateFor's throw surfaces as an LLMError result.
            loop.run(AgentTask(id = "t10", input = "go")).shouldBeInstanceOf<AgentResult.LLMError>()

            // Pitfall 5: span is still ended even though activateFor threw.
            val span = exporter.finishedSpanItems.firstOrNull { it.name == "kore.skill.activate" }
            span shouldNotBe null
            span?.attributes?.get(AttributeKey.longKey("kore.skill.count")) shouldBe 0L
        }
}
