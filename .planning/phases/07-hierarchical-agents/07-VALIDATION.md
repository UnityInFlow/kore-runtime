---
phase: 7
slug: hierarchical-agents
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-06-21
updated: 2026-06-21
---

# Phase 7 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Kotest assertions + MockK + kotlinx-coroutines-test; Testcontainers (postgres:16-alpine) for Postgres integration |
| **Config file** | per-module `build.gradle.kts` (`test` + `integrationTest` tasks; integration tagged `@Tag("integration")`) |
| **Quick run command** | `./gradlew :kore-core:test` (unit, Docker-free, ~sub-30s) |
| **Full suite command** | `./gradlew test` then `./gradlew :kore-storage:integrationTest` (Docker) |
| **Estimated runtime** | unit ~20-30s (kore-core); integration ~60-120s (Testcontainers Postgres cold start) |

---

## Sampling Rate

- **After every task commit:** `./gradlew :kore-core:test` (or the module the task touched: `:kore-storage:compileKotlin` / `:kore-spring:test`)
- **After every plan wave:** `./gradlew test` (all unit) + `lintKotlin`/`ktlintFormat`
- **Before `/gsd-verify-work`:** `./gradlew test` green AND `./gradlew :kore-storage:integrationTest` green (the parent_run_id Testcontainers assertion)
- **Max feedback latency:** ~30s for the unit gate; integration runs at wave merge only

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 07-01-01 | 01 | 1 | HIER-04 / crit #5 | T-7-03 | Cancel path audits exactly one Cancelled row then re-throws; defaulted fields keep published API compiling | unit | `./gradlew :kore-core:test --tests "*AgentLoopTest*"` | ✅ existing | ⬜ pending |
| 07-01-02 | 01 | 1 | HIER-03 / HIER-02 | T-7-01 | maxDepth data carrier defaulted; ChildDispatchBinder seam; bind parentDepth/parentRunId | unit | `./gradlew :kore-core:test` | ✅ existing | ⬜ pending |
| 07-02-01 | 02 | 2 | HIER-04 | T-7-04 | parent_run_id nullable indexed, NO FK; null-safe UUID parse rejects forged ids | compile | `./gradlew :kore-storage:compileKotlin` | ✅ existing | ⬜ pending |
| 07-02-02 | 02 | 2 | HIER-04 / crit #4 | T-7-05 | child row parent_run_id == parent id; root row NULL (real Postgres) | integration | `./gradlew :kore-storage:integrationTest --tests "*MigrationTest*" --tests "*PostgresAuditLogAdapterTest*"` | ✅ extend existing | ⬜ pending |
| 07-03-01 | 03 | 2 | HIER-01 / HIER-03 | T-7-01 / T-7-07 | depth guard before child run; LLM-supplied input parsed defensively; parentRunId never from input | compile | `./gradlew :kore-core:compileKotlin :kore-core:compileTestKotlin` | ❌ W0 (AgentTool.kt) | ⬜ pending |
| 07-03-02 | 03 | 2 | HIER-01 / HIER-02 / HIER-03 | T-7-01 | D-01/D-03 asymmetry; cancel-propagation; single Cancelled row; in-memory run-tree | unit | `./gradlew :kore-core:test --tests "*AgentToolTest*" --tests "*AgentLoopCancellationTest*"` | ❌ W0 (AgentToolTest.kt, AgentLoopCancellationTest.kt) | ⬜ pending |
| 07-04-01 | 04 | 3 | HIER-03 | T-7-01 | kore.hierarchy.max-depth default 5; threaded into factory; user override wins | compile | `./gradlew :kore-spring:compileKotlin` | ✅ existing | ⬜ pending |
| 07-04-02 | 04 | 3 | HIER-03 | T-7-01 | property binds (default 5 / override 9) and reaches factory; no socket opened | unit (Spring context) | `./gradlew :kore-spring:test --tests "*KoreHierarchyPropertiesTest*"` | ❌ W0 (KoreHierarchyPropertiesTest.kt) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

*No 3 consecutive tasks lack an `<automated>` verify — every task above has one.*

---

## Wave 0 Requirements

New test files that must be created during execution (the named acceptance tests):

- [ ] `kore-core/src/test/.../AgentToolTest.kt` (Plan 03, Task 2) — HIER-01 feedback, D-01 ran-and-failed → isError=false, D-03 depth-limit → isError=true (criterion #3), HIER-04 in-memory run-tree.
- [ ] `kore-core/src/test/.../AgentLoopCancellationTest.kt` (Plan 03, Task 2) — HIER-02 cancel-propagation (criterion #2) + D-05 single Cancelled audit row.
- [ ] Extend `kore-storage/src/test/.../PostgresAuditLogAdapterTest.kt` (Plan 02, Task 2) — child parent_run_id == parent id; root parent_run_id NULL (criterion #4).
- [ ] Extend `kore-storage/src/test/.../MigrationTest.kt` (Plan 02, Task 2) — V2 column UUID/nullable/indexed.
- [ ] `kore-spring/src/test/.../KoreHierarchyPropertiesTest.kt` (Plan 04, Task 2) — property binding default/override/threading.

Framework install: none — JUnit 5 + Kotest + MockK + Testcontainers + kotlinx-coroutines-test already wired.

The named acceptance tests from the planning brief map as:
1. cancellation-propagation unit test (criterion #2) → `AgentLoopCancellationTest` (07-03-02).
2. depth-limit `ToolError` unit test (criterion #3) → `AgentToolTest` D-03 case (07-03-02).
3. D-01/D-03 result-mapping asymmetry tests → `AgentToolTest` D-01 + D-03 cases (07-03-02).
4. Testcontainers `parent_run_id` persistence (criterion #4) → `PostgresAuditLogAdapterTest` (07-02-02).

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| — | — | — | — |

All phase behaviors have automated verification.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 30s (unit gate)
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** ready for execution
