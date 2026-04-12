---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: verifying
stopped_at: Phase 2 context gathered
last_updated: "2026-04-12T13:29:04.883Z"
last_activity: 2026-04-12
progress:
  total_phases: 4
  completed_phases: 1
  total_plans: 7
  completed_plans: 7
  percent: 100
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-10)

**Core value:** A developer adds one Spring Boot dependency, writes an `agent { }` block, and has a production-ready agent running with observability and budget control.
**Current focus:** Phase 01 — core-runtime

## Current Position

Phase: 2
Plan: Not started
Status: Phase complete — ready for verification
Last activity: 2026-04-12

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**

- Total plans completed: 7
- Average duration: -
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 | 7 | - | - |

**Recent Trend:**

- Last 5 plans: -
- Trend: -

*Updated after each plan completion*
| Phase 01-core-runtime P01 | 4 | 2 tasks | 20 files |
| Phase 01-core-runtime P02 | 4min | 2 tasks | 19 files |
| Phase 01-core-runtime P03 | 4min | 2 tasks | 11 files |
| Phase 01-core-runtime P04 | 2min | 2 tasks | 5 files |
| Phase 01-core-runtime P05 | 15min | 2 tasks | 11 files |
| Phase 01-core-runtime P06 | 10min | 2 tasks | 7 files |
| Phase 01-core-runtime P07 | 5min | 3 tasks | 5 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- kore-core must have zero external dependencies except kotlinx.coroutines + stdlib
- OTel context propagation (OpenTelemetryContextElement) must be in kore-core from day one
- LLMBackend interface must be designed against all 4 providers simultaneously
- kore-test built alongside kore-core (Phase 1), not deferred to later
- kore-spring is last integration layer — depends on all adapters (Phase 3)
- [Phase 01-core-runtime]: kore-llm created as separate module keeping kore-core free of LLM SDK dependencies (D-15)
- [Phase 01-core-runtime]: kore-test uses api() declarations so MockK, Kotest, coroutines-test propagate transitively to consumer test scopes
- [Phase 01-core-runtime]: kotlinter (org.jmailen.kotlinter) chosen for ktlint integration — simpler Gradle plugin, actively maintained
- [Phase 01-core-runtime]: LLMBackend.call() returns Flow<LLMChunk> (not suspend) so streaming and non-streaming backends satisfy one interface without overloading
- [Phase 01-core-runtime]: LLMChunk.ToolCall.arguments is String (JSON) not Map<String,Any> — provider-agnostic per D-07 and Pitfall 5
- [Phase 01-core-runtime]: BudgetEnforcer.checkBudget() returns Boolean (continue=true) rather than throwing — consistent with AgentResult no-throw contract
- [Phase 01-core-runtime]: AgentLoop history is MutableList mutated in-place each iteration — not re-created
- [Phase 01-core-runtime]: InMemoryBudgetEnforcer uses ConcurrentHashMap.merge for atomic token accumulation (thread-safe)
- [Phase 01-core-runtime]: ResilientLLMBackend.retryPolicy is internal (not private) so fallbackTo infix can copy it when chaining
- [Phase 01-core-runtime]: SessionRecorder.kt hosts SessionReplayer — shared SerializableChunk types kept in one file, avoids adding @Serializable to kore-core domain types (preserves D-15 zero-dep constraint)
- [Phase 01-core-runtime]: anthropic-java version corrected 2.20.0→0.1.0 (planned version doesn't exist on Maven Central; okhttp artifact is separate)
- [Phase 01-core-runtime]: langchain4j 1.0.1→0.26.1/0.36.1 (planned version doesn't exist; ollama and gemini on separate version trains, Gradle resolves langchain4j-core to 0.36.1 for both)
- [Phase 01-core-runtime]: ChatLanguageModel injected as constructor param to OllamaBackend and GeminiBackend — enables MockK mocking without network, no concrete LangChain4j model types in signatures
- [Phase 01-core-runtime]: McpServerAdapter.invokeAgent() extracted as public suspend fun for testability without transport
- [Phase 01-core-runtime]: MCP SDK version resolved as 0.11.0 (plan specified 0.11.1); APIs identical
- [Phase 01-core-runtime]: HeroDemoTest comment enforces README/test sync — test must be updated first if README hero demo changes (T-07-01 mitigation)
- [Phase 01-core-runtime]: kore-core testImplementation depends on kore-test — runtimeClasspath stays clean (zero non-coroutines external deps)

### Pending Todos

None yet.

### Blockers/Concerns

- budget-breaker (Tool 05) not yet shipped — BudgetEnforcer uses InMemoryBudgetEnforcer stub per design. Real adapter deferred to v2 (BUDG-05).

## Session Continuity

Last session: 2026-04-12T13:29:04.880Z
Stopped at: Phase 2 context gathered
Resume file: .planning/phases/02-observability-storage/02-CONTEXT.md
