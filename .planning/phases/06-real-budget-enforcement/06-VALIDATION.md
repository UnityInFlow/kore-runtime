---
phase: 6
slug: real-budget-enforcement
status: draft
nyquist_compliant: false
wave_0_complete: false
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
| TBD (populated by planner/Nyquist audit) | — | — | — | — | — | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

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

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
