package dev.unityinflow.kore.dashboard

import dev.unityinflow.kore.dashboard.html.activeAgentsFragment
import dev.unityinflow.kore.dashboard.html.costSummaryFragment
import dev.unityinflow.kore.dashboard.html.fragmentContainer
import dev.unityinflow.kore.dashboard.html.koreDashboardPage
import dev.unityinflow.kore.dashboard.html.recentRunsFragment
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.body

/**
 * Lightweight dashboard configuration. Held by [DashboardServer] and passed
 * through to [configureDashboardRoutes].
 *
 * Mirrors the property-binding shape of `KoreProperties.DashboardProperties`
 * in kore-spring so the auto-config bridge can copy fields 1:1.
 */
data class DashboardConfig(
    val port: Int = 8090,
    val path: String = "/kore",
    val enabled: Boolean = true,
)

/**
 * Registers the four dashboard routes on the given Ktor [Route] block:
 *
 * - `GET {path}` — full page shell with three HTMX fragment containers.
 * - `GET {path}/fragments/active-agents` — in-memory observer snapshot.
 * - `GET {path}/fragments/recent-runs` — AuditLog query (or degraded notice).
 * - `GET {path}/fragments/cost-summary` — AuditLog query (or degraded notice).
 *
 * Receiver is [Route] (not [io.ktor.server.routing.Routing]) because the
 * `routing { }` lambda in Ktor 3.2 has a `Route.() -> Unit` signature —
 * `Routing` extends `Route` so this is the more general (and correct)
 * binding.
 *
 * Ktor 3.2 CIO route handlers are `suspend` — we call the suspend
 * [DashboardDataService] methods directly. Never wrap in `runBlocking`
 * (RESEARCH.md Pitfall 1).
 */
fun Route.configureDashboardRoutes(
    observer: EventBusDashboardObserver,
    dataService: DashboardDataService,
    config: DashboardConfig,
) {
    val basePath = config.path

    get(basePath) {
        call.respondHtml {
            koreDashboardPage {
                fragmentContainer("active-agents", 2, "$basePath/fragments/active-agents")
                fragmentContainer("recent-runs", 5, "$basePath/fragments/recent-runs")
                fragmentContainer("cost-summary", 10, "$basePath/fragments/cost-summary")
            }
        }
    }

    get("$basePath/fragments/active-agents") {
        val snapshot = observer.snapshot()
        call.respondHtml {
            body {
                activeAgentsFragment(snapshot)
            }
        }
    }

    get("$basePath/fragments/recent-runs") {
        val runs = dataService.getRecentRuns(20)
        val degraded = !dataService.hasStorage()
        call.respondHtml {
            body {
                recentRunsFragment(runs, degraded = degraded)
            }
        }
    }

    get("$basePath/fragments/cost-summary") {
        val costs = dataService.getCostSummary()
        val degraded = !dataService.hasStorage()
        call.respondHtml {
            body {
                costSummaryFragment(costs, degraded = degraded)
            }
        }
    }
}
