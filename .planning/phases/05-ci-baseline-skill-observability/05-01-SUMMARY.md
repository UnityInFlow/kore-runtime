---
phase: 05-ci-baseline-skill-observability
plan: 01
subsystem: kore-storage / ci
tags: [ci, gradle, testcontainers, integration-test, docker, junit5, postgres]
requires: []
provides:
  - "integrationTest Gradle task (tag-filtered, fail-loud zero-test guard) — CI-01"
  - "integration-test CI job on self-hosted runner with Docker pre-flight — CI-02"
affects:
  - kore-storage/build.gradle.kts
  - .github/workflows/ci.yml
tech-stack:
  added: []
  patterns:
    - "Gradle TestListener + AtomicInteger zero-test guard (replaces deprecated afterSuite)"
    - "Tag-filtered Test task reusing existing src/test source set (no source-set move)"
    - "Self-hosted CI job mirroring arm64-build shape with docker info pre-flight"
key-files:
  created: []
  modified:
    - kore-storage/build.gradle.kts
    - .github/workflows/ci.yml
decisions:
  - "Zero-test guard uses addTestListener(TestListener) + AtomicInteger (val holding mutable state), not afterSuite — afterSuite is deprecated in Gradle 9, removed in Gradle 10, and config-cache-incompatible (D-10)"
  - "integrationTest reuses the existing src/test source set rather than introducing a src/integrationTest source set (D-09)"
  - "integrationTest is NOT wired into check/build so the default ./gradlew build stays fast and Docker-free (D-11)"
  - "Docker pre-flight emits a ::error title=Docker unavailable:: annotation worded as a RUNNER CONFIG ERROR (not a test failure) and exits 1 before tests run (D-14)"
  - "No CI-side XML/test-count parsing — the zero-test assertion lives entirely in the Gradle guard (D-15)"
metrics:
  duration: "~2min"
  completed: "2026-06-20"
  tasks: 2
  files: 2
---

# Phase 5 Plan 01: CI Integration-Test Baseline Summary

Made kore-storage's Testcontainers integration tests runnable as a dedicated fail-loud `integrationTest` Gradle task (CI-01) and wired a self-hosted `integration-test` CI job with a Docker pre-flight (CI-02), closing the last v0.0.1 CI-correctness gap so a broken tag filter or missing Docker daemon fails loudly instead of passing green.

## What Was Built

### Task 1 — `integrationTest` Gradle task with fail-loud zero-test guard (CI-01)
`kore-storage/build.gradle.kts` registers `tasks.register<Test>("integrationTest")` under the `verification` group:
- Reuses the existing `src/test` source set: `testClassesDirs = sourceSets["test"].output.classesDirs`, `classpath = sourceSets["test"].runtimeClasspath` (D-09, no source-set move).
- `useJUnitPlatform { includeTags("integration") }` — the inverse of the unchanged unit `test` task's `excludeTags("integration")`.
- Zero-test guard (D-10): `val executed = AtomicInteger(0)` + an anonymous `TestListener` whose `afterTest` increments the counter (other three methods `= Unit`), plus a `doLast { }` that throws `GradleException` when `executed.get() == 0`. Uses `addTestListener(TestListener)`, NOT the removed-in-Gradle-10 `afterSuite`.
- Required imports added: `TestDescriptor`, `TestListener`, `TestResult`, `AtomicInteger`.
- Deliberately decoupled from `check`/`build` (D-11). No dependency changes (Testcontainers already present).

Commit: `48493b4`

### Task 2 — `integration-test` CI job with Docker pre-flight (CI-02)
`.github/workflows/ci.yml` adds an `integration-test` job parallel to `arm64-build`:
- `runs-on: [arc-runner-unityinflow]` (self-hosted X64 — never `ubuntu-latest`), `needs: build`.
- Preamble mirrors `arm64-build`: `actions/checkout@v4` → `actions/setup-java@v4` (21/temurin) → `gradle/actions/setup-gradle@v4`.
- Docker pre-flight step (D-14): `if ! docker info > /dev/null 2>&1` emits `::error title=Docker unavailable::...RUNNER CONFIG ERROR, not a test failure...` and `exit 1` before tests run; otherwise prints "Docker daemon reachable."
- Integration tests step: `./gradlew :kore-storage:integrationTest`.
- No per-job trigger (workflow-level `on:` already covers PR + push to main, D-13); no CI-side XML/test-count parsing (D-15).

Commit: `24e17a2`

## Verification

- `./gradlew :kore-storage:tasks --all` lists `integrationTest` (TASK_LISTED: yes).
- `./gradlew :kore-storage:lintKotlin` passes (BUILD SUCCESSFUL).
- `ci.yml` YAML parses; assertion script confirms `integration-test` job has `runs-on: [arc-runner-unityinflow]`, `needs: build`, a `docker info` pre-flight, `kore-storage:integrationTest` invocation, and no `ubuntu-latest` (OK).
- Manual UAT (deferred to first PR CI run / Docker-equipped runner): `./gradlew :kore-storage:integrationTest` runs the ~13 `@Tag("integration")` Testcontainers tests against real PostgreSQL with >0 executed (CI-01 human-check, per 05-VALIDATION.md). Local agent host has no Docker daemon, so the >0-executed live run is the documented manual gate, not run here.

## Deviations from Plan

None — plan executed exactly as written. The implementation matched PATTERNS.md Pattern 1 (Gradle task) and Pattern 3 (CI job) verbatim.

Note: both task commits (`48493b4`, `24e17a2`) were already present in history from an earlier executor pass. This run re-verified the registered task, ktlint, and the YAML assertions all pass, then finalized the SUMMARY/STATE/ROADMAP. No re-implementation was needed.

## Self-Check: PASSED

- Commit `48493b4` — FOUND
- Commit `24e17a2` — FOUND
- `kore-storage/build.gradle.kts` — FOUND, contains `integrationTest`
- `.github/workflows/ci.yml` — FOUND, contains `integration-test`
