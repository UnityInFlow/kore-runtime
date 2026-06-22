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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Cancellation tests for the hierarchical agent loop.
 *
 * Covers HIER-02 / criterion #2 / D-06: cancelling a parent cancels a running
 * child promptly (a single-level test satisfies criterion #2; grandchild
 * propagation is deferred — D-06). And D-05: a cancelled run leaves exactly ONE
 * `Cancelled` audit row.
 *
 * Uses `backgroundScope` + `runCurrent` (kotlinx-coroutines-test), NEVER
 * `advanceUntilIdle` — the child suspends on a never-completing primitive, so
 * `advanceUntilIdle` would hang (RESEARCH Pitfall 5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopCancellationTest {
    private fun makeLoop(
        backend: LLMBackend,
        toolProviders: List<ToolProvider> = emptyList(),
        auditLog: AuditLog = InMemoryAuditLog(),
    ): AgentLoop =
        AgentLoop(
            llmBackend = backend,
            toolProviders = toolProviders,
            budgetEnforcer = InMemoryBudgetEnforcer(defaultLimitPerAgent = Long.MAX_VALUE),
            eventBus = InProcessEventBus(),
            auditLog = auditLog,
            config = LLMConfig(model = "test-model"),
        )

    @Test
    fun `HIER-02 cancelling the parent cancels a running child promptly`() =
        runTest {
            // Latch the test can observe: completes (with cancellation) once the
            // child's suspended LLM call is cancelled.
            val childCancelled = CompletableDeferred<Boolean>()

            // Child backend suspends forever inside its flow, observing cancellation.
            val neverEndingChildBackend =
                object : LLMBackend {
                    override val name = "child-blocking"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow<LLMChunk> {
                        try {
                            CompletableDeferred<Unit>().await() // never completes
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            childCancelled.complete(true)
                            throw e // re-throw — structured concurrency (T-03-03)
                        }
                    }
                }
            val childLoop = makeLoop(neverEndingChildBackend)
            val agentTool =
                AgentTool(
                    childName = "blocker",
                    description = "blocks forever",
                    childLoop = childLoop,
                    maxDepth = 5,
                )

            // Parent requests the child tool, then would suspend inside the child run.
            val parentBackend =
                object : LLMBackend {
                    override val name = "parent"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow {
                        emit(LLMChunk.ToolCall(id = "c1", name = "blocker", arguments = """{"input":"x"}"""))
                        emit(LLMChunk.Done)
                    }
                }
            val parentLoop = makeLoop(parentBackend, toolProviders = listOf(agentTool))

            val parentJob =
                backgroundScope.launch {
                    parentLoop.run(AgentTask(id = "parent-cancel", input = "delegate"))
                }

            // Let the parent reach the suspended child run.
            runCurrent()
            childCancelled.isCompleted shouldBe false

            // Cancel the parent — cancellation must propagate into the inline child.
            parentJob.cancel()
            runCurrent()

            childCancelled.isCompleted shouldBe true
            childCancelled.await() shouldBe true
        }

    @Test
    fun `D-05 a cancelled run leaves exactly one Cancelled audit row`() =
        runTest {
            val sharedAudit = InMemoryAuditLog()
            val blockingBackend =
                object : LLMBackend {
                    override val name = "blocking"

                    override fun call(
                        messages: List<ConversationMessage>,
                        tools: List<ToolDefinition>,
                        config: LLMConfig,
                    ) = flow<LLMChunk> {
                        CompletableDeferred<Unit>().await() // never completes
                    }
                }
            val loop = makeLoop(blockingBackend, auditLog = sharedAudit)

            val job =
                backgroundScope.launch {
                    loop.run(AgentTask(id = "cancel-row", input = "hello"))
                }
            runCurrent()
            job.cancel()
            runCurrent()

            val cancelledRows = sharedAudit.recordedRuns.filter { it.resultType == "Cancelled" }
            cancelledRows shouldHaveSize 1
        }
}
