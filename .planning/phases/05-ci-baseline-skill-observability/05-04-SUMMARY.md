---
phase: 05-ci-baseline-skill-observability
plan: 04
subsystem: observability
tags: [opentelemetry, span-attributes, string-array, koretracer, koreattrs, kotlin]

# Dependency graph
requires:
  - phase: 05-ci-baseline-skill-observability
    plan: 02
    provides: "AgentLoop emits kore.skill.activate span with literal kore.skill.names/count/duration_ms keys (KoreAttrs is the mirror source-of-truth those constants must match)"
provides:
  - "KoreAttrs.SKILL_NAMES/SKILL_COUNT/SKILL_DURATION_MS constants — single source of truth for the kore.skill.* attribute keys"
  - "KoreTracer.withSpan List<*> dispatch branch — sets a native OTel string-array via AttributeKey.stringArrayKey + filterIsInstance<String>()"
affects: [05-03-observer-reactions, 07-hierarchical-agents]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "List<*> -> AttributeKey.stringArrayKey(key) + filterIsInstance<String>() in withSpan dispatch (no UNCHECKED_CAST, no !!, no comma-join)"
    - "KoreAttrs as the single source of truth for kore.skill.* keys; AgentLoop mirrors the literal strings inline because kore-core cannot depend on kore-observability"

key-files:
  created: []
  modified:
    - kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/KoreTracer.kt
    - kore-observability/src/test/kotlin/io/github/unityinflow/kore/observability/KoreTracerTest.kt

key-decisions:
  - "is List<*> branch (not is List<String>) — JVM type erasure makes List<String> unreachable in a when; filterIsInstance<String>() narrows safely at runtime and drops non-String elements without crashing (Pitfall 4: no @Suppress, no !!)"
  - "Skill constants documented in-code as mirroring the literal keys AgentLoop emits inline (A4 / Open-Q 1) — KoreAttrs is the source of truth, AgentLoop cannot import it because kore-core has no kore-observability dependency"

requirements-completed: [OBSV-03]

# Metrics
duration: 8min
completed: 2026-06-20
---

# Phase 5 Plan 04: KoreTracer String-Array Attribute & KoreAttrs Skill Constants Summary

**Teach KoreTracer.withSpan to set a native OTel string-array from a List<String> value and publish the three kore.skill.* attribute-key constants on KoreAttrs (OBSV-03 support side).**

## Performance

- **Duration:** ~8 min
- **Completed:** 2026-06-20
- **Tasks:** 1 (TDD: RED → GREEN, no REFACTOR needed)
- **Files modified:** 2

## Accomplishments
- Added the `// Skill (D-03)` group to `KoreAttrs`: `SKILL_NAMES = "kore.skill.names"`, `SKILL_COUNT = "kore.skill.count"`, `SKILL_DURATION_MS = "kore.skill.duration_ms"` — exact-string mirror of the literal keys `AgentLoop` emits inline (Plan 05-02), preventing key drift. `KoreAttrs` is now the single source of truth for the `kore.skill.*` keys.
- Added an `is List<*> ->` branch to `withSpan`'s `Map<String, Any>` attribute dispatch (which previously handled only String/Long/Int/Double/Boolean and silently dropped everything else). It sets a native OTel string-array via `AttributeKey.stringArrayKey(key)` + `value.filterIsInstance<String>()` — D-03's queryable-per-element requirement, with no comma-join, no `@Suppress("UNCHECKED_CAST")`, and no `!!`.
- Added `import io.opentelemetry.api.common.AttributeKey` to `KoreTracer.kt`.
- Proved the behavior with three `KoreTracerTest` cases (reusing the existing `InMemorySpanExporter` setup): a `List<String>` round-trips as a string-array on a finished span; a mixed list drops its non-String elements without crashing; and the three `KoreAttrs` skill constants equal their literal key strings.

## TDD Gate Compliance
- **RED:** Added the three tests first; `:kore-observability:compileTestKotlin` failed with `Unresolved reference 'SKILL_NAMES'/'SKILL_COUNT'/'SKILL_DURATION_MS'` (compile-level RED — the constants and branch did not yet exist).
- **GREEN:** Added the constants + `is List<*>` branch; `./gradlew :kore-observability:test --tests "*KoreTracer*" :kore-observability:lintKotlin` passed.
- **REFACTOR:** None needed — the one-line `filterIsInstance` branch is already minimal and idiomatic.

_Note: the RED and GREEN changes landed in a single atomic commit (`ca30f78`) because the failing-test reference and the constant/branch that resolves it touch the same two files and must compile together; the RED state was verified independently via `compileTestKotlin` before the implementation was added._

## Task Commits

1. **Task 1: Add KoreAttrs skill constants + string-array withSpan branch (TDD)** — `ca30f78` (feat)

**Plan metadata:** (final docs commit)

## Files Created/Modified
- `kore-observability/.../KoreTracer.kt` — Added `AttributeKey` import; added the `// Skill (D-03)` constant group to `KoreAttrs`; added the `is List<*>` string-array branch to `withSpan`.
- `kore-observability/.../KoreTracerTest.kt` — Added three tests: string-array round-trip, non-String drop, and constant-key equality (reusing the existing `InMemorySpanExporter` + `SdkTracerProvider` + `SimpleSpanProcessor` harness).

## Decisions Made
- Used `is List<*>` (not `is List<String>`) in the `when` — JVM type erasure makes a `List<String>` type check unreachable; `filterIsInstance<String>()` performs the safe runtime narrowing and also satisfies the "non-String elements dropped, not crashed on" behavior. No `@Suppress("UNCHECKED_CAST")`, no `!!` (CLAUDE.md / Pitfall 4 compliant).
- Documented the constants in-code as the mirror contract for `AgentLoop`'s inline literal keys, cross-referencing that kore-core cannot import `KoreAttrs` (A4 / Open-Q 1).

## Deviations from Plan

None - plan executed exactly as written. (During editing, a linter/auto-format pass briefly introduced near-duplicate test methods; the redundant copies were removed before commit, leaving the three intended tests. No behavior change, no extra commit.)

## Issues Encountered
None - clean RED → GREEN cycle on the first implementation pass; lint passed without a formatKotlin step.

## User Setup Required
None - no new dependencies (`opentelemetry-sdk-testing` + `AttributeKey` already on the classpath).

## Next Phase Readiness
- OBSV-03 is now complete on both sides: the emission side (Plan 05-02, `AgentLoop` literal keys) and the support side (this plan, `KoreAttrs` constants + `withSpan` string-array branch).
- Plan 05-03 remains: observer `when` reactions to `AgentEvent.SkillActivated` (`EventBusMetricsObserver` counter/duration, `EventBusSpanObserver` explicit no-op) and the span-parenting test under `kore.agent.run` via `ObservableAgentRunner`. Those are independent of this plan's `KoreTracer` change.

---
*Phase: 05-ci-baseline-skill-observability*
*Completed: 2026-06-20*

## Self-Check: PASSED

- Both modified source files present on disk (`KoreTracer.kt`, `KoreTracerTest.kt`) and `05-04-SUMMARY.md` created.
- Task commit present in git history (`ca30f78`).
- Verify gate green: `./gradlew :kore-observability:test --tests "*KoreTracer*" :kore-observability:lintKotlin` (BUILD SUCCESSFUL); RED state confirmed independently via `compileTestKotlin` (Unresolved reference before implementation).
