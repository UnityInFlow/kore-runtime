package io.github.unityinflow.kore.core.port

/**
 * Dispatch-time binding seam for child-spawning tool providers (A2 binding —
 * RESEARCH Pattern 3 / Pitfall 1).
 *
 * `parentDepth` and `parentRunId` are *runtime* values that only exist once a
 * task is actually running — they cannot be known at construction time. The fixed
 * [ToolProvider.callTool] signature carries no place for them, so [AgentLoop]
 * pushes them into any provider implementing this interface (an `AgentTool`,
 * Plan 03) immediately before the tool-dispatch block.
 *
 * Keeping the seam as its own port interface means [AgentLoop] binds against
 * `is ChildDispatchBinder` without a forward reference to `AgentTool`, so kore-core
 * compiles standalone in wave 1 (D-04 / A2).
 */
interface ChildDispatchBinder {
    /**
     * Bind the current run's lineage into this provider before dispatch.
     *
     * @param parentDepth the running task's [io.github.unityinflow.kore.core.AgentTask.depth];
     *   a spawned child runs at `parentDepth + 1`.
     * @param parentRunId the running agent's id, recorded as the child's
     *   `parentRunId` (D-09); never sourced from LLM/tool input (T-7-02).
     */
    fun bind(
        parentDepth: Int,
        parentRunId: String?,
    )
}
