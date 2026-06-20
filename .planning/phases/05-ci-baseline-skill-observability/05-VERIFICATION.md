---
phase: 05-ci-baseline-skill-observability
verified: 2026-06-20T00:00:00Z
status: passed
score: 4/4 must-haves verified
overrides_applied: 0
human_verification_resolved: "Both Docker-gated UAT items user-attested PASS on 2026-06-20 (05-UAT.md): CI-01 live integrationTest run against real PostgreSQL, and CI-02 CI integration-test job on arc-runner-unityinflow. Verification moves human_needed → passed."
re_verification:
  previous_status: human_needed
  previous_score: 4/4
  gaps_closed: ["CI-01 live integrationTest run (UAT test 1 — user-attested pass)", "CI-02 CI integration-test job on arc-runner-unityinflow (UAT test 2 — user-attested pass)"]
  gaps_remaining: []
  regressions: []
  note: "Previous VERIFICATION.md had no `gaps:` section — re-run as full verification with the prior report treated as a prior to falsify. All four success criteria re-confirmed against the live codebase; criteria 3 and 4 machine-verified by a fresh `--rerun-tasks` test run (not cached). No regressions."
human_verification:
  - test: "Run `./gradlew :kore-storage:integrationTest` on a Docker-equipped host (or observe the first PR CI run on arc-runner-unityinflow)"
    expected: "All 13 @Tag(\"integration\") Testcontainers tests spin up real PostgreSQL and execute with >0 tests run; no GradleException from the zero-test guard; BUILD SUCCESSFUL."
    why_human: "This host has no Docker daemon. Testcontainers requires a live daemon to start a real PostgreSQL container — the 'tests actually execute against real PostgreSQL' clause of CI-01 cannot be exercised by static inspection. Task registration, tag filter, and the AtomicInteger/TestListener/GradleException fail-loud guard ARE verified by inspection and task discovery."
  - test: "Trigger the CI `integration-test` job (open a PR or push to main) and observe the run on arc-runner-unityinflow"
    expected: "The `docker info` pre-flight passes, then `./gradlew :kore-storage:integrationTest` runs green. If Docker were absent, the pre-flight fails loudly with the `::error title=Docker unavailable::...RUNNER CONFIG ERROR, not a test failure...` annotation and exit 1 BEFORE any test runs."
    why_human: "CI execution against a self-hosted runner with a live Docker daemon is first-PR-gated and not reproducible on this host. Job shape, runner label, needs:build, docker pre-flight, and the gradle task invocation ARE verified by inspecting ci.yml."
---

# Phase 5: CI Baseline & Skill Observability Verification Report

**Phase Goal:** kore-storage's Testcontainers integration tests run in CI, and skill activations emit both an OTel span and an event-bus event — closing the last gaps in CI correctness and the span hierarchy.
**Verified:** 2026-06-20
**Status:** passed (both Docker-gated UAT items user-attested pass — see 05-UAT.md)
**Re-verification:** Yes — prior report had no `gaps:` section; re-run as full verification. Criteria 3 and 4 re-proven with a fresh (non-cached) test run; criteria 1 and 2 closed by user UAT attestation.

## Goal Achievement

### Observable Truths

| # | Truth (Success Criterion) | Status | Evidence |
|---|---------------------------|--------|----------|
| 1 | `./gradlew :kore-storage:integrationTest` runs the Testcontainers tests against real PostgreSQL and fails loudly if 0 tests run | ✓ VERIFIED (mechanism) / ? human (live Docker run) | `kore-storage/build.gradle.kts:51-95`: `tasks.register<Test>("integrationTest")` reuses `src/test` (`testClassesDirs`/`classpath` from `sourceSets["test"]`), `useJUnitPlatform { includeTags("integration") }`, zero-test guard = `val executed = AtomicInteger(0)` + `addTestListener(TestListener.afterTest{ incrementAndGet() })` + `doLast { if (executed.get()==0) throw GradleException(...) }`. Decoupled from build/check; unit `test` still `excludeTags("integration")` (lines 39-43). 3 classes carry `@Tag("integration")`+`@Testcontainers` (13 `@Test` total). Live containerized run → human (no Docker daemon on host). |
| 2 | CI runs the integration tests on arc-runner-unityinflow with a `docker info` pre-flight and asserts tests actually executed (no silent 0-test pass) | ✓ VERIFIED (mechanism) / ? human (live CI run) | `.github/workflows/ci.yml:52-78`: `integration-test` job, `runs-on: [arc-runner-unityinflow]`, `needs: build`, `docker info` pre-flight emitting `::error title=Docker unavailable::...RUNNER CONFIG ERROR, not a test failure...` + `exit 1` before tests, then `run: ./gradlew :kore-storage:integrationTest`. "Tests actually executed" assertion is the in-Gradle zero-test guard (no CI-side XML parsing). No `ubuntu-latest`. Live CI run on self-hosted runner → human. |
| 3 | A skill activation produces a `kore.skill.activate` OTel span parented under the agent-run span, carrying skill name/count/duration attributes | ✓ VERIFIED (machine) | `AgentLoop.kt:106-130`: `tracer?.spanBuilder(SKILL_ACTIVATE_SPAN)?.setParent(Context.current())?.startSpan()`; in `finally` sets `kore.skill.names` (stringArrayKey), `kore.skill.count` (longKey), `kore.skill.duration_ms` (longKey) and `end()` — always emits incl. count=0 and on throw. `SKILL_ACTIVATE_SPAN = "kore.skill.activate"` (line 292). Parenting proven by `ObservableAgentRunnerTest.kt:164-197` (`skillSpan.parentSpanContext.spanId shouldBe agentRunSpan.spanContext.spanId`, same traceId, names attr present). `KoreAttrs.SKILL_NAMES/COUNT/DURATION_MS` (KoreTracer.kt:42-44) match the literal keys; `is List<*>` → stringArrayKey branch (line 85). Fresh test run PASS: AgentLoopSkillTest 10/0/0, ObservableAgentRunnerTest 9/0/0, KoreTracerTest 8/0/0. |
| 4 | A skill activation emits `AgentEvent.SkillActivated` on the event bus, observable by metrics observers | ✓ VERIFIED (machine) | `AgentEvent.kt:77-83`: `@Serializable @SerialName("SkillActivated") data class SkillActivated(agentId, skillNames, durationMs)`. `AgentLoop.kt:133-149`: emitted only when `activated.isNotEmpty()` (D-07 asymmetry vs always-on span). `EventBusMetricsObserver.kt:80`: `is AgentEvent.SkillActivated ->` increments `kore.skills.activated` per skill + records duration. `EventBusSpanObserver.kt:107` explicit `-> Unit` no-op; `EventBusDashboardObserver.kt:90` explicit `-> Unit` no-op. Fresh test run PASS: EventBusMetricsObserverTest 5/0/0, AgentEventSerializationTest 7/0/0 (JSON round-trip), SkillRegistryAdapterTest 7/0/0. |

**Score:** 4/4 truths verified. Criteria 3 and 4 are fully machine-verified by fresh (non-cached, `--rerun-tasks`) test execution. Criteria 1 and 2 are mechanism-verified in code; only the inherently Docker-dependent live execution is human-gated.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `kore-storage/build.gradle.kts` | integrationTest task + zero-test guard | ✓ VERIFIED | Registered under `verification` group, tag-filtered, fail-loud `GradleException` guard, decoupled from build/check; unit `test` excludes integration tag. |
| `.github/workflows/ci.yml` | integration-test job + docker pre-flight | ✓ VERIFIED | Self-hosted runner, `needs: build`, loud docker pre-flight, gradle task. No ubuntu-latest. |
| `kore-core/.../port/SkillRegistry.kt` | `ActivatedSkill` + `activateFor: List<ActivatedSkill>` | ✓ VERIFIED | `data class ActivatedSkill(name, prompt)`, stdlib-only, NOT @Serializable (D-02). Default impl returns emptyList(). |
| `kore-core/.../AgentEvent.kt` | `SkillActivated` subclass | ✓ VERIFIED | @Serializable + @SerialName, names-only payload (no prompts cross the bus). |
| `kore-core/.../AgentLoop.kt` | skill span (always) + conditional event | ✓ VERIFIED | 3 attrs, always-emit span via try/finally (var-free arrayOfNulls holder), event-on-match. |
| `kore-skills/.../SkillRegistryAdapter.kt` | maps to `ActivatedSkill(name, prompt)` | ✓ VERIFIED | SkillRegistryAdapterTest 7/0/0. |
| `kore-observability/.../KoreTracer.kt` | KoreAttrs skill consts + List<*> branch | ✓ VERIFIED | Constants equal AgentLoop literal keys; `is List<*>` → stringArrayKey + filterIsInstance. |
| `kore-observability/.../KoreMetrics.kt` | skillsActivatedCounter + duration | ✓ VERIFIED | `kore.skills.activated` counter + `kore.skills.activate.duration` recorder. |
| Three event-bus observers | explicit SkillActivated branches | ✓ VERIFIED | Metrics (behavior), Span (explicit no-op line 107), Dashboard (explicit no-op line 90). |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| ci.yml integration-test job | :kore-storage:integrationTest | gradle task invocation | ✓ WIRED | `run: ./gradlew :kore-storage:integrationTest` (ci.yml:78) |
| integrationTest task | @Tag("integration") classes | includeTags("integration") | ✓ WIRED | build.gradle.kts:58-60; all 3 classes tagged |
| AgentLoop skill span | OTel span attributes | stringArrayKey/longKey + setAttribute | ✓ WIRED | AgentLoop.kt:125-127 |
| AgentLoop | SkillActivated on bus | eventBus.emit when activated.isNotEmpty() | ✓ WIRED | AgentLoop.kt:133,142-148 |
| EventBusMetricsObserver | kore.skills.activated meter | skillsActivatedCounter(...).increment() | ✓ WIRED | EventBusMetricsObserver.kt:80-89 |
| ObservableAgentRunner skill span | kore.agent.run parent span | setParent(Context.current()) | ✓ WIRED | proven by ObservableAgentRunnerTest.kt:193 (spanId match) |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Docker-free unit suites (criteria 3 & 4) execute fresh and pass | `./gradlew :kore-core:test :kore-observability:test :kore-skills:test --rerun-tasks` | BUILD SUCCESSFUL in 10s; 24 tasks executed (not cached) | ✓ PASS |
| Skill span/parenting proof tests pass with >0 tests | parsed JUnit XML | AgentLoopSkillTest 10/0/0, ObservableAgentRunnerTest 9/0/0, KoreTracerTest 8/0/0 | ✓ PASS |
| Skill event/serialization/metrics proof tests pass | parsed JUnit XML | EventBusMetricsObserverTest 5/0/0, AgentEventSerializationTest 7/0/0, SkillRegistryAdapterTest 7/0/0 | ✓ PASS |
| integrationTest live run against real Postgres | `./gradlew :kore-storage:integrationTest` | No Docker daemon on host | ? SKIP → human |
| CI integration-test job on self-hosted runner | (CI only) | First-PR-gated; not reproducible locally | ? SKIP → human |

### Probe Execution

Not applicable — no `scripts/*/tests/probe-*.sh` and the phase declares no probe-based verification.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| CI-01 | 05-01 | Dedicated `integrationTest` task, tag-filtered, fails loudly if 0 tests execute | ✓ SATISFIED | Task + zero-test guard verified by inspection; live containerized run human-gated |
| CI-02 | 05-01 | CI runs integration tests on arc-runner-unityinflow with `docker info` pre-flight, asserting tests executed | ✓ SATISFIED | Job shape + pre-flight + in-Gradle assertion verified; live CI run human-gated |
| OBSV-03 | 05-02, 05-03, 05-04 | Skill activation emits `kore.skill.activate` span parented under agent-run span, name/count/duration attrs | ✓ SATISFIED | Emission + parenting test + KoreAttrs/withSpan support, all machine-verified green |
| OBSV-04 | 05-02, 05-03 | Skill activation emits `AgentEvent.SkillActivated` for metrics observers | ✓ SATISFIED | Subclass + conditional emission + observer reactions + counter-moved test, all green |

All 4 phase requirement IDs accounted for. REQUIREMENTS.md maps exactly CI-01, CI-02, OBSV-03, OBSV-04 to Phase 5 (lines 25-31), all four appear in plan `requirements:` frontmatter, all four are marked `[x]` and "Complete" in the traceability table (lines 75-78). No orphaned requirements.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (none) | — | No TODO/FIXME/XXX/TBD/HACK/placeholder markers; no `var`; no `!!` in any phase-modified source file | — | Clean (CLAUDE.md compliant) |

### Quality Observations (informational — do NOT fail any criterion)

1. **WR-02 (from 05-REVIEW.md): skill duration measured twice.** `AgentLoop.kt` computes `durMs` once in the `finally` block (line 127, for the span's `kore.skill.duration_ms`) and again at the emission block (the second `val durMs = (System.nanoTime() - startNanos) / 1_000_000` before `eventBus.emit`). The two reads can diverge by the cost of the intervening work, so the span's `kore.skill.duration_ms` and the event's `durationMs` may not be byte-identical. Both values ARE still emitted and both measure from the same `startNanos`, so neither Success Criterion 3 nor 4 fails. Recommend hoisting a single `durMs` (computed once after `activateFor` returns) and reusing it for both the span and the event for consistency.

2. **Documentation discrepancy (stale count).** REQUIREMENTS.md (line 30) and ROADMAP describe "7 Testcontainers integration tests"; the actual count is **13 `@Test` methods** across 3 `@Tag("integration")` classes (MigrationTest 4, PostgresAuditLogAdapterTest 5, PostgresAuditLogAdapterQueryTest 4). Does NOT affect goal achievement — the fail-loud guard fires on `count == 0` regardless of the exact non-zero count. Optionally update the "7" wording for accuracy.

### Human Verification Required

#### 1. Live integrationTest run against real PostgreSQL (CI-01)

**Test:** Run `./gradlew :kore-storage:integrationTest` on a Docker-equipped host (or observe the first PR CI run).
**Expected:** All 13 `@Tag("integration")` Testcontainers tests start a real PostgreSQL container and execute with >0 tests run; no GradleException; BUILD SUCCESSFUL.
**Why human:** This host has no Docker daemon; Testcontainers needs a live daemon. Task registration, tag filter, and fail-loud guard are verified by inspection — only the containerized execution is human-gated.

#### 2. CI integration-test job run on arc-runner-unityinflow (CI-02)

**Test:** Open a PR or push to `main`; observe the `integration-test` job on the self-hosted runner.
**Expected:** `docker info` pre-flight passes, then `./gradlew :kore-storage:integrationTest` runs green. (If Docker were missing, the pre-flight fails loudly with the config-error annotation before any test.)
**Why human:** CI execution against a self-hosted runner with a live Docker daemon is first-PR-gated and not reproducible on this host. Job shape, runner, `needs: build`, pre-flight, and gradle invocation are verified by inspecting ci.yml.

### Gaps Summary

No gaps. All four success criteria are achieved in the codebase:

1. The `integrationTest` task exists, is tag-filtered to `@Tag("integration")`, reuses `src/test`, is decoupled from build/check, and fails loudly via an `AtomicInteger`/`TestListener`/`doLast(GradleException)` zero-test guard.
2. The CI `integration-test` job is on `arc-runner-unityinflow`, `needs: build`, runs a loud `docker info` pre-flight worded as a config error, then invokes the gradle task. No ubuntu-latest. YAML well-formed.
3. The `kore.skill.activate` span is always emitted (incl. count=0 and on throw), carries `kore.skill.names`/`count`/`duration_ms`, and is parented under `kore.agent.run` — proven by an end-to-end test through `ObservableAgentRunner` that ran fresh and green.
4. `AgentEvent.SkillActivated` is `@Serializable`, emitted only on ≥1 match, and reacted to by all three observers (metrics moves a real counter + duration, span/dashboard explicit no-ops), with a counter-moved test that ran fresh and green.

Status is `human_needed` (not `passed`) solely because the inherently Docker-dependent live runs (criteria 1 and 2's "actually execute against real PostgreSQL" / "CI job runs green") cannot be exercised on a host without a Docker daemon. The wiring, guards, and job shape are fully verified; only the containerized execution awaits human confirmation on a Docker-equipped host / first PR.

---

_Verified: 2026-06-20_
_Verifier: Claude (gsd-verifier)_
