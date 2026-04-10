package dev.unityinflow.kore.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job

/**
 * Manages the coroutine scope for agent execution.
 * Uses [SupervisorJob] so a single agent failure does not cancel other running agents.
 *
 * Per D-18: semaphore per backend is the responsibility of the backend adapter.
 * Per D-19: child agents MUST be launched in this scope (not a new scope) so that
 * cancelling the runner cancels all child coroutines.
 *
 * NOTE: OpenTelemetryContextElement will be added in Phase 2 (kore-observability).
 * Placeholder comment kept here per architecture doc Pattern 4.
 */
class AgentRunner(
    private val loop: AgentLoop,
    // OpenTelemetryContextElement will be added in Phase 2
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Run the agent asynchronously. Returns a [Deferred]<[AgentResult]>. */
    fun run(task: AgentTask): Deferred<AgentResult> =
        scope.async {
            loop.run(task)
        }

    /** Cancel all running agents in this runner's scope. */
    fun shutdown() {
        scope.coroutineContext.job.cancel()
    }
}
