---
phase: 03-skills-spring-dashboard
plan: 05
subsystem: dashboard + spring
tags: [gap-closure, dashboard, spring, auditlog, smartlifecycle, tdd]
gap_closure: true
closes: [HI-01, HI-02]
requirements: [DASH-01, DASH-02, DASH-03, DASH-04, SPRN-01]
dependency_graph:
  requires:
    - 03-01 (SkillRegistry + AuditLog read contracts)
    - 03-02 (kore-spring auto-configuration)
    - 03-03 (kore-dashboard Ktor server)
    - 03-04 (integration gate)
  provides:
    - "AuditLog.isPersistent discriminator on the port interface"
    - "DashboardDataService.hasStorage() degraded-mode contract pinned at unit + routing + Spring layers"
    - "DashboardServer restartable SmartLifecycle (start→stop→start swap) pinned by unit-level identity + event round-trip"
  affects:
    - kore-core (port shape — backwards compatible default)
    - kore-storage (PostgresAuditLogAdapter override)
    - kore-dashboard (DashboardDataService, DashboardServer, routing tests)
    - kore-spring (no source change; new Spring bean test added)
tech-stack:
  added: []
  patterns:
    - "AtomicReference-backed lifecycle fields for restartable val-only singletons"
    - "Port-level discriminator property (isPersistent) instead of type-introspection (takeUnless sentinel)"
    - "Cross-module test accessors: public `xForTest()` for kore-spring reachability, internal `xForTest()` for same-module tests"
key-files:
  created:
    - kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardDegradedModeTest.kt
    - kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardDegradedModeRoutingTest.kt
    - kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardServerRestartTest.kt
    - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/DashboardDegradedModeSpringTest.kt
  modified:
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt
    - kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt
    - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardDataService.kt
    - kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt
    - kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardRoutingTest.kt
    - kore-dashboard/build.gradle.kts
decisions:
  - "[Phase 03-skills-spring-dashboard]: AuditLog.isPersistent added as interface-default `get() = false` property so in-memory stubs inherit degraded-mode semantics with zero code change (HI-02 fix)"
  - "[Phase 03-skills-spring-dashboard]: DashboardServer.dataServiceForTest() is PUBLIC (not internal) because Kotlin `internal` is not transitive across Gradle/Kotlin compilation modules — the cross-module kore-spring test needs public visibility; the -Test suffix is the reviewer guard"
  - "[Phase 03-skills-spring-dashboard]: DashboardServer.scopeForTest() / observerForTest() stay `internal` because their only callers live in kore-dashboard/src/test — the SAME Kotlin module as kore-dashboard/src/main"
  - "[Phase 03-skills-spring-dashboard]: DashboardServer holds scope + observer in AtomicReference<T?> so start() after stop() installs a fresh generation — CLAUDE.md forbids `var`, so AtomicReference is the only mechanism that preserves val-only fields while allowing in-place swap"
  - "[Phase 03-skills-spring-dashboard]: kore-dashboard adds spring-context 7.0.0 as testImplementation — compileOnly does NOT propagate to the test classpath in Gradle, and tests constructing DashboardServer directly need the SmartLifecycle supertype on the test compile classpath"
  - "[Phase 03-skills-spring-dashboard]: TDD test functions using `= runBlocking { ... }` expression bodies MUST declare explicit `: Unit` return type — otherwise Kotest infix `shouldBe` leaks a Boolean return from the lambda and JUnit 5 silently skips the test (not visible in gradle output, only via javap)"
metrics:
  duration_minutes: 18
  completed: 2026-04-14
  tasks: 2
  commits_new_code: 4
  commits_docs: 1
  baseline_test_count: 72
  final_test_count: 83
  new_tests: 11
  test_delta: "+11 (8 HI-02 + 3 HI-01)"
  regressions: 0
---

# Phase 3 Plan 05: Gap Closure Summary

Closed HI-01 (DashboardServer cannot restart) and HI-02 (degraded-mode notice unreachable via default Spring wiring) flagged by Phase 3 verification + review. Both fixes landed as minimal, mechanical changes pinned by 11 new TDD-ordered tests distributed across kore-dashboard (8) and kore-spring (3).

## Objective

Close the two high-severity gaps from 03-VERIFICATION.md and 03-REVIEW.md that block Phase 3 completion. All changes follow strict RED→GREEN ordering with one production fix per commit.

## Findings Closed

### HI-01 — DashboardServer cannot restart

**Root cause.** `DashboardServer.scope` was a `private val CoroutineScope(...)` initialised once at class construction. `stop()` called `scope.cancel()`; a subsequent `start()` then launched the observer collector on the cancelled scope, producing a silent empty active-agents view and violating the Spring `SmartLifecycle` restart contract.

**Fix.** Replace the single-shot `scope` / `observer` fields with `AtomicReference<CoroutineScope?>` / `AtomicReference<EventBusDashboardObserver?>`. Each `start()` constructs a new scope + observer pair, installs them via `AtomicReference.set()`, then calls `newObserver.startCollecting()` and binds the Ktor engine against `newObserver`. Each `stop()` clears the engine ref first, then cancels the current scope via `getAndSet(null)?.cancel()` and clears the observer ref. CLAUDE.md forbids `var`, so `AtomicReference` is the only mechanism that preserves val-only fields while allowing in-place swap.

**Proof.** `DashboardServerRestartTest` adds 3 unit-level tests:
1. `start after stop recreates scope and observer and the new observer receives events` — Phase 1 start installs scope₁/observer₁, Phase 2 stop cancels scope₁ and clears the refs, Phase 3 second start installs distinct scope₂/observer₂ (identity check `scope2 !== scope1`), Phase 4 emits an `AgentStarted` event via `InProcessEventBus` and asserts `observer₂.snapshot()` receives it. Pre-fix the identity check fails because there is only one scope instance.
2. `double stop is idempotent` — second stop must not throw, `scopeForTest()` must be null afterwards. Pre-fix fails because `scope` is a `val` field that is never cleared.
3. `double start without intervening stop is idempotent` — second start early-returns because `engine.get() != null`, scope reference is preserved. Passes trivially both pre- and post-fix; documents the no-op guard.

Zero HTTP, zero `testApplication`, zero static port — all assertions go through `internal fun scopeForTest()` / `internal fun observerForTest()`. Port 0 ephemeral binding used so nothing collides on CI runners.

### HI-02 — Degraded-mode notice unreachable via Spring wiring

**Root cause.** `KoreAutoConfiguration.inMemoryAuditLog()` unconditionally supplied `InMemoryAuditLog` as the default `AuditLog` bean. In the zero-config hero-demo Spring path, `DashboardDataService` received a non-null `InMemoryAuditLog` and `hasStorage()` returned `true` (because it was a simple null check), so `/kore/fragments/recent-runs` and `/kore/fragments/cost-summary` rendered empty tables instead of the UI-SPEC.md degraded copy `"History unavailable — kore-storage not configured"`.

**Fix.** Add `val isPersistent: Boolean get() = false` to the `AuditLog` port interface as an interface-default property. Override to `true` in `PostgresAuditLogAdapter`. Change `DashboardDataService.hasStorage()` to `auditLog?.isPersistent == true`. In-memory stubs (`InMemoryAuditLog`, `InertAuditLog`) inherit the `false` default with zero code change. Drop the now-obsolete `takeUnless { it === InertAuditLog }` sentinel bypass in `DashboardServer.dataService` initialiser.

**Proof.** Three test files pin the contract at three layers:

1. `DashboardDegradedModeTest` (unit, kore-dashboard, 3 tests) — pins `hasStorage()` against `InMemoryAuditLog`, `PersistentStubAuditLog`, and `null` inputs.
2. `DashboardDegradedModeRoutingTest` (routing, kore-dashboard, 2 tests) — pins the exact UI-SPEC degraded copy (with en-dash `—`, not hyphen) at `/kore/fragments/recent-runs` and `/kore/fragments/cost-summary` when a non-null `InMemoryAuditLog` is bound. This is the critical distinction from pre-existing `DashboardRoutingTest` which only tested `auditLog = null` and never exercised the bug.
3. `DashboardDegradedModeSpringTest` (Spring bean, kore-spring, 3 tests) — pins the end-to-end contract at the REAL `@Autowired DashboardServer` bean via `dashboardServer.dataServiceForTest().hasStorage() shouldBe false` under default wiring, and `shouldBe true` when a fresh `DashboardServer` is constructed against a `PersistentStubAuditLog`. Any future refactor that re-introduces a null-coalescing wrapper around the audit log will break this assertion because the wrapper would no longer read the live `dataService` field.

No new test dependencies in kore-spring: `DashboardDegradedModeSpringTest` is pure bean introspection (no ktor `testApplication {}`), and `DashboardServer.dataServiceForTest()` is declared `public` (not `internal`) because Kotlin `internal` visibility is NOT transitive across Gradle/Kotlin compilation modules. The `-Test` suffix is the reviewer guard.

## Baseline and Final Test Counts

| Metric | Value |
| --- | --- |
| `BASELINE_N` (Task 1 Step 0) | 72 |
| Post-Task-1 count | 80 (+8) |
| Post-Task-2 count | **83 (+11)** |
| Expected delta | `BASELINE_N + 11` |
| Regressions | 0 |

Captured in `/tmp/gsd-03-05-baseline.txt` before any file change. Computed across `:kore-core:test :kore-storage:test :kore-skills:test :kore-dashboard:test :kore-spring:test` via test-results XML aggregation.

## Diff Summary Per File

| File | Change | LOC delta |
| --- | --- | --- |
| `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt` | Add `val isPersistent: Boolean get() = false` with KDoc on the `AuditLog` interface. | +16 |
| `kore-storage/src/main/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapter.kt` | `override val isPersistent: Boolean = true` (single line). | +2 |
| `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardDataService.kt` | `hasStorage()` body → `auditLog?.isPersistent == true`; KDoc paragraph rewritten. | +6 / -2 |
| `kore-dashboard/src/main/kotlin/dev/unityinflow/kore/dashboard/DashboardServer.kt` | Replace `private val scope` / `private val observer` with `scopeRef` / `observerRef` via `AtomicReference`; rewrite `start()` / `stop()` to swap atomically; add `dataServiceForTest()` (public), `scopeForTest()` (internal), `observerForTest()` (internal); simplify `dataService` initialiser by dropping the `takeUnless { it === InertAuditLog }` sentinel bypass. | +70 / -13 |
| `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardRoutingTest.kt` | `SeededAuditLog` adds `override val isPersistent: Boolean = true` so the pre-existing badge + cost-summary tests continue to render tables instead of the new degraded notice. | +5 |
| `kore-dashboard/build.gradle.kts` | Add `testImplementation("org.springframework:spring-context:7.0.0")` — tests constructing `DashboardServer` directly need the `SmartLifecycle` supertype on the test compile classpath; `compileOnly` does not propagate. | +5 |
| `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardDegradedModeTest.kt` (new) | 3 unit tests + `PersistentStubAuditLog` test double. | +79 |
| `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardDegradedModeRoutingTest.kt` (new) | 2 routing tests with `testApplication {}` blocks feeding a non-null `InMemoryAuditLog`. | +69 |
| `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardServerRestartTest.kt` (new) | 3 unit-level scope-swap tests (restart with event round-trip, double stop, double start). | +125 |
| `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/DashboardDegradedModeSpringTest.kt` (new) | 3 `@SpringBootTest` bean-introspection tests + `PersistentStubAuditLog` test double. | +105 |

**Not modified:** `kore-spring/build.gradle.kts`, `gradle/libs.versions.toml`, `kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InMemoryAuditLog.kt` (inherits default), `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt` (default InMemoryAuditLog bean intentionally preserved).

## Commands Run

```
# Baseline
./gradlew :kore-core:test :kore-storage:test :kore-skills:test :kore-dashboard:test :kore-spring:test
# → BASELINE_N = 72 passing (from test-results XML)

# Task 1 RED (HI-02)
./gradlew :kore-dashboard:test --tests 'dev.unityinflow.kore.dashboard.DashboardDegradedModeTest' \
          :kore-dashboard:test --tests 'dev.unityinflow.kore.dashboard.DashboardDegradedModeRoutingTest' \
          :kore-spring:test    --tests 'dev.unityinflow.kore.spring.DashboardDegradedModeSpringTest'
# → compileTestKotlin FAILED (unresolved isPersistent / dataServiceForTest) — RED confirmed

# Task 1 GREEN
./gradlew formatKotlin lintKotlin
./gradlew :kore-core:test :kore-storage:test :kore-skills:test :kore-dashboard:test :kore-spring:test
# → 80 passing = 72 + 8 ✓

# Task 2 RED (HI-01)
./gradlew :kore-dashboard:test --tests 'dev.unityinflow.kore.dashboard.DashboardServerRestartTest'
# → 3 tests discovered, 2 failed (double stop + start-after-stop), 1 passed (double start) — RED confirmed

# Task 2 GREEN
./gradlew formatKotlin lintKotlin
./gradlew :kore-core:test :kore-storage:test :kore-skills:test :kore-dashboard:test :kore-spring:test
# → 83 passing = 72 + 11 ✓

# Full regression
./gradlew build
# → BUILD SUCCESSFUL
```

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] DashboardRoutingTest SeededAuditLog needs isPersistent=true override**

- **Found during:** Task 1 Step 3 full test run (after GREEN production fix).
- **Issue:** The pre-existing `SeededAuditLog` test double in `DashboardRoutingTest.kt` simulates a persistent kore-storage adapter returning seeded rows, but it inherited the new `isPersistent = false` default — causing the pre-existing `result badges render...` and `cost-summary fragment renders per-agent rows and a TOTAL footer row` tests to render the degraded notice instead of the table.
- **Fix:** Added `override val isPersistent: Boolean = true` to `SeededAuditLog` with a comment explaining that it mirrors `PostgresAuditLogAdapter`.
- **Files modified:** `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardRoutingTest.kt`
- **Commit:** `9bdf7ee fix(03-05): add AuditLog.isPersistent discriminator…`

**2. [Rule 3 - Blocking] kore-dashboard tests cannot see SmartLifecycle supertype**

- **Found during:** Task 2 Step 1 RED test run.
- **Issue:** `spring-context:7.0.0` was declared `compileOnly` in `kore-dashboard/build.gradle.kts`, so tests that constructed `DashboardServer` directly could not resolve its `SmartLifecycle` supertype — 23 Kotlin compilation errors.
- **Fix:** Added `testImplementation("org.springframework:spring-context:7.0.0")` alongside the existing `compileOnly` declaration.
- **Files modified:** `kore-dashboard/build.gradle.kts`
- **Commit:** `b2ecf59 test(03-05): add failing regression test for DashboardServer restart…`

**3. [Rule 3 - Blocking] Kotest infix `shouldBe` leaks Boolean from `runBlocking` expression body → JUnit 5 silently skips the test**

- **Found during:** Task 2 Step 1 RED test run (only 2 of 3 tests were discovered — `start after stop recreates...` missing from the run output).
- **Issue:** The test function `fun \`start after stop...\`() = runBlocking { ... }` has an expression body. The last statement in the `runBlocking {}` lambda was `observer2.snapshot().containsKey("restart-agent") shouldBe true`; Kotest's infix `shouldBe` returns the receiver, so the lambda returns `Boolean`, and `runBlocking` returns `Boolean`, so the test function returns `Boolean`. JUnit 5 requires `void`/`Unit` return types for test methods and silently skipped this one (confirmed via `javap -p`).
- **Fix:** Added explicit `: Unit` return type to the test function signature: `fun \`start after stop...\`(): Unit = runBlocking { ... }`. After the fix, 3 tests discovered, 2 failing, 1 passing — the correct RED state.
- **Files modified:** `kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardServerRestartTest.kt`
- **Commit:** `b2ecf59 test(03-05): add failing regression test for DashboardServer restart…`

**Design note:** This is a subtle gotcha worth recording as a Kotlin+JUnit5+Kotest pattern. Any test function using `= runBlocking { ... }` whose last statement is a Kotest `shouldBe` assertion must declare `: Unit` explicitly. Without it, the test is silently skipped — no compile error, no runtime warning, just a missing test case in the report.

### Additional Deviations

None beyond the three auto-fixes above. No architectural changes. No Rule 4 escalations.

## Commit Trail

1. `e75f537 test(03-05): add failing degraded-mode regression tests (HI-02)` — RED for Task 1
2. `9bdf7ee fix(03-05): add AuditLog.isPersistent discriminator so degraded notice renders on default Spring wiring (HI-02)` — GREEN for Task 1 (includes SeededAuditLog fix-up)
3. `b2ecf59 test(03-05): add failing regression test for DashboardServer restart (HI-01)` — RED for Task 2 (includes spring-context testImplementation + `: Unit` return-type fix)
4. `f0e8106 fix(03-05): make DashboardServer restartable by holding scope in AtomicReference (HI-01)` — GREEN for Task 2
5. (this file) `docs(03-05): gap closure SUMMARY` — SUMMARY + STATE + ROADMAP updates

## Confirmation That kore-spring and gradle/libs.versions.toml Were Not Modified

```
$ git diff --name-only f0e8106~5 f0e8106 -- kore-spring/build.gradle.kts gradle/libs.versions.toml
(empty)
```

`kore-spring` only gained a new test file (`DashboardDegradedModeSpringTest.kt`); no build script or dependency change. `gradle/libs.versions.toml` is untouched — no new `ktor-server-test-host` alias, no new catalog entries.

## 03-VERIFICATION.md Re-verification Readiness

| Finding | Status | Proof |
| --- | --- | --- |
| HI-01 DashboardServer cannot restart | CLOSED | `DashboardServerRestartTest` (3 tests, unit-level, identity check + event round-trip) |
| HI-02 Degraded-mode notice unreachable via Spring wiring | CLOSED | `DashboardDegradedModeTest` (3 unit) + `DashboardDegradedModeRoutingTest` (2 routing) + `DashboardDegradedModeSpringTest` (3 Spring bean, pinned to real `@Autowired DashboardServer`) |

Ready for 03-VERIFICATION.md re-run.

## Self-Check: PASSED

**Files exist:**
- FOUND: kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardDegradedModeTest.kt
- FOUND: kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardDegradedModeRoutingTest.kt
- FOUND: kore-dashboard/src/test/kotlin/dev/unityinflow/kore/dashboard/DashboardServerRestartTest.kt
- FOUND: kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/DashboardDegradedModeSpringTest.kt

**Commits exist:**
- FOUND: e75f537 test(03-05): add failing degraded-mode regression tests (HI-02)
- FOUND: 9bdf7ee fix(03-05): add AuditLog.isPersistent discriminator...
- FOUND: b2ecf59 test(03-05): add failing regression test for DashboardServer restart (HI-01)
- FOUND: f0e8106 fix(03-05): make DashboardServer restartable by holding scope in AtomicReference (HI-01)

**Static checks:**
- `val isPersistent` in AuditLog.kt: 1 match (with `get() = false`)
- `override val isPersistent` in PostgresAuditLogAdapter.kt: 1 match (with `= true`)
- `isPersistent` in InMemoryAuditLog.kt: 0 matches (inherits default)
- `auditLog?.isPersistent == true` in DashboardDataService.kt: 1 match
- `takeUnless { it === InertAuditLog }` in DashboardServer.kt: 0 matches
- `private val scopeRef = AtomicReference` in DashboardServer.kt: 1 match
- `private val scope = CoroutineScope` in DashboardServer.kt: 0 matches (legacy field removed)
- `fun dataServiceForTest` in DashboardServer.kt: 1 match (public)
- `internal fun scopeForTest` in DashboardServer.kt: 1 match
- `internal fun observerForTest` in DashboardServer.kt: 1 match
- `testImplementation.*ktor` in kore-spring/build.gradle.kts: 0 matches
- `\bvar\b` in DashboardServer.kt: 0 matches (only doc-comment mentions)
- `!!` in DashboardServer.kt: 0 matches

**Final test count:** 83 passing = BASELINE_N(72) + 11. Zero regressions.
