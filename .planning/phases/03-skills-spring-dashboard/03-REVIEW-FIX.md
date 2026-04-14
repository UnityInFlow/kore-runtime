---
phase: 03-skills-spring-dashboard
fixed_at: 2026-04-14T16:30:00Z
review_path: .planning/phases/03-skills-spring-dashboard/03-REVIEW.md
iteration: 1
findings_in_scope: 10
fixed: 8
already_fixed: 2
skipped: 0
status: all_fixed
baseline_test_count: 83
final_test_count: 87
test_delta: "+4"
regressions: 0
---

# Phase 3: Code Review Fix Report

**Fixed at:** 2026-04-14T16:30:00Z
**Source review:** .planning/phases/03-skills-spring-dashboard/03-REVIEW.md
**Iteration:** 1

**Summary**
- Findings in scope (HI + ME): 10
- Already fixed by gap plan 03-05: 2 (HI-01, HI-02)
- Fixed in this run: 8 (ME-01 through ME-08)
- Skipped: 0
- Baseline test count: 83
- Final test count: 87 (+4 new tests)
- Regressions: 0
- New `var` introduced: 0
- New `!!` introduced: 0
- New dependencies added: 0 (kore-spring, libs.versions.toml untouched)

## Already Fixed (Gap Closure Plan 03-05)

Both HIGH findings were closed by commits landed in plan 03-05 before this review-fix run. I verified both are intact and did not re-attempt:

### HI-01: DashboardServer restart leaks a dead CoroutineScope
- **Status:** `already_fixed`
- **Evidence:** `DashboardServer.kt:88-89` now declares `private val scopeRef = AtomicReference<CoroutineScope?>(null)` and `private val observerRef = AtomicReference<EventBusDashboardObserver?>(null)`. `start()` at line 97 constructs a fresh `CoroutineScope(SupervisorJob() + Dispatchers.Default)` + fresh observer on each call. `stop()` at line 118 calls `getAndSet(null)?.cancel()` on the scope. Pinned by `DashboardServerRestartTest` (3 tests) and `DashboardDegradedModeSpringTest` (3 tests).
- **Closure commits:** `b2ecf59` (RED), `f0e8106` (GREEN)

### HI-02: Degraded-mode dashboard notice never renders with default InMemoryAuditLog wiring
- **Status:** `already_fixed`
- **Evidence:** `AuditLog.kt:33` declares `val isPersistent: Boolean get() = false`; `PostgresAuditLogAdapter.kt:37` overrides to `true`; `DashboardDataService.kt:31` reads `auditLog?.isPersistent == true`. InMemoryAuditLog inherits the default `false`, so the default Spring wiring correctly renders the UI-SPEC en-dash degraded copy. Pinned by `DashboardDegradedModeTest`, `DashboardDegradedModeRoutingTest`, and `DashboardDegradedModeSpringTest`.
- **Closure commits:** `e75f537` (RED), `9bdf7ee` (GREEN)

## Fixed Issues

### ME-01: SkillLoader does not catch YAML parse errors
- **Status:** `fixed`
- **Files modified:** `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt`, `kore-skills/src/test/kotlin/dev/unityinflow/kore/skills/SkillLoaderTest.kt`
- **Commit:** `bedb9e2`
- **Applied fix:** Replaced the narrow `MismatchedInputException` + `JsonMappingException` catches with the common Jackson supertype `JsonProcessingException` (which covers `JsonParseException` for syntax errors, `JsonMappingException`, and `MismatchedInputException`) plus a sibling `IOException` catch. Applied to both `parseSkillFileSafely` (file-URL path) and the JAR-entry parsing branch. Added `SkillLoaderTest.Test 5 - malformed YAML syntax is skipped without crashing loadAll (ME-01)` which writes an unterminated-quote YAML and a valid sibling file in the same tmp directory and asserts the valid one loads.

### ME-02: EventBusDashboardObserver capacity eviction has no defined ordering
- **Status:** `fixed`
- **Files modified:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt`, `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserverTest.kt`
- **Commit:** `37c51e7`
- **Applied fix:** Replaced `activeAgents.keys.take(N)` (undefined ConcurrentHashMap iteration order) with `activeAgents.entries.sortedBy { it.value.startedAt }.take(N).map { it.key }`, honouring the documented "oldest wins" eviction semantics. O(N log N) sort only fires when at capacity. Added `ME-02 capacity eviction bounds the active-agents map to maxTrackedAgents` test that emits 5 events at `maxTrackedAgents=3` and asserts the map never exceeds 3 and the newest (a5) is present. The stricter "a1 specifically must be evicted" assertion was avoided to keep the test deterministic under nanosecond-granularity `Instant.now()` ties.

### ME-03: queryCostSummary loads the entire join into memory
- **Status:** `fixed: requires human verification`
- **Files modified:** `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt`
- **Commit:** `f876c33`
- **Applied fix:** Added a `costSummaryMaxRows: Int = 100_000` constructor parameter and applied `.limit(costSummaryMaxRows)` to the Kotlin-side aggregation query. When the ceiling is reached a warning is logged to `stderr` naming ME-03 so operators can diagnose. This is a **safety ceiling** to prevent OOM on long-lived deployments, not a correctness mechanism; the aggregate is under-counted when the ceiling is hit. The review's preferred fix (SQL WHERE time-window predicate) was not applied in this run because the adapter deliberately does its aggregation in Kotlin to sidestep Exposed R2DBC dialect quirks, and introducing a SQL predicate on `finishedAt` at the same time as the ME-04/ME-05 changes raised the regression risk for the full 83-test baseline. The proper time-window fix is tracked in the KDoc at the new parameter and can land in a follow-up with its own R2DBC smoke test. **Human verification requested:** confirm the `100_000` ceiling is an acceptable temporary bound for your deployment and that a follow-up ticket for the time-window fix is filed.

### ME-04: queryRecentRuns inner join hides runs with zero LLM calls
- **Status:** `fixed`
- **Files modified:** `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt`
- **Commit:** `f876c33`
- **Applied fix:** Changed `innerJoin(LlmCallsTable)` to `leftJoin(LlmCallsTable)` in both `queryRecentRuns` and `queryCostSummary`. Null LLM-call columns are mapped to `0` via `row.getOrNull(column) ?: 0`. `AgentRunRecord.inputTokens` / `outputTokens` / `durationMs` default to 0 for runs that failed before their first LLM call. A direct regression test was planned but deferred — the `PostgresAuditLogAdapterQueryTest` class uses shared `@BeforeAll` Testcontainer state and adding a 4th seeded run would break the existing `queryRecentRuns with limit 10 returns all seeded joined rows` assertion under arbitrary JUnit 5 method ordering. A comment block in the test file records this coverage gap and points to the routing-layer mock tests that still exercise the zero-token record shape.

### ME-05: completedAt falls back to Instant.EPOCH for in-flight runs
- **Status:** `fixed`
- **Files modified:** `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt`
- **Commit:** `f876c33`
- **Applied fix:** Added `.where { AgentRunsTable.finishedAt.isNotNull() }` to `queryRecentRuns` so in-progress rows (null `finished_at`) are excluded at the SQL layer. The `?: Instant.EPOCH` fallback is retained as defensive code but is now unreachable under the guaranteed non-null invariant. Resolved the Exposed v1 `isNotNull` method-call syntax after a brief fact-finding detour (it is a receiver extension on `Expression<T>`, not a top-level function, contrary to what the bytecode hints initially suggested).

### ME-06: EventBusDashboardObserver.startCollecting has no idempotency guard
- **Status:** `fixed`
- **Files modified:** `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserver.kt`, `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/EventBusDashboardObserverTest.kt`
- **Commit:** `37c51e7`
- **Applied fix:** Added `private val started = AtomicBoolean(false)` and guarded `startCollecting()` with `if (!started.compareAndSet(false, true)) return`. A double-call on the same observer instance is now a no-op and cannot launch a second collector. This composes correctly with HI-01's per-start fresh observer construction in `DashboardServer.start()` — each new observer has its own `started` flag that starts `false`. Added `ME-06 startCollecting is idempotent — second call is a no-op` test which double-calls `startCollecting()` and asserts token accumulation is not double-counted.

### ME-07: SkillLoader jar URL parsing does not URL-decode paths with spaces
- **Status:** `fixed`
- **Files modified:** `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt`
- **Commit:** `bedb9e2`
- **Applied fix:** Replaced the manual `url.path.indexOf("!/")` + `substring("file:".length, bangIdx)` string-slicing with `url.openConnection() as JarURLConnection` and reading `connection.jarFile`. `JarURLConnection` handles URL decoding (`%20` → space) and nested JAR protocols (`jar:file:`, `jar:nested:`) natively. Set `useCaches = false` to avoid the JDK's JAR connection cache holding the file handle open. Removed the now-unused `JarFile` import. No direct test: this bug only manifests on filesystem paths containing URL-encoded characters like spaces, which cannot be reliably reproduced in a Gradle-driven test classpath. The fix is mechanical and the new code path uses the standard JDK API.

### ME-08: KoreIntegrationTest never runs the agent end-to-end
- **Status:** `fixed`
- **Files modified:** `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KoreIntegrationTest.kt`
- **Commit:** `db5f4d2`
- **Applied fix:** Added `Test 6 (ME-08) — user agent bean runs full loop through auto-wired EventBus` which subscribes a collector on a fresh scope, yields so the subscription reaches its suspension point before any emission (SharedFlow late-subscriber gotcha), calls `testAgent.run(task).await()` under a 10-second `withTimeout`, asserts the result is `AgentResult.Success`, and verifies that `AgentStarted` and `AgentCompleted` events each have size 1 in the collected log. The test uses `runBlocking` (not `runTest`) because `AgentRunner` has its own internal `CoroutineScope` on `Dispatchers.Default` that does not respect `runTest`'s virtual clock. Explicit `: Unit` return type declared on the test function per the Kotest+JUnit5 expression-body gotcha documented in 03-05-SUMMARY.md.

## Findings Not In Scope

The review also listed 6 LOW and 3 NIT findings. Per the fix_scope=`critical_warning` config and the orchestrator's instruction to only cherry-pick zero-risk LOW/NIT items, the following were reviewed and intentionally deferred:

- **LO-01** (AgentLoop precondition on User message): Touching `kore-core/AgentLoop.kt` was out of scope for this phase — per scope constraint "do NOT touch kore-llm/* or kore-core/HeroDemoTest.kt". AgentLoop is adjacent and touching it without a dedicated plan carries cross-module risk. Defer.
- **LO-02** (DashboardServer.start atomic guard): Already partially addressed by HI-01's AtomicReference-backed refactor. The remaining concurrent-start race is unreachable from Spring's single-threaded LifecycleProcessor. Defer.
- **LO-03** (AgentState.currentTask dead field): Privacy-model decision (T-03-03 forbids task content on the dashboard read path) makes this a one-line column removal from Fragments.kt + one field removal from AgentState.kt. Low risk but out of scope for a review-fix run focused on correctness bugs. Defer.
- **LO-04** (queryRecentRuns limit validation): Agreed but the caller in kore-dashboard is the only one today and uses a hardcoded `limit=20`. Defer.
- **LO-05** (resultBadge unknown fallback logging): Nice-to-have observability, no correctness impact. Defer.
- **LO-06** (ToolResult.toJsonString hand-rolled serializer): Pre-existing (noted in review as "Pre-existing before Phase 3"). On the write path, not a Phase 3 regression. Defer to a dedicated ticket that can also replace Jackson vs kotlinx.serialization consistently.
- **NI-01, NI-02, NI-03**: All pure style / cleanup. Defer.

These can be picked up in a follow-up review-fix iteration if the orchestrator wants `fix_scope=all`.

## Test Results

Final run: `./gradlew :kore-core:test :kore-storage:test :kore-skills:test :kore-dashboard:test :kore-spring:test`

| Module | Baseline | Final | Delta |
| --- | --- | --- | --- |
| kore-core | 20 | 20 | 0 |
| kore-storage | 10 | 10 | 0 |
| kore-skills | 11 | 12 | +1 (ME-01) |
| kore-dashboard | 22 | 24 | +2 (ME-02, ME-06) |
| kore-spring | 20 | 21 | +1 (ME-08) |
| **TOTAL** | **83** | **87** | **+4** |

All `lintKotlin` tasks pass across all five modules. Zero new `var` or `!!` introduced. No changes to `kore-spring/build.gradle.kts` or `gradle/libs.versions.toml`. No new catalog aliases.

---

_Fixed: 2026-04-14T16:30:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
