---
phase: 06-real-budget-enforcement
plan: 02
subsystem: kore-spring
tags: [spring-boot, auto-config, budget-breaker, conditional-on-class, application-context-runner, kotlin]

# Dependency graph
requires:
  - phase: 06-real-budget-enforcement
    provides: BudgetBreakerAdapter (kore-budget module), byte-identical BudgetEnforcer port
provides:
  - BudgetBreakerAutoConfiguration triple-gate inner @Configuration in KoreAutoConfiguration
  - KoreProperties.BudgetProperties.enabled flag (default false, extensible shape D-01)
  - BUDG-05 4-scenario ApplicationContextRunner bean-selection matrix
  - kore-budget wired as compileOnly + testImplementation in kore-spring
affects: [budget-enforcement, kore-spring]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "triple-gate auto-config: @ConditionalOnClass(name=[fqn string], Pitfall 3) + @ConditionalOnProperty(enabled=true, matchIfMissing=false) + @ConditionalOnMissingBean(port::class), mirroring KafkaEventBusAutoConfiguration"
    - "FilteredClassLoader scenario in ApplicationContextRunner to prove @ConditionalOnClass keeps the default + context still starts when an optional adapter is absent"
    - "getBean(port).shouldBeInstanceOf<concrete>() doubles as a single-bean assertion (getBean throws on duplicates) — used because the adapter opens no socket"

key-files:
  created:
    - kore-spring/src/test/kotlin/io/github/unityinflow/kore/spring/BudgetBreakerAutoConfigurationTest.kt
  modified:
    - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreProperties.kt
    - kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAutoConfiguration.kt
    - kore-spring/build.gradle.kts

key-decisions:
  - "BudgetBreakerAutoConfiguration mirrors KafkaEventBusAutoConfiguration exactly; no bean-ordering annotations — both adapter and default carry @ConditionalOnMissingBean(BudgetEnforcer::class) and only one gate is satisfiable per app (same proof as StorageAutoConfiguration vs inMemoryAuditLog)"
  - "Context test asserts concrete bean type via getBean(...).shouldBeInstanceOf<...>() rather than the Kafka test's definition-level hasBean idiom — BudgetBreakerAdapter opens no socket so factory invocation is safe and proves exactly-one-bean (T-06-05 guard)"
  - "enabled defaults false (D-01) and reuses defaultMaxTokens as the single global hard limit — no new token-limit config key; InMemoryBudgetEnforcer stays the zero-config default"

requirements-completed: [BUDG-05]

# Metrics
duration: 2min
completed: 2026-06-21
---

# Phase 06 Plan 02: BudgetBreaker Auto-Configuration Summary

**Wired the real `BudgetBreakerAdapter` into Spring Boot 4 auto-configuration via a `BudgetBreakerAutoConfiguration` triple-gate — adding `kore-budget` plus `kore.budget.enabled=true` makes it the sole `BudgetEnforcer` bean, while `InMemoryBudgetEnforcer` stays the zero-config default for absent dependency, unset flag, or `enabled=false` (BUDG-05), proven by a 4-scenario ApplicationContextRunner matrix.**

## Performance

- **Duration:** ~2 min
- **Started:** 2026-06-21T09:19Z
- **Completed:** 2026-06-21T09:21Z
- **Tasks:** 2
- **Files modified:** 4 (1 created, 3 modified)

## Accomplishments
- BUDG-05: `BudgetBreakerAutoConfiguration` inner `@Configuration(proxyBeanMethods=false)` triple-gate added to `KoreAutoConfiguration` — `@ConditionalOnClass(name=["...BudgetBreakerAdapter"])` (string form, Pitfall 3), `@ConditionalOnProperty(prefix="kore.budget", name=["enabled"], havingValue="true", matchIfMissing=false)`, `@Bean @ConditionalOnMissingBean(BudgetEnforcer::class)` returning the port with a fully-qualified `BudgetBreakerAdapter(defaultHardLimitTokens = properties.budget.defaultMaxTokens)`.
- Added `BudgetProperties.enabled: Boolean = false` with extended KDoc marking the shape extensible for a future per-agent override map (D-01); reused `defaultMaxTokens` as the single global limit (no new key).
- Wired `kore-budget` as `compileOnly` (so the fully-qualified adapter constructor resolves at compile time) + `testImplementation` (so the `@ConditionalOnClass` gate fires in the context test) in `kore-spring/build.gradle.kts`.
- Created `BudgetBreakerAutoConfigurationTest` — 4 ApplicationContextRunner scenarios: enabled=true+present → `BudgetBreakerAdapter`; enabled unset → `InMemoryBudgetEnforcer`; enabled=false → `InMemoryBudgetEnforcer`; `FilteredClassLoader` hiding the adapter with enabled=true → `InMemoryBudgetEnforcer` (proves the class gate keeps the default and the context still starts, T-06-04).
- Left the existing `inMemoryBudgetEnforcer` default bean and the `BudgetEnforcer` port byte-unchanged (D-00).

## Task Commits

1. **Task 1: enabled flag + BudgetBreakerAutoConfiguration triple-gate + build wiring** - `a1b143f` (feat)
2. **Task 2: BUDG-05 4-scenario bean-selection matrix** - `8c72ee0` (test)

## Files Created/Modified
- `kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreProperties.kt` - Added `enabled: Boolean = false` to `BudgetProperties` with extended KDoc (BUDG-05 opt-in, D-01 extensible shape).
- `kore-spring/src/main/kotlin/io/github/unityinflow/kore/spring/KoreAutoConfiguration.kt` - Added `BudgetBreakerAutoConfiguration` inner triple-gate class before the Storage section; no change to the existing `inMemoryBudgetEnforcer` default bean.
- `kore-spring/build.gradle.kts` - Added `compileOnly(project(":kore-budget"))` + `testImplementation(project(":kore-budget"))`.
- `kore-spring/src/test/kotlin/io/github/unityinflow/kore/spring/BudgetBreakerAutoConfigurationTest.kt` - 4-scenario bean-selection matrix.

## Decisions Made
- Followed the locked decisions (D-00, D-01, D-06) and 06-RESEARCH.md Pattern 2 / 06-PATTERNS.md byte-accurately. No new decisions beyond those recorded in CONTEXT.md.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- The PostToolUse lint/build hook fired after the `KoreAutoConfiguration.kt` edit but before the `compileOnly(project(":kore-budget"))` build wiring (the third part of the same Task 1) was in place, reporting `Unresolved reference 'budget'`. This was expected mid-task ordering, not a code defect — adding the build wiring in the same task resolved it, and `./gradlew :kore-spring:compileKotlin :kore-spring:lintKotlin` was clean immediately after. No code change beyond the planned build wiring was needed.

## User Setup Required
None - no external service configuration required. To opt into real enforcement, a consuming app adds `implementation("io.github.unityinflow:kore-budget")` and sets `kore.budget.enabled=true`.

## Next Phase Readiness
- BUDG-05 complete: real budget enforcement is opt-in via one dependency + one property, with the in-memory default preserved for existing apps. Phase 06 (real-budget-enforcement) is fully delivered (BUDG-05/06/07).

---
*Phase: 06-real-budget-enforcement*
*Completed: 2026-06-21*

## Self-Check: PASSED

- All files present on disk (1 created, 3 modified + SUMMARY).
- Both task commits present in git history (`a1b143f`, `8c72ee0`).
