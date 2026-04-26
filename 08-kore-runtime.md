# Tool 08: `kore-runtime`
## Kotlin Orchestration Runtime for Agents — Deep Dive

> **Phase:** 3 · **Effort:** 9/10 · **Impact:** 9/10 · **Stack:** Kotlin + Spring WebFlux  
> **Repo name:** `kore-runtime` · **Build in:** Months 4–7

---

## 1. Problem Statement

Every enterprise Java/Kotlin shop needs to run AI agents in production. LangChain4j provides basic LLM call abstraction but is not an agent runtime: no reactive execution, no skills system, no governance, no Kafka, no observability. The entire JVM ecosystem is unserved for production-grade agent runtimes.

---

## 2. Key Features — v0.1 Checklist

- [ ] Kotlin coroutine agent loop: task intake → LLM call → tool use → result → loop
- [ ] MCP protocol client over stdio and SSE
- [ ] Hexagonal architecture: LLM backends, event buses, storage are ports/adapters
- [ ] LLM backends: Claude, GPT, Ollama, Gemini
- [ ] Skills engine: YAML skill loader with activation context matching
- [ ] budget-breaker integration: zero-config token budget enforcement
- [ ] OpenTelemetry span on every LLM call, tool use, skill activation
- [ ] PostgreSQL audit log via Flyway migrations
- [ ] Spring Boot starter: auto-configuration from application.yml
- [ ] HTMX dashboard: active agents, recent traces, cost summary
- [ ] Pluggable event bus: Kotlin Flows (default), Kafka, RabbitMQ
- [ ] AgentResult sealed class: Success | BudgetExceeded | ToolError | LLMError

---

## 3. Technical Stack

**Language:** Kotlin  
**Async:** kotlinx.coroutines  
**Web:** Spring WebFlux (reactive)  
**ORM:** Exposed (PostgreSQL)  
**Migrations:** Flyway  
**Observability:** OpenTelemetry SDK + Micrometer  
**Dashboard:** Ktor + HTMX  
**Build:** Gradle (Kotlin DSL)  
**Distribution:** Maven Central (multi-module)  

**Module structure:**  
```
kore/
├── kore-core/          ← agent loop, interfaces, sealed classes
├── kore-mcp/           ← MCP protocol client (stdio + SSE)
├── kore-skills/        ← YAML skill loader + executor
├── kore-observability/ ← OTel + Micrometer wiring
├── kore-storage/       ← PostgreSQL + Flyway audit log
├── kore-dashboard/     ← HTMX admin UI
└── kore-spring/        ← Spring Boot auto-configuration
```

---

## 4. Key Implementation Todos

### Month 4: kore-core + kore-mcp
- [ ] Multi-module Gradle project scaffold
- [ ] AgentTask, AgentResult sealed class hierarchies
- [ ] AgentExecutor interface + coroutine implementation
- [ ] LLMBackend interface + Claude adapter
- [ ] MCP JSON-RPC client (stdio + SSE)
- [ ] McpToolRegistry and McpToolExecutor

### Month 5: kore-observability + kore-storage
- [ ] OTel span builder with agent-specific attributes
- [ ] Micrometer metrics registration
- [ ] PostgreSQL schema via Flyway (agent_runs, llm_calls, tool_calls)
- [ ] AuditRepository (append-only)

### Month 6: kore-spring + kore-dashboard
- [ ] KoreAutoConfiguration
- [ ] KoreProperties @ConfigurationProperties
- [ ] Actuator endpoints
- [ ] HTMX dashboard with Ktor embedded server

### Month 7: v0.1 Release
- [ ] Maven Central publish (all modules)
- [ ] Demo video: "Kotlin AI agent in 15 minutes"
- [ ] KotlinConf CFP submission
- [ ] Baeldung article pitch

---

## 5. Success Metrics

| Metric | First Release Target | 3 Month Target |
|---|---|---|
| GitHub stars | 200 | 1,000 |
| Maven Central downloads | 50 | 1,000 |
| Spring Boot starter downloads | 30 | 500 |
| External blog posts | 0 | 3 |

---

*Part of the AI Agent Tooling Ecosystem · See 00-MASTER-ANALYSIS.md for full context*
