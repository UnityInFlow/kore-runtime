# Phase 1: Core Runtime - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-10
**Phase:** 01-core-runtime
**Areas discussed:** DSL surface area, LLM interface shape, MCP client lifecycle, Module boundaries

---

## DSL Surface Area

| Option | Description | Selected |
|--------|-------------|----------|
| Agent + tools only | DSL for agent definition and tool registration. Result handling, skill config, lifecycle hooks are regular Kotlin code outside the block | |
| Full lifecycle DSL | Everything inside the block: model, tools, budget, result handling, lifecycle hooks, child agent spawning. One block = complete agent definition | ✓ |
| Minimal DSL + builders | DSL only for common case. Everything else uses builder methods for explicit control | |

**User's choice:** Full lifecycle DSL
**Notes:** One `agent { }` block defines everything. @DslMarker prevents nesting mistakes.

---

## LLM Interface Shape

### Streaming vs Non-Streaming

| Option | Description | Selected |
|--------|-------------|----------|
| Flow-based unified | All responses are Flow<LLMChunk>. Non-streaming emits one chunk. Single interface method | ✓ |
| Two methods | Separate call() and stream(). More explicit, two code paths per backend | |
| Non-streaming only | v0.1 ships non-streaming. Streaming added in v0.2 | |

**User's choice:** Flow-based unified
**Notes:** Single `call()` method returns `Flow<LLMChunk>`. Sealed class: Text, ToolCall, Usage, Done.

### Message History Management

| Option | Description | Selected |
|--------|-------------|----------|
| Agent loop owns it | Loop accumulates messages. LLMBackend is stateless | |
| Conversation object | Separate class with truncation/summarization | |
| You decide | Claude has discretion | ✓ |

**User's choice:** Claude's discretion
**Notes:** Planner decides approach.

---

## MCP Client Lifecycle

### Configuration

| Option | Description | Selected |
|--------|-------------|----------|
| YAML config list | MCP servers in application.yml with name, transport, command/URL | ✓ |
| DSL registration | MCP servers in agent DSL block | |
| Both: global + override | Global pool in YAML, agents select by name in DSL | |

**User's choice:** YAML config list
**Notes:** Spring Boot application.yml configuration with environment variable substitution.

### Connection Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Lazy + auto-reconnect | Connect on first call, auto-reconnect with backoff | ✓ |
| Eager + fail fast | Connect at startup, fail if unreachable | |
| Lazy + no reconnect | Connect on first call, ToolError on disconnect | |

**User's choice:** Lazy + auto-reconnect
**Notes:** ToolError only if reconnection exhausted.

---

## Module Boundaries

### Core Module Contents

| Option | Description | Selected |
|--------|-------------|----------|
| Core = interfaces + loop | Port interfaces, sealed classes, DSL, and default agent loop. No external deps | |
| Core = interfaces only | Just interfaces and sealed classes. Agent loop in separate module | |
| You decide | Claude has discretion | ✓ |

**User's choice:** Claude's discretion
**Notes:** Planner decides the exact split. Key constraint: kore-core must have zero external deps except kotlinx.coroutines + stdlib.

---

## Claude's Discretion

- Message history management approach
- Exact kore-core module contents boundary
- InMemoryBudgetEnforcer internals
- MockLLMBackend API design

## Deferred Ideas

None — discussion stayed within phase scope
