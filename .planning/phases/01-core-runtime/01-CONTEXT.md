# Phase 1: Core Runtime - Context

**Gathered:** 2026-04-10
**Status:** Ready for planning

<domain>
## Phase Boundary

Deliver the core agent execution runtime: coroutine-based agent loop, Kotlin DSL for agent definition, AgentResult sealed class hierarchy, 4 LLM backend adapters (Claude, GPT, Ollama, Gemini), MCP protocol client (tools + resources + prompts over stdio and SSE) and server (expose agents as MCP tools), budget enforcement (port interface + in-memory stub), and kore-test module (MockLLMBackend + session recording/replay). This is the "hero demo" phase — the README example must work end-to-end.

</domain>

<decisions>
## Implementation Decisions

### DSL Surface Area
- **D-01:** Full lifecycle DSL — one `agent { }` block defines everything: model, tools, budget, retries, result handling, lifecycle hooks, child agent spawning
- **D-02:** Use `@DslMarker` annotation to prevent accidental nesting mistakes
- **D-03:** The DSL is the primary API. No separate builder pattern for v0.1 — DSL covers all cases

### LLM Interface Shape
- **D-04:** Unified Flow-based interface — all LLM responses are `Flow<LLMChunk>`. Non-streaming backends emit one text chunk + usage + done. Single `call()` method on `LLMBackend`
- **D-05:** `LLMChunk` sealed class: `Text`, `ToolCall`, `Usage`, `Done`
- **D-06:** `LLMBackend` is stateless — receives full message list each call. Agent loop accumulates messages
- **D-07:** Design `LLMBackend` interface against all 4 providers simultaneously — never shape it around one provider
- **D-08:** Claude and GPT use official SDKs (Anthropic Java SDK 2.20.0, OpenAI Java SDK 4.30.0). Ollama and Gemini use LangChain4j as HTTP transport only

### MCP Client Lifecycle
- **D-09:** MCP servers configured in `application.yml` as a named list with transport type (stdio/SSE), command/URL, and environment variables
- **D-10:** Lazy connection — connect on first tool call, not at startup
- **D-11:** Auto-reconnect with exponential backoff on failure. Return `ToolError` only if reconnection exhausted
- **D-12:** MCP initialize handshake (capability negotiation) completes before tools/list

### MCP Server
- **D-13:** kore agents exposed as MCP tools — external MCP clients can invoke kore agents
- **D-14:** MCP server transport: stdio + Ktor-based SSE (from MCP Kotlin SDK 0.11.1)

### Module Boundaries
- **D-15:** kore-core has zero external dependencies except kotlinx.coroutines + stdlib
- **D-16:** kore-test built alongside kore-core (not after) — agent loop cannot be validated without MockLLMBackend
- **D-17:** MCP protocol implementation in kore-mcp module using official MCP Kotlin SDK 0.11.1

### Agent Execution Model
- **D-18:** Coroutine per agent with semaphore-based LLM rate limiting (configurable per backend)
- **D-19:** Hierarchical agents: parent spawns children with structured concurrency. Cancel parent = cancel children
- **D-20:** LLM retry with configurable exponential backoff + fallback chain (`claude() fallbackTo gpt() fallbackTo ollama()`)
- **D-21:** Agent loop never throws — all failures are AgentResult sealed class variants. Only CancellationException is re-thrown
- **D-22:** Parallel tool dispatch: `coroutineScope { toolCalls.map { async { dispatch(it) } }.awaitAll() }`

### Budget Enforcement
- **D-23:** BudgetEnforcer port interface in kore-core
- **D-24:** InMemoryBudgetEnforcer stub — tracks tokens per agent, returns BudgetExceeded when limit reached
- **D-25:** Budget check before each LLM call in the agent loop

### Testing (kore-test)
- **D-26:** MockLLMBackend for scripted responses in unit tests
- **D-27:** MockMcpServer for scripted tool/resource responses
- **D-28:** Session recording mode — capture real LLM interactions to file
- **D-29:** Session replay mode — deterministic replay in CI without API keys

### Claude's Discretion
- Message history management approach (agent loop owns it vs separate Conversation object)
- Exact kore-core module contents (interfaces + loop together, or interfaces-only with separate engine module)
- InMemoryBudgetEnforcer internal data structures
- MockLLMBackend API details (fluent builder vs DSL)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project Spec
- `08-kore-runtime.md` — Full feature spec, module structure, implementation todos, success metrics
- `.planning/PROJECT.md` — Core value, constraints, key decisions from project init

### Research (verified 2026-04-10)
- `.planning/research/STACK.md` — Technology recommendations with exact versions (Kotlin 2.3, Spring Boot 4.0.5, MCP SDK 0.11.1, etc.)
- `.planning/research/ARCHITECTURE.md` — Component boundaries, data flows, agent loop pattern, parallel tool dispatch pattern, SharedFlow EventBus config
- `.planning/research/PITFALLS.md` — 15 domain-specific pitfalls with prevention strategies and phase mapping
- `.planning/research/FEATURES.md` — Competitive landscape (Koog, LangChain4j, Spring AI), table stakes vs differentiators

### MCP Protocol
- MCP Kotlin SDK 0.11.1 (io.modelcontextprotocol:kotlin-sdk) — official docs for client/server transport implementations

### Harness
- `claude-code-harness-engineering-guide-v2.md` — Harness engineering patterns, hooks, skills structure

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- None — greenfield project, no existing code

### Established Patterns
- Project constraints define patterns: hexagonal architecture, sealed classes for errors, coroutines for async, `val` only, KDoc on public APIs

### Integration Points
- kore-core is the inner hexagon — all other modules depend inward on it
- kore-test depends on kore-core port interfaces
- kore-mcp depends on kore-core for AgentResult, ToolDef types

</code_context>

<specifics>
## Specific Ideas

- Agent DSL should feel idiomatic to Kotlin developers — discoverable, concise, like Ktor routing DSL
- The README example (`agent { model = claude(); tools = mcp("github") }`) is the marketing material — it must compile and run
- LLM fallback chain syntax: `claude() fallbackTo gpt() fallbackTo ollama()` using infix functions
- MCP config in `application.yml` should feel like configuring any Spring Boot integration

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 01-core-runtime*
*Context gathered: 2026-04-10*
