package dev.unityinflow.kore.storage

import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.TokenUsage
import dev.unityinflow.kore.core.ToolCall
import dev.unityinflow.kore.core.ToolResult
import dev.unityinflow.kore.core.port.AgentCostRecord
import dev.unityinflow.kore.core.port.AgentRunRecord
import dev.unityinflow.kore.core.port.AuditLog
import dev.unityinflow.kore.storage.tables.AgentRunsTable
import dev.unityinflow.kore.storage.tables.LlmCallsTable
import dev.unityinflow.kore.storage.tables.ToolCallsTable
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * PostgreSQL-backed implementation of the [AuditLog] port.
 *
 * Append-only: only INSERT operations. No UPDATE or DELETE (D-16).
 * Uses Exposed R2DBC [suspendTransaction] — NOT Spring @Transactional (D-18, Pitfall 7).
 * Schema created by Flyway migration V1__init_schema.sql before this adapter is used.
 *
 * NOTE (T-02-05): task.input may contain user PII. Callers must sanitize PII before
 * creating AgentTask if audit retention is enabled. A future kore-spring property
 * kore.storage.mask-task-content=true can truncate/hash this column.
 */
class PostgresAuditLogAdapter(
    private val database: R2dbcDatabase,
    /**
     * ME-03 safety ceiling for [queryCostSummary]. The query currently
     * aggregates in Kotlin (SQL GROUP BY is deferred until Exposed R2DBC
     * dialect quirks are resolved), so without an upper bound a long-lived
     * deployment would drag millions of (run × call) rows over R2DBC on
     * every 10-second dashboard poll and OOM the JVM. The default of
     * 100_000 rows is well above the "dashboard scale" design target but
     * well below the heap-pressure threshold on a typical 512 MiB Spring
     * Boot container. A future fix (proper SQL GROUP BY or a time-window
     * predicate) can replace this ceiling — see ME-03 in 03-REVIEW.md.
     */
    private val costSummaryMaxRows: Int = 100_000,
) : AuditLog {
    override val isPersistent: Boolean = true

    override suspend fun recordAgentRun(
        agentId: String,
        task: AgentTask,
        result: AgentResult,
    ) {
        suspendTransaction(database) {
            AgentRunsTable.insert { stmt ->
                stmt[id] = UUID.fromString(agentId)
                stmt[agentName] = task.metadata["agent_name"] ?: "unknown"
                stmt[AgentRunsTable.task] = task.input
                stmt[resultType] = result.typeName()
                stmt[startedAt] = OffsetDateTime.now()
                stmt[finishedAt] = OffsetDateTime.now()
                stmt[metadata] = "{}" // base metadata; enrichment via kore-spring in Phase 3
            }
        }
    }

    override suspend fun recordLLMCall(
        agentId: String,
        backend: String,
        usage: TokenUsage,
    ) {
        suspendTransaction(database) {
            LlmCallsTable.insert { stmt ->
                stmt[runId] = UUID.fromString(agentId)
                stmt[model] = backend
                stmt[tokensIn] = usage.inputTokens
                stmt[tokensOut] = usage.outputTokens
                stmt[durationMs] = 0 // duration tracked at observability layer (OTel span)
                stmt[metadata] = "{}"
            }
        }
    }

    override suspend fun recordToolCall(
        agentId: String,
        call: ToolCall,
        result: ToolResult,
    ) {
        suspendTransaction(database) {
            ToolCallsTable.insert { stmt ->
                stmt[runId] = UUID.fromString(agentId)
                stmt[llmCallId] = null // correlated in Phase 3 with span context linkage
                stmt[toolName] = call.name
                stmt[mcpServer] = null // MCP server tracking added in Phase 3 via context
                stmt[durationMs] = 0
                stmt[arguments] = call.arguments // already a JSON string per kore-core design
                stmt[ToolCallsTable.result] = result.toJsonString()
            }
        }
    }

    /**
     * Dashboard read query: the [limit] most recent agent runs joined with
     * their LLM calls, ordered by `finished_at DESC` (D-26, Pattern 11).
     *
     * ME-04: uses a LEFT join on [LlmCallsTable] so that agent runs which
     * terminated before their first LLM call (e.g. `BudgetExceeded` on the
     * pre-call budget check, `LLMError` from an auth failure on the very
     * first request, `Cancelled` before the first call completed) still
     * appear in the dashboard view — these are exactly the failures an
     * operator most needs to see. Null LLM-call columns fall back to 0.
     *
     * ME-05: in-progress runs (rows where `finished_at` is NULL) are
     * filtered out with `where { finishedAt.isNotNull() }`. The dashboard
     * fragment previously rendered their completedAt as `1970-01-01` via
     * a fallback to `Instant.EPOCH`, which was misleading.
     *
     * Task content is deliberately NOT projected (T-03-03, T-02-05).
     */
    override suspend fun queryRecentRuns(limit: Int): List<AgentRunRecord> =
        suspendTransaction(database) {
            AgentRunsTable
                .leftJoin(LlmCallsTable)
                .select(
                    AgentRunsTable.agentName,
                    AgentRunsTable.resultType,
                    AgentRunsTable.finishedAt,
                    LlmCallsTable.tokensIn,
                    LlmCallsTable.tokensOut,
                    LlmCallsTable.durationMs,
                ).where { AgentRunsTable.finishedAt.isNotNull() }
                .orderBy(AgentRunsTable.finishedAt, SortOrder.DESC)
                .limit(limit)
                .toList()
                .map { row ->
                    // Left-join can yield NULL LLM-call columns; getOrNull
                    // maps NULL to null for any column type.
                    val inTokens = row.getOrNull(LlmCallsTable.tokensIn) ?: 0
                    val outTokens = row.getOrNull(LlmCallsTable.tokensOut) ?: 0
                    val duration = row.getOrNull(LlmCallsTable.durationMs)?.toLong() ?: 0L
                    // finishedAt is guaranteed non-null here by the WHERE
                    // clause above; fall back to Instant.EPOCH defensively
                    // if a future schema change re-introduces nulls.
                    val finished =
                        row[AgentRunsTable.finishedAt]?.toInstant()
                            ?: Instant.EPOCH
                    AgentRunRecord(
                        agentName = row[AgentRunsTable.agentName],
                        resultType = row[AgentRunsTable.resultType],
                        inputTokens = inTokens,
                        outputTokens = outTokens,
                        durationMs = duration,
                        completedAt = finished,
                    )
                }
        }

    /**
     * Dashboard read query: per-agent-name token totals and run counts.
     *
     * Implementation note (per plan): grouping is done in Kotlin rather than
     * via SQL GROUP BY to sidestep Exposed R2DBC dialect quirks around mixing
     * non-aggregated columns with aggregates. Pulls the join result into memory
     * and folds on `agentName`.
     *
     * ME-03: capped at [costSummaryMaxRows] rows (default 100_000) to prevent
     * the 10-second dashboard poll from dragging millions of (run × call) rows
     * into the JVM heap on long-lived deployments. This is a safety ceiling,
     * not a correctness mechanism — when the ceiling is reached the aggregate
     * is under-counted and a warning is logged. Proper fix (time-window
     * predicate or SQL GROUP BY) is tracked by ME-03 in 03-REVIEW.md.
     *
     * ME-04: uses a LEFT join so that agent runs with zero LLM calls still
     * contribute to `totalRuns`. Null LLM-call columns fall back to 0 tokens.
     */
    override suspend fun queryCostSummary(): List<AgentCostRecord> =
        suspendTransaction(database) {
            val rows =
                AgentRunsTable
                    .leftJoin(LlmCallsTable)
                    .select(
                        AgentRunsTable.id,
                        AgentRunsTable.agentName,
                        LlmCallsTable.tokensIn,
                        LlmCallsTable.tokensOut,
                    ).limit(costSummaryMaxRows)
                    .toList()
            if (rows.size >= costSummaryMaxRows) {
                System.err.println(
                    "kore-storage: queryCostSummary hit costSummaryMaxRows=$costSummaryMaxRows ceiling " +
                        "(ME-03); aggregate is under-counted. Shrink retention or add a time-window predicate.",
                )
            }
            rows
                .groupBy { it[AgentRunsTable.agentName] }
                .map { (agentName, rows) ->
                    // Each row is one llm_call (or null for a run with no
                    // calls); count distinct agent_run ids for totalRuns.
                    val distinctRunIds =
                        rows.mapTo(HashSet()) { it[AgentRunsTable.id].value }
                    AgentCostRecord(
                        agentName = agentName,
                        totalRuns = distinctRunIds.size,
                        totalInputTokens = rows.sumOf { (it.getOrNull(LlmCallsTable.tokensIn) ?: 0).toLong() },
                        totalOutputTokens = rows.sumOf { (it.getOrNull(LlmCallsTable.tokensOut) ?: 0).toLong() },
                    )
                }
        }
}

/** Maps [AgentResult] to a stable string for the result_type column. */
private fun AgentResult.typeName(): String =
    when (this) {
        is AgentResult.Success -> "success"
        is AgentResult.BudgetExceeded -> "budget_exceeded"
        is AgentResult.ToolError -> "tool_error"
        is AgentResult.LLMError -> "llm_error"
        is AgentResult.Cancelled -> "cancelled"
    }

/** Serializes [ToolResult] to a JSON string without requiring kotlinx.serialization. */
private fun ToolResult.toJsonString(): String {
    val escapedContent = content.replace("\\", "\\\\").replace("\"", "\\\"")
    return """{"content":"$escapedContent","isError":$isError}"""
}
