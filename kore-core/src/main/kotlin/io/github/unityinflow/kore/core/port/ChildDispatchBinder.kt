package io.github.unityinflow.kore.core.port

/**
 * Dispatch-time binding seam for child-spawning tool providers (A2 binding —
 * RESEARCH Pattern 3 / Pitfall 1).
 *
 * `parentDepth` and `parentRunId` are *runtime* values that only exist once a
 * task is actually running — they cannot be known at construction time. The fixed
 * [ToolProvider.callTool] signature carries no place for them, so [io.github.unityinflow.kore.core.AgentLoop]
 * asks any provider implementing this interface (an `AgentTool`) for a [ChildLineage]
 * element and installs it into the [kotlin.coroutines.CoroutineContext] of the
 * tool-dispatch scope immediately before dispatch.
 *
 * CR-01 fix (07-VERIFICATION): [bind] is now a pure function that RETURNS the lineage
 * as an immutable [ChildLineage] element instead of mutating shared instance cells.
 * The previous mutating contract raced under concurrent parent runs sharing one
 * provider instance (the second run clobbered the first run's cells before the first
 * child read them). Carrying lineage per-coroutine via the returned element removes
 * the cross-run shared state entirely.
 *
 * Keeping the seam as its own port interface means [io.github.unityinflow.kore.core.AgentLoop]
 * binds against `is ChildDispatchBinder` without a forward reference to `AgentTool`, so
 * kore-core compiles standalone (D-04 / A2).
 */
interface ChildDispatchBinder {
    /**
     * Build the current run's lineage element for installation into the dispatch
     * coroutine context. Pure — does NOT mutate provider state (CR-01).
     *
     * @param parentDepth the running task's [io.github.unityinflow.kore.core.AgentTask.depth];
     *   a spawned child runs at `parentDepth + 1`.
     * @param parentRunId the running agent's id, recorded as the child's
     *   `parentRunId` (D-09); never sourced from LLM/tool input (T-7-02).
     * @return an immutable [ChildLineage] element the loop installs per-coroutine.
     */
    fun bind(
        parentDepth: Int,
        parentRunId: String?,
    ): ChildLineage
}
