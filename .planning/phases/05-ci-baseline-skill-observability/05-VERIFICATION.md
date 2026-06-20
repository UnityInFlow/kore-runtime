---
phase: 05-ci-baseline-skill-observability
verified: 2026-06-20T00:00:00Z
status: human_needed
score: 4/4 must-haves verified
overrides_applied: 0
human_verification:
  - test: "Run `./gradlew :kore-storage:integrationTest` on a Docker-equipped host (or wait for the first PR CI run on arc-runner-unityinflow)"
    expected: "All 13 @Tag(\"integration\") Testcontainers tests spin up real PostgreSQL and execute with >0 tests run; no GradleException; BUILD SUCCESSFUL. The zero-test guard does NOT fire."
    why_human: "The agent host has no Docker daemon. Testcontainers requires a live Docker daemon to start a real PostgreSQL container — the 'tests actually execute against real PostgreSQL' clause of CI-01 cannot be exercised by static code inspection. Task registration, tag filter, and fail-loud guard ARE verified by inspection."
  - test: "Trigger the CI `integration-test` job (open a PR or push to main) and observe the run on arc-runner-unityinflow"
    expected: "The `docker info` pre-flight passes (Docker reachable), then `./gradlew :kore-storage:integrationTest` runs the integration tests green. If Docker were absent, the pre-flight would fail loudly with the 'RUNNER CONFIG ERROR, not a test failure' annotation BEFORE any test runs."
    why_human: "CI job execution against a self-hosted runner with a live Docker daemon is inherently first-PR-gated and cannot be reproduced on this host. The job shape, runner, needs:build, docker pre-flight, and gradle task invocation ARE verified by inspecting ci.yml."
---

# Phase 5: CI Baseline & Skill Observability Verification Report

**Phase Goal:** kore-storage's Testcontainers integration tests run in CI, and skill activations emit both an OTel span and an event-bus event — closing the last gaps in CI correctness and the span hierarchy.
**Verified:** 2026-06-20
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Developer can run `./gradlew :kore-storage:integrationTest` and the Testcontainers tests execute against real PostgreSQL; the task fails loudly if 0 tests run | ✓ VERIFIED (wiring) / ? human (live Docker run) | `kore-storage/build.gradle.kts:51-95`: `tasks.register<Test>("integrationTest")` reuses `src/test` source set, `useJUnitPlatform { includeTags("integration") }`, zero-test guard via `AtomicInteger` + `addTestListener(TestListener)` + `doLast { throw GradleException(...) }` when `executed.get() == 0`. Task confirmed registered/discoverable: `./gradlew :kore-storage:tasks --all` lists `integrationTest` (BUILD SUCCESSFUL). 3 test classes all carry `@Tag("integration")` + `@Testcontainers`. Live run against real PostgreSQL → human (no Docker on host). |
| 2 | CI runs the integration tests on arc-runner-unityinflow with a `docker info` pre-flight, asserting tests actually executed | ✓ VERIFIED (wiring) / ? human (live CI run) | `.github/workflows/ci.yml:53-78`: `integration-test` job, `runs-on: [arc-runner-unityinflow]`, `needs: build`, `docker info` pre-flight that emits a `::error title=Docker unavailable::...RUNNER CONFIG ERROR, not a test failure...` annotation and `exit 1` before tests, then `./gradlew :kore-storage:integrationTest`. "Tests actually executed" assertion is the in-Gradle zero-test guard (D-15 — no CI-side XML parsing). YAML parses; assertion script OK. Live CI run → human. |
| 3 | A skill activation produces a `kore.skill.activate` OTel span parented under the agent-run span, carrying name/count/duration attributes | ✓ VERIFIED | `AgentLoop.kt:105-130`: span built with `tracer?.spanBuilder(SKILL_ACTIVATE_SPAN)?.setParent(Context.current())?.startSpan()`; in `finally` sets `kore.skill.names` (stringArrayKey), `kore.skill.count` (longKey), `kore.skill.duration_ms` (longKey) and ends — always emits, incl. count=0 and on throw. Parenting proven by `ObservableAgentRunnerTest.kt:164-197`: drives a real loop through `ObservableAgentRunner`, asserts `skillSpan.parentSpanContext.spanId == agentRunSpan.spanContext.spanId` + same traceId + names attribute present. `KoreAttrs.SKILL_NAMES/COUNT/DURATION_MS` constants + `withSpan` `is List<*>` string-array branch in `KoreTracer.kt:42-44,85`. |
| 4 | A skill activation emits `AgentEvent.SkillActivated` on the event bus, observable by metrics observers | ✓ VERIFIED | `AgentEvent.kt:77-83`: `@Serializable @SerialName("SkillActivated") data class SkillActivated(agentId, skillNames, durationMs)`. `AgentLoop.kt:133-149`: emitted only when `activated.isNotEmpty()` (D-07 asymmetry). `EventBusMetricsObserver.kt:80-90`: explicit `is AgentEvent.SkillActivated ->` branch increments `kore.skills.activated` per skill name + records `kore.skills.activate.duration`. `EventBusSpanObserver.kt:107` explicit no-op; `EventBusDashboardObserver.kt:90` explicit no-op. Counter-moved proven by `EventBusMetricsObserverTest.kt:155-193`. |

**Score:** 4/4 truths verified at code level. Truths 1 and 2 have an additional live-Docker dimension routed to human verification (the wiring/guard/job-shape is fully verified; only the actual containerized run is human-gated).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `kore-storage/build.gradle.kts` | integrationTest task + zero-test guard | ✓ VERIFIED | Task registered under `verification` group, tag-filtered, fail-loud guard, decoupled from build/check. Unit `test` still `excludeTags("integration")` (line 39-43). |
| `.github/workflows/ci.yml` | integration-test job + docker pre-flight | ✓ VERIFIED | Job on self-hosted runner, needs:build, docker pre-flight, gradle task. No ubuntu-latest. |
| `kore-core/.../port/SkillRegistry.kt` | ActivatedSkill + activateFor: List<ActivatedSkill> | ✓ VERIFIED | `data class ActivatedSkill(name, prompt)`, stdlib-only, NOT @Serializable. Both impls migrated. |
| `kore-core/.../AgentEvent.kt` | SkillActivated subclass | ✓ VERIFIED | @Serializable @SerialName, names-only payload (no prompts). |
| `kore-core/.../AgentLoop.kt` | skill span + conditional emission | ✓ VERIFIED | Three attrs, always-emit span, event-on-match. var-free try/finally guard. |
| `kore-skills/.../SkillRegistryAdapter.kt` | maps to ActivatedSkill(name, prompt) | ✓ VERIFIED | `.map { ActivatedSkill(name = it.name, prompt = it.prompt) }`. |
| `kore-observability/.../KoreTracer.kt` | KoreAttrs skill constants + List<*> branch | ✓ VERIFIED | Three constants match AgentLoop literal keys; `is List<*>` → stringArrayKey + filterIsInstance. |
| `kore-observability/.../KoreMetrics.kt` | skillsActivatedCounter + duration | ✓ VERIFIED | Counter `kore.skills.activated` (agent_name+skill_name), DistributionSummary `kore.skills.activate.duration`. |
| Three event-bus observers | explicit SkillActivated branches | ✓ VERIFIED | Metrics (behavior), Span (no-op), Dashboard (no-op) — all explicit before `else -> Unit`. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| ci.yml integration-test job | :kore-storage:integrationTest | gradle task invocation | ✓ WIRED | `run: ./gradlew :kore-storage:integrationTest` (ci.yml:78) |
| integrationTest task | @Tag("integration") classes | includeTags("integration") | ✓ WIRED | build.gradle.kts:58-60; all 3 classes tagged |
| AgentLoop skill span | OTel Span attributes | AttributeKey.stringArrayKey/longKey | ✓ WIRED | AgentLoop.kt:125-127 |
| AgentLoop | SkillActivated on bus | eventBus.emit when activated.isNotEmpty() | ✓ WIRED | AgentLoop.kt:133,142-148 |
| EventBusMetricsObserver | kore.skills.activated meter | metrics.skillsActivatedCounter(...).increment() | ✓ WIRED | EventBusMetricsObserver.kt:86-89 |
| ObservableAgentRunner skill span | kore.agent.run parent span | setParent(Context.current()) under withSpan(AGENT_RUN) | ✓ WIRED | ObservableAgentRunner.kt:45-46; proven by ObservableAgentRunnerTest.kt:193 |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| integrationTest task is registered/discoverable | `./gradlew :kore-storage:tasks --all` | Lists `integrationTest - Runs kore-storage Testcontainers integration tests (CI-01).` — BUILD SUCCESSFUL in 777ms | ✓ PASS |
| CI integration-test job well-formed | python3 yaml.safe_load + asserts | runs-on=[arc-runner-unityinflow], needs=build, docker info present, gradle task present, no ubuntu-latest → "CI YAML OK" | ✓ PASS |
| Phase commits exist in history | `git cat-file -t` x7 | 48493b4, 24e17a2, c948ab9, 0520a1d, 41b9da5, 37d2fbd, ca30f78 all FOUND | ✓ PASS |
| integrationTest executes real Postgres tests | `./gradlew :kore-storage:integrationTest` | No Docker daemon on host | ? SKIP → human |
| Full `test lintKotlin` across 11 modules | `./gradlew test lintKotlin` | Verified green by orchestrator (per task brief) | ✓ PASS (delegated) |

### Probe Execution

Not applicable — no `scripts/*/tests/probe-*.sh` and the phase declares no probe-based verification.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| CI-01 | 05-01 | Dedicated `integrationTest` task, tag-filtered, fails loudly if 0 tests execute | ✓ SATISFIED | Task registered + zero-test guard verified by inspection and task discovery; live run human-gated |
| CI-02 | 05-01 | CI runs integration tests on arc-runner-unityinflow with `docker info` pre-flight, asserting tests executed | ✓ SATISFIED | Job shape + pre-flight + in-Gradle assertion verified; live CI run human-gated |
| OBSV-03 | 05-02, 05-03, 05-04 | Skill activation emits `kore.skill.activate` span parented under agent-run span, name/count/duration attrs | ✓ SATISFIED | AgentLoop span emission + parenting test + KoreAttrs/withSpan support all verified |
| OBSV-04 | 05-02, 05-03 | Skill activation emits `AgentEvent.SkillActivated` for metrics observers | ✓ SATISFIED | Subclass + conditional emission + observer reactions + counter-moved test all verified |

All 4 phase requirement IDs are accounted for. No orphaned requirements: REQUIREMENTS.md maps exactly CI-01, CI-02, OBSV-03, OBSV-04 to Phase 5, and all four appear in plan `requirements:` frontmatter (05-01: CI-01/CI-02; 05-02: OBSV-03/OBSV-04; 05-03: OBSV-03/OBSV-04; 05-04: OBSV-03).

**Traceability consistency:** REQUIREMENTS.md checklist marks all four `[x]`; the traceability table marks all four "Complete". Checklist and table are mutually consistent and consistent with the verified codebase.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (none) | — | No TBD/FIXME/XXX/HACK/placeholder/"not yet implemented" markers in any phase-modified source file | — | Clean |

### Documentation Discrepancy (informational, not a gap)

ROADMAP/REQUIREMENTS describe "7 Testcontainers integration tests". The actual count is **13 `@Test` methods** across 3 `@Tag("integration")` classes (MigrationTest: 4, PostgresAuditLogAdapterTest: 5, PostgresAuditLogAdapterQueryTest: 4). This is a stale early estimate in the requirement wording. It does NOT affect goal achievement: the fail-loud guard fires on `count == 0`, independent of the exact non-zero count, and CI-01's behavioral contract ("fails loudly if 0 execute") is fully satisfied. No action required; optionally update the "7" wording in REQUIREMENTS.md/ROADMAP.md to "13" or "the @Tag(\"integration\") suite" for accuracy.

### Human Verification Required

#### 1. Live integrationTest run against real PostgreSQL (CI-01)

**Test:** Run `./gradlew :kore-storage:integrationTest` on a Docker-equipped host (or observe the first PR CI run).
**Expected:** All 13 `@Tag("integration")` Testcontainers tests start a real PostgreSQL container and execute with >0 tests run; no GradleException; BUILD SUCCESSFUL.
**Why human:** The verification host has no Docker daemon; Testcontainers needs a live daemon to start the container. Task registration, tag filter, and fail-loud guard are already verified by inspection — only the containerized execution is human-gated.

#### 2. CI integration-test job run on arc-runner-unityinflow (CI-02)

**Test:** Open a PR or push to `main`; observe the `integration-test` job on the self-hosted runner.
**Expected:** `docker info` pre-flight passes, then `./gradlew :kore-storage:integrationTest` runs green. (If Docker were missing, the pre-flight fails loudly with the config-error annotation before any test.)
**Why human:** CI execution against a self-hosted runner with a live Docker daemon is first-PR-gated and not reproducible on this host. Job shape, runner, `needs: build`, pre-flight, and gradle invocation are verified by inspecting ci.yml.

### Gaps Summary

No gaps. All four success criteria are achieved in the codebase:

1. The `integrationTest` task exists, is tag-filtered to `@Tag("integration")`, reuses `src/test`, is decoupled from build/check, and fails loudly via an `AtomicInteger`/`TestListener`/`doLast(GradleException)` zero-test guard. Confirmed registered and discoverable.
2. The CI `integration-test` job is on `arc-runner-unityinflow`, `needs: build`, runs a loud `docker info` pre-flight worded as a config error, then invokes the gradle task. YAML well-formed.
3. The `kore.skill.activate` span is always emitted (incl. count=0 and on throw), carries `kore.skill.names`/`count`/`duration_ms`, and is parented under `kore.agent.run` (proven by an end-to-end test through `ObservableAgentRunner`). `KoreAttrs` constants + `withSpan` string-array branch support it.
4. `AgentEvent.SkillActivated` is `@Serializable`, emitted only on ≥1 match, and reacted to by all three observers (metrics moves a real counter + duration, span/dashboard explicit no-ops), with a counter-moved test.

The only items routed to human verification are the inherently Docker-dependent live runs (criteria 1 and 2's "actually execute against real PostgreSQL" / "CI job runs green"), which cannot be exercised on a host without a Docker daemon. The full `./gradlew test lintKotlin` across all 11 modules passes (orchestrator-confirmed), and all seven phase commits are present in history.

---

_Verified: 2026-06-20_
_Verifier: Claude (gsd-verifier)_
