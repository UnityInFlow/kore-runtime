---
status: passed
phase: 07-hierarchical-agents
source: [07-VERIFICATION.md]
started: 2026-06-22T15:20:00Z
updated: 2026-06-22T15:35:00Z
---

## Current Test

number: 1
name: Persistent-path Postgres integration tests on a Docker/Ryuk-capable host
expected: |
  All PostgresAuditLogAdapterTest cases green, including the new
  'recordAgentRun persists a non-UUID agentId and preserves non-UUID parent-child
  correlation (CR-02)' — recordAgentRun does not throw on non-UUID ids, the child row
  exists under its derived UUID, and its parent_run_id equals toStableUuid("parent-1").
  MigrationTest confirms parent_run_id is UUID/nullable/indexed. Confirms criterion #4
  on the persistent path end-to-end.
awaiting: none — passed

## Tests

### 1. Persistent-path Postgres integration tests on a Docker/Ryuk-capable host
expected: Run `./gradlew :kore-storage:integrationTest --tests "*PostgresAuditLogAdapterTest*" --tests "*MigrationTest*"` on a host where Testcontainers/Ryuk starts (CI arc-runner-unityinflow, or local Docker with the reaper enabled). All cases green — non-UUID id persists without throwing, child `parent_run_id == toStableUuid("parent-1")`, root `parent_run_id IS NULL`; MigrationTest confirms `parent_run_id` is UUID/nullable/indexed.
result: passed — ran 2026-06-22 with `TESTCONTAINERS_RYUK_DISABLED=true` against real PostgreSQL via Testcontainers. PostgresAuditLogAdapterTest 7/7 (incl. "recordAgentRun persists a non-UUID agentId and preserves non-UUID parent-child correlation (CR-02)") and MigrationTest 5/5 — 0 failures, 0 errors, 0 skipped. Confirms criterion #4 on the persistent path end-to-end.

## Summary

total: 1
passed: 1
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
