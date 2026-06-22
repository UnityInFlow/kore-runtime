package io.github.unityinflow.kore.core.port

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-coroutine carrier of a spawning parent's lineage (CR-01 fix).
 *
 * `parentDepth` and `parentRunId` are *runtime* values that only exist once a task
 * is actually running. The fixed [ToolProvider.callTool] signature carries no place
 * for them, so [io.github.unityinflow.kore.core.AgentLoop] installs this element into
 * the [CoroutineContext] of its tool-dispatch scope. A child-spawning provider
 * ([AgentTool]) reads it back from `coroutineContext[ChildLineage]` INSIDE
 * `callTool`, so each concurrently-dispatched child observes its OWN parent lineage.
 *
 * This replaces the previous shared-mutable instance cells in `AgentTool` that raced
 * under concurrent parent runs sharing one provider instance (07-VERIFICATION CR-01):
 * the cells were written by `bind()` before dispatch and read by `callTool()` after
 * suspension, so a second parent run clobbered the first run's lineage. A
 * [CoroutineContext] element is immutable and inherited per-coroutine, so there is no
 * cross-run shared state to corrupt.
 *
 * Immutable (`val` only, no shared mutable state).
 *
 * @property parentDepth the running task's [io.github.unityinflow.kore.core.AgentTask.depth];
 *   a spawned child runs at `parentDepth + 1`.
 * @property parentRunId the running agent's id, recorded as the child's `parentRunId`
 *   (D-09); never sourced from LLM/tool input (T-7-02).
 */
class ChildLineage(
    val parentDepth: Int,
    val parentRunId: String?,
) : AbstractCoroutineContextElement(ChildLineage) {
    /** Context key used to retrieve [ChildLineage] from a [CoroutineContext]. */
    companion object Key : CoroutineContext.Key<ChildLineage>
}
