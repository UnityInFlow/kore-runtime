package dev.unityinflow.kore.dashboard.html

import dev.unityinflow.kore.core.port.AgentCostRecord
import dev.unityinflow.kore.core.port.AgentRunRecord
import dev.unityinflow.kore.dashboard.AgentState
import kotlinx.html.FlowContent
import kotlinx.html.td
import kotlinx.html.tr
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val displayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

private fun Instant.formatted(): String = displayFormatter.format(this)

private fun String.truncate(max: Int): String = if (length <= max) this else take(max - 1) + "…"

private fun runningSince(startedAt: Instant): String {
    val seconds = Duration.between(startedAt, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}

/**
 * Renders the `/kore/fragments/active-agents` HTMX fragment body.
 *
 * Empty map → empty-state with UI-SPEC copy. Otherwise a `kore-table` keyed
 * by agent name with mono-cell agent id, status badge, truncated current
 * task, accumulated tokens, and a relative "running since" duration.
 */
fun FlowContent.activeAgentsFragment(agents: Map<String, AgentState>) {
    sectionHeading("Active Agents")
    if (agents.isEmpty()) {
        emptyState(
            heading = "No agents running",
            body = "Agents appear here as soon as they start. Check your agent configuration.",
        )
        return
    }
    dataTable(
        caption = "Active Agents",
        headers = listOf("Agent Name", "Status", "Current Task", "Tokens Used", "Running Since"),
    ) {
        agents.values.forEach { state ->
            tr {
                td { monoCell(state.name) }
                td { statusBadge(state.status) }
                td { +state.currentTask.truncate(60) }
                td { +state.tokensUsed.toString() }
                td { +runningSince(state.startedAt) }
            }
        }
    }
}

/**
 * Renders the `/kore/fragments/recent-runs` HTMX fragment body.
 *
 * `degraded == true` → degraded notice (kore-storage absent, D-27).
 * Empty list → "No runs recorded" empty state.
 * Otherwise a `kore-table` of [AgentRunRecord] with result badges.
 */
fun FlowContent.recentRunsFragment(
    runs: List<AgentRunRecord>,
    degraded: Boolean,
) {
    sectionHeading("Recent Runs")
    if (degraded) {
        degradedNotice(
            "History unavailable — kore-storage not configured. " +
                "Configure kore.storage.url in application.yml to enable run history.",
        )
        return
    }
    if (runs.isEmpty()) {
        emptyState(
            heading = "No runs recorded",
            body = "Completed agent runs appear here. Runs are stored in PostgreSQL when kore-storage is configured.",
        )
        return
    }
    dataTable(
        caption = "Recent Runs",
        headers = listOf("Agent Name", "Result", "Input Tokens", "Output Tokens", "Duration", "Completed At"),
    ) {
        runs.forEach { run ->
            tr {
                td { monoCell(run.agentName) }
                td { resultBadge(run.resultType) }
                td { +run.inputTokens.toString() }
                td { +run.outputTokens.toString() }
                td { +"${run.durationMs}ms" }
                td { +run.completedAt.formatted() }
            }
        }
    }
}

/**
 * Renders the `/kore/fragments/cost-summary` HTMX fragment body.
 *
 * `degraded == true` → degraded notice. Empty list → empty state.
 * Otherwise a `kore-table` with one row per agent and a bold footer row of
 * grand totals across all agents.
 */
fun FlowContent.costSummaryFragment(
    costs: List<AgentCostRecord>,
    degraded: Boolean,
) {
    sectionHeading("Token Cost Summary")
    if (degraded) {
        degradedNotice("Cost history unavailable — kore-storage not configured.")
        return
    }
    if (costs.isEmpty()) {
        emptyState(
            heading = "No cost data",
            body = "Token costs appear here after agents complete runs.",
        )
        return
    }

    val totalRuns = costs.sumOf { it.totalRuns }
    val totalIn = costs.sumOf { it.totalInputTokens }
    val totalOut = costs.sumOf { it.totalOutputTokens }
    val grandTotal = totalIn + totalOut

    dataTable(
        caption = "Token Cost Summary",
        headers = listOf("Agent Name", "Total Runs", "Total Input Tokens", "Total Output Tokens", "Total Tokens"),
    ) {
        costs.forEach { cost ->
            tr {
                td { monoCell(cost.agentName) }
                td { +cost.totalRuns.toString() }
                td { +cost.totalInputTokens.toString() }
                td { +cost.totalOutputTokens.toString() }
                td { +(cost.totalInputTokens + cost.totalOutputTokens).toString() }
            }
        }
        tr {
            td {
                attributes["style"] = "font-weight: 600;"
                +"TOTAL"
            }
            td {
                attributes["style"] = "font-weight: 600;"
                +totalRuns.toString()
            }
            td {
                attributes["style"] = "font-weight: 600;"
                +totalIn.toString()
            }
            td {
                attributes["style"] = "font-weight: 600;"
                +totalOut.toString()
            }
            td {
                attributes["style"] = "font-weight: 600;"
                +grandTotal.toString()
            }
        }
    }
}
