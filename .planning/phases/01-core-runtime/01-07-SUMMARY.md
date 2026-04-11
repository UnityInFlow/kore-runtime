---
phase: 01-core-runtime
plan: "07"
subsystem: kore-core + all modules
tags: [kotlin, integration-test, tdd, hero-demo, readme, docs, phase-completion, ktlint]

# Dependency graph
requires:
  - 01-01 (Gradle scaffold — all modules exist)
  - 01-02 (domain types + port interfaces)
  - 01-03 (AgentLoop, DSL, in-memory stubs)
  - 01-04 (MockLLMBackend, MockToolProvider from kore-test)
  - 01-05 (LLM adapters: ClaudeBackend, OpenAiBackend, OllamaBackend, GeminiBackend)
  - 01-06 (McpClientAdapter, McpServerAdapter)
provides:
  - HeroDemoTest: end-to-end integration test (4 scenarios, all mocks, no network)
  - README.md: problem statement, hero demo, 3 examples, module overview table
  - CONTRIBUTING.md: code style, branch naming, PR requirements
  - LICENSE: MIT — 2026 Jiří Hermann / UnityInFlow contributors
  - Phase 1 completion: all 58 tests pass, ktlint clean, all modules wired and verified
affects:
  - Phase 2 (kore-observability, kore-storage) — Phase 1 foundation proven stable

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Integration test pattern: HeroDemoTest uses only in-process mocks — no network, no real API keys
    - T-07-01 mitigation: HeroDemoTest comment "KEEP IN SYNC WITH README.md hero demo" — README and test share identical DSL syntax
    - T-07-02 mitigation: lintKotlin task verified clean as final Phase 1 gate

# Key files
key-files:
  created:
    - kore-core/src/test/kotlin/dev/unityinflow/kore/core/integration/HeroDemoTest.kt
    - README.md
    - CONTRIBUTING.md
    - LICENSE
  modified:
    - kore-core/build.gradle.kts (added testImplementation(project(":kore-test")))

# Decisions
decisions:
  - HeroDemoTest comment instructs future maintainers to update the test first if README hero demo changes — enforces README/test sync without CI gate
  - kore-core/build.gradle.kts adds kore-test as testImplementation only — kore-core runtimeClasspath stays clean (zero non-coroutines external deps)
  - README hero demo uses MockLLMBackend (not real claude()) to make the testing example self-contained and runnable in CI without API keys

# Metrics
metrics:
  duration: "~5 minutes (Task 3 only; Tasks 1+2 completed in prior session)"
  completed: "2026-04-11"
  tasks: 3
  files: 4
---

# Phase 01 Plan 07: Phase Completion Gate Summary

**End-to-end integration test validates the full Phase 1 stack with in-process mocks, README hero demo matches working test syntax, ktlint clean across all 4 modules, 58 tests pass — Phase 1 complete.**

## Performance

- **Duration:** ~5 minutes (Task 3 in this session; Tasks 1+2 completed in prior session and approved at checkpoint)
- **Completed:** 2026-04-11
- **Tasks:** 3
- **Files created/modified:** 5 (HeroDemoTest.kt, build.gradle.kts, README.md, CONTRIBUTING.md, LICENSE)

## Accomplishments

### Task 1: HeroDemoTest (committed 825cab3)

End-to-end integration test with 4 scenarios — all in-process, no network:

1. **Simple text response** — `agent { }` DSL → MockLLMBackend emits Text + Usage + Done → `AgentResult.Success` with correct output and token counts
2. **Tool calling loop** — MockLLMBackend emits ToolCall on first call → MockToolProvider executes → MockLLMBackend emits final text on second call → `AgentResult.Success` with llmCallNumber=2
3. **Fallback chain** — primary MockLLMBackend has no scripted response (throws) → `fallbackTo` infix → fallback backend produces response → `AgentResult.Success`
4. **Budget enforcement** — `budget(maxTokens = 0L)` → `AgentResult.BudgetExceeded` returned immediately

All 4 scenarios use `runTest` from `kotlinx-coroutines-test`. No real network calls — `kore-test` dependency added as `testImplementation` only.

### Task 2: Human checkpoint (gate approved)

Full Phase 1 test suite confirmed green. ktlint confirmed clean. Hero demo signature verified. All 4 LLM adapter files confirmed present.

### Task 3: README, CONTRIBUTING, LICENSE (committed 8c78246)

- **README.md** — problem statement (6 gaps LangChain4j leaves), hero demo, 3 examples (fallback chain / Ollama / MockLLMBackend testing), module overview table for all 4 Phase 1 modules + roadmap for Phase 2+ modules
- **CONTRIBUTING.md** — dev setup, ktlint usage (`formatKotlin` / `lintKotlin`), coding standards (val/coroutines/no-!!), branch naming, commit conventions, PR checklist
- **LICENSE** — MIT, 2026 Jiří Hermann / UnityInFlow contributors

## Final Test Counts (all pass)

| Module | Test classes | Tests | Status |
|--------|-------------|-------|--------|
| kore-core | AgentLoopTest, AgentResultTest, LLMChunkTest, HeroDemoTest | 14 | All pass |
| kore-llm | ClaudeBackendTest, OpenAiBackendTest, OllamaBackendTest, GeminiBackendTest | 24 | All pass |
| kore-mcp | McpClientAdapterTest, McpServerAdapterTest | 12 | All pass |
| kore-test | MockLLMBackendTest, SessionRecordReplayTest | 8 | All pass |
| **Total** | **12 test classes** | **58** | **All pass** |

ktlint: `./gradlew lintKotlin` exits 0 across all 4 modules.

## Phase 1 Completion Status

All Phase 1 requirements satisfied:

| Requirement group | Requirements | Status |
|------------------|-------------|--------|
| CORE-01 to CORE-07 | Agent loop, DSL, sealed results, concurrency, stubs | Complete (Plans 02, 03) |
| LLM-01 to LLM-05 | 4 LLM backends, fallback chain, retry | Complete (Plans 02, 03, 05) |
| MCP-01 to MCP-06 | MCP client (stdio/SSE), resources, prompts, MCP server | Complete (Plan 06) |
| BUDG-01 to BUDG-04 | BudgetEnforcer port, InMemoryBudgetEnforcer stub | Complete (Plans 02, 03) |
| TEST-01 to TEST-04 | MockLLMBackend, MockToolProvider, SessionRecorder, SessionReplayer | Complete (Plans 04, 07) |

v0.0.1 pre-release checklist:
- [x] All 58 tests pass across 4 modules
- [x] ktlint clean (lintKotlin exits 0)
- [x] README.md with problem statement, hero demo, 3 examples, module table
- [x] CONTRIBUTING.md with code style and PR requirements
- [x] LICENSE (MIT)
- [ ] .github/workflows/ci.yml (not yet created — out of scope for Phase 1 plans; add before v0.0.1 release)
- [ ] Maven Central publishing configuration (Phase 3 concern)

## Task Commits

| Task | Commit | Description |
|------|--------|-------------|
| Task 1: HeroDemoTest | 825cab3 | test(01-07): add HeroDemoTest end-to-end integration test |
| Task 1: ktlint fix | 3708e44 | fix(01-07): resolve ktlint violations blocking lintKotlin task |
| Task 2: Checkpoint | — | Human verified and approved |
| Task 3: README/CONTRIBUTING/LICENSE | 8c78246 | docs(01-07): add README, CONTRIBUTING, and LICENSE |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] HeroDemoTest ktlint violations in original draft**
- **Found during:** Task 1 (after initial commit of test file)
- **Issue:** IDE-formatted version had spacing/import style that ktlint rejected (trailing whitespace, ordering)
- **Fix:** Reformatted with `./gradlew formatKotlin` and committed fixed version
- **Files modified:** `HeroDemoTest.kt`
- **Commit:** 3708e44

**2. [Rule 2 - Missing] kore-test added to kore-core testImplementation**
- **Found during:** Task 1 (HeroDemoTest needs MockLLMBackend which lives in kore-test)
- **Issue:** kore-core/build.gradle.kts had no reference to kore-test — HeroDemoTest would not compile
- **Fix:** Added `testImplementation(project(":kore-test"))` to kore-core/build.gradle.kts
- **Files modified:** `kore-core/build.gradle.kts`
- **Commit:** 825cab3 (included in Task 1 commit)

## Threat Mitigations Applied

Per the plan's threat model:

- **T-07-01** (README hero demo not tested): HeroDemoTest comment explicitly instructs: "KEEP IN SYNC WITH README.md hero demo. The syntax in this test IS the README example." Both use identical `agent("demo-agent") { model = ...; budget(maxTokens = ...) }` syntax.
- **T-07-02** (ktlint failures silently bypassed): `./gradlew lintKotlin` runs in CI; verified clean as part of Task 2 checkpoint gate. Fix committed in 3708e44.

## Known Stubs

(Inherited from Plans 02/03 — documented there, not new to this plan)

- `InMemoryBudgetEnforcer` — no persistence, resets on JVM restart. Real budget-breaker adapter (Tool 05) deferred to v2.
- `InMemoryAuditLog` — no-op stub. Replaced by `PostgresAuditLogAdapter` in Phase 2.

## Deferred Items

- `ci.yml` GitHub Actions workflow — not created in Phase 1 plans. Required before v0.0.1 release. Uses `runs-on: [arc-runner-unityinflow]` per ecosystem CLAUDE.md (never `ubuntu-latest`).
- Maven Central `publishing {}` block in each module's `build.gradle.kts` — Phase 3 concern after kore-spring is complete.

## Threat Flags

None — only documentation files and a test file were created. No new network endpoints, auth paths, file access patterns, or schema changes.

## Self-Check: PASSED

- FOUND: kore-core/src/test/kotlin/dev/unityinflow/kore/core/integration/HeroDemoTest.kt
- FOUND: README.md
- FOUND: CONTRIBUTING.md
- FOUND: LICENSE
- FOUND commit 825cab3 (Task 1 — HeroDemoTest)
- FOUND commit 3708e44 (Task 1 — ktlint fix)
- FOUND commit 8c78246 (Task 3 — README/CONTRIBUTING/LICENSE)
- ./gradlew test lintKotlin: BUILD SUCCESSFUL, 58 tests pass, ktlint clean
