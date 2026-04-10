# kore-runtime

## What This Is

A production-grade Kotlin agent runtime for the JVM. kore lets developers define, run, and observe AI agents using a Kotlin DSL, with coroutine-based execution, MCP protocol support, multi-LLM backends, and Spring Boot integration. It fills the gap between LangChain4j's LLM call abstraction and what enterprises actually need to run agents in production.

## Core Value

The agent loop must work — task intake, LLM call, tool use, result handling, loop. A developer adds one Spring Boot dependency, writes an `agent { }` block, and has a production-ready agent running with observability and budget control. Everything else is additive.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Kotlin coroutine agent loop: task intake -> LLM call -> tool use -> result -> loop
- [ ] Kotlin DSL for agent definition (`agent("name") { model = claude(); tools = mcp("github") }`)
- [ ] Hierarchical agents: parent agents spawn child agents with structured concurrency
- [ ] Coroutine per agent with semaphore-based LLM rate limiting
- [ ] LLM backends: Claude, GPT, Ollama, Gemini (all four from day one)
- [ ] LLM fallback chains: retry with exponential backoff, fallback to alternate backend
- [ ] AgentResult sealed class: Success | BudgetExceeded | ToolError | LLMError
- [ ] MCP protocol client: tools, resources, prompts over stdio and SSE
- [ ] MCP protocol server: expose kore agents as MCP tools to external clients
- [ ] Skills engine: YAML skill definitions with pattern-based activation context matching
- [ ] BudgetEnforcer port interface with in-memory stub (real budget-breaker adapter later)
- [ ] OpenTelemetry span on every LLM call, tool use, skill activation
- [ ] Micrometer metrics registration for agent runs, token usage, error rates
- [ ] PostgreSQL audit log via Flyway migrations (agent_runs, llm_calls, tool_calls)
- [ ] Spring Boot starter: auto-configuration from application.yml
- [ ] Pluggable event bus: Kotlin Flows (default), Kafka (opt-in), RabbitMQ (opt-in)
- [ ] HTMX dashboard: active agents, recent runs, token cost summary
- [ ] kore-test module: MockLLMBackend + session recording/replay for deterministic agent testing

### Out of Scope

- Kafka/RabbitMQ as hard dependencies — Kotlin Flows is the default event bus, messaging adapters are opt-in
- Frontend SPA for dashboard — HTMX server-rendered HTML only, no build step
- Python/TypeScript SDKs — JVM only for v0.1
- Agent marketplace / registry — agents are code, not hosted
- Real budget-breaker integration — interface + stub only until Tool 05 ships
- Mobile SDKs — server-side runtime only

## Context

Tool 08 in the UnityInFlow AI Agent Tooling Ecosystem (20 open-source tools). Part of Phase 3 (Months 4-7). Depends on budget-breaker (Tool 05) for token budget enforcement, but will use a stub adapter until that tool ships.

The JVM ecosystem has no production-grade agent runtime. LangChain4j provides LLM call abstraction but not: reactive execution, skills system, governance, event bus, or observability. kore fills that gap for enterprise Java/Kotlin teams.

Three target personas:
- **Backend developers** — adding AI features to existing Spring Boot apps (kore-core + kore-spring)
- **Platform engineers** — running agents at scale with observability and governance (kore-observability + kore-storage)
- **AI engineers** — building agent-first products, coming from Python/LangChain (kore-core + kore-mcp + kore-skills + kore-test)

## Constraints

- **Language**: Kotlin 2.0+, JVM target 21
- **Build**: Gradle (Kotlin DSL only — never Groovy)
- **Architecture**: Hexagonal — LLM backends, event bus, storage, budget enforcement are ports/adapters
- **Async**: Coroutines everywhere — never Thread.sleep() or raw threads
- **Immutability**: `val` only, no `var`. Immutable data classes for value objects
- **Error handling**: Sealed classes (AgentResult) instead of exceptions for expected failures
- **Testing**: JUnit 5 + Kotest matchers. Coverage >80% on core logic
- **Format**: ktlint before every commit
- **Distribution**: Maven Central via Sonatype. Group: `dev.unityinflow`
- **CI**: Self-hosted runners (arc-runner-unityinflow). Never ubuntu-latest
- **Dashboard**: HTMX + Ktor embedded server. No frontend build step
- **Event bus**: Kotlin Flows default. Kafka/RabbitMQ opt-in only
- **Secrets**: Never committed. All credentials via environment variables
- **Docs**: KDoc on all public APIs

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Kotlin DSL for agent API | Idiomatic, concise, discoverable. README examples must feel native to Kotlin developers. Annotation-based support can layer on later | — Pending |
| All 4 LLM backends from day one | Proves LLMBackend port interface is honest abstraction, not Claude-shaped. Credibility with multi-LLM users | — Pending |
| Full MCP (client + server) | Positions kore as THE JVM MCP implementation. Agents can both call and be called via MCP | — Pending |
| Interface + stub for budget-breaker | Hexagonal architecture allows building the port now, real adapter later. Avoids blocking on Tool 05 | — Pending |
| Pattern-based skill activation | Skills declare activation patterns in YAML. Runtime matches automatically. Similar to Superpowers auto-activation | — Pending |
| Coroutine per agent + semaphore | Lightweight (KB per agent), structured concurrency for cancellation, semaphore configurable per LLM backend | — Pending |
| Retry + fallback for LLM resilience | Enterprise-grade: exponential backoff + fallback chain (e.g., claude() fallbackTo gpt() fallbackTo ollama()) | — Pending |
| Hierarchical multi-agent | Parent/child agents via structured concurrency. Cancelling parent cancels children. Enables supervisor/worker patterns | — Pending |
| kore-test with Mock + record/replay | Strong differentiator — LangChain4j has no testing story. MockLLMBackend for unit tests, recording for CI replay | — Pending |
| Minimal HTMX dashboard in v0.1 | Active agents, recent runs, cost summary. Good for demos and README screenshots. No frontend build step | — Pending |

## Module Structure

```
kore/
├── kore-core/          <- agent loop, DSL, interfaces, sealed classes, concurrency
├── kore-mcp/           <- MCP client (tools + resources + prompts) + MCP server
├── kore-skills/        <- YAML skill loader + pattern-based activation engine
├── kore-observability/ <- OpenTelemetry spans + Micrometer metrics
├── kore-storage/       <- PostgreSQL + Flyway audit log
├── kore-dashboard/     <- HTMX admin UI (Ktor embedded)
├── kore-spring/        <- Spring Boot auto-configuration starter
└── kore-test/          <- MockLLMBackend + session recording/replay
```

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? -> Move to Out of Scope with reason
2. Requirements validated? -> Move to Validated with phase reference
3. New requirements emerged? -> Add to Active
4. Decisions to log? -> Add to Key Decisions
5. "What This Is" still accurate? -> Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-10 after initialization*
