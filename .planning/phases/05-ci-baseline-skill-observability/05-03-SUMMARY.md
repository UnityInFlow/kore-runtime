---
phase: 05-ci-baseline-skill-observability
plan: 03
subsystem: observability
tags: [opentelemetry, micrometer, event-bus, skills, span-parenting, observer, kotlin]

# Dependency graph
requires:
  - phase: 05-ci-baseline-skill-observability
    provides: "Plan 05-02 — AgentEvent.SkillActivated(agentId, skillNames, durationMs), AgentLoop in-process kore.skill.activate span with setParent(Context.current())"
  - phase: 05-ci-baseline-skill-observability
    provides: "Plan 05-04 — KoreAttrs.SKILL_NAMES/COUNT/DURATION_MS constants + KoreTracer.withSpan string-array branch"
provides:
  - "KoreMetrics.skillsActivatedCounter(agentName, skillName) — kore.skills.activated counter (per-skill tag)"
  - "KoreMetrics.skillActivationDuration(agentName) — kore.skills.activate.duration DistributionSummary (per-run percentiles)"
  - "EventBusMetricsObserver explicit is AgentEvent.SkillActivated -> branch (counter per skill name + duration record)"
  - "EventBusSpanObserver explicit documented no-op SkillActivated branch (no duplicate span)"
  - "EventBusDashboardObserver explicit no-op SkillActivated branch (discoverability, no behavior change)"
  - "ObservableAgentRunnerTest parenting proof: kore.skill.activate parented under kore.agent.run through the real runner (OBSV-03)"
affects: [07-hierarchical-agents, kore-spring, kore-dashboard]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Explicit observer branch BEFORE else -> Unit so a new AgentEvent subclass is never silently dropped (RESEARCH Pitfall 1)"
    - "Micrometer DistributionSummary (not counter) for per-run durations to preserve percentiles/max (Open-Q 2)"
    - "Span-parenting proof drives a REAL AgentLoop through ObservableAgentRunner so Context.current() is the agent-run span — bare loop would emit a root span (Pitfall 2)"

key-files:
  created: []
  modified:
    - kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/KoreMetrics.kt
    - kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/EventBusMetricsObserver.kt
    - kore-observability/src/main/kotlin/io/github/unityinflow/kore/observability/EventBusSpanObserver.kt
    - kore-dashboard/src/main/kotlin/io/github/unityinflow/kore/dashboard/EventBusDashboardObserver.kt
    - kore-observability/src/test/kotlin/io/github/unityinflow/kore/observability/EventBusMetricsObserverTest.kt
    - kore-observability/src/test/kotlin/io/github/unityinflow/kore/observability/ObservableAgentRunnerTest.kt

key-decisions:
  - "Each of the three observers got an EXPLICIT is AgentEvent.SkillActivated -> branch BEFORE its else -> Unit; the compiler does not flag a missing branch on a sealed-event when, so the event would otherwise be silently dropped (RESEARCH Pitfall 1)"
  - "Metrics duration uses a DistributionSummary (kore.skills.activate.duration, tagged agent_name only) rather than a plain counter, keeping the distribution per-run not per-skill and preserving percentiles (Open-Q 2)"
  - "skill_name kept low-cardinality (configured names, never UUIDs) per D-24 / threat T-05-06; documented in the counter KDoc"
  - "EventBusSpanObserver SkillActivated branch is a deliberate documented no-op — the real kore.skill.activate span is created in-process by AgentLoop; synthesizing a second span would duplicate it in the single-JVM topology (D-08)"
  - "OBSV-03 parenting test wires the SAME SdkTracerProvider tracer into both the runner's KoreTracer and the real AgentLoop so both spans land in one InMemorySpanExporter, then asserts skillSpan.parentSpanContext.spanId == agentRunSpan.spanContext.spanId (and same traceId)"

patterns-established:
  - "Pattern: never let a new AgentEvent subclass fall through to else -> Unit in an observer — add an explicit branch (even if no-op) and pair behavior-bearing branches with a counter-moved test"
  - "Pattern: prove cross-component span parenting by driving the real component graph (loop through runner) into a shared InMemorySpanExporter, not by mocking the span source"

requirements-completed: [OBSV-03, OBSV-04]

# Metrics
duration: 8min
completed: 2026-06-20
---

# Phase 5 Plan 03: Observer Reactions to SkillActivated & Span-Parenting Proof Summary

**Wired all three event-bus observers to AgentEvent.SkillActivated (metrics counter + duration, span/dashboard explicit no-ops) and proved the kore.skill.activate span is parented under kore.agent.run through ObservableAgentRunner**

## Performance

- **Duration:** ~8 min
- **Started:** 2026-06-20 (approx)
- **Completed:** 2026-06-20
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- Closed the highest-risk gap of the phase (RESEARCH Pitfall 1): all three observers ended their `when (event)` in `else -> Unit`, so a new `SkillActivated` would compile silently and be dropped. Each observer now has an EXPLICIT branch placed before its `else`.
- `EventBusMetricsObserver` now increments `kore.skills.activated` once per skill name (tagged `agent_name` + `skill_name`) and records the activation-pass duration via a `DistributionSummary` (`kore.skills.activate.duration`, tagged `agent_name`).
- `KoreMetrics` gained `skillsActivatedCounter(agentName, skillName)` and `skillActivationDuration(agentName)` following the established `Counter.builder(...).register(registry)` / `DistributionSummary.builder(...)` factory pattern; cardinality constraint documented in KDoc (T-05-06).
- `EventBusSpanObserver` got an explicit documented no-op `SkillActivated` branch (the real span is created in-process by `AgentLoop`; a second span would duplicate it) and its stale "Phase 3" KDoc was updated to reflect the Phase-5 deliberate no-op (D-08).
- `EventBusDashboardObserver` got an explicit `is AgentEvent.SkillActivated -> Unit` branch for discoverability (no behavior change in v0.0.2) — kore-dashboard still compiles.
- OBSV-03 proven: a new `ObservableAgentRunnerTest` case drives a REAL `AgentLoop` (matching ≥1 skill) THROUGH `ObservableAgentRunner` into a shared `InMemorySpanExporter` and asserts `skillSpan.parentSpanContext.spanId == agentRunSpan.spanContext.spanId` (same trace), plus the `kore.skill.names` array attribute is present.

## Task Commits

Each task was committed atomically:

1. **Task 1: KoreMetrics skill counter/duration + the three observer branches** - `41b9da5` (feat: wire three observers to AgentEvent.SkillActivated (OBSV-04))
2. **Task 2: Span-parenting test through ObservableAgentRunner** - `37d2fbd` (test: prove kore.skill.activate span parented under kore.agent.run (OBSV-03))

**Plan metadata:** (final docs commit — SUMMARY/STATE/ROADMAP)

_Note: Both tasks are TDD tasks. The metrics counter-moved test landed in the same `feat` commit as the observer branches (the test asserts the new branch's behavior); the OBSV-03 parenting test is the `test` commit._

## Files Created/Modified
- `kore-observability/.../KoreMetrics.kt` - Added `skillsActivatedCounter(agentName, skillName)` (kore.skills.activated counter) and `skillActivationDuration(agentName)` (kore.skills.activate.duration DistributionSummary), both following the existing builder/register pattern; cardinality KDoc.
- `kore-observability/.../EventBusMetricsObserver.kt` - Added `is AgentEvent.SkillActivated ->` branch before `else -> Unit`: resolves agent name, increments the counter once per skill name, records duration once per run.
- `kore-observability/.../EventBusSpanObserver.kt` - Added explicit documented no-op `is AgentEvent.SkillActivated -> Unit` branch; updated the stale "Phase 3" KDoc to the Phase-5 deliberate-no-op rationale.
- `kore-dashboard/.../EventBusDashboardObserver.kt` - Added explicit `is AgentEvent.SkillActivated -> Unit` branch (discoverability, no behavior change).
- `kore-observability/src/test/.../EventBusMetricsObserverTest.kt` - Added a SkillActivated test (two skill names + duration) using the `backgroundScope` + `yield()` + `runCurrent()` + `SimpleMeterRegistry` idiom; asserts the counter moved per skill name and the duration summary recorded `count=1`, `totalAmount=42.0`.
- `kore-observability/src/test/.../ObservableAgentRunnerTest.kt` - Added a `DoneBackend` stub and the OBSV-03 parenting test driving a real `AgentLoop` through `ObservableAgentRunner`; asserts parent spanId + traceId equality and the skill-names attribute.

## Decisions Made
- Used a `DistributionSummary` (not a counter) for duration so percentiles/max survive, and tagged it by `agent_name` only (one activation pass = one duration regardless of how many skills matched) — Open-Q 2 recommendation.
- Made the span and dashboard branches explicit no-ops rather than relying on the existing `else -> Unit`: an explicit branch documents intent and guards against the silent-drop class of bug for any future reader (RESEARCH Pitfall 1 / D-08).
- The parenting test wires one shared `SdkTracerProvider` tracer into both the `KoreTracer` (for the agent-run span) and the real `AgentLoop` (for the skill span) so both spans land in the same exporter and the `setParent(Context.current())` relationship is observable end-to-end.

## Deviations from Plan

None - plan executed exactly as written. Both tasks (KoreMetrics + three observer branches; OBSV-03 parenting test) match the plan's `<action>` and `<acceptance_criteria>` exactly. No deviation rules were triggered; no packages installed (Micrometer/OTel already on the classpath — threat T-05-SC n/a).

## Issues Encountered
None during this execution. (Note: the working-tree state showed prior uncommitted/just-committed work for both tasks; verification confirmed both task commits — `41b9da5`, `37d2fbd` — were present and the implementation matched the plan, so this run validated the work, added the metadata, and ran the full regression gate.)

## User Setup Required
None - no external service configuration required (no new dependencies; Micrometer + OTel already on the classpath).

## Next Phase Readiness
- OBSV-03 and OBSV-04 are both complete and tested — no observer silently drops `SkillActivated`, and skill-span parenting is proven through the real runner.
- Phase 07 (hierarchical agents) can rely on the 3-level span hierarchy (agent.run → skill.activate / llm.call → tool.use) being correctly parented through `ObservableAgentRunner`.
- The `kore.skills.activated` counter and `kore.skills.activate.duration` summary are now queryable for any future dashboard/metrics surfacing of skill activations.

---
*Phase: 05-ci-baseline-skill-observability*
*Completed: 2026-06-20*

## Self-Check: PASSED

- All 6 modified source/test files present on disk; `05-03-SUMMARY.md` created.
- Both task commits present in git history (`41b9da5` feat, `37d2fbd` test).
- Verify gates green: `./gradlew :kore-observability:test :kore-observability:lintKotlin :kore-dashboard:compileKotlin :kore-dashboard:lintKotlin` (targeted) and `./gradlew test` (full unit-suite regression) both BUILD SUCCESSFUL.
