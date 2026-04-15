# Requirements: kore-runtime

**Defined:** 2026-04-10
**Core Value:** A developer adds one Spring Boot dependency, writes an `agent { }` block, and has a production-ready agent running with observability and budget control.

## v1 Requirements

Requirements for v0.1 release. Each maps to roadmap phases.

### Agent Core

- [x] **CORE-01**: Agent executes a coroutine-based loop: task intake -> LLM call -> tool use -> result -> loop
- [x] **CORE-02**: AgentResult sealed class hierarchy: Success | BudgetExceeded | ToolError | LLMError | Cancelled
- [x] **CORE-03**: Agent defined via Kotlin DSL: `agent("name") { model = claude(); tools = mcp("server") }`
- [x] **CORE-04**: Each agent runs as a coroutine with semaphore-based LLM rate limiting per backend
- [x] **CORE-05**: Parent agent can spawn child agents with structured concurrency (cancel parent cancels children)
- [x] **CORE-06**: LLM retry with configurable exponential backoff
- [x] **CORE-07**: LLM fallback chain: `claude() fallbackTo gpt() fallbackTo ollama()`

### LLM Backends

- [x] **LLM-01**: Claude adapter via Anthropic Java SDK (tool calling, streaming)
- [x] **LLM-02**: GPT adapter via OpenAI Java SDK (tool calling, streaming)
- [x] **LLM-03**: Ollama adapter via LangChain4j transport (local inference)
- [x] **LLM-04**: Gemini adapter via LangChain4j transport (tool calling)
- [x] **LLM-05**: LLMBackend port interface designed against all four providers simultaneously

### MCP Protocol

- [x] **MCP-01**: MCP client: call tools on MCP servers over stdio transport
- [x] **MCP-02**: MCP client: call tools on MCP servers over SSE transport
- [x] **MCP-03**: MCP client: read resources from MCP servers
- [x] **MCP-04**: MCP client: use prompt templates from MCP servers
- [x] **MCP-05**: MCP server: expose kore agents as callable MCP tools to external clients
- [x] **MCP-06**: MCP capability negotiation (initialize handshake) before tool/resource listing

### Skills Engine

- [x] **SKIL-01**: YAML skill definition format with name, description, activation patterns
- [x] **SKIL-02**: Pattern-based activation context matching (task content, available tools, agent state)
- [x] **SKIL-03**: Skill loader discovers and loads skills from configurable directory
- [x] **SKIL-04**: Active skills injected into agent context before LLM calls

### Budget & Governance

- [x] **BUDG-01**: BudgetEnforcer port interface for token budget enforcement
- [x] **BUDG-02**: InMemoryBudgetEnforcer stub implementation with configurable token limits
- [x] **BUDG-03**: Token counting on every LLM call (input + output tokens tracked)
- [x] **BUDG-04**: Agent loop checks budget before each LLM call, returns BudgetExceeded if exceeded

### Observability

- [x] **OBSV-01**: OpenTelemetry span on every LLM call with model, token count, duration attributes
- [x] **OBSV-02**: OpenTelemetry span on every tool use with tool name, MCP server, duration
- [x] **OBSV-03**: OpenTelemetry span on every skill activation
- [x] **OBSV-04**: OpenTelemetryContextElement on every agent CoroutineScope (context survives suspension)
- [x] **OBSV-05**: Micrometer metrics: agent_runs_total, llm_calls_total, tokens_used_total, errors_total

### Storage

- [x] **STOR-01**: PostgreSQL audit log schema: agent_runs, llm_calls, tool_calls tables
- [x] **STOR-02**: Flyway migrations for all schema changes
- [x] **STOR-03**: AuditRepository (append-only) via Exposed R2DBC
- [x] **STOR-04**: Audit logging records every agent run, LLM call, and tool invocation

### Spring Integration

- [x] **SPRN-01**: KoreAutoConfiguration for Spring Boot auto-configuration
- [x] **SPRN-02**: KoreProperties @ConfigurationProperties for application.yml config
- [x] **SPRN-03**: Spring Boot starter dependency: add one dependency, agents work
- [x] **SPRN-04**: Spring Actuator endpoints for agent health and metrics

### Event Bus

- [x] **EVNT-01**: EventBus port interface for agent lifecycle events
- [x] **EVNT-02**: Kotlin Flows implementation (SharedFlow, DROP_OLDEST backpressure, default)
- [x] **EVNT-03**: Kafka adapter (opt-in, separate module)
- [ ] **EVNT-04**: RabbitMQ adapter (opt-in, separate module)

### Dashboard

- [x] **DASH-01**: HTMX dashboard showing list of active agents with status
- [x] **DASH-02**: HTMX dashboard showing recent agent runs with results
- [x] **DASH-03**: HTMX dashboard showing token cost summary per agent and total
- [x] **DASH-04**: Ktor 3.2 embedded server with HTMX module (no frontend build step)

### Testing

- [x] **TEST-01**: MockLLMBackend for scripted LLM responses in unit tests
- [x] **TEST-02**: MockMcpServer for scripted tool/resource responses in tests
- [x] **TEST-03**: Session recording mode: capture real LLM interactions to file
- [x] **TEST-04**: Session replay mode: deterministic replay of recorded sessions in CI

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Agent-to-Agent Protocol

- **A2A-01**: A2A protocol support for inter-agent communication (Google A2A spec)

### Advanced Skills

- **SKIL-05**: Skill marketplace integration with skills-registry (Tool 17)
- **SKIL-06**: Skill composition DSL for chaining skills

### Advanced Observability

- **OBSV-06**: Distributed tracing across hierarchical agent trees
- **OBSV-07**: Cost forecasting based on historical token usage

### Real budget-breaker Integration

- **BUDG-05**: budget-breaker adapter (Tool 05) replacing InMemoryBudgetEnforcer

### Additional LLM Backends

- **LLM-06**: Mistral adapter
- **LLM-07**: Cohere adapter

## Out of Scope

| Feature | Reason |
|---------|--------|
| Kafka/RabbitMQ as hard dependencies | Kotlin Flows is the default; messaging adapters are opt-in separate modules |
| Frontend SPA for dashboard | HTMX server-rendered HTML only, no build step — per project constraints |
| Vector database / RAG pipeline | Delegate to MCP tools, not the runtime's responsibility |
| gRPC MCP transport | Not in MCP spec as of 2025-06-18 |
| Multi-tenancy in dashboard | v1 is single-tenant; multi-tenancy is enterprise feature for later |
| Python/TypeScript SDKs | JVM only for v0.1 |
| Agent marketplace / hosted agents | Agents are code, not hosted services |
| Mobile SDKs | Server-side runtime only |
| Koog as a dependency | Competing framework, explicitly not used |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| CORE-01 | Phase 1 | Complete |
| CORE-02 | Phase 1 | Complete |
| CORE-03 | Phase 1 | Complete |
| CORE-04 | Phase 1 | Complete |
| CORE-05 | Phase 1 | Complete |
| CORE-06 | Phase 1 | Complete |
| CORE-07 | Phase 1 | Complete |
| LLM-01 | Phase 1 | Complete |
| LLM-02 | Phase 1 | Complete |
| LLM-03 | Phase 1 | Complete |
| LLM-04 | Phase 1 | Complete |
| LLM-05 | Phase 1 | Complete |
| MCP-01 | Phase 1 | Complete |
| MCP-02 | Phase 1 | Complete |
| MCP-03 | Phase 1 | Complete |
| MCP-04 | Phase 1 | Complete |
| MCP-05 | Phase 1 | Complete |
| MCP-06 | Phase 1 | Complete |
| BUDG-01 | Phase 1 | Complete |
| BUDG-02 | Phase 1 | Complete |
| BUDG-03 | Phase 1 | Complete |
| BUDG-04 | Phase 1 | Complete |
| TEST-01 | Phase 1 | Complete |
| TEST-02 | Phase 1 | Complete |
| TEST-03 | Phase 1 | Complete |
| TEST-04 | Phase 1 | Complete |
| OBSV-01 | Phase 2 | Complete |
| OBSV-02 | Phase 2 | Complete |
| OBSV-03 | Phase 2 | Complete |
| OBSV-04 | Phase 2 | Complete |
| OBSV-05 | Phase 2 | Complete |
| STOR-01 | Phase 2 | Complete |
| STOR-02 | Phase 2 | Complete |
| STOR-03 | Phase 2 | Complete |
| STOR-04 | Phase 2 | Complete |
| SKIL-01 | Phase 3 | Complete |
| SKIL-02 | Phase 3 | Complete |
| SKIL-03 | Phase 3 | Complete |
| SKIL-04 | Phase 3 | Complete |
| SPRN-01 | Phase 3 | Complete |
| SPRN-02 | Phase 3 | Complete |
| SPRN-03 | Phase 3 | Complete |
| SPRN-04 | Phase 3 | Complete |
| DASH-01 | Phase 3 | Complete |
| DASH-02 | Phase 3 | Complete |
| DASH-03 | Phase 3 | Complete |
| DASH-04 | Phase 3 | Complete |
| EVNT-01 | Phase 4 | Complete |
| EVNT-02 | Phase 4 | Complete |
| EVNT-03 | Phase 4 | Complete |
| EVNT-04 | Phase 4 | Pending |

**Coverage:**
- v1 requirements: 51 total (note: initial estimate in this file was 44; actual count is 51)
- Mapped to phases: 51
- Unmapped: 0

---
*Requirements defined: 2026-04-10*
*Last updated: 2026-04-10 after roadmap creation*
