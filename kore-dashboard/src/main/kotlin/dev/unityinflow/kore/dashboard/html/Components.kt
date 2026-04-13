package dev.unityinflow.kore.dashboard.html

import dev.unityinflow.kore.dashboard.AgentStatus
import kotlinx.html.BODY
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * Top nav bar shown on every dashboard render.
 *
 * UI-SPEC.md Layout Contract: full-width #F5F5F5 background, "kore dashboard"
 * Display title (24px/600), version label in secondary text.
 */
fun BODY.navBar(version: String) {
    nav(classes = "kore-nav") {
        h1 { +"kore dashboard" }
        span(classes = "version") { +"v$version" }
    }
}

/**
 * HTMX-polled fragment container.
 *
 * Renders an outer `<div>` wired with `hx-get`, `hx-trigger="every Ns"`,
 * `hx-swap="innerHTML"` and an `aria-live` region (assertive for the 2s
 * active-agents poll, polite otherwise). The inner `.kore-fragment` div is
 * the swap target — HTMX replaces its contents on each poll.
 *
 * @param id fragment id used for accessibility + debugging.
 * @param pollSeconds poll interval in seconds (2 / 5 / 10 per UI-SPEC.md).
 * @param path hx-get URL — must match the route registered in
 *             [dev.unityinflow.kore.dashboard.configureDashboardRoutes].
 */
fun FlowContent.fragmentContainer(
    id: String,
    pollSeconds: Int,
    path: String,
    initialContent: FlowContent.() -> Unit = {},
) {
    val ariaLive = if (pollSeconds <= 2) "assertive" else "polite"
    div {
        attributes["id"] = id
        attributes["hx-get"] = path
        attributes["hx-trigger"] = "every ${pollSeconds}s"
        attributes["hx-swap"] = "innerHTML"
        attributes["aria-live"] = ariaLive
        div(classes = "kore-fragment") {
            initialContent()
        }
    }
}

/** UI-SPEC Heading typography (18px/600). */
fun FlowContent.sectionHeading(title: String) {
    h2(classes = "kore-section-heading") { +title }
}

/**
 * Fully-styled data table with caption (screen-reader navigation), header
 * row, and lambda-rendered body rows so callers can mix mono cells, badges
 * and plain text in the same table.
 *
 * @param caption table caption (matches the section heading) — required for
 *                accessibility per UI-SPEC.md Accessibility Contract.
 * @param headers column header labels.
 * @param rows lambda invoked on the `<tbody>` element to emit `<tr>` rows.
 */
fun FlowContent.dataTable(
    caption: String,
    headers: List<String>,
    rows: TBODY.() -> Unit,
) {
    table(classes = "kore-table") {
        caption { +caption }
        thead {
            tr {
                headers.forEach { h -> th { +h } }
            }
        }
        tbody {
            rows()
        }
    }
}

/**
 * Status badge for the active-agents fragment.
 *
 * RUNNING gets the animated pulse dot + accent-blue border (UI-SPEC.md
 * Color contract: accent #2563EB reserved for RUNNING). All variants
 * combine color + text label so colour is never the sole differentiator
 * (UI-SPEC.md Accessibility Contract).
 */
fun FlowContent.statusBadge(status: AgentStatus) {
    val (variant, label) =
        when (status) {
            AgentStatus.RUNNING -> "running" to "RUNNING"
            AgentStatus.IDLE -> "neutral" to "IDLE"
            AgentStatus.COMPLETED -> "success" to "COMPLETED"
            AgentStatus.ERROR -> "error" to "ERROR"
        }
    span(classes = "kore-badge kore-badge--$variant") {
        attributes["aria-label"] = "Status: ${label.lowercase().replaceFirstChar { it.uppercase() }}"
        if (status == AgentStatus.RUNNING) {
            span(classes = "kore-pulse") {}
        }
        +label
    }
}

/**
 * Result badge for the recent-runs fragment.
 *
 * Maps `AgentResult.typeName()` strings (`success`, `budget_exceeded`,
 * `tool_error`, `llm_error`, `cancelled`) to UI-SPEC.md result badge
 * variants and human-readable copy ("BUDGET EXCEEDED", "LLM ERROR", ...).
 * Unknown values fall through to the neutral variant rather than crashing
 * the fragment.
 */
fun FlowContent.resultBadge(resultType: String) {
    val normalized = resultType.lowercase()
    val (variant, label) =
        when (normalized) {
            "success" -> "success" to "SUCCESS"
            "budget_exceeded", "budgetexceeded" -> "warning" to "BUDGET EXCEEDED"
            "tool_error", "toolerror" -> "error" to "TOOL ERROR"
            "llm_error", "llmerror" -> "error" to "LLM ERROR"
            "cancelled" -> "neutral" to "CANCELLED"
            else -> "neutral" to normalized.uppercase()
        }
    span(classes = "kore-badge kore-badge--$variant") {
        attributes["aria-label"] = "Result: $label"
        +label
    }
}

/** Centred empty-state block (UI-SPEC.md Copywriting Contract). */
fun FlowContent.emptyState(
    heading: String,
    body: String,
) {
    div(classes = "kore-empty") {
        h3 { +heading }
        p { +body }
    }
}

/**
 * Italic secondary-text notice rendered when [DashboardDataService.hasStorage]
 * is false. Copy comes from UI-SPEC.md Copywriting Contract.
 */
fun FlowContent.degradedNotice(message: String) {
    div(classes = "kore-degraded") { +message }
}

/** Inline monospace span for agent IDs, run IDs, model names. */
fun FlowContent.monoCell(value: String) {
    span(classes = "kore-mono") { +value }
}
