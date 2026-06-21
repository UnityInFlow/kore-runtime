---
phase: 6
slug: real-budget-enforcement
status: approved
nyquist_compliant: true
wave_0_complete: true
created: 2026-06-21
---

# Phase 6 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Kotest assertions; MockK; kotlinx-coroutines-test; Spring `ApplicationContextRunner` (kore-spring) |
| **Config file** | `build.gradle.kts` per module (new `kore-budget` module added to `settings.gradle.kts`); Gradle 9.4.1 Kotlin DSL |
| **Quick run command** | `./gradlew :kore-budget:test` |
| **Full suite command** | `./gradlew test` (all modules; Docker-free — no Testcontainers in this phase) |
| **Estimated runtime** | ~tens of seconds (pure unit/coroutine + Spring context tests; no containers) |

---

## Sampling Rate

- **After every task commit:** Run the affected module's tests (`./gradlew :kore-budget:test` or `:kore-spring:test`)
- **After every plan wave:** Run `./gradlew test` (full unit suite — catches cross-module auto-config wiring)
- **Before `/gsd-verify-work`:** Full `./gradlew test` green
- **Max feedback latency:** ~60 seconds

---

## Per-Requirement Validation Map

> Derived from RESEARCH.md §Validation Architecture. Per-task IDs populated by planner/Nyquist audit once PLAN.md exists.

| Requirement | Validation Level | What to assert | How |
|-------------|------------------|----------------|-----|
| BUDG-05 | integration (Spring) | Auto-config gating: real adapter wired ONLY when budget-breaker class present AND `kore.budget.enabled=true`; `InMemoryBudgetEnforcer` remains the default otherwise; existing apps unchanged | `ApplicationContextRunner` 4-scenario matrix: (class present + enabled→BudgetBreakerAdapter), (present + disabled→InMemory), (absent→InMemory), (user `@Bean` wins via `@ConditionalOnMissingBean`) |
| BUDG-06 | unit (adapter) | Driving the adapter past the hard limit makes `checkBudget` return false → an agent run ends in `AgentResult.BudgetExceeded`; `BudgetHardLimitException` never propagates out of the port (with `TokenTracker.isAboveHardLimit()` it is never thrown — assert no throwable escapes) | Adapter unit test: set a tiny limit, `recordUsage` past it, assert `checkBudget`==false and no exception; plus an `AgentLoop` test wiring the adapter and asserting `AgentResult.BudgetExceeded` |
| BUDG-07 | unit (concurrency) | Two concurrent agents with distinct `AgentTask.id` (UUID) have isolated budgets — no cross-interference, no `TokenTracker` map-key collision | `runTest` (kotlinx-coroutines-test): launch two agents concurrently, exhaust one's budget, assert the other is unaffected; assert per-`agentId` `ConcurrentHashMap<String,TokenTracker>` isolation |

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | Status |
|---------|------|------|-------------|-----------|-------------------|--------|
| 06-01-01 | 01 | 1 | (module scaffold) | gradle | `./gradlew :kore-budget:dependencies … grep budget-breaker:0.0.1` | ✅ green |
| 06-01-02 | 01 | 1 | BUDG-06, BUDG-07 | unit | `./gradlew :kore-budget:test --tests "*BudgetBreakerAdapterTest"` | ✅ green |
| 06-02-01 | 02 | 2 | BUDG-05 | compile+lint | `./gradlew :kore-spring:compileKotlin :kore-spring:lintKotlin` | ✅ green |
| 06-02-02 | 02 | 2 | BUDG-05 | integration | `./gradlew :kore-spring:test --tests "*BudgetBreakerAutoConfigurationTest"` | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*
*Sampling continuity: every task carries an `<automated>` verify — no gap. No watch-mode flags; no Docker.*

---

## Wave 0 Requirements

- New `kore-budget` module must be scaffolded (build.gradle.kts + `settings.gradle.kts` include) before its tests can run — this is the phase's first task, not a separate Wave 0.
- `budget-breaker:0.0.1` dependency resolvable from Maven Central (verified present by research).
- Existing infra otherwise covers all requirements (JUnit 5 + Kotest + MockK + coroutines-test + Spring `ApplicationContextRunner` already available).

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| (none) | — | All three success criteria are machine-verifiable (no Docker, no external service) | — |

*All phase behaviors have automated verification.*

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (module scaffold is task 06-01-01; both test files created in their plans)
- [x] No watch-mode flags
- [x] Feedback latency < 60s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-06-21

---

## Validation Audit 2026-06-21

Post-execution audit (State A). All four task-map entries verified green against the implemented code with `--rerun-tasks` (fresh, not cached):

| Metric | Count |
|--------|-------|
| Requirements | 3 (BUDG-05, BUDG-06, BUDG-07) |
| Gaps found | 0 |
| Resolved | 0 (all COVERED at execution) |
| Escalated | 0 |

Evidence:
- `budget-breaker:0.0.1` present on `:kore-budget` runtimeClasspath (06-01-01).
- `BudgetBreakerAdapterTest` — tests=4 failures=0 errors=0 (BUDG-06 hard-stop no-escape, BUDG-07 16-agent×100-call isolation).
- `BudgetBreakerAutoConfigurationTest` — tests=4 failures=0 errors=0 (BUDG-05 4-scenario `ApplicationContextRunner` matrix incl. `FilteredClassLoader`).
- `:kore-spring:compileKotlin` + `:kore-spring:lintKotlin` clean (06-02-01).

**Verdict:** Nyquist-compliant — every requirement has automated verification, zero manual-only behaviors. No new tests needed.
