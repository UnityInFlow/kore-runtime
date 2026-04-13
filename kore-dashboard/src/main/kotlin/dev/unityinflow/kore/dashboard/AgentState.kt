package dev.unityinflow.kore.dashboard

import java.time.Instant

/**
 * Snapshot of an in-flight agent maintained by [EventBusDashboardObserver].
 *
 * Populated by `AgentEvent.AgentStarted`, mutated by `LLMCallCompleted`,
 * removed on `AgentCompleted`. Surfaced in the `/kore/fragments/active-agents`
 * HTMX fragment.
 */
data class AgentState(
    val name: String,
    val status: AgentStatus,
    val currentTask: String = "",
    val tokensUsed: Long = 0L,
    val startedAt: Instant = Instant.now(),
)

/**
 * Lifecycle status of an active agent in the dashboard view.
 *
 * Only [RUNNING] is emitted by the current observer; the other variants exist
 * so future enrichment (idle wait, completed-but-still-rendered, error sticky)
 * can be added without breaking the fragment HTML contract.
 */
enum class AgentStatus { RUNNING, IDLE, COMPLETED, ERROR }
