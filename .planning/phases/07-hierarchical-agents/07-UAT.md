---
status: testing
phase: 07-hierarchical-agents
source: [07-VERIFICATION.md]
started: 2026-06-22T15:20:00Z
updated: 2026-06-22T15:20:00Z
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
awaiting: user response

## Tests

### 1. Persistent-path Postgres integration tests on a Docker/Ryuk-capable host
expected: Run `./gradlew :kore-storage:integrationTest --tests "*PostgresAuditLogAdapterTest*" --tests "*MigrationTest*"` on a host where Testcontainers/Ryuk starts (CI arc-runner-unityinflow, or local Docker with the reaper enabled). All cases green — non-UUID id persists without throwing, child `parent_run_id == toStableUuid("parent-1")`, root `parent_run_id IS NULL`; MigrationTest confirms `parent_run_id` is UUID/nullable/indexed.
result: [pending]

## Summary

total: 1
passed: 0
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps
