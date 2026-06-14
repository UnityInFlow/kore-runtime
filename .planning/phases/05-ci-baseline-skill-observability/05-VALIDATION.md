---
phase: 5
slug: ci-baseline-skill-observability
status: approved
nyquist_compliant: true
wave_0_complete: false
created: 2026-06-14
---

# Phase 5 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (JUnit Platform) + Kotest assertions; MockK; kotlinx-coroutines-test |
| **Config file** | `build.gradle.kts` (root + per-module); Gradle 9.4.1 Kotlin DSL |
| **Quick run command** | `./gradlew test` (unit; excludes `@Tag("integration")`) |
| **Full suite command** | `./gradlew test :kore-storage:integrationTest` (unit + Testcontainers integration) |
| **Estimated runtime** | unit ~tens of seconds; integration ~1–3 min (Docker/Testcontainers cold start) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test` (Docker-free, fast)
- **After every plan wave:** Run the wave's affected module tests; full `./gradlew test` for observability changes
- **Before `/gsd-verify-work`:** `./gradlew test :kore-storage:integrationTest` must be green (Docker required)
- **Max feedback latency:** ~60 seconds for unit; integration gated behind explicit invocation

---

## Per-Task Verification Map

> Populated during planning / Nyquist audit once PLAN.md tasks exist. Requirement → validation level mapping derived from RESEARCH.md §Validation Architecture:

| Requirement | Validation Level | What to assert | How |
|-------------|------------------|----------------|-----|
| CI-01 | unit (Gradle) + manual CI | `integrationTest` task exists, tag-filters `integration`, throws `GradleException` on 0 executed tests | `./gradlew :kore-storage:integrationTest`; force-empty-filter test proves the guard fires |
| CI-02 | manual CI | CI job runs integration tests on `arc-runner-unityinflow` with `docker info` pre-flight; 0-test pass impossible (same `afterSuite`/TestListener guard) | PR CI run; inspect workflow logs for loud Docker-config error path |
| OBSV-03 | integration (kore-observability) | `kore.skill.activate` span emitted, parented under `kore.agent.run`, carries `kore.skill.names` (string-array), `kore.skill.count`, `kore.skill.duration_ms` | OTel **in-memory span exporter** asserting parent spanId + attributes; **must run through `ObservableAgentRunner`**, not bare `AgentLoop` (bare loop emits a root span) |
| OBSV-04 | unit + integration | `AgentEvent.SkillActivated(agentId, skillNames, durationMs)` emitted on bus only when ≥1 skill matched; `EventBusMetricsObserver` increments counter + records duration; `EventBusSpanObserver`/`EventBusDashboardObserver` handle the new subclass (no silent `else -> Unit` drop) | event-bus test observer captures emission; observer unit tests assert each `when` branch (the `else -> Unit` swallow means branches must be tested, not assumed compiler-surfaced) |

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 05-01-01 | 01 | 1 | CI-01 | — | N/A (build config) | gradle | `./gradlew :kore-storage:tasks --all \| grep -q integrationTest && ./gradlew :kore-storage:lintKotlin` | ✅ | ⬜ pending |
| 05-01-02 | 01 | 1 | CI-02 | — | docker pre-flight = config-error signal, not security control | ci-config | `python3` yaml assertion on `.github/workflows/ci.yml` (runs-on arc-runner, needs build, docker info, no ubuntu-latest) | ✅ | ⬜ pending |
| 05-02-01 | 02 | 1 | OBSV-03, OBSV-04 | T-05-02 | event payload excludes prompts (no sensitive-content leak to bus) | unit | `./gradlew :kore-skills:test :kore-skills:lintKotlin` | ✅ | ⬜ pending |
| 05-02-02 | 02 | 1 | OBSV-03, OBSV-04 | — | non-PII skill names only on span/event | unit | `./gradlew :kore-core:test :kore-core:lintKotlin` | ✅ | ⬜ pending |
| 05-04-01 | 04 | 1 | OBSV-03 | — | N/A | unit | `./gradlew :kore-observability:test --tests "*KoreTracer*" :kore-observability:lintKotlin` | ✅ | ⬜ pending |
| 05-03-01 | 03 | 2 | OBSV-04 | — | observer branch handles event (no silent `else -> Unit` drop) | unit | `./gradlew :kore-observability:test --tests "*EventBusMetricsObserver*" :kore-observability:lintKotlin :kore-dashboard:compileKotlin :kore-dashboard:lintKotlin` | ✅ | ⬜ pending |
| 05-03-02 | 03 | 2 | OBSV-03 | — | span parented under `kore.agent.run` via ObservableAgentRunner | integration | `./gradlew :kore-observability:test --tests "*ObservableAgentRunner*" :kore-observability:lintKotlin` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*
*Sampling continuity: every task carries an `<automated>` verify — no 3-consecutive-task gap. No watch-mode flags.*

---

## Wave 0 Requirements

- Existing infrastructure covers all phase requirements (JUnit 5 + Kotest + Testcontainers + MockK already wired; OTel in-memory exporter available via OTel SDK test deps).
- Confirm during planning: OTel in-memory span exporter test dependency present in `kore-observability` test scope; if absent, add as a Wave 0 task.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| CI integration job executes on `arc-runner-unityinflow` with working Docker | CI-02 | Requires a live self-hosted runner with Docker daemon; cannot be asserted locally | Open a PR; confirm the `integration-test` job runs, `docker info` passes, and the 7 tests execute (not 0) |
| Docker-unavailable surfaces as loud config error (not test failure) | CI-02 | Depends on runner state | If Docker is down, the `docker info` step must fail with a clear "Docker unavailable" message before tests run |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (none — existing infra covers all reqs)
- [x] No watch-mode flags
- [x] Feedback latency < 60s (unit)
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-06-14
