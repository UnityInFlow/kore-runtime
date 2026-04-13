---
phase: 03-skills-spring-dashboard
plan: 03
subsystem: dashboard
tags: [kotlin, ktor, htmx, kotlinx-html, smart-lifecycle, hexagonal, dashboard]

requires:
  - phase: 01-core-runtime
    provides: EventBus port + InProcessEventBus, AgentEvent sealed hierarchy, TokenUsage
  - phase: 02-observability-storage
    provides: PostgresAuditLogAdapter (consumed via the AuditLog port at runtime)
  - phase: 03-skills-spring-dashboard plan 01
    provides: AuditLog.queryRecentRuns + queryCostSummary + AgentRunRecord/AgentCostRecord
  - phase: 03-skills-spring-dashboard plan 02
    provides: KoreProperties.DashboardProperties + DashboardAutoConfiguration reflective bridge expecting (EventBus, AuditLog, DashboardProperties) constructor
provides:
  - kore-dashboard module (Ktor 3.2 CIO embedded server)
  - EventBusDashboardObserver (active-agents ConcurrentHashMap with T-03-11 bound)
  - DashboardDataService (degraded-mode facade over nullable AuditLog)
  - kotlinx.html DSL component library: PageShell, navBar, fragmentContainer, dataTable, statusBadge, resultBadge, emptyState, degradedNotice, monoCell
  - Three HTMX fragments: activeAgentsFragment, recentRunsFragment, costSummaryFragment with empty + degraded + populated render paths
  - configureDashboardRoutes(Route) registering /kore + three /kore/fragments/* endpoints
  - DashboardServer SmartLifecycle with the 3-arg (EventBus, AuditLog, DashboardProperties) constructor consumed by kore-spring's reflective bridge
affects: [03-04 phase integration: KoreAutoConfiguration can replace its reflective bridge with a direct DashboardServer constructor call]

tech-stack:
  added:
    - io.ktor:ktor-server-cio:3.2.0 (CIO engine, coroutine-native)
    - io.ktor:ktor-server-html-builder:3.2.0 (kotlinx.html DSL on Ktor)
    - io.ktor:ktor-server-test-host:3.2.0 (testImplementation)
    - io.ktor:ktor-client-content-negotiation:3.2.0 (testImplementation)
    - org.springframework:spring-context:7.0.0 (compileOnly — SmartLifecycle interface only, kore-spring brings full Spring at runtime via the Spring Boot 4.0.5 BOM)
  patterns:
    - "Injected CoroutineScope for EventBusDashboardObserver — same pattern as EventBusMetricsObserver from Phase 02-03 (no internal var Job? field, tests inject backgroundScope, DashboardServer injects an internal supervisor scope)"
    - "Bounded active-agents map with maxTrackedAgents=10_000 eviction — T-03-11 DoS mitigation when AgentCompleted is dropped by the bus DROP_OLDEST overflow"
    - "FlowContent receiver for koreDashboardPage content lambda — usable inside both <body> and nested <div>, vs the original BODY-only contract from the plan snippet"
    - "Manual HTMX attribute emission via kotlinx.html attributes[\"hx-get\"] = ... — avoids the experimental ktor-server-htmx plugin and its @OptIn(ExperimentalKtorApi::class) requirement"
    - "AtomicReference<EmbeddedServer<*, *>?> backing store in DashboardServer — satisfies CLAUDE.md no-var rule while permitting start/stop swap"
    - "Sentinel InertAuditLog object for the convenience constructor — preserves the (EventBus, AuditLog, DashboardProperties) primary signature kore-spring reflects against, but still routes the dataService through DashboardDataService(null) so degraded mode renders correctly when callers explicitly pass null"
    - "configureDashboardRoutes is a Route extension (not Routing) — Ktor 3.2 routing { } lambda receiver is Route.() -> Unit, so the more general type binds correctly"

key-files:
  created:
    - kore-dashboard/build.gradle.kts
    - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/AgentState.kt
    - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt
    - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardDataService.kt
    - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardRouting.kt
    - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt
    - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/PageShell.kt
    - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Components.kt
    - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Fragments.kt
    - kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserverTest.kt
    - kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardRoutingTest.kt
  modified:
    - settings.gradle.kts

key-decisions:
  - "EventBusDashboardObserver takes an injected CoroutineScope (mirrors EventBusMetricsObserver from Phase 02-03) instead of the plan's internal AtomicReference<Job?>. Tests pass runTest's backgroundScope; DashboardServer passes its own internal supervisor scope. Cleaner, fewer moving parts, no fake immutability tricks for the Job field."
  - "ktor-server-htmx plugin intentionally NOT used. The plan note flagged it as optional behind @OptIn(ExperimentalKtorApi::class). HTMX attributes are emitted as plain hx-* via kotlinx.html attributes[] — works without the plugin and keeps kore-dashboard off experimental Ktor APIs."
  - "Manual HTMX 1.9 CDN script tag in PageShell.kt: https://unpkg.com/htmx.org@1.9.12/dist/htmx.min.js. UI-SPEC pinned the major.minor; .12 is the latest stable patch in the 1.9 line."
  - "koreDashboardPage content lambda is FlowContent.() -> Unit (not BODY.() -> Unit per the plan). The lambda is invoked inside div(\"kore-page\") whose receiver is DIV; FlowContent is the common supertype, so DIV-receiver compiles. The fragmentContainer extension was promoted to FlowContent for the same reason."
  - "configureDashboardRoutes is fun Route.configureDashboardRoutes (not Routing). The Ktor 3.2 routing { } block lambda receiver is Route.() -> Unit, so a Routing-receiver extension cannot be called from inside it. Route is the more general (and correct) binding — Routing extends Route."
  - "DashboardServer 3-arg constructor takes a non-nullable AuditLog because kore-spring's KoreAutoConfiguration.DashboardAutoConfiguration uses getConstructor(EventBus, AuditLog, DashboardProperties) — Class.forName + getConstructor cannot resolve a nullable parameter type, only the JVM erasure. The DashboardProperties parameter is a kore-dashboard interface (not a kore-spring class) so we don't drag a Spring dep into kore-dashboard; kore-spring binds via interface SAM at runtime."
  - "A second convenience constructor (EventBus, AuditLog?, DashboardConfig) exists for unit tests and standalone usage with explicit-null degraded mode. It routes a sentinel InertAuditLog object through the primary constructor and forces DashboardDataService to null when the caller-supplied AuditLog was null, so both code paths can render the degraded notice."
  - "AgentStarted carries only agentId + taskId — no name. AgentState.name = event.agentId for now (matches the EventBusMetricsObserver fallback). Phase 03-04 / kore-spring's name registry can plug in a resolver lambda the same way EventBusMetricsObserver does."
  - "tokensUsed accumulation uses TokenUsage.totalTokens (inputTokens + outputTokens) since AgentEvent.LLMCallCompleted carries the full TokenUsage. The plan snippet referenced .total which doesn't exist — totalTokens is the correct property name."
  - "Test for the running-agent path uses a retry-emit loop (up to 50 × 20ms = 1s) instead of a single emit + delay. InProcessEventBus is a no-replay SharedFlow — events emitted before the collector's subscription is registered are silently dropped. Polling observer.snapshot() between emits avoids the race deterministically."

requirements-completed: [DASH-01, DASH-02, DASH-03, DASH-04]

duration: ~8 min
completed: 2026-04-13
---

# Phase 3 Plan 3: kore-dashboard Module — HTMX Dashboard Side-car Summary

**Ktor 3.2 CIO embedded dashboard side-car wired as a Spring SmartLifecycle bean. Three HTMX-polled fragments (`/kore/fragments/active-agents`, `/recent-runs`, `/cost-summary`) render via kotlinx.html DSL components matching UI-SPEC.md exactly — colours, typography, accessibility attributes, copy. The 3-arg constructor `(EventBus, AuditLog, DashboardProperties)` is the contract kore-spring's reflective bridge consumes today and the direct constructor call plan 03-04 will swap in.**

## Performance

- **Duration:** ~8 min
- **Started:** 2026-04-13T21:27:35Z
- **Completed:** 2026-04-13T21:35:00Z
- **Tasks:** 2 (both TDD, auto mode)
- **Files created:** 11
- **Files modified:** 1

## Accomplishments

- New `kore-dashboard` module added to settings.gradle.kts
- `kore-dashboard/build.gradle.kts` declares Ktor 3.2 CIO + html-builder + ktor-server-test-host, compileOnly Spring Framework 7.0.0 for the SmartLifecycle interface only (no Spring Boot dep — kore-spring brings full Spring at runtime via its BOM)
- `AgentState` data class + `AgentStatus` enum (RUNNING / IDLE / COMPLETED / ERROR)
- `EventBusDashboardObserver`: subscribes to EventBus through an injected `CoroutineScope`, maintains a `ConcurrentHashMap<agentId, AgentState>`, evicts oldest entries at `maxTrackedAgents=10_000` (T-03-11 mitigation)
- `DashboardDataService`: nullable `AuditLog` facade, returns empty lists in degraded mode (D-27)
- `PageShell.kt` `koreDashboardPage()`: full HTML document with HTMX 1.9.12 CDN script and a single embedded `<style>` block defining every CSS class from UI-SPEC.md "CSS Delivery Strategy"
- `Components.kt` 9 reusable kotlinx.html helpers: `navBar`, `fragmentContainer` (HTMX attributes + aria-live), `sectionHeading`, `dataTable` (with `<caption>` for screen-reader navigation), `statusBadge` (RUNNING gets the kore-pulse animated dot + accent-blue border), `resultBadge` (5 result types → variant + label), `emptyState`, `degradedNotice`, `monoCell`
- `Fragments.kt` three HTMX fragments — `activeAgentsFragment`, `recentRunsFragment`, `costSummaryFragment` — each with empty-state, degraded-mode and populated render paths plus a `TOTAL` footer row in the cost summary table
- `DashboardRouting.kt`: `fun Route.configureDashboardRoutes(observer, dataService, config)` registering `/kore` + three `/kore/fragments/*` GET endpoints, calling suspend `DashboardDataService` methods directly (RESEARCH.md Pitfall 1)
- `DashboardServer.kt` SmartLifecycle:
    - 3-arg primary constructor `(EventBus, AuditLog, DashboardProperties)` matching kore-spring's reflective bridge contract (this is the load-bearing signature plan 03-04 will swap to a direct constructor)
    - Convenience constructor `(EventBus, AuditLog?, DashboardConfig)` for tests / standalone main
    - `start()` launches the observer in an internal supervisor scope BEFORE starting the Ktor CIO engine with `wait = false` (Pitfall 6 + Pitfall 3)
    - `stop()` stops the engine with a 1s grace period and cancels the supervisor scope
    - `getPhase() = Int.MAX_VALUE - 1` so the dashboard starts last and stops first
    - Engine reference held via `AtomicReference<EmbeddedServer<*, *>?>` to satisfy CLAUDE.md no-`var`
- 14 tests pass: 7 EventBusDashboardObserverTest cases (start/complete/accumulate/cancel/null-AuditLog) + 7 DashboardRoutingTest cases (page shell, empty active-agents, populated active-agents with status badge, recent-runs degraded, cost-summary degraded, all 5 result badge variants, cost-summary TOTAL footer)
- `:kore-dashboard:test :kore-dashboard:lintKotlin :kore-dashboard:build` all green, JAR artifact produced

## Task Commits

1. **Task 1: kore-dashboard scaffold + EventBusDashboardObserver + DashboardDataService** — `a04796e` (feat)
   - settings.gradle.kts adds kore-dashboard
   - build.gradle.kts with Ktor 3.2 + ktor-server-test-host + Spring Framework 7 compileOnly
   - AgentState / AgentStatus
   - EventBusDashboardObserver with injected scope + bounded map
   - DashboardDataService null-tolerant facade
   - 7-test EventBusDashboardObserverTest using runTest backgroundScope + InProcessEventBus
2. **Task 2: HTML components + Fragments + Routing + DashboardServer SmartLifecycle** — `253f364` (feat)
   - PageShell.kt with embedded UI-SPEC CSS + HTMX CDN script
   - Components.kt with 9 kotlinx.html helpers
   - Fragments.kt with 3 fragment functions covering empty/degraded/populated paths and TOTAL footer
   - DashboardRouting.kt Route.configureDashboardRoutes()
   - DashboardServer.kt SmartLifecycle 3-arg + convenience constructor
   - DashboardRoutingTest with 7 cases via ktor-server-test-host

**Plan metadata commit:** (this SUMMARY + STATE/ROADMAP/REQUIREMENTS updates, see final_commit step)

## Files Created/Modified

**Created:**
- `kore-dashboard/build.gradle.kts` — Ktor 3.2 CIO + html-builder + ktor-server-test-host + compileOnly Spring Framework 7.0.0 for SmartLifecycle
- `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/AgentState.kt` — data class + AgentStatus enum
- `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt` — injected-scope observer with T-03-11 bound
- `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardDataService.kt` — nullable AuditLog facade
- `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardRouting.kt` — Route extension + DashboardConfig
- `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt` — SmartLifecycle 3-arg + convenience constructor + InertAuditLog sentinel
- `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/PageShell.kt` — full HTML document + embedded CSS + HTMX script
- `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Components.kt` — 9 reusable component functions
- `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Fragments.kt` — 3 fragment functions
- `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserverTest.kt` — 7 tests
- `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardRoutingTest.kt` — 7 tests

**Modified:**
- `settings.gradle.kts` — adds kore-dashboard to include() list

## Decisions Made

See `key-decisions:` block in the frontmatter above. Highlights:

- **Injected scope vs internal AtomicReference<Job?>** for the observer — mirrors the EventBusMetricsObserver pattern from Phase 02-03 and is dramatically simpler than the plan's snippet. Tests use `runTest`'s `backgroundScope`, DashboardServer uses its own internal `SupervisorJob() + Dispatchers.Default` scope.
- **No ktor-server-htmx plugin** — the plan flagged it as optional. We emit `hx-*` attributes manually via `attributes[]` so we don't need `@OptIn(ExperimentalKtorApi::class)`.
- **3-arg constructor with non-nullable AuditLog** is the load-bearing kore-spring contract. The convenience 3-arg `(EventBus, AuditLog?, DashboardConfig)` constructor uses an `InertAuditLog` sentinel so the primary-constructor signature stays JVM-resolvable via `getConstructor`.
- **`Route` not `Routing` receiver** for `configureDashboardRoutes` — Ktor 3.2's `routing { }` lambda receiver is `Route.() -> Unit`.
- **TokenUsage.totalTokens** (not `.total`) — the plan snippet was off by one; the existing kore-core data class exposes `totalTokens`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Plan's BODY-receiver content lambda did not compile inside the `div("kore-page")` block in PageShell.kt**
- **Found during:** Task 2, first compile attempt of PageShell.kt
- **Issue:** The plan snippet declared `koreDashboardPage(content: BODY.() -> Unit)` and called `content()` from inside `div("kore-page") { ... }`. The div lambda's receiver is `DIV`, which is not `BODY`, so `content()` fails to dispatch with "implicit receiver mismatch".
- **Fix:** Widened the content type to `FlowContent.() -> Unit`. `BODY`, `DIV` and the other block elements all extend `FlowContent`, so the lambda is callable from any of them. Promoted `fragmentContainer` from `BODY` to `FlowContent` for the same reason — it now lives inside the `div("kore-page")` content block, not directly under `body`.
- **Files modified:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/PageShell.kt`, `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Components.kt`
- **Verification:** `./gradlew :kore-dashboard:compileKotlin` passes.
- **Committed in:** `253f364` (Task 2)

**2. [Rule 3 - Blocking] `kotlinx.html.h3` and `kotlinx.html.p` not imported in Components.kt**
- **Found during:** Task 2, compile attempt
- **Issue:** `emptyState()` references `h3 { ... }` and `p { ... }` — both are `FlowContent` extension functions in kotlinx.html but were missing from the import list.
- **Fix:** Added `import kotlinx.html.h3` and `import kotlinx.html.p`.
- **Files modified:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Components.kt`
- **Verification:** Compile succeeds.
- **Committed in:** `253f364` (Task 2)

**3. [Rule 3 - Blocking] `configureDashboardRoutes` declared as `Routing` extension cannot be called from `routing { ... }` block**
- **Found during:** Task 2, compileTestKotlin
- **Issue:** Ktor 3.2's `routing { }` block on `Application` has a lambda signature of `Route.() -> Unit`. `Routing` extends `Route`, but a `Route.()` lambda cannot dispatch a `Routing.()` extension (Kotlin contravariant rules). Every `routing { configureDashboardRoutes(...) }` call in the test file failed with "receiver type mismatch".
- **Fix:** Changed the function from `fun Routing.configureDashboardRoutes(...)` to `fun Route.configureDashboardRoutes(...)`. `Route` is the more general (and correct) binding — `Routing` is a subtype, so any `Routing` instance still satisfies the receiver.
- **Files modified:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardRouting.kt`
- **Verification:** All 7 DashboardRoutingTest cases compile and pass.
- **Committed in:** `253f364` (Task 2)

**4. [Rule 1 - Bug] `TokenUsage.total` referenced in plan snippet does not exist**
- **Found during:** Task 1, drafting EventBusDashboardObserver
- **Issue:** The plan snippet wrote `state.tokensUsed + event.tokenUsage.total`, but `dev.unityinflow.kore.core.TokenUsage` exposes `totalTokens` (computed as `inputTokens + outputTokens`), not `total`.
- **Fix:** Used `event.tokenUsage.totalTokens.toLong()`. Verified by reading `kore-core/src/main/kotlin/dev/unityinflow/kore/core/TokenUsage.kt`.
- **Files modified:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt`
- **Committed in:** `a04796e` (Task 1)

**5. [Rule 2 - Critical correctness] Race between InProcessEventBus emit and observer collector subscription registration in the routing-test running-agent case**
- **Found during:** Task 2, first run of `:kore-dashboard:test`
- **Issue:** `InProcessEventBus` is a no-replay `MutableSharedFlow` — events emitted before the collector's subscription is registered are silently dropped. The single `emit + delay(50)` pattern from the plan caused the running-agent test to flake: the emit happened before the launched coroutine had reached `eventBus.subscribe().collect { ... }`, and the agent never appeared in the snapshot.
- **Fix:** Replaced the single-emit-then-delay with a retry loop: up to 50 iterations of `emit + observer.snapshot().containsKey(...)` with a 20ms delay between attempts (1s total budget). Followed by a `check()` that the observer received the event before the test proceeds. Deterministic, no race.
- **Files modified:** `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardRoutingTest.kt`
- **Verification:** All 14 :kore-dashboard tests pass on multiple runs.
- **Committed in:** `253f364` (Task 2)

**6. [Rule 3 - Blocking] kotlinter rejected three formatting violations after first compile + test pass**
- **Found during:** Task 2, after `:kore-dashboard:test` succeeded, running `:kore-dashboard:lintKotlin`
- **Issue:** ktlint flagged: (a) trailing blank lines in Fragments.kt (no-consecutive-blank-lines), (b) unused `BODY` import in Components.kt left over from the FlowContent refactor (no-unused-imports), (c) function-signature whitespace + redundant trailing comma on `DashboardServer.queryRecentRuns` single-parameter override.
- **Fix:** `./gradlew :kore-dashboard:formatKotlin` auto-resolved all three. Accepted the formatter output verbatim — the InertAuditLog single-arg overrides were collapsed onto a single line.
- **Files modified:** `Fragments.kt`, `Components.kt`, `DashboardServer.kt`
- **Verification:** `:kore-dashboard:lintKotlin :kore-dashboard:build` clean.
- **Committed in:** `253f364` (Task 2)

---

**Total deviations:** 6 auto-fixed (3 Rule 3 blocking, 1 Rule 1 bug, 1 Rule 2 critical correctness, 1 Rule 3 lint formatting)
**Impact on plan:** All deviations are mechanical — type-binding fixes, missing imports, an off-by-one property name, and a deterministic test fix. Output contracts (DashboardServer 3-arg constructor, route URLs, fragment HTML structure, UI-SPEC class names + colors + copy) match the plan exactly. The kore-spring reflective bridge from plan 03-02 will resolve `getConstructor(EventBus, AuditLog, DashboardProperties)` against this DashboardServer without modification.

### Pre-existing issues out of scope

- None encountered in this plan. The reflective bridge in kore-spring was unchanged — plan 03-04 will swap it for a direct constructor call.

## Issues Encountered

- Compile-time type-binding mismatches in the kotlinx.html DSL (BODY vs DIV vs FlowContent) — root cause was reading the plan snippet without checking which receiver was in scope at the call site. Fixed by widening to `FlowContent`.
- ktlint flagged trailing blank lines and unused imports after the FlowContent refactor — `formatKotlin` auto-resolved.
- One test race against `InProcessEventBus` no-replay subscription registration — replaced single emit + delay with a retry loop.

## User Setup Required

None.

## Next Phase Readiness

Plan **03-04** (Phase 3 integration) is unblocked:

- `DashboardServer(EventBus, AuditLog, KoreProperties.DashboardProperties)` constructor is in place. Plan 03-04 should:
    1. Add `compileOnly(project(":kore-dashboard"))` to `kore-spring/build.gradle.kts`.
    2. In `KoreAutoConfiguration.DashboardAutoConfiguration.dashboardServer`, replace the `Class.forName + Constructor.newInstance` reflective bridge with a direct constructor call: `DashboardServer(eventBus, auditLog, KoreDashboardPropertiesAdapter(properties.dashboard))` (where the adapter implements `dev.unityinflow.kore.dashboard.DashboardServer.DashboardProperties` against `KoreProperties.DashboardProperties`).
    3. Wire the dashboard into the integration test alongside the other auto-configurations, hitting `/kore` and the three fragment URLs to assert the HTML body contains the UI-SPEC markers.
- `kore-dashboard` is a clean library JAR — no Spring runtime dep, only compileOnly on `org.springframework:spring-context:7.0.0` for the `SmartLifecycle` interface symbol.
- The `kore-dashboard` Maven coords will be `dev.unityinflow:kore-dashboard:0.0.1-SNAPSHOT`.

## Known Stubs

None. Every fragment renders real data (or the UI-SPEC degraded notice when kore-storage is absent). The active-agents fragment uses `event.agentId` as the display name because `AgentEvent.AgentStarted` does not carry an agent name — Phase 03-04 / kore-spring's name registry will plug in a resolver lambda the same way `EventBusMetricsObserver` does. This is documented in `key-decisions` above and is consistent with the existing observability-layer behaviour, not a placeholder.

## Self-Check: PASSED

- [x] `kore-dashboard/build.gradle.kts` FOUND
- [x] `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/AgentState.kt` FOUND
- [x] `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt` FOUND
- [x] `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardDataService.kt` FOUND
- [x] `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardRouting.kt` FOUND
- [x] `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt` FOUND
- [x] `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/PageShell.kt` FOUND
- [x] `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Components.kt` FOUND
- [x] `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Fragments.kt` FOUND
- [x] `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserverTest.kt` FOUND
- [x] `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardRoutingTest.kt` FOUND
- [x] Commit `a04796e` FOUND in `git log`
- [x] Commit `253f364` FOUND in `git log`
- [x] `./gradlew :kore-dashboard:test :kore-dashboard:lintKotlin :kore-dashboard:build` all green (14 tests pass)
- [x] `class DashboardServer` exposes `(EventBus, AuditLog, DashboardProperties)` primary constructor
- [x] `SmartLifecycle` reference present in DashboardServer.kt
- [x] `hx-get` reference present in Components.kt (fragmentContainer)
- [x] `aria-live` reference present in Components.kt
- [x] `kore-pulse` reference present in PageShell.kt CSS and Components.kt RUNNING badge
- [x] `History unavailable` reference present in Fragments.kt degradedNotice call
- [x] UI-SPEC.md colors `#2563EB`, `#16A34A`, `#D97706`, `#DC2626` all present in PageShell.kt CSS

---
*Phase: 03-skills-spring-dashboard*
*Completed: 2026-04-13*
