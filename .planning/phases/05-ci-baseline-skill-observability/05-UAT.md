---
status: testing
phase: 05-ci-baseline-skill-observability
source: [05-VERIFICATION.md]
started: 2026-06-20
updated: 2026-06-20
---

## Current Test

number: 1
name: Live integrationTest run against real PostgreSQL (CI-01)
expected: |
  Running `./gradlew :kore-storage:integrationTest` on a Docker-equipped host
  starts a real PostgreSQL container and executes all `@Tag("integration")`
  Testcontainers tests (13 @Test methods across 3 tagged classes) with >0 tests
  run; no GradleException; BUILD SUCCESSFUL. The fail-loud zero-test guard does
  NOT fire.
awaiting: user response

## Tests

### Test 1: Live integrationTest run against real PostgreSQL (CI-01)

- **Requirement:** CI-01
- **Test:** On a host with a running Docker daemon, run
  `./gradlew :kore-storage:integrationTest`.
- **Expected:** Real PostgreSQL container starts; all `@Tag("integration")`
  tests execute (>0 tests run); no `GradleException`; BUILD SUCCESSFUL.
- **Why human:** The agent host has no Docker daemon. Task registration, tag
  filter, and the fail-loud zero-test guard are already verified by code
  inspection — only the containerized execution against real PostgreSQL is
  human-gated.
- **Status:** awaiting

### Test 2: CI integration-test job on arc-runner-unityinflow (CI-02)

- **Requirement:** CI-02
- **Test:** Open a PR (or push to `main`) and observe the `integration-test`
  job on the self-hosted runner.
- **Expected:** The `docker info` pre-flight passes (Docker reachable), then
  `./gradlew :kore-storage:integrationTest` runs the integration tests green.
  If Docker were absent, the pre-flight fails loudly with the
  "RUNNER CONFIG ERROR, not a test failure" annotation BEFORE any test runs.
- **Why human:** CI execution against a self-hosted runner with a live Docker
  daemon is first-PR-gated and cannot be reproduced on this host. Job shape,
  runner, `needs: build`, docker pre-flight, and gradle task invocation are all
  verified by inspecting `ci.yml`.
- **Status:** awaiting

## Notes

Both items are inherently Docker-dependent and were anticipated before execution
(the loud `docker info` pre-flight, decision D-14, exists precisely so a missing
daemon on the runner surfaces as an unambiguous config error). The open
prerequisite from research — whether `arc-runner-unityinflow` runs in
Docker-in-Docker / `dind` containerMode — is what Test 2 confirms on first run.
Everything verifiable by code inspection (4/4 success criteria) passed.
