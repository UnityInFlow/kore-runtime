---
phase: 06-real-budget-enforcement
plan: 01
subsystem: infra
tags: [budget-breaker, token-tracker, hexagonal, coroutines, gradle-module, kotlin]

# Dependency graph
requires:
  - phase: 01-core-runtime
    provides: BudgetEnforcer port, TokenUsage value type, InMemoryBudgetEnforcer stub
provides:
  - kore-budget Gradle module (Spring-free library) registered in settings.gradle.kts
  - BudgetBreakerAdapter implementing the byte-identical BudgetEnforcer port over budget-breaker TokenTracker
  - Per-agentId hard-stop token enforcement (BUDG-06) with concurrent isolation (BUDG-07)
affects: [06-02-auto-config, kore-spring, budget-enforcement]

# Tech tracking
tech-stack:
  added: ["io.github.unityinflow:budget-breaker:0.0.1"]
  patterns: ["ConcurrentHashMap<String, TokenTracker> per-agentId state mirroring InMemoryBudgetEnforcer", "defensive try/catch(BudgetException) at the port boundary"]

key-files:
  created:
    - kore-budget/build.gradle.kts
    - kore-budget/src/main/kotlin/io/github/unityinflow/kore/budget/BudgetBreakerAdapter.kt
    - kore-budget/src/test/kotlin/io/github/unityinflow/kore/budget/BudgetBreakerAdapterTest.kt
  modified:
    - settings.gradle.kts

key-decisions:
  - "kore-budget is Spring-free (D-06): no Spring/serialization/jacoco plugins — auto-config lives in kore-spring (Plan 06-02)"
  - "Adapter reads getUsage straight off TokenTracker (promptTokens/completionTokens) — single source of truth, no parallel tally to drift"
  - "softLimitTokens = hardLimitTokens passed to AgentBudget to satisfy its soft<=hard init validation; soft is never read (D-02/D-03)"
  - "Defensive catch(BudgetException) kept even though TokenTracker.add is non-throwing — makes the BUDG-06 no-escape invariant explicit"

patterns-established:
  - "Port-adapter over a published first-party library: thin BudgetEnforcer impl delegating counting/limit-check to budget-breaker TokenTracker"
  - "Per-agentId (AgentTask.id UUID) ConcurrentHashMap keying for free concurrent isolation, no eviction (D-05)"

requirements-completed: [BUDG-06, BUDG-07]

# Metrics
duration: 8min
completed: 2026-06-21
---

# Phase 06 Plan 01: kore-budget Adapter Summary

**New Spring-free kore-budget module whose BudgetBreakerAdapter implements the unchanged BudgetEnforcer port over budget-breaker 0.0.1's TokenTracker — real per-agentId hard-stop enforcement with concurrent isolation and no library exception escaping the port.**

## Performance

- **Duration:** ~8 min
- **Started:** 2026-06-21T09:13Z
- **Completed:** 2026-06-21T09:21Z
- **Tasks:** 2
- **Files modified:** 4 (3 created, 1 modified)

## Accomplishments
- Scaffolded the `kore-budget` Gradle module (Spring-free library; `kore-core` + `budget-breaker:0.0.1` + coroutines), registered in `settings.gradle.kts`.
- Implemented `BudgetBreakerAdapter`: `ConcurrentHashMap<String, TokenTracker>` keyed by agentId; `recordUsage` → `tracker.add`, `checkBudget` → `!isAboveHardLimit`, `getUsage` read off the tracker.
- BUDG-06: driving the adapter past its hard limit does not throw and flips `checkBudget` to `false`; no `BudgetException` ever escapes the port.
- BUDG-07: distinct agentIds are fully isolated; concurrent `recordUsage` across 16 ids never cross-talks and never raises `IllegalArgumentException` (proving `withBudget` is never called).

## Task Commits

1. **Task 1: Scaffold kore-budget module (build + settings registration)** - `682c84a` (feat)
2. **Task 2 (RED): failing BUDG-06 + BUDG-07 tests** - `579c0c4` (test)
3. **Task 2 (GREEN): implement BudgetBreakerAdapter** - `739f65b` (feat)

_TDD task: test → feat. No refactor commit needed — build green on first GREEN pass._

## Files Created/Modified
- `settings.gradle.kts` - Added `kore-budget` as the final include entry.
- `kore-budget/build.gradle.kts` - Spring-free library module: `kotlin.jvm` + `kotlinter` + `kore.publishing`, deps on `kore-core` + `budget-breaker:0.0.1` + coroutines, JUnit5/Kotest/MockK/coroutines-test test deps, publishing POM.
- `kore-budget/src/main/kotlin/io/github/unityinflow/kore/budget/BudgetBreakerAdapter.kt` - `BudgetEnforcer` impl backed by `TokenTracker`, per-agentId map, defensive `catch(BudgetException)`, `val`-only, no `!!`.
- `kore-budget/src/test/kotlin/io/github/unityinflow/kore/budget/BudgetBreakerAdapterTest.kt` - 4 tests covering no-escape/hard-stop, fresh+unseen agents, isolation, and concurrency stress.

## Decisions Made
- Followed the locked decisions D-00..D-06 and 06-RESEARCH.md Pattern 1 byte-accurately. No new decisions beyond those already recorded in CONTEXT.md.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- The PostToolUse lint hook fired on the `settings.gradle.kts` edit before `kore-budget/build.gradle.kts` was written, momentarily erroring that the project directory didn't exist. Self-resolved once the build file (and thus the directory) was created in the same task; `./gradlew :kore-budget:dependencies` then resolved cleanly. No code change needed.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- `BudgetBreakerAdapter` is ready for Plan 06-02 to wire via `BudgetBreakerAutoConfiguration` (triple-gate) in `kore-spring`, plus the `BudgetProperties.enabled` flag and the BUDG-05 `ApplicationContextRunner` matrix.
- `BudgetEnforcer` port confirmed byte-identical (D-00) — zero AgentLoop ripple.

---
*Phase: 06-real-budget-enforcement*
*Completed: 2026-06-21*

## Self-Check: PASSED

- All 4 created files present on disk.
- All 3 task commits present in git history (`682c84a`, `579c0c4`, `739f65b`).
