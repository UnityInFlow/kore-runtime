---
phase: 05-ci-baseline-skill-observability
plan: 02
subsystem: observability
tags: [opentelemetry, span-attributes, kotlinx-serialization, event-bus, skills, agent-loop, kotlin]

# Dependency graph
requires:
  - phase: 03-skills-spring-dashboard
    provides: SkillRegistry port + NoOpSkillRegistry, SkillRegistryAdapter (kore-skills), AgentLoop nullable Tracer skill-activate span
  - phase: 04-event-bus-publishing
    provides: AgentEvent sealed hierarchy with @Serializable/@SerialName/@JsonClassDiscriminator wire contract
provides:
  - "ActivatedSkill(name, prompt) stdlib-only data class in kore-core port package"
  - "SkillRegistry.activateFor() returns List<ActivatedSkill> (breaking port change, both impls migrated)"
  - "AgentEvent.SkillActivated(agentId, skillNames, durationMs) @Serializable subclass"
  - "AgentLoop kore.skill.activate span with kore.skill.names/count/duration_ms attrs, always emitted"
  - "AgentLoop emits SkillActivated on the bus only when >=1 skill matched (span/event asymmetry)"
affects: [05-03-observer-reactions, 05-04-koretracer-attrs, 07-hierarchical-agents]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Raw-Tracer span attribute setting via AttributeKey.stringArrayKey/longKey (kore-core cannot import KoreAttrs)"
    - "var-free try/finally span guard: val arrayOfNulls holder read in finally so attrs compute even on throw"
    - "Span-always / event-on-match asymmetry (D-04 vs D-07): observability is unconditional, broker traffic is frugal"

key-files:
  created: []
  modified:
    - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/port/SkillRegistry.kt
    - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentEvent.kt
    - kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentLoop.kt
    - kore-skills/src/main/kotlin/io/github/unityinflow/kore/skills/SkillRegistryAdapter.kt
    - kore-skills/src/test/kotlin/io/github/unityinflow/kore/skills/SkillRegistryAdapterTest.kt
    - kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentLoopSkillTest.kt
    - kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentEventSerializationTest.kt

key-decisions:
  - "ActivatedSkill placed in kore-core port package, stdlib-only, NOT @Serializable (D-02) — never crosses the bus"
  - "kore.skill.activate span name held as a private companion const SKILL_ACTIVATE_SPAN in AgentLoop (kore-core cannot depend on KoreSpans), mirroring KoreSpans.SKILL_ACTIVATE"
  - "var-free span guard: val arrayOfNulls<List<ActivatedSkill>>(1) holder assigned via .also in the try, read with .orEmpty() in finally so names/count/duration are always computable (incl. when activateFor throws) — no var, no !!"
  - "Duration measured with System.nanoTime() (monotonic) per D-04 / anti-pattern guidance"
  - "makeLoop test helper gained an eventBus parameter so SkillActivated emission can be observed via InProcessEventBus subscribe"

patterns-established:
  - "Pattern: raw OTel Span attributes from kore-core using literal key strings that mirror KoreAttrs (Plan 05-04 owns the constants)"
  - "Pattern: SharedFlow event-collection in runTest via backgroundScope.launch + subscribe().toList + runCurrent/yield (no advanceUntilIdle, which hangs on infinite collect loops)"

requirements-completed: [OBSV-03, OBSV-04]

# Metrics
duration: 12min
completed: 2026-06-20
---

# Phase 5 Plan 02: Skill-Activation Span & SkillActivated Event Summary

**Breaking SkillRegistry port change to List<ActivatedSkill> plus AgentLoop emitting an always-on kore.skill.activate span (name/count/duration attrs) and a >=1-match-only AgentEvent.SkillActivated on the bus**

## Performance

- **Duration:** ~12 min
- **Started:** 2026-06-20T17:20Z (approx)
- **Completed:** 2026-06-20
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments
- Landed the breaking `SkillRegistry.activateFor() : List<ActivatedSkill>` port change (D-01) — both in-repo impls (`NoOpSkillRegistry`, `SkillRegistryAdapter`) migrated, codebase compiles whole-build.
- Added the stdlib-only `ActivatedSkill(name, prompt)` data class in the kore-core port package (D-02) — not `@Serializable`, never crosses the bus.
- Added `AgentEvent.SkillActivated(agentId, skillNames, durationMs)` `@Serializable @SerialName` subclass (D-05/D-06); prompts deliberately excluded; round-trips through polymorphic JSON.
- Instrumented `AgentLoop` to build a `kore.skill.activate` span carrying `kore.skill.names` (string-array), `kore.skill.count` (long), `kore.skill.duration_ms` (long), always emitted when a tracer is present — including count=0 and even when `activateFor` throws (D-04 / Pitfall 5).
- Emitted `SkillActivated` on the bus only when >=1 skill matched (D-07), keeping the deliberate span-always / event-on-match asymmetry; prompts injected from `ActivatedSkill.prompt`, only names reach the event.

## Task Commits

Each task was committed atomically:

1. **Task 1: Breaking port change — ActivatedSkill + SkillRegistry/NoOpSkillRegistry + SkillRegistryAdapter (+ migrate test)** - `c948ab9` (refactor)
2. **Task 2: AgentEvent.SkillActivated + AgentLoop span attrs + conditional event emission** - `0520a1d` (feat)

**Plan metadata:** (final docs commit)

_Note: this plan's two tasks were each a single cohesive commit; the breaking change had to ripple through impls + tests in lockstep to keep every commit compilable._

## Files Created/Modified
- `kore-core/.../port/SkillRegistry.kt` - Added `ActivatedSkill` data class; `activateFor` + `NoOpSkillRegistry` return `List<ActivatedSkill>`.
- `kore-core/.../AgentEvent.kt` - Added `SkillActivated` subclass.
- `kore-core/.../AgentLoop.kt` - var-free span guard, three attrs via `AttributeKey`, `setParent(Context.current())`, conditional `SkillActivated` emission, `SKILL_ACTIVATE_SPAN` companion const.
- `kore-skills/.../SkillRegistryAdapter.kt` - Maps matched YAML skills to `ActivatedSkill(name, prompt)`.
- `kore-skills/.../SkillRegistryAdapterTest.kt` - Migrated Test 8/10 to read `.prompt` on the new element type.
- `kore-core/.../AgentLoopSkillTest.kt` - Migrated 3 anonymous stubs to `List<ActivatedSkill>`; added span-attr, event-on-match, no-event-on-0-match, and span-survives-throw tests; `makeLoop` gained an `eventBus` param.
- `kore-core/.../AgentEventSerializationTest.kt` - Added `SkillActivated` JSON round-trip case asserting `"type":"SkillActivated"` and field fidelity.

## Decisions Made
- Held the span name as a `private companion const SKILL_ACTIVATE_SPAN` in `AgentLoop` rather than hardcoding the literal inline — gives a single kore-core-side reference point that mirrors `KoreSpans.SKILL_ACTIVATE` (Open Question 1 in RESEARCH resolved toward a local const). Low stakes.
- Used a `val arrayOfNulls<List<ActivatedSkill>>(1)` holder assigned via `.also` inside the `try` and read with `.orEmpty()` in the `finally` to satisfy "always emit + compute attrs even on throw" without any `var` or `!!` (CLAUDE.md compliant).
- Added an `eventBus` parameter to the `makeLoop` test helper so `SkillActivated` emission can be observed; followed the established `backgroundScope` + `subscribe().toList` + `runCurrent`/`yield` collection idiom (per Phase-2 decision: `advanceUntilIdle` hangs on never-finishing collect loops).

## Deviations from Plan

None - plan executed exactly as written. (The observer `when` branches in kore-observability/kore-dashboard remain `else -> Unit` and are Plan 05-03's responsibility, as RESEARCH Pitfall 1 documented; whole-build compile confirms the new subclass does not break their compilation.)

## Issues Encountered
- A transient `ClassNotFoundException` for the kore-core test classes appeared once immediately after `formatKotlin`, accompanied by a "multiple Kotlin daemon sessions" warning (formatKotlin and test ran under different embedded Kotlin daemon versions). Resolved with `./gradlew --stop` + `:kore-core:cleanTest` and a clean re-run — all tests green. Not a code defect; no source change was needed.

## User Setup Required
None - no external service configuration required (no new dependencies; OTel + serialization already on the classpath).

## Next Phase Readiness
- OBSV-03 (emission side) and OBSV-04 (emission side) are complete and tested.
- Plan 05-03 can now add observer `when` branches for `AgentEvent.SkillActivated` (the subclass exists and serializes) and verify span parenting under `kore.agent.run` through `ObservableAgentRunner`.
- Plan 05-04 can add the `kore.skill.names`/`count`/`duration_ms` constants to `KoreAttrs` and the `List<String>` string-array branch to `KoreTracer.withSpan` (the literal key strings used in `AgentLoop` are the mirror contract those constants must match).

---
*Phase: 05-ci-baseline-skill-observability*
*Completed: 2026-06-20*

## Self-Check: PASSED

- All modified source files present on disk (SkillRegistry.kt, AgentEvent.kt, AgentLoop.kt, SkillRegistryAdapter.kt) and SUMMARY.md created.
- Both task commits present in git history (c948ab9, 0520a1d).
- Verify gates green: `./gradlew :kore-skills:test :kore-skills:lintKotlin` (Task 1), `./gradlew :kore-core:test :kore-core:lintKotlin` (Task 2), and whole-build `compileKotlin compileTestKotlin` (breaking port change ripples cleanly across all modules).
