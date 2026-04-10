---
phase: 01-core-runtime
plan: "02"
subsystem: kore-core
tags: [kotlin, sealed-classes, domain-types, hexagonal-architecture, port-interfaces, tdd]

# Dependency graph
requires:
  - 01-01 (Gradle multi-module scaffold with version catalog)
provides:
  - AgentResult sealed class: Success, BudgetExceeded, ToolError, LLMError, Cancelled
  - LLMChunk sealed class: Text, ToolCall, Usage, Done
  - TokenUsage with + accumulation operator
  - ConversationMessage with sealed Role (User/Assistant/System/Tool)
  - ToolDefinition, ToolCall, ToolResult value objects
  - AgentTask, AgentContext, LLMConfig value objects
  - LLMBackend port interface (Flow<LLMChunk> based, stateless, provider-agnostic)
  - ToolProvider port interface
  - BudgetEnforcer port interface
  - EventBus port interface
  - AuditLog port interface
  - AgentEvent sealed class (6 lifecycle events)
  - kore-core compiles with only kotlinx-coroutines + stdlib (D-15)
affects:
  - 01-03 (agent loop — uses AgentResult, LLMBackend, BudgetEnforcer, ToolProvider, EventBus, AuditLog)
  - 01-04 (MCP client — implements ToolProvider port)
  - 01-05 (LLM adapters — implement LLMBackend port)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - TDD RED/GREEN for sealed class exhaustiveness (write tests before domain types)
    - Port interfaces in kore-core with zero external deps except coroutines (hexagonal inner ring)
    - All port methods use suspend or Flow — no blocking signatures
    - AgentResult sealed class variants with val-only data classes
    - LLMChunk.Done as data object (no fields) for final stream signal

key-files:
  created:
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentResult.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentTask.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentContext.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/ConversationMessage.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/ToolDefinition.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/ToolCall.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/ToolResult.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/LLMChunk.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/LLMConfig.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/TokenUsage.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentEvent.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/LLMBackend.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/ToolProvider.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/BudgetEnforcer.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/EventBus.kt
    - kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt
    - kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentResultTest.kt
    - kore-core/src/test/kotlin/dev/unityinflow/kore/core/LLMChunkTest.kt
  modified:
    - build.gradle.kts (auto-fix: added mavenCentral() to subprojects repositories block)

key-decisions:
  - "LLMBackend.call() uses Flow<LLMChunk> (not suspend) so both streaming and non-streaming backends satisfy one interface — non-streaming emits Text+Usage+Done as a single hot collection"
  - "LLMChunk.ToolCall.arguments is String (JSON) not Map<String,Any> — provider-agnostic per D-07 and Pitfall 5"
  - "AgentContext holds history as List<ConversationMessage> — immutable snapshots, loop replaces the list each iteration"
  - "EventBus.emit() is suspend — implementations using DROP_OLDEST buffer should never actually suspend in practice"
  - "BudgetEnforcer.checkBudget() returns Boolean (continue=true) rather than throwing — consistent with AgentResult no-throw contract"

patterns-established:
  - "Domain type pattern: sealed classes for results/chunks/roles/events; data classes for value objects; all val, no var"
  - "Port pattern: interfaces only in kore-core, implementations in adapter modules or as stubs (InMemory*) in kore-core"
  - "LLMBackend interface is the contract for all 4 providers — adapters translate internally"

requirements-completed:
  - CORE-02
  - LLM-05
  - BUDG-01
  - MCP-06

# Metrics
duration: 4min
completed: 2026-04-10
---

# Phase 1 Plan 02: kore-core Domain Types and Port Interfaces Summary

**AgentResult sealed class (5 variants), LLMChunk sealed class (4 variants), 10 domain value objects, and 5 port interfaces defining the hexagonal architecture boundary — kore-core compiles with only coroutines as external dependency**

## Performance

- **Duration:** 4 min
- **Started:** 2026-04-10T18:58:12Z
- **Completed:** 2026-04-10T19:02:06Z
- **Tasks:** 2
- **Files modified:** 19

## Accomplishments

- AgentResult sealed class: 5 variants (Success, BudgetExceeded, ToolError, LLMError, Cancelled) — exhaustive when() verified in tests
- LLMChunk sealed class: 4 variants (Text, ToolCall, Usage, Done) — Done is data object — exhaustive when() verified in tests
- TokenUsage data class with `+` operator for accumulation across agent loop iterations
- ConversationMessage with sealed Role class — 4 roles (User/Assistant/System/Tool)
- ToolDefinition, ToolCall, ToolResult — JSON-string arguments (provider-agnostic per D-07/Pitfall 5)
- AgentTask, AgentContext, LLMConfig — val-only domain value objects
- 5 port interfaces: LLMBackend, ToolProvider, BudgetEnforcer, EventBus, AuditLog
- AgentEvent sealed class with 6 lifecycle events for observability and dashboard
- kore-core runtimeClasspath: ONLY kotlin-stdlib + kotlinx-coroutines-core (D-15 compliant)

## Critical Interface Signatures

### LLMBackend (for Plan 05 LLM adapters)

```kotlin
interface LLMBackend {
    val name: String
    fun call(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig,
    ): Flow<LLMChunk>
}
```

### BudgetEnforcer (for Plan 03 agent loop stubs)

```kotlin
interface BudgetEnforcer {
    suspend fun recordUsage(agentId: String, usage: TokenUsage)
    suspend fun checkBudget(agentId: String): Boolean
    suspend fun getUsage(agentId: String): TokenUsage
}
```

### AgentEvent variants (for Plan 03 agent loop emissions)

```kotlin
sealed class AgentEvent {
    data class AgentStarted(val agentId: String, val taskId: String)
    data class LLMCallStarted(val agentId: String, val backend: String)
    data class LLMCallCompleted(val agentId: String, val tokenUsage: TokenUsage)
    data class ToolCallStarted(val agentId: String, val toolName: String)
    data class ToolCallCompleted(val agentId: String, val toolName: String, val isError: Boolean)
    data class AgentCompleted(val agentId: String, val result: AgentResult)
}
```

## Task Commits

Each task was committed atomically:

1. **Task 1: Define domain types (sealed classes and value objects)** - `ccfe3b8` (feat)
2. **Task 2: Define port interfaces** - `32c3989` (feat)

## Files Created/Modified

- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentResult.kt` — 5-variant sealed class, val-only fields
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/LLMChunk.kt` — 4-variant sealed class (data object Done)
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/TokenUsage.kt` — value object with + operator
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/ConversationMessage.kt` — message with nested sealed Role
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/ToolDefinition.kt` — name + description + JSON schema string
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/ToolCall.kt` — id + name + JSON arguments string
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/ToolResult.kt` — toolCallId + content + isError flag
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentTask.kt` — id + input + metadata map
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/LLMConfig.kt` — model + maxTokens/temperature/maxHistory defaults
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentContext.kt` — agentId + task + history + accumulatedUsage
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentEvent.kt` — 6-variant sealed class for loop lifecycle
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/LLMBackend.kt` — Flow<LLMChunk> interface
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/ToolProvider.kt` — suspend listTools/callTool
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/BudgetEnforcer.kt` — suspend record/check/get
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/EventBus.kt` — suspend emit + Flow subscribe
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt` — suspend record* append-only
- `kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentResultTest.kt` — 4 tests (TDD)
- `kore-core/src/test/kotlin/dev/unityinflow/kore/core/LLMChunkTest.kt` — 2 tests (TDD)
- `build.gradle.kts` — auto-fix: added mavenCentral() to subprojects repositories block

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added mavenCentral() repository to root build.gradle.kts**
- **Found during:** Task 1 TDD RED phase (test compilation attempt)
- **Issue:** `./gradlew :kore-core:compileTestKotlin` failed with "Cannot resolve external dependency org.jetbrains.kotlin:kotlin-stdlib:2.3.0 because no repositories are defined." The root `build.gradle.kts` from Plan 01-01 had no `repositories` block in its `subprojects { }` configuration.
- **Fix:** Added `repositories { mavenCentral() }` inside the `subprojects { }` block in `build.gradle.kts`
- **Files modified:** `build.gradle.kts`
- **Commit:** `ccfe3b8` (included in Task 1 commit)

---

**Total deviations:** 1 auto-fixed (Rule 3 — missing repository declaration blocked compilation)
**Impact on plan:** Required fix — without repositories, no external dependencies resolve. All subsequent plans would have been blocked.

## Threat Mitigations Applied

Per the plan's threat model:

- **T-02-01** (Tampering — TokenUsage token counts): TokenUsage is a data class with all `val` fields — immutable once constructed. Non-negative count validation is responsibility of the agent loop (Plan 03).
- **T-02-02** (Spoofing — BudgetEnforcer result): BudgetEnforcer is a pure port interface. Only trusted implementations (InMemoryBudgetEnforcer stub in Plan 03) are wired by the agent runner. No external input reaches the interface.
- **T-02-04** (DoS — unbounded ConversationMessage list): AgentContext holds the message list. LLMConfig.maxHistoryMessages (default 50) is the enforcement point — the agent loop (Plan 03) will enforce this limit.

## Known Stubs

None — this plan is interfaces-only. The stubs (InMemoryBudgetEnforcer, InProcessEventBus, InMemoryAuditLog) are scheduled for Plan 03.

## Threat Flags

None — no new network endpoints, auth paths, file access, or trust boundary changes introduced. All types are in-process value objects and interfaces.

## Next Phase Readiness

- kore-core domain types complete — Plan 03 (agent loop) can be implemented
- LLMBackend port contract defined — Plan 05 (LLM adapters) can implement against it
- ToolProvider port contract defined — Plan 04 (MCP client) can implement against it
- All types KDoc-documented and ktlint-formatted

No blockers for Wave 3 execution.

---
## Self-Check: PASSED

- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentResult.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/LLMChunk.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/TokenUsage.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/ConversationMessage.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/ToolDefinition.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/ToolCall.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/ToolResult.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentTask.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/LLMConfig.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentContext.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentEvent.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/LLMBackend.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/ToolProvider.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/BudgetEnforcer.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/EventBus.kt
- FOUND: kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/AuditLog.kt
- FOUND: kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentResultTest.kt
- FOUND: kore-core/src/test/kotlin/dev/unityinflow/kore/core/LLMChunkTest.kt
- FOUND commit ccfe3b8 (Task 1)
- FOUND commit 32c3989 (Task 2)

---
*Phase: 01-core-runtime*
*Completed: 2026-04-10*
