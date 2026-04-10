---
phase: 01-core-runtime
plan: "04"
subsystem: kore-test
tags: [kotlin, testing, mock, session-recording, session-replay, tdd, coroutines]

# Dependency graph
requires:
  - 01-02 (LLMBackend, ToolProvider port interfaces; LLMChunk, ToolDefinition, ToolResult domain types)
provides:
  - MockLLMBackend: scripted LLMChunk sequences for fully deterministic unit tests, no network
  - MockToolProvider: scripted ToolResult by tool name, no external process
  - SessionRecorder: wraps any LLMBackend and records exchanges to JSON on save()
  - SessionReplayer: reads JSON recording and replays deterministically (implements LLMBackend)
affects:
  - All kore-core tests (MockLLMBackend already used in AgentLoopTest via inline mock)
  - 01-05 (LLM adapter integration tests can use MockLLMBackend for unit layer)
  - Any consumer of kore-test (api() declarations propagate transitively)

# Tech tracking
tech-stack:
  added:
    - kotlinx.serialization-json (SessionRecorder/Replayer JSON format)
  patterns:
    - TDD RED/GREEN: test file written before production code in both tasks
    - Scripted mock pattern: ArrayDeque<List<LLMChunk>> for ordered multi-call scripting
    - AtomicInteger for thread-safe call count tracking
    - Lazy evaluation: SessionReplayer.entries uses `by lazy` to defer JSON parsing
    - kotlinx.serialization sealed class: @Serializable sealed SerializableChunk for LLMChunk variants

key-files:
  created:
    - kore-test/src/main/kotlin/dev/unityinflow/kore/test/MockLLMBackend.kt
    - kore-test/src/main/kotlin/dev/unityinflow/kore/test/MockToolProvider.kt
    - kore-test/src/main/kotlin/dev/unityinflow/kore/test/SessionRecorder.kt
    - kore-test/src/test/kotlin/dev/unityinflow/kore/test/MockLLMBackendTest.kt
    - kore-test/src/test/kotlin/dev/unityinflow/kore/test/SessionRecordReplayTest.kt
  modified: []

key-decisions:
  - "SessionRecorder and SessionReplayer placed in same file (SessionRecorder.kt) — they share serialization types and are always used together"
  - "SerializableChunk as internal @Serializable sealed class — avoids adding @Serializable to LLMChunk in kore-core (which must have zero external deps)"
  - "SessionReplayer uses by lazy for JSON parsing — avoids failing at construction time if file is missing; fails at first call() with informative error"
  - "MockLLMBackend.emittedChunks tracked as mutableListOf — enables assertToolCallEmitted() without requiring test to inspect chunks manually"

requirements-completed:
  - TEST-01
  - TEST-02
  - TEST-03
  - TEST-04

# Metrics
duration: 2min
completed: 2026-04-10
---

# Phase 1 Plan 04: kore-test Module Summary

**MockLLMBackend with scripted coroutine sequences, MockToolProvider with named handlers, SessionRecorder wrapping any LLMBackend to JSON, and SessionReplayer for deterministic CI replay — kore's unique testing story is now complete**

## Performance

- **Duration:** 2 min
- **Started:** 2026-04-10T19:11:51Z
- **Completed:** 2026-04-10T19:14:00Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments

- MockLLMBackend: scripted `whenCalled()` queues for multi-call test scenarios; `assertCallCount()` and `assertToolCallEmitted()` assertion helpers
- T-04-03 mitigation: `IllegalStateException` thrown when `call()` invoked more times than scripted — never silently returns empty chunks
- MockToolProvider: `withTool()` + `returnsFor()` fluent builder; throws descriptive error for unknown tool names
- SessionRecorder: transparent delegate wrapper; collects all chunks emitted by real backend; `save()` writes pretty-printed JSON
- T-04-01 mitigation: KDoc security note warning against committing session files with real API keys
- T-04-02 mitigation: `SessionReplayer` validates JSON structure via kotlinx.serialization decode before replay; throws descriptive error on malformed files
- SessionReplayer: `by lazy` JSON parsing; `IllegalStateException` with "no more recorded sessions" on over-call
- Record-then-replay round-trip test: two exchanges recorded, replayed, verified identical
- All 8 kore-test tests pass green with zero network access

## API Reference

### MockLLMBackend

```kotlin
val mock = MockLLMBackend("mock")           // optional name
    .whenCalled(LLMChunk.Text("hi"), LLMChunk.Done)  // queue response 1
    .whenCalled(LLMChunk.Done)                        // queue response 2

// After test:
mock.assertCallCount(2)                     // throws if not exactly 2 calls
mock.assertToolCallEmitted("search")        // throws if no ToolCall("search") emitted
```

### MockToolProvider

```kotlin
val provider = MockToolProvider()
    .withTool("echo", "echoes text", schema = "{}")
    .returnsFor("echo", "echoed!")                   // fixed content overload
    .returnsFor("search") { call ->                  // handler overload
        ToolResult(call.id, "search result")
    }

provider.listTools()     // returns registered ToolDefinitions
provider.callTool(call)  // returns scripted result or throws for unknown name
```

### SessionRecorder

```kotlin
// Wrap any real backend to capture exchanges
val recorder = SessionRecorder(realClaudeBackend, Path.of("session.json"))
// Use recorder as backend in agent (transparent pass-through + recording)
recorder.save()  // write JSON file after all calls complete
```

### SessionReplayer

```kotlin
// Replay recorded session deterministically in CI
val replayer = SessionReplayer(Path.of("session.json"))
// Use replayer as backend — returns recorded chunks in order per call()
```

## Task Commits

Each task was committed atomically:

1. **Task 1: MockLLMBackend and MockToolProvider with TDD tests** — `462804e` (feat)
2. **Task 2: SessionRecorder and SessionReplayer with record/replay TDD tests** — `283421f` (feat)

## Files Created

- `kore-test/src/main/kotlin/dev/unityinflow/kore/test/MockLLMBackend.kt` — scripted LLMBackend, call count tracking, assertion helpers
- `kore-test/src/main/kotlin/dev/unityinflow/kore/test/MockToolProvider.kt` — scripted ToolProvider, named handler registry
- `kore-test/src/main/kotlin/dev/unityinflow/kore/test/SessionRecorder.kt` — SessionRecorder, SessionReplayer, SerializableChunk sealed class, serialization converters
- `kore-test/src/test/kotlin/dev/unityinflow/kore/test/MockLLMBackendTest.kt` — 5 tests for MockLLMBackend and MockToolProvider
- `kore-test/src/test/kotlin/dev/unityinflow/kore/test/SessionRecordReplayTest.kt` — 3 tests for record/replay round-trip, over-call detection, JSON structure

## Decisions Made

- **SessionRecorder.kt hosts SessionReplayer**: Both share `SessionEntry` and `SerializableChunk` serialization types — keeping them in one file avoids a fourth file and makes the internal/package-private converters accessible.
- **SerializableChunk internal sealed class**: LLMChunk in kore-core cannot have `@Serializable` without adding kotlinx.serialization as a kore-core dependency (violating D-15: zero external deps). SerializableChunk acts as the serialization DTO layer.
- **by lazy for entries**: JSON parsing deferred to first `call()` invocation. If session file is missing or malformed, the error occurs when the test tries to replay, not at construction — which produces a clearer stack trace.

## Deviations from Plan

None — plan executed exactly as written. All 4 threat mitigations (T-04-01 through T-04-03) applied as specified.

## Threat Mitigations Applied

- **T-04-01** (Information Disclosure — session files): KDoc on `SessionRecorder` explicitly warns "Never commit session files containing real API keys or user data." Consumers should add session files to `.gitignore`.
- **T-04-02** (Tampering — replayer reads JSON): `kotlinx.serialization.decodeFromString<List<SessionEntry>>()` validates structure before any chunks are emitted. Malformed JSON throws `SerializationException` with field-level error detail before replay begins.
- **T-04-03** (Repudiation — MockLLMBackend silent failure): `MockLLMBackend.call()` throws `IllegalStateException` with descriptive message when scripted responses are exhausted. Test with `SessionRecordReplayTest.replayer throws when calls exceed recorded sessions` covers the same for replayer.

## Known Stubs

None — kore-test contains no stubs. All four utilities are fully functional.

## Threat Flags

None — kore-test contains no network endpoints, auth paths, or external trust boundaries. All I/O is limited to local filesystem writes (`SessionRecorder.save()`) and reads (`SessionReplayer.entries`).

## Next Phase Readiness

- `MockLLMBackend` is ready for use in kore-mcp (Plan 05) and kore-llm (Plan 06) unit tests
- `SessionRecorder/Replayer` enables CI-safe integration tests once real LLM backend adapters are wired
- kore-test exports `api()` for MockK, Kotest assertions, and coroutines-test — consumers gain all three transitively

---
## Self-Check: PASSED

- FOUND: kore-test/src/main/kotlin/dev/unityinflow/kore/test/MockLLMBackend.kt
- FOUND: kore-test/src/main/kotlin/dev/unityinflow/kore/test/MockToolProvider.kt
- FOUND: kore-test/src/main/kotlin/dev/unityinflow/kore/test/SessionRecorder.kt
- FOUND: kore-test/src/test/kotlin/dev/unityinflow/kore/test/MockLLMBackendTest.kt
- FOUND: kore-test/src/test/kotlin/dev/unityinflow/kore/test/SessionRecordReplayTest.kt
- FOUND commit 462804e (Task 1)
- FOUND commit 283421f (Task 2)

---
*Phase: 01-core-runtime*
*Completed: 2026-04-10*
