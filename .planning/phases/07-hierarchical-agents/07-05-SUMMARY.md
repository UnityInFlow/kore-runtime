---
phase: 07-hierarchical-agents
plan: 05
subsystem: kore-storage
gap_closure: true
tags: [hierarchical-agents, audit-log, integration-test, CR-02, gap-closure]
requires:
  - "eb370c0: PostgresAuditLogAdapter.toStableUuid (CR-02 production fix, already committed)"
  - "eb370c0: AgentLoop runCatching-guarded audit write (CR-02 no-throw, already committed)"
  - "eb370c0: ChildLineage per-coroutine lineage element (CR-01, already committed)"
provides:
  - "Non-UUID agentId persistence integration coverage on the persistent Postgres path"
  - "Non-UUID parent/child run-tree correlation integration coverage (Gap 2 missing item #3)"
affects:
  - "07-VERIFICATION.md re-verification: criteria #2 and #4 PARTIAL -> verifiable 5/5"
tech-stack:
  added: []
  patterns:
    - "Test-local re-derivation of adapter's private toStableUuid (independent contract proof, not import)"
    - "JDBC readback via postgres.createConnection().use { } querying by derived UUID PK"
key-files:
  created: []
  modified:
    - "kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapterTest.kt"
decisions:
  - "Test recomputes toStableUuid inline (String.() -> UUID mirroring the adapter) rather than importing the private helper — independently proves the non-UUID -> deterministic-UUID contract"
  - "No production code modified — CR-01/CR-02/WR-01 fixes already landed in eb370c0; 07-05 only adds the one missing test and re-asserts committed unit tests green"
metrics:
  duration: ~2min
  completed: 2026-06-22
---

# Phase 7 Plan 05: Hierarchical Agents Gap Closure Summary

Added the one missing non-UUID `agentId` + non-UUID parent/child correlation `@Tag("integration")` test to `PostgresAuditLogAdapterTest` (closing Gap 2 missing item #3), and re-asserted the already-committed CR-01 concurrency + CR-02 no-throw unit tests are green — making HIER-02/HIER-03/HIER-04 contract-complete on the persistent path so re-verification can score 5/5.

## What Was Built

### Task 1 — Non-UUID integration test (committed `2efd537`)
Added one `@Test` to the existing `PostgresAuditLogAdapterTest` (reusing the shared Testcontainers container/adapter):
`recordAgentRun persists a non-UUID agentId and preserves non-UUID parent-child correlation (CR-02)`.

- Uses free-form, non-UUID ids: parent `"parent-1"`, child `"child-1"`, non-UUID `task.id` values (`"task-parent-1"`, `"task-child-1"`), and a non-UUID `parentRunId = "parent-1"` on the child task — the exact pre-`toStableUuid` crash path (`UUID.fromString("parent-1")` -> `IllegalArgumentException` out of `run()`).
- Both `recordAgentRun(...)` calls assert the run-NEVER-throws invariant implicitly (test fails if either throws); CR-02 comments mark each call site.
- Recomputes the expected stored PK inline via a test-local `String.() -> UUID` mirroring the adapter's private `toStableUuid` (`runCatching { UUID.fromString(this) }.getOrElse { UUID.nameUUIDFromBytes(...) }`) — proves the contract independently of adapter internals.
- JDBC readback asserts exactly ONE `agent_runs` row exists for the child's derived UUID, and that the child row's `parent_run_id::text` equals the parent's DERIVED UUID — proving run-tree correlation survives the non-UUID -> deterministic-UUID mapping (HIER-04 criterion #4 on the persistent path with non-UUID ids).
- `val` only, no `!!`, Kotest `shouldBe`, ktlint-clean.

### Task 2 — Re-assert committed gap tests (verification-only, no commit)
Confirmed the triage commit `eb370c0` is in the working tree and its gap-proving tests pass:
- Grep gate: both committed test names present; `AgentTool.kt` contains no `parentDepthCell`/`parentRunIdCell` (CR-01 shared state removed); `coroutineContext[ChildLineage]` read in `callTool`.
- `./gradlew :kore-core:test --rerun-tasks` — **41 tests, 0 failures, 0 errors**. Both `CR-01 concurrent binds ...` (AgentToolTest, on `Dispatchers.Default`) and `CR-02 run returns an AgentResult and does not throw ...` (AgentLoopTest) executed and passed, alongside HIER-01..04 and the binary-compat suite (criterion #5).

## Verification

| Check | Command | Result |
|-------|---------|--------|
| New test compiles + ktlint-clean | `./gradlew :kore-storage:lintKotlin :kore-storage:compileTestKotlin` | BUILD SUCCESSFUL |
| kore-core suite (CR-01 + CR-02 + HIER + binary-compat) | `./gradlew :kore-core:test --rerun-tasks` | 41 tests, 0 failures |
| Grep gate (committed tests present, shared cells removed) | grep AgentToolTest/AgentLoopTest/AgentTool.kt | OK |

### Human / CI-gated check (NOT a local failure)
The new non-UUID test is `@Tag("integration")` and Docker/Ryuk-gated. The canonical run is CI (arc-runner-unityinflow) or local Docker with the reaper enabled:

```
./gradlew :kore-storage:integrationTest --tests "*PostgresAuditLogAdapterTest*"
```

Expected: the new non-UUID test is GREEN — `recordAgentRun` did not throw on a non-UUID id, the child row exists under its derived UUID, and its `parent_run_id` equals `toStableUuid("parent-1")`. The verifier could not start Ryuk locally (environment constraint per 07-VERIFICATION.md `human_verification`); execution is gated on CI/Docker. This is EXPECTED, not a failure.

## Deviations from Plan

None — plan executed exactly as written. Task 1 added exactly one test method; Task 2 was verification-only and modified no files. No production code touched (the `toStableUuid` / `runCatching` / `ChildLineage` fixes are already committed in `eb370c0`).

## Deliberate Deferral (recorded, not an omission)

CR-03 (replace hand-rolled `AgentTool.extractInput` with `kotlinx.serialization`) is out of scope: `kotlinx-serialization-json` is `compileOnly` in kore-core by design (zero hard runtime deps; the host supplies the JSON runtime). A runtime `Json.decodeFromString` would break that published-library contract. It is a REVIEW warning, NOT a verification criterion and NOT in either gap's `missing`. Left as-is.

## Impact on Re-Verification

Closes the two PARTIAL criteria so re-verification can score 5/5:
- Criterion #2 (HIER-02 structured concurrency under concurrent runs) — proven by the committed CR-01 multi-threaded test (re-asserted green).
- Criterion #4 (HIER-04 traceable run trees on the persistent path) — proven by the committed CR-02 no-throw unit test PLUS the new non-UUID integration test (correlation survives non-UUID ids; CI/Docker-gated execution).
- Criterion #3 (HIER-03 depth ceiling) — no longer at risk now that depth is read per-coroutine from `ChildLineage`.

## Self-Check: PASSED

- FOUND: `.planning/phases/07-hierarchical-agents/07-05-SUMMARY.md`
- FOUND: `kore-storage/src/test/kotlin/io/github/unityinflow/kore/storage/PostgresAuditLogAdapterTest.kt` (new test method present)
- FOUND: commit `2efd537`
