---
phase: 03-skills-spring-dashboard
verified: 2026-04-14T15:04:00Z
status: passed
score: 18/18 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 16/18
  previous_verified: 2026-04-14T14:18:28Z
  gaps_closed:
    - "HI-02 — Degraded-mode notice now renders via default Spring wiring (AuditLog.isPersistent discriminator)"
    - "HI-01 — DashboardServer restart now recreates scope + observer atomically (AtomicReference swap)"
  gaps_remaining: []
  regressions: []
  closure_plan: 03-05
  closure_commits:
    - "e75f537 test(03-05): add failing degraded-mode regression tests (HI-02)"
    - "9bdf7ee fix(03-05): add AuditLog.isPersistent discriminator so degraded notice renders on default Spring wiring (HI-02)"
    - "b2ecf59 test(03-05): add failing regression test for DashboardServer restart (HI-01)"
    - "f0e8106 fix(03-05): make DashboardServer restartable by holding scope in AtomicReference (HI-01)"
    - "f1e599d docs(03-05): gap closure SUMMARY"
  test_delta: "+11 (8 HI-02 + 3 HI-01)"
  baseline_test_count: 72
  final_test_count: 83
---

# Phase 3: Skills, Spring & Dashboard Verification Report

**Phase Goal:** deliver kore-skills + kore-spring + kore-dashboard so that a developer can add one Spring Boot dependency (`dev.unityinflow:kore-spring`), write a single `@Bean { agent { } }`, and have a production-ready agent running with live dashboard and actuator observability.

**Verified:** 2026-04-14T15:04:00Z
**Status:** passed
**Re-verification:** Yes — after gap closure plan 03-05

## Goal Achievement

The "one dependency, one @Bean, live dashboard" promise is now **fully demonstrable end-to-end**, including the two zero-config edge cases flagged in the initial verification. Both high-severity findings from 03-REVIEW.md / initial 03-VERIFICATION.md have been closed by plan 03-05 with strict TDD ordering and zero regressions:

1. **HI-02 closed.** A developer following the README hero demo without kore-storage now sees the UI-SPEC-specified "History unavailable — kore-storage not configured" notice in the recent-runs and cost-summary fragments, pinned by a Spring-level bean-introspection test against the real `@Autowired DashboardServer`.
2. **HI-01 closed.** DashboardServer.stop() now atomically clears the CoroutineScope, and start() installs a fresh generation — pinned by an identity-check + event round-trip test.

Full test suite: **83 passing = BASELINE_N(72) + 11**, zero regressions, zero failures across `:kore-core:test :kore-storage:test :kore-skills:test :kore-dashboard:test :kore-spring:test`, zero new `var`, zero new `!!`, zero new ktor deps in kore-spring, zero new catalog aliases.

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | YAML skill with name/description/version/activation/prompt fields is parsed without error | ✓ VERIFIED | `SkillLoader.loadAll()` + `SkillYamlDef`; SkillLoaderTest 4 tests |
| 2 | Skill with task_matches regex activates when task matches and is absent when it does not | ✓ VERIFIED | `PatternMatcher` + `SkillRegistryAdapter.activateFor`; SkillRegistryAdapterTest positive/negative |
| 3 | Skill with requires_tools only activates when ALL required tools are present (AND, not OR) | ✓ VERIFIED | `availableTools.containsAll(...)`; tests 10-11 |
| 4 | Filesystem skills override classpath skills with the same name (user wins) | ✓ VERIFIED | Name-keyed merge with filesystem last; SkillLoaderTest test 3 |
| 5 | Activated skill prompts appear as a System message at history[0] before the first LLM call | ✓ VERIFIED | AgentLoop.runLoop() line 111 add(0,…); AgentLoopSkillTest 6 tests |
| 6 | Adding kore-spring + setting kore.llm.claude.api-key creates a ClaudeBackend bean via @ConditionalOnClass + @ConditionalOnProperty | ✓ VERIFIED | KoreAutoConfiguration.ClaudeLlmAutoConfiguration + KoreAutoConfigurationTest |
| 7 | InProcessEventBus and InMemoryBudgetEnforcer are always created as defaults (D-15) | ✓ VERIFIED | `@ConditionalOnMissingBean` defaults at lines 52-59 |
| 8 | A @Bean returning agent{} produces a fully wired AgentRunner bean with no extra configuration | ✓ VERIFIED | KoreIntegrationTest test 3 injects `testAgent: AgentRunner` |
| 9 | With kore-storage on classpath and kore.storage.r2dbc-url set, PostgresAuditLogAdapter replaces InMemoryAuditLog | ✓ VERIFIED | `StorageAutoConfiguration` string-form `@ConditionalOnClass`; PostgresAuditLogAdapterQueryTest under Testcontainers |
| 10 | With kore-skills on classpath, SkillRegistryAdapter bean is created from kore.skills.directory config | ✓ VERIFIED | SkillsAutoConfiguration + KoreIntegrationTest test 2 |
| 11 | GET /actuator/kore returns a JSON document with agent count and recent activity state | ✓ VERIFIED | KoreActuatorEndpoint `@ReadOperation`; KoreIntegrationTest test 4 |
| 12 | All conditional beans use @ConditionalOnClass(name=[...]) string form — no ClassNotFoundException on partial classpath | ✓ VERIFIED | 8 `name=[…]` matches, zero `::class` array forms |
| 13 | GET /kore returns an HTML page with three div containers each having hx-get, hx-trigger, hx-swap attributes | ✓ VERIFIED | PageShell.koreDashboardPage + fragmentContainer |
| 14 | GET /kore/fragments/active-agents, /recent-runs, /cost-summary return HTML with the UI-SPEC tables | ✓ VERIFIED | Fragments.kt + DashboardRoutingTest 7 cases |
| 15 | When EventBus emits AgentStarted, observer adds the agent; AgentCompleted removes it | ✓ VERIFIED | EventBusDashboardObserver + 7 tests |
| 16 | DashboardServer implements SmartLifecycle with start/stop/isRunning, embeddedServer with wait=false | ✓ VERIFIED | DashboardServer : SmartLifecycle; wait=false |
| 17 | README hero demo shows: add dev.unityinflow:kore-spring, write @Bean { agent{} }, visit /kore | ✓ VERIFIED | README.md lines 66-120 |
| 18 | Degraded mode: when AuditLog is not persistent (kore-storage absent), recent-runs and cost-summary fragments show the "History unavailable — kore-storage not configured" notice specified by UI-SPEC.md | ✓ VERIFIED | **Closure plan 03-05.** `AuditLog.isPersistent` port-level discriminator (`get() = false` default); PostgresAuditLogAdapter overrides to `true`; InMemoryAuditLog inherits default; `DashboardDataService.hasStorage() = auditLog?.isPersistent == true`. Pinned by: (a) `DashboardDegradedModeTest` 3 unit tests; (b) `DashboardDegradedModeRoutingTest` 2 routing tests asserting exact en-dash copy `"History unavailable — kore-storage not configured"` and `"Cost history unavailable — kore-storage not configured"` on `/kore/fragments/recent-runs` + `/kore/fragments/cost-summary` with a non-null InMemoryAuditLog bound (NOT a null audit log, so the real bug is exercised); (c) `DashboardDegradedModeSpringTest` 3 `@SpringBootTest` tests asserting `dashboardServer.dataServiceForTest().hasStorage() shouldBe false` under default Spring wiring against the real `@Autowired DashboardServer` bean. |
| 19 | DashboardServer SmartLifecycle can be stopped and restarted by Spring without silently breaking the observer (start() must be idempotent across stop() cycles) | ✓ VERIFIED | **Closure plan 03-05.** `scopeRef: AtomicReference<CoroutineScope?>` + `observerRef: AtomicReference<EventBusDashboardObserver?>`. `start()` constructs a fresh `CoroutineScope(SupervisorJob() + Dispatchers.Default)` + fresh observer and installs them via `scopeRef.set(...)`. `stop()` calls `scopeRef.getAndSet(null)?.cancel()`. Pinned by `DashboardServerRestartTest` 3 tests: (a) start → stop → start installs a NEW scope + observer (identity check `scope2 === scope1 shouldBe false`), and the new observer receives an `AgentStarted` event round-tripped through `InProcessEventBus` (`observer2.snapshot().containsKey("restart-agent") shouldBe true`); (b) double stop is idempotent and leaves `scopeForTest()` null; (c) double start without intervening stop is idempotent and preserves scope identity. CLAUDE.md `var` prohibition respected — AtomicReference is the mechanism. |

**Score:** 18/18 truths verified. Two new truths (18 degraded mode, 19 restart) added during re-verification because plan 03-05 explicitly closed them — the initial verification already flagged the restart issue as a "note" even though the plan's must-have only pinned the SmartLifecycle shape. Including both in the score makes the re-verification contract explicit and ensures any future regression breaks this file.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/SkillRegistry.kt` | `interface SkillRegistry` + NoOpSkillRegistry | ✓ VERIFIED | Unchanged from initial verification |
| `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt` | queryRecentRuns + queryCostSummary + AgentRunRecord + AgentCostRecord + `isPersistent: Boolean` discriminator | ✓ VERIFIED | Port interface now declares `val isPersistent: Boolean get() = false` at line 33 with KDoc describing the degraded-mode contract. Read methods unchanged. |
| `kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InMemoryAuditLog.kt` | In-memory `AuditLog` stub inherits `isPersistent=false` | ✓ VERIFIED | Zero `isPersistent` overrides — inherits interface default of `false`, which is exactly what the fix relies on (no code change required to this file) |
| `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt` | Override `isPersistent = true` | ✓ VERIFIED | Line 37: `override val isPersistent: Boolean = true` |
| `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillRegistryAdapter.kt` | Full SkillRegistry port impl | ✓ VERIFIED | Unchanged |
| `kore-skills/src/main/kotlin/dev/unityinflow/kore/skills/SkillLoader.kt` | Two-source loader: classpath + filesystem | ✓ VERIFIED | Unchanged |
| `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt` | @ConfigurationProperties("kore") hierarchy | ✓ VERIFIED | Unchanged |
| `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt` | @AutoConfiguration with all @ConditionalOnClass nested configs | ✓ VERIFIED | Unchanged — default `inMemoryAuditLog()` bean still wired, but its `isPersistent=false` now correctly routes DashboardDataService into degraded mode |
| `kore-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Spring Boot auto-configuration SPI registration | ✓ VERIFIED | Unchanged |
| `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/actuator/KoreActuatorEndpoint.kt` | @Endpoint(id='kore') @ReadOperation | ✓ VERIFIED | Unchanged |
| `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt` | Ktor CIO server as Spring SmartLifecycle with **restartable** scope lifecycle | ✓ VERIFIED | `scopeRef`, `observerRef` as `AtomicReference<T?>`; start() builds new scope+observer; stop() getAndSet(null).cancel(); 0 `var`, 0 `!!`; `fun dataServiceForTest()` public, `internal fun scopeForTest()`, `internal fun observerForTest()` — test accessors named with `-Test` suffix reviewer guard |
| `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardDataService.kt` | `hasStorage()` reads `AuditLog.isPersistent` (not null-check) | ✓ VERIFIED | Line 31: `fun hasStorage(): Boolean = auditLog?.isPersistent == true` |
| `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/PageShell.kt` | koreDashboardPage() + HTMX script + embedded CSS | ✓ VERIFIED | Unchanged |
| `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Components.kt` | statusBadge/resultBadge/dataTable/emptyState/degradedNotice helpers | ✓ VERIFIED | Unchanged |
| `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/html/Fragments.kt` | activeAgents/recentRuns/costSummary with exact UI-SPEC en-dash degraded copy | ✓ VERIFIED | Line 76: `"History unavailable — kore-storage not configured. "` (en-dash `—`, not hyphen `-`) |
| `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardDegradedModeTest.kt` | 3 unit tests pinning hasStorage() against persistent / non-persistent / null audit logs | ✓ VERIFIED | New file (+79 LOC); includes private `PersistentStubAuditLog` test double |
| `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardDegradedModeRoutingTest.kt` | 2 routing tests asserting exact degraded copy at the two fragment endpoints with a NON-null InMemoryAuditLog | ✓ VERIFIED | New file (+69 LOC); uses `testApplication {}` with InMemoryAuditLog; asserts en-dash copy + `shouldNotContain "<table"` |
| `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardServerRestartTest.kt` | 3 unit-level scope-swap tests (start/stop/start identity swap, double stop idempotent, double start idempotent) | ✓ VERIFIED | New file (+125 LOC); port=0 ephemeral binding; identity check `scope2 === scope1 shouldBe false`; event round-trip via `InProcessEventBus`; explicit `: Unit` return types on `runBlocking` expression-body test functions per the Kotest+JUnit5 gotcha documented in 03-05-SUMMARY |
| `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/DashboardDegradedModeSpringTest.kt` | 3 `@SpringBootTest` bean-introspection tests pinning degraded mode at the real `@Autowired DashboardServer` | ✓ VERIFIED | New file (+105 LOC); mirrors KoreIntegrationTest Spring Boot properties (`spring.main.web-application-type=none`, `kore.dashboard.enabled=false`) so no ktor test host is needed in kore-spring's test classpath |
| `kore-dashboard/build.gradle.kts` | testImplementation spring-context for SmartLifecycle supertype on test classpath | ✓ VERIFIED | `testImplementation("org.springframework:spring-context:7.0.0")` added alongside existing compileOnly (Rule 3 deviation resolved in 03-05) |
| `kore-spring/build.gradle.kts` | NO new ktor test dependencies | ✓ VERIFIED | Zero `ktor` matches — the Spring bean test is pure introspection, no `testApplication {}` block |
| `gradle/libs.versions.toml` | NO new catalog entries | ✓ VERIFIED | No `ktor-server-test-host` alias introduced |
| `README.md` | Phase 3 hero demo section | ✓ VERIFIED | Unchanged |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| DashboardDataService.hasStorage() | AuditLog.isPersistent | property read on optional port | ✓ WIRED | `auditLog?.isPersistent == true` at DashboardDataService.kt line 31 |
| KoreAutoConfiguration default AuditLog bean | DashboardServer.dataService | Spring @Autowired through DashboardAutoConfiguration.dashboardServer() factory | ✓ WIRED | Default InMemoryAuditLog inherits `isPersistent=false` → DashboardDataService.hasStorage() returns false → Fragments render degraded copy. Pinned by DashboardDegradedModeSpringTest against the real `@Autowired DashboardServer` bean. |
| PostgresAuditLogAdapter | AuditLog.isPersistent | override | ✓ WIRED | `override val isPersistent: Boolean = true` at PostgresAuditLogAdapter.kt line 37 — kore-storage classpath path reports `hasStorage() = true` and the real table renders |
| DashboardServer.start() generation N | CoroutineScope gen N + EventBusDashboardObserver gen N | AtomicReference swap | ✓ WIRED | start() builds a new `CoroutineScope(SupervisorJob() + Dispatchers.Default)` and a new `EventBusDashboardObserver(eventBus, newScope)`, installs both via `scopeRef.set(...)` / `observerRef.set(...)`, calls `newObserver.startCollecting()` before binding the Ktor engine. stop() `getAndSet(null)` on both refs then calls `cancel()`. |
| DashboardServer.stop() → start() | fresh scope gen N+1 | AtomicReference re-read | ✓ WIRED | Pinned by DashboardServerRestartTest identity check `scope2 === scope1 shouldBe false` + observer receives a new `AgentStarted` event emitted via `InProcessEventBus` after the second start. |
| AgentLoop → SkillRegistry (pre-existing) | activation at history[0] | add(0, ...) | ✓ WIRED | Unchanged from initial verification |
| KoreAutoConfiguration → META-INF SPI | auto-configuration registration | resource file | ✓ WIRED | Unchanged |
| DashboardServer → DashboardRouting | routing DSL | routing { configureDashboardRoutes(...) } | ✓ WIRED | Unchanged |
| EventBusDashboardObserver → EventBus.subscribe() | flow collect on scope | eventBus.subscribe().collect { ... } | ✓ WIRED | Unchanged |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| activeAgentsFragment | observer snapshot (Map<String, AgentState>) | EventBusDashboardObserver → eventBus.subscribe().collect → ConcurrentHashMap write | ✓ | ✓ FLOWING — EventBusDashboardObserverTest + new DashboardServerRestartTest event round-trip |
| recentRunsFragment | DashboardDataService.getRecentRuns() + hasStorage() | AuditLog.queryRecentRuns(limit=20) + AuditLog.isPersistent | ✓ | ✓ FLOWING — kore-storage path returns real rows (Testcontainers test); default zero-config path now correctly reports `hasStorage()=false` and renders the UI-SPEC degraded copy (DashboardDegradedModeRoutingTest). **No more static-via-InMemoryAuditLog warning.** |
| costSummaryFragment | DashboardDataService.getCostSummary() + hasStorage() | AuditLog.queryCostSummary() + AuditLog.isPersistent | ✓ | ✓ FLOWING — same as recentRunsFragment |
| KoreActuatorEndpoint.health() | in-memory counters + EventBus subscription | ConcurrentHashMap.newKeySet + @PostConstruct subscription | ✓ | ✓ FLOWING — unchanged |
| testAgent @Bean | AgentRunner from agent{} DSL | MockLLMBackend in integration test | ✓ | ✓ FLOWING — unchanged |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full regression across all Phase 3 modules | `./gradlew :kore-core:test :kore-storage:test :kore-skills:test :kore-dashboard:test :kore-spring:test` | BUILD SUCCESSFUL; all tasks up-to-date; 20 + 10 + 11 + 22 + 20 = **83** tests passing, 0 failures, 0 errors (JUnit XML aggregated) | ✓ PASS |
| Test count delta vs baseline | `BASELINE_N(72) + 11 = 83` | Matches exactly: kore-dashboard +8 (DashboardDegradedModeTest 3 + DashboardDegradedModeRoutingTest 2 + DashboardServerRestartTest 3), kore-spring +3 (DashboardDegradedModeSpringTest) | ✓ PASS |
| HI-01 restart path closed (DashboardServer) | DashboardServerRestartTest `start after stop recreates scope and observer and the new observer receives events` passes | Identity check `scope2 === scope1 shouldBe false`; observer2 receives `AgentStarted("restart-agent")` round-trip | ✓ PASS |
| HI-02 degraded-mode closed at Spring layer | DashboardDegradedModeSpringTest `dashboardServer dataServiceForTest hasStorage is false under default wiring` passes | Real `@Autowired DashboardServer` reports `hasStorage()=false` with default InMemoryAuditLog wiring | ✓ PASS |
| HI-02 degraded-mode closed at routing layer | DashboardDegradedModeRoutingTest 2 tests pass | Both fragment bodies contain the exact en-dash UI-SPEC copy and do NOT contain `<table` | ✓ PASS |
| No new `var` in modified Kotlin source | grep `\bvar\b` in DashboardServer.kt | Only 2 doc-comment mentions of "var", zero field/variable declarations | ✓ PASS |
| No new `!!` in modified Kotlin source | grep `!!` in DashboardServer.kt | Zero matches | ✓ PASS |
| kore-spring did NOT gain ktor test deps | grep `ktor` in kore-spring/build.gradle.kts | Zero matches | ✓ PASS |
| gradle/libs.versions.toml did NOT gain ktor-server-test-host alias | grep in libs.versions.toml | Zero matches | ✓ PASS |
| `takeUnless { it === InertAuditLog }` sentinel bypass removed from DashboardServer | grep in DashboardServer.kt | Zero matches (sentinel bypass deleted) | ✓ PASS |
| Exact UI-SPEC en-dash copy in Fragments.kt | grep `History unavailable — kore-storage not configured` | 1 match at Fragments.kt:76 (with Unicode en-dash `—`, not ASCII `-`) | ✓ PASS |
| Default InMemoryAuditLog bean intentionally preserved in KoreAutoConfiguration | KoreAutoConfiguration.kt inMemoryAuditLog() @Bean unchanged | Default InMemoryAuditLog bean still present; now correctly inherits `isPersistent=false` from port interface | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| SKIL-01 | 03-01, 03-04 | YAML skill definition format | ✓ SATISFIED | Unchanged |
| SKIL-02 | 03-01, 03-04 | Pattern-based activation context matching | ✓ SATISFIED | Unchanged |
| SKIL-03 | 03-01, 03-04 | Skill loader with configurable directory | ✓ SATISFIED | Unchanged |
| SKIL-04 | 03-01, 03-04 | Active skills injected into agent context | ✓ SATISFIED | Unchanged |
| SPRN-01 | 03-02, 03-04, 03-05 | KoreAutoConfiguration for Spring Boot | ✓ SATISFIED | Plan 03-05 re-declares SPRN-01 (DashboardServer auto-config now pins degraded-mode semantics via a Spring bean test) |
| SPRN-02 | 03-02, 03-04 | KoreProperties @ConfigurationProperties | ✓ SATISFIED | Unchanged |
| SPRN-03 | 03-02, 03-04 | One-dependency agent bootstrap | ✓ SATISFIED | Unchanged |
| SPRN-04 | 03-02, 03-04 | Spring Actuator endpoints | ✓ SATISFIED | Unchanged |
| DASH-01 | 03-03, 03-04, 03-05 | HTMX dashboard: active agents with status | ✓ SATISFIED | Active-agents flow verified by new DashboardServerRestartTest event round-trip after restart |
| DASH-02 | 03-03, 03-04, 03-05 | HTMX dashboard: recent agent runs with results | ✓ SATISFIED | **Upgraded from PARTIAL.** Positive path verified via PostgresAuditLogAdapterQueryTest; degraded path now verified via DashboardDegradedModeRoutingTest + DashboardDegradedModeSpringTest end-to-end |
| DASH-03 | 03-03, 03-04, 03-05 | HTMX dashboard: token cost summary per agent and total | ✓ SATISFIED | **Upgraded from PARTIAL.** Same coverage as DASH-02 — degraded notice pinned by routing test asserting exact "Cost history unavailable — kore-storage not configured" copy |
| DASH-04 | 03-03, 03-04 | Ktor 3.2 embedded server with no frontend build step | ✓ SATISFIED | Unchanged |

**Orphaned requirements:** None. All 12 phase requirement IDs appear in at least one plan's `requirements` frontmatter. Plan 03-05 re-declares DASH-01/02/03/04 + SPRN-01 for the gap-closure contract.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| kore-core/HeroDemoTest.kt (pre-existing, Phase 1) | 115, 117 | Two ktlint formatting violations | ℹ️ Info | Tracked in deferred-items.md; out of Phase 3 scope |
| kore-llm lintKotlinMain/Test (pre-existing) | — | ktlint violations in OllamaBackend.kt, GeminiBackend.kt, ClaudeBackendTest.kt | ℹ️ Info | Tracked in deferred-items.md |

**No new anti-patterns introduced by plan 03-05.** The previously-flagged HI-01 (`scope: val` cancelled on stop, never recreated) and HI-02 (InMemoryAuditLog unconditionally reported `hasStorage()=true`) are both removed. No TODO/FIXME/PLACEHOLDER strings, no hardcoded empty data flowing into rendering, no `var`, no `!!`.

### Human Verification Required

None. Every closure is programmatically testable and pinned by at least one test at the correct abstraction layer (unit, routing, Spring bean, restart identity). The en-dash degraded copy is asserted as a literal string at both routing endpoints. The restart contract is pinned by identity check + event round-trip. No visual UX verification is required beyond the kotlinx.html string-level tests.

### Gaps Summary

Phase 3 fully achieves its goal. The "one dependency, one @Bean, live dashboard" promise now works in every path the phase advertises:

- **Happy path (kore-storage present):** dashboard at `/kore` shows active agents, recent runs, and cost summary with real PostgreSQL data. PostgresAuditLogAdapter reports `isPersistent=true`, DashboardDataService.hasStorage() returns true, real tables render.
- **Zero-config path (kore-storage absent):** dashboard at `/kore` shows active agents (in-memory from EventBus), and the recent-runs + cost-summary fragments show the UI-SPEC-mandated degraded notice with the exact en-dash copy `"History unavailable — kore-storage not configured. Configure kore.storage.url in application.yml to enable run history."` InMemoryAuditLog inherits `isPersistent=false` from the port interface default; DashboardDataService.hasStorage() returns false; Fragments.kt renders the degraded notice.
- **Lifecycle path (Spring context refresh, devtools):** DashboardServer.stop() followed by DashboardServer.start() correctly installs a fresh CoroutineScope + EventBusDashboardObserver generation. The second observer collects events on a live scope. Pinned by a unit-level identity check + event round-trip test.

Both HI-01 and HI-02 are closed by targeted, mechanical changes (AtomicReference swap for lifecycle, port-level discriminator for degraded mode) with 11 new tests across 4 new test files and zero regressions in 72 pre-existing tests (final count 83).

**Status: passed. Ready to proceed to Phase 4.**

---

## Re-Verification Metadata

| Metric | Value |
|---|---|
| Previous status | gaps_found |
| Previous score | 16/18 |
| Previous verified | 2026-04-14T14:18:28Z |
| Closure plan | 03-05 |
| Closure commits | e75f537, 9bdf7ee, b2ecf59, f0e8106, f1e599d |
| New tests added | 11 |
| Test delta | +11 (8 HI-02 + 3 HI-01) |
| Baseline test count | 72 |
| Final test count | 83 (20 kore-core + 10 kore-storage + 11 kore-skills + 22 kore-dashboard + 20 kore-spring) |
| Regressions | 0 |
| New `var` introduced | 0 |
| New `!!` introduced | 0 |
| New ktor deps in kore-spring | 0 |
| New catalog aliases | 0 |
| Gaps closed | HI-01, HI-02 |
| Gaps remaining | none |

---

*Re-verified: 2026-04-14T15:04:00Z*
*Verifier: Claude (gsd-verifier)*
