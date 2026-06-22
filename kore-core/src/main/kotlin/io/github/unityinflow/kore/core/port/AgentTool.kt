package io.github.unityinflow.kore.core.port

import io.github.unityinflow.kore.core.AgentLoop
import io.github.unityinflow.kore.core.AgentResult
import io.github.unityinflow.kore.core.AgentTask
import io.github.unityinflow.kore.core.ToolCall
import io.github.unityinflow.kore.core.ToolDefinition
import io.github.unityinflow.kore.core.ToolResult
import java.util.UUID

/**
 * A [ToolProvider] that spawns a child agent as a single tool call (the spawn
 * model — D-04). The parent LLM invokes this tool like any other; the child
 * [AgentLoop] runs INLINE inside [callTool] (a plain suspend call, NOT an
 * `AgentRunner`) so cancellation propagates through the parent coroutine scope
 * (D-04 / HIER-02).
 *
 * Advertises exactly ONE tool whose name is [childName] with a single required
 * `input` string schema (D-02).
 *
 * Implements [ChildDispatchBinder]: [AgentLoop] binds the running parent's depth
 * and run id into the val-cell holders ([bind]) immediately before dispatch (A2),
 * because those are runtime values the fixed [ToolProvider.callTool] signature
 * cannot carry.
 *
 * Depth ceiling (D-03 / HIER-03 / T-7-01): a spawn at `parentDepth + 1 > maxDepth`
 * is refused BEFORE the child runs — the ONLY `isError = true` path. Every other
 * outcome (a child that actually RAN and then failed) maps to `isError = false`
 * (D-01) so the parent loop does not abort.
 *
 * NOTE (Pitfall 3): child tool names must be unique within the parent's tool
 * surface — a collision with a real tool name would shadow one or the other in
 * `AgentLoop.findProvider`.
 */
class AgentTool(
    private val childName: String,
    private val description: String,
    private val childLoop: AgentLoop,
    private val maxDepth: Int,
) : ToolProvider,
    ChildDispatchBinder {
    // A2 val-cell holders (no `var` — CLAUDE.md). AgentLoop writes index 0 via
    // [bind] right before the tool-dispatch block; [callTool] reads them.
    private val parentDepthCell = IntArray(1)
    private val parentRunIdCell = arrayOfNulls<String>(1)

    override fun bind(
        parentDepth: Int,
        parentRunId: String?,
    ) {
        parentDepthCell[0] = parentDepth
        parentRunIdCell[0] = parentRunId
    }

    override suspend fun listTools(): List<ToolDefinition> =
        listOf(
            ToolDefinition(
                name = childName,
                description = description,
                inputSchema = INPUT_SCHEMA,
            ),
        )

    override suspend fun callTool(call: ToolCall): ToolResult {
        val childDepth = parentDepthCell[0] + 1
        if (childDepth > maxDepth) {
            // D-03 / T-7-01: refuse BEFORE running the child — no child loop, no
            // child audit row. Low-cardinality message (no per-call values).
            return ToolResult(
                toolCallId = call.id,
                content = "child '$childName' refused: maxDepth $maxDepth exceeded",
                isError = true,
            )
        }
        // T-7-07: the single "input" string is opaque text used only as the child's
        // task input — never evaluated, never used as a path/command.
        val input = extractInput(call.arguments)
        val childTask =
            AgentTask(
                id = UUID.randomUUID().toString(),
                input = input,
                depth = childDepth, // D-07
                // T-7-02: parentRunId comes ONLY from the dispatch-time bind (parent
                // agentId), never from call.arguments — the LLM cannot forge lineage.
                parentRunId = parentRunIdCell[0], // D-09
            )
        val childResult = childLoop.run(childTask) // D-04: INLINE suspend call
        return mapResult(call.id, childResult)
    }

    /**
     * D-01 asymmetry: a child that RAN and failed is informational
     * (`isError = false`) so the parent loop does NOT abort (AgentLoop turns the
     * first `isError = true` ToolResult into [AgentResult.ToolError]). Only a
     * depth-limit refusal (handled in [callTool], BEFORE the child runs) is
     * `isError = true` (D-03).
     */
    private fun mapResult(
        toolCallId: String,
        result: AgentResult,
    ): ToolResult =
        when (result) {
            is AgentResult.Success ->
                ToolResult(toolCallId, result.output, isError = false)
            is AgentResult.BudgetExceeded ->
                ToolResult(toolCallId, "child '$childName' budget exceeded", isError = false)
            is AgentResult.ToolError ->
                ToolResult(toolCallId, "child '$childName' tool error: ${result.toolName}", isError = false)
            is AgentResult.LLMError ->
                ToolResult(toolCallId, "child '$childName' LLM error: ${result.backend}", isError = false)
            is AgentResult.Cancelled ->
                ToolResult(toolCallId, "child '$childName' cancelled", isError = false)
        }

    /**
     * Extract the single required `input` string (D-02) defensively from the
     * LLM-supplied JSON object. Falls back to the raw arguments if no `input`
     * field is present, so a sloppily-formatted argument string is still passed
     * to the child rather than dropped.
     */
    private fun extractInput(arguments: String): String {
        val key = "\"input\""
        val keyIndex = arguments.indexOf(key)
        if (keyIndex < 0) return arguments
        val colonIndex = arguments.indexOf(':', startIndex = keyIndex + key.length)
        if (colonIndex < 0) return arguments
        val firstQuote = arguments.indexOf('"', startIndex = colonIndex + 1)
        if (firstQuote < 0) return arguments
        val closingQuote = arguments.indexOf('"', startIndex = firstQuote + 1)
        if (closingQuote < 0) return arguments
        return arguments.substring(firstQuote + 1, closingQuote)
    }

    private companion object {
        /** D-02: single required `input` string property. */
        const val INPUT_SCHEMA =
            """{"type":"object","properties":{"input":{"type":"string",""" +
                """"description":"The subtask for the child agent."}},"required":["input"]}"""
    }
}
