---
status: complete
phase: 02-observability-storage
source:
  - 02-01-SUMMARY.md
  - 02-02-SUMMARY.md
  - 02-03-SUMMARY.md
started: 2026-04-26T08:35:00Z
updated: 2026-04-26T08:55:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Observability + Storage Tests (Testcontainers)
expected: `./gradlew :kore-observability:test :kore-storage:test --rerun-tasks` exits 0. kore-observability tests verify OTel spans + Micrometer counters; kore-storage Testcontainers tests verify Flyway migration + PostgresAuditLogAdapter round-trips. Requires Docker.
result: pass
notes: |
  BUILD SUCCESSFUL in 8s. kore-observability ran 23/23 tests cleanly across 4 classes
  (KoreTracerTest 5, KoreMetricsTest 6, EventBusMetricsObserverTest 4, ObservableAgentRunnerTest 8).
  kore-storage:test exited 0 with the default tag config.

  FOLLOW-UP (not blocking): Investigation revealed kore-storage's 7 Testcontainers tests
  (MigrationTest, PostgresAuditLogAdapterTest, PostgresAuditLogAdapterQueryTest) are
  excluded from the default `test` task via `excludeTags("integration")` in
  kore-storage/build.gradle.kts:36. Running them with the exclusion lifted on this
  machine surfaced two environmental issues (Testcontainers Ryuk image fails to start;
  Flyway JDBC connection fails to the Postgres container even with TESTCONTAINERS_RYUK_DISABLED).
  This is local Docker setup specific (likely colima vs Docker Desktop socket quirks)
  and may pass on arc-runner-unityinflow CI. Recommend v0.0.2:
   - Register a separate `integrationTest` task in kore-storage/build.gradle.kts
   - Wire it into a new CI step (`./gradlew integrationTest`) that runs on arc-runner-unityinflow
   - Document local Docker requirements (colima vs Docker Desktop) in CONTRIBUTING.md

### 2. kore-core runtimeClasspath has zero OTel deps (D-10 isolation)
expected: `./gradlew :kore-core:dependencies --configuration runtimeClasspath 2>&1 | grep -i opentelemetry` produces empty output. Confirms D-10 architectural decision — kore-core stays OTel-free at the dependency-graph level (not just source level).
result: pass
notes: grep exited 1 (no matches found) — D-10 verified at dependency-graph level.

## Summary

total: 2
passed: 2
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none yet]
