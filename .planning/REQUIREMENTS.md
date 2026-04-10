# Requirements: kore-runtime

**Defined:** 2026-04-10
**Core Value:** A developer adds one Spring Boot dependency, writes an `agent { }` block, and has a production-ready agent running with observability and budget control.

## v1 Requirements

Requirements for v0.1 release. Each maps to roadmap phases.

### Agent Core

- [ ] **CORE-01**: Agent executes a coroutine-based loop: task intake -> LLM call -> tool use -> result -> loop
- [ ] **CORE-02**: AgentResult sealed class hierarchy: Success | BudgetExceeded | ToolError | LLMError | Cancelled
- [ ] **CORE-03**: Agent defined via Kotlin DSL: `agent("name") { model = claude(); tools = mcp("server") }`
- [ ] **CORE-04**: Each agent runs as a coroutine with semaphore-based LLM rate limiting per backend
- [ ] **CORE-05**: Parent agent can spawn child agents with structured concurrency (cancel parent cancels children)
- [ ] **CORE-06**: LLM retry with configurable exponential backoff
- [ ] **CORE-07**: LLM fallback chain: `claude() fallbackTo gpt() fallbackTo ollama()`

### LLM Backends

- [ ] **LLM-01**: Claude adapter via Anthropic Java SDK (tool calling, streaming)
- [ ] **LLM-02**: GPT adapter via OpenAI Java SDK (tool calling, streaming)
- [ ] **LLM-03**: Ollama adapter via LangChain4j transport (local inference)
- [ ] **LLM-04**: Gemini adapter via LangChain4j transport (tool calling)
- [ ] **LLM-05**: LLMBackend port interface designed against all four providers simultaneously

### MCP Protocol

- [ ] **MCP-01**: MCP client: call tools on MCP servers over stdio transport
- [ ] **MCP-02**: MCP client: call tools on MCP servers over SSE transport
- [ ] **MCP-03**: MCP client: read resources from MCP servers
- [ ] **MCP-04**: MCP client: use prompt templates from MCP servers
- [ ] **MCP-05**: MCP server: expose kore agents as callable MCP tools to external clients
- [ ] **MCP-06**: MCP capability negotiation (initialize handshake) before tool/resource listing

### Skills Engine

- [ ] **SKIL-01**: YAML skill definition format with name, description, activation patterns
- [ ] **SKIL-02**: Pattern-based activation context matching (task content, available tools, agent state)
- [ ] **SKIL-03**: Skill loader discovers and loads skills from configurable directory
- [ ] **SKIL-04**: Active skills injected into agent context before LLM calls

### Budget & Governance

- [ ] **BUDG-01**: BudgetEnforcer port interface for token budget enforcement
- [ ] **BUDG-02**: InMemoryBudgetEnforcer stub implementation with configurable token limits
- [ ] **BUDG-03**: Token counting on every LLM call (input + output tokens tracked)
- [ ] **BUDG-04**: Agent loop checks budget before each LLM call, returns BudgetExceeded if exceeded

### Observability

- [ ] **OBSV-01**: OpenTelemetry span on every LLM call with model, token count, duration attributes
- [ ] **OBSV-02**: OpenTelemetry span on every tool use with tool name, MCP server, duration
- [ ] **OBSV-03**: OpenTelemetry span on every skill activation
- [ ] **OBSV-04**: OpenTelemetryContextElement on every agent CoroutineScope (context survives suspension)
- [ ] **OBSV-05**: Micrometer metrics: agent_runs_total, llm_calls_total, tokens_used_total, errors_total

### Storage

- [ ] **STOR-01**: PostgreSQL audit log schema: agent_runs, llm_calls, tool_calls tables
- [ ] **STOR-02**: Flyway migrations for all schema changes
- [ ] **STOR-03**: AuditRepository (append-only) via Exposed R2DBC
- [ ] **STOR-04**: Audit logging records every agent run, LLM call, and tool invocation

### Spring Integration

- [ ] **SPRN-01**: KoreAutoConfiguration for Spring Boot auto-configuration
- [ ] **SPRN-02**: KoreProperties @ConfigurationProperties for application.yml config
- [ ] **SPRN-03**: Spring Boot starter dependency: add one dependency, agents work
- [ ] **SPRN-04**: Spring Actuator endpoints for agent health and metrics

### Event Bus

- [ ] **EVNT-01**: EventBus port interface for agent lifecycle events
- [ ] **EVNT-02**: Kotlin Flows implementation (SharedFlow, DROP_OLDEST backpressure, default)
- [ ] **EVNT-03**: Kafka adapter (opt-in, separate module)
- [ ] **EVNT-04**: RabbitMQ adapter (opt-in, separate module)

### Dashboard

- [ ] **DASH-01**: HTMX dashboard showing list of active agents with status
- [ ] **DASH-02**: HTMX dashboard showing recent agent runs with results
- [ ] **DASH-03**: HTMX dashboard showing token cost summary per agent and total
- [ ] **DASH-04**: Ktor 3.2 embedded server with HTMX module (no frontend build step)

### Testing

- [ ] **TEST-01**: MockLLMBackend for scripted LLM responses in unit tests
- [ ] **TEST-02**: MockMcpServer for scripted tool/resource responses in tests
- [ ] **TEST-03**: Session recording mode: capture real LLM interactions to file
- [ ] **TEST-04**: Session replay mode: deterministic replay of recorded sessions in CI

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
| *(populated by roadmapper)* | | |

**Coverage:**
- v1 requirements: 44 total
- Mapped to phases: 0
- Unmapped: 44

---
*Requirements defined: 2026-04-10*
*Last updated: 2026-04-10 after initial definition*
