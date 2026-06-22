package io.github.unityinflow.kore.core

import io.github.unityinflow.kore.core.internal.InMemoryAuditLog
import io.github.unityinflow.kore.core.internal.InMemoryBudgetEnforcer
import io.github.unityinflow.kore.core.internal.InProcessEventBus
import io.github.unityinflow.kore.core.port.AgentTool
import io.github.unityinflow.kore.core.port.AuditLog
import io.github.unityinflow.kore.core.port.LLMBackend
import io.github.unityinflow.kore.core.port.ToolProvider
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AgentTool] — the child-as-tool-call spawn model.
 *
 * Covers HIER-01 (child runs as a tool call, result feeds back), D-01 (a child
 * that RAN and failed → isError=false, parent NOT aborted), D-03/HIER-03 (a spawn
 * beyond maxDepth → isError=true, child never ran), and the in-memory HIER-04
 * run-tree (child record carries parentRunId == parent id).
 *
 * Reuses the inline scriptedBackend/makeLoop helpers from [AgentLoopTest] (copied
 * inline — no cross-module dependency).
 */
class AgentToolTest {
    private fun scriptedBackend(vararg chunks: LLMChunk): LLMBackend =
        object : LLMBackend {
            override val name = "mock"

            override fun call(
                messages: List<ConversationMessage>,
                tools: List<ToolDefinition>,
                config: LLMConfig,
            ) = flow { chunks.forEach { emit(it) } }
        }

    private fun makeLoop(
        backend: LLMBackend,
        toolProviders: List<ToolProvider> = emptyList(),
        maxTokensBudget: Long = Long.MAX_VALUE,
        auditLog: AuditLog = InMemoryAuditLog(),
        maxDepth: Int = 5,
    ): AgentLoop {
        val budgetEnforcer = InMemoryBudgetEnforcer(defaultLimitPerAgent = maxTokensBudget)
        return AgentLoop(
            llmBackend = backend,
            toolProviders = toolProviders,
            budgetEnforcer = budgetEnforcer,
            eventBus = InProcessEventBus(),
            auditLog = auditLog,
            maxDepth = maxDepth,
            config = LLMConfig(model = "test-model"),
        )
    }

    @Test
    fun `HIER-01 child runs as a tool call and parent reaches Success`() =
        runTest {
            // Child backend simply produces final text.
            val childLoop = makeLoop(scriptedBackend(LLMChunk.Text("child answer"), LLMChunk.Done))
            val agentTool =
                AgentTool(
                    childName = "researcher",
                    description = "researches things",
                    childLoop = childLoop,
                    maxDepth = 5,
                )

            // Parent first asks for the child tool, then emits text and is done.
            var parentCall = 0
            val parentBackend =
                object : LLMBackend {
                    override val name = "parent"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow {
                        parentCall++
                        if (parentCall == 1) {
                            emit(LLMChunk.ToolCall(id = "c1", name = "researcher", arguments = """{"input":"go"}"""))
                            emit(LLMChunk.Done)
                        } else {
                            emit(LLMChunk.Text("parent done"))
                            emit(LLMChunk.Done)
                        }
                    }
                }
            val parentLoop = makeLoop(parentBackend, toolProviders = listOf(agentTool))

            val result = parentLoop.run(AgentTask(id = "parent-1", input = "delegate"))

            result.shouldBeInstanceOf<AgentResult.Success>()
            result.output shouldBe "parent done"
        }

    @Test
    fun `D-01 a child that ran and failed maps to isError=false and parent is not ToolError`() =
        runTest {
            // Child backend always throws → child run ends LLMError (it RAN, then failed).
            val failingChildBackend =
                object : LLMBackend {
                    override val name = "child-failing"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow<LLMChunk> { throw RuntimeException("child boom") }
                }
            val childLoop = makeLoop(failingChildBackend)
            val agentTool =
                AgentTool(
                    childName = "worker",
                    description = "does work",
                    childLoop = childLoop,
                    maxDepth = 5,
                )
            // Bind dispatch lineage directly (depth 0 → child depth 1, within limit).
            agentTool.bind(parentDepth = 0, parentRunId = "parent-x")

            val toolResult = agentTool.callTool(ToolCall(id = "tc", name = "worker", arguments = """{"input":"x"}"""))

            // D-01: a child that ran and failed is informational, never isError.
            toolResult.isError shouldBe false

            // And a parent loop consuming such a child does NOT end in ToolError.
            var parentCall = 0
            val parentBackend =
                object : LLMBackend {
                    override val name = "parent"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow {
                        parentCall++
                        if (parentCall == 1) {
                            emit(LLMChunk.ToolCall(id = "c1", name = "worker", arguments = """{"input":"x"}"""))
                            emit(LLMChunk.Done)
                        } else {
                            emit(LLMChunk.Text("recovered"))
                            emit(LLMChunk.Done)
                        }
                    }
                }
            val parentLoop = makeLoop(parentBackend, toolProviders = listOf(agentTool))
            val parentResult = parentLoop.run(AgentTask(id = "parent-2", input = "delegate"))

            (parentResult is AgentResult.ToolError) shouldBe false
            parentResult.shouldBeInstanceOf<AgentResult.Success>()
        }

    @Test
    fun `D-03 spawn beyond maxDepth refuses with isError=true and never runs the child`() =
        runTest {
            var childInvocations = 0
            val childBackend =
                object : LLMBackend {
                    override val name = "child"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow {
                        childInvocations++
                        emit(LLMChunk.Text("should not run"))
                        emit(LLMChunk.Done)
                    }
                }
            val sharedAudit = InMemoryAuditLog()
            val childLoop = makeLoop(childBackend, auditLog = sharedAudit)
            val agentTool =
                AgentTool(
                    childName = "deep",
                    description = "deep child",
                    childLoop = childLoop,
                    maxDepth = 1,
                )
            // Parent already at depth 1 → child depth 2 > maxDepth 1 → refusal.
            agentTool.bind(parentDepth = 1, parentRunId = "parent-deep")

            val toolResult = agentTool.callTool(ToolCall(id = "tc", name = "deep", arguments = """{"input":"x"}"""))

            toolResult.isError shouldBe true
            childInvocations shouldBe 0
            // No child run → no child audit row in the shared log.
            sharedAudit.recordedRuns shouldHaveSize 0

            // Through a parent loop, the isError=true ToolResult becomes ToolError.
            val parentBackend =
                scriptedBackendToolThenNothing()
            val parentLoop =
                makeLoop(parentBackend, toolProviders = listOf(agentTool), maxDepth = 1)
            val parentResult = parentLoop.run(AgentTask(id = "parent-deep", input = "delegate", depth = 1))

            parentResult.shouldBeInstanceOf<AgentResult.ToolError>()
        }

    @Test
    fun `HIER-04 shared audit log records a child run with parentRunId == parent id`() =
        runTest {
            val sharedAudit = InMemoryAuditLog()
            val childLoop =
                makeLoop(
                    scriptedBackend(LLMChunk.Text("child done"), LLMChunk.Done),
                    auditLog = sharedAudit,
                )
            val agentTool =
                AgentTool(
                    childName = "sub",
                    description = "sub agent",
                    childLoop = childLoop,
                    maxDepth = 5,
                )

            var parentCall = 0
            val parentBackend =
                object : LLMBackend {
                    override val name = "parent"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow {
                        parentCall++
                        if (parentCall == 1) {
                            emit(LLMChunk.ToolCall(id = "c1", name = "sub", arguments = """{"input":"go"}"""))
                            emit(LLMChunk.Done)
                        } else {
                            emit(LLMChunk.Text("parent done"))
                            emit(LLMChunk.Done)
                        }
                    }
                }
            val parentLoop = makeLoop(parentBackend, toolProviders = listOf(agentTool), auditLog = sharedAudit)

            parentLoop.run(AgentTask(id = "parent-tree", input = "delegate"))

            // The child record carries the parent's run id (in-memory run tree, HIER-04).
            val childRecord = sharedAudit.recordedRuns.firstOrNull { it.parentRunId == "parent-tree" }
            (childRecord != null) shouldBe true
        }

    // Parent backend that requests the child tool then would loop forever — used
    // only for the D-03 refusal path where the errored ToolResult short-circuits
    // into ToolError before a second LLM call is needed.
    private fun scriptedBackendToolThenNothing(): LLMBackend =
        object : LLMBackend {
            override val name = "parent"

            override fun call(
                messages: List<ConversationMessage>,
                tools: List<ToolDefinition>,
                config: LLMConfig,
            ) = flow {
                emit(LLMChunk.ToolCall(id = "c1", name = "deep", arguments = """{"input":"x"}"""))
                emit(LLMChunk.Done)
            }
        }
}
