# Feature Landscape

**Domain:** Production AI Agent Runtime (JVM / Kotlin)
**Researched:** 2026-04-10
**Comparators:** LangChain (Python), CrewAI, AutoGen/Microsoft Agent Framework, Semantic Kernel, LangChain4j, Koog (JetBrains), Spring AI, Embabel

---

## Table Stakes

Features users expect. Missing = developers leave for Koog, LangChain4j, or Spring AI.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Coroutine agent loop (task → LLM → tool → result → loop) | Every framework provides this; it is the definition of "agent runtime" | Medium | Must be non-blocking, suspend-based. Raw threads or `Thread.sleep()` disqualify on day one for Kotlin teams |
| Multi-LLM backend support (Claude, GPT, Gemini, Ollama) | LangChain4j covers 20+ providers; single-backend = vendor lock-in; developers need Ollama for offline/cost, Claude for quality | High | The `LLMBackend` port interface must not be Claude-shaped. All four from v0.1 proves the abstraction is honest |
| Tool calling via `@Tool` annotation or builder API | Every serious framework provides this. Missing = can't connect agents to real systems | Medium | Standard JVM pattern. LangChain4j uses `@Tool`; Koog uses typed functions. Both approaches work |
| MCP protocol client (stdio + SSE transports) | MCP is the de facto standard as of 2026: 10,000+ public servers, 97M monthly SDK downloads, donated to Linux Foundation. Missing = can't consume the MCP tool ecosystem | High | Must support both stdio (local tools) and SSE (remote services). Resources and prompts in addition to tools |
| Chat memory / conversation context | Stateless agents forget context between turns. Every user expects persistence across a session | Medium | Minimum: in-memory with configurable sliding window. Storage backends (Redis, PostgreSQL) additive |
| OpenTelemetry spans on LLM calls and tool use | 89% of production AI teams use observability tooling; 62% require per-step tracing. Zero traces = zero debuggability in production | Medium | One span per LLM call, one span per tool invocation. Attributes: model, token counts, duration, status |
| Structured error types (sealed class hierarchy) | Kotlin teams expect typed errors, not stringly-typed exceptions. Missing = every caller writes its own try/catch | Low | `AgentResult.Success`, `BudgetExceeded`, `ToolError`, `LLMError`. Standard sealed class pattern |
| Retry with exponential backoff | LLM APIs are rate-limited and flaky. Unretried 429s/503s surface directly to users | Low | Per-backend configurable. Most teams hardcode this themselves today |
| Spring Boot auto-configuration | 70%+ of enterprise JVM workloads run Spring Boot. Without a starter, every team writes the same wiring boilerplate | Medium | `application.yml`-driven. No code required for basic configuration |
| Audit / execution log (who ran what, when, result) | Enterprise compliance and debugging. Teams get paged at 2am and need to replay what happened | High | PostgreSQL with Flyway migrations. Schema: `agent_runs`, `llm_calls`, `tool_calls`. Minimum viable audit trail |
| Token budget enforcement | Runaway agents have generated $47,000 bills in 11 days (documented incident). Without hard limits, a loop bug is a financial incident | Medium | Enforce as a port/adapter. Hard limit (reject) + soft limit (warn). Per-agent and per-run granularity |

---

## Differentiators

Features that set kore apart from LangChain4j, Spring AI, and Koog. Not expected, but valued.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| MCP protocol server (expose kore agents as MCP tools) | LangChain4j and Spring AI are MCP *clients* only. Being a full MCP server means kore agents are callable from any MCP client (Claude Desktop, other agents, IDEs). Positions kore as THE JVM MCP implementation | High | Full server implementation over SSE. Tools, resources, and prompts exposed. Enables agent-to-agent composition via MCP |
| Kotlin DSL for agent definition (`agent("name") { ... }`) | LangChain4j requires annotations + interfaces; Spring AI uses bean configuration. A Kotlin DSL is idiomatic, discoverable, and produces shorter README examples that sell the framework | Medium | `agent("name") { model = claude(); tools = mcp("github") }`. The DSL is the marketing pitch |
| Hierarchical multi-agent with structured concurrency | CrewAI has sequential-by-default execution; AutoGen has dynamic group chat. Neither gives Kotlin developers structured concurrency guarantees (cancellation propagation, resource cleanup). Parent coroutine cancels all children | High | Uses `supervisorScope` or `coroutineScope`. Child agents inherit budget limits from parent. Documented cancellation contract |
| LLM fallback chains | LangChain4j requires manual try/catch for fallback. Kore DSL: `claude() fallbackTo gpt() fallbackTo ollama()`. Enterprise teams need circuit-breaker behavior without custom code | Medium | Builder pattern. Configurable: exponential backoff, max retries, fallback trigger conditions (rate limit, timeout, error) |
| Skills engine with YAML definitions and pattern-based activation | Spring AI has generic "agent skills" concept but no YAML-driven activation. Kore skills activate automatically when task context matches patterns — like Superpowers auto-activation. Reduces hardcoded routing logic | High | YAML frontmatter: name, description, activation patterns. Runtime scores context against patterns. Enables reusable skill libraries |
| kore-test: MockLLMBackend + session recording/replay | LangChain4j has no testing story. Spring AI has basic mocking. Docker's Cagent and CheckAgent are external tools. kore-test ships in the core distribution: unit tests with deterministic mocks, CI regression tests with recorded sessions | Medium | `MockLLMBackend` returns scripted responses. Recording captures real LLM interactions. Replay re-runs them deterministically. Critical for CI pipelines |
| Pluggable event bus (Flows default, Kafka/RabbitMQ opt-in) | Spring AI is request/response only. LangChain4j has no event bus. For platform engineers running agents at scale, a reactive event bus enables backpressure, fan-out, and decoupled audit consumers | High | Kotlin Flows is zero-dependency default. Kafka adapter is a separate artifact (`kore-kafka`). No hard dependency on message brokers |
| HTMX dashboard (active agents, traces, cost) | No JVM agent runtime ships a built-in dashboard. Grafana/Langfuse require separate infrastructure. HTMX + Ktor embedded server = one jar, no build step, good for demos and README screenshots | Medium | Not a substitute for Grafana in production. Value: immediate visibility for developers running kore locally or in staging |
| Micrometer metrics registration | OpenTelemetry provides traces; Micrometer provides counters/gauges that feed Prometheus/Grafana. Every Spring Boot shop has a Prometheus scraper. Named metrics: `kore.agent.runs.total`, `kore.llm.tokens.total`, `kore.agent.errors.total` | Low | Additive on top of OTel. Critical for SRE dashboards and alerting |

---

## Anti-Features

Features to explicitly NOT build in v0.1.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| Kafka/RabbitMQ as hard dependencies | Adds `broker must be running` to the getting-started experience. Kills adoption for developers who just want to run an agent. Real incident: every framework that ships with Kafka as default loses the simple-case audience | Ship Kotlin Flows as default event bus. Kafka adapter is a separate optional artifact. Document upgrade path |
| Python or TypeScript SDKs | Two communities already have mature options (LangChain, LangGraph, CrewAI for Python; Vercel AI SDK for TS). Splitting effort reduces JVM quality without capturing new users | JVM only for v0.1. List as explicit out-of-scope in README |
| Agent marketplace / registry / hosted service | SaaS infrastructure is not an open-source library concern. Agents are code, not hosted assets. Building a registry before the runtime is stable inverts priorities | Agents are defined in code. Skills registry (Tool 17 in the ecosystem) handles this separately |
| Frontend SPA for the dashboard | Build step, npm dependencies, bundler configuration — every frontend framework adds friction. React dashboard requires `node` to be installed alongside a JVM runtime | HTMX + server-rendered HTML. One jar, zero frontend tooling. Constraint from project CLAUDE.md |
| Automatic prompt injection defense | Prompt injection defense requires model-level mitigations that kore cannot provide reliably. False sense of security is worse than acknowledged risk | Document the attack surface. Integrate with injection-scanner (Tool 03) for static analysis. Runtime output validation is separate |
| Built-in vector database / RAG pipeline | LangChain4j and Spring AI both have extensive vector store integrations. Duplicating this is weeks of work for capabilities that can be composed in. kore agents can use MCP tools that wrap vector DBs | Document composition pattern: MCP tool wrapping a vector store. Don't ship the vector store |
| Graphical agent builder / visual workflow editor | CrewAI Enterprise and LangSmith have visual builders. These are commercial products. An open-source runtime's visual editor will always lag behind commercial offerings | Focus on the Kotlin DSL being readable enough that it functions as its own visual language |
| gRPC transport for MCP | MCP spec as of 2025-11-25 defines stdio and SSE transports. gRPC is not in the spec. Implementing non-standard transports fragments interoperability | Strict spec compliance: stdio + SSE only. Track MCP roadmap for future transport additions |
| Multi-tenancy / team access control in kore-dashboard | Access control is an infrastructure concern (API gateway, reverse proxy). Building RBAC into an embedded HTMX dashboard adds 3+ months of scope and creates a false security boundary | Document: "put kore-dashboard behind your existing auth layer." nginx/Caddy basic auth is sufficient for most cases |

---

## Feature Dependencies

```
Agent loop (coroutine)
  └─> LLM backends (multi-provider)
        └─> Retry + fallback chains
              └─> Token budget enforcement (BudgetEnforcer port)
                    └─> budget-breaker adapter (Tool 05, future)

MCP client (stdio + SSE)
  └─> Tool calling (agent loop consumes MCP tools)
        └─> Hierarchical agents (child agents spawn with subset of parent tools)

MCP server
  └─> (depends on MCP client implementation being stable first)

Skills engine (YAML loader)
  └─> Agent loop (skills activate on task context match)
        └─> MCP client (skills can delegate to MCP tools)

OpenTelemetry spans
  └─> Agent loop (each step emits a span)
  └─> Tool calling (each invocation emits a span)
  └─> Skills activation (each activation emits a span)

Micrometer metrics
  └─> OpenTelemetry (runs alongside, separate concern)

PostgreSQL audit log
  └─> Flyway migrations (schema management)
  └─> Agent loop (writes a row on run start/end)

Spring Boot starter
  └─> All modules (auto-configures everything via application.yml)

kore-test (MockLLMBackend)
  └─> LLM backend interface (mock must satisfy the same port contract)
  └─> Agent loop (tests drive the loop with mocked responses)

HTMX dashboard
  └─> PostgreSQL audit log (reads agent_runs for display)
  └─> Micrometer metrics (reads gauges for active agent count)

Pluggable event bus
  └─> Agent loop (publishes events at each lifecycle transition)
  └─> Audit log (optional consumer of event stream)
```

---

## MVP Recommendation

**Phase 1 (kore-core + kore-mcp): Months 4-5**

Prioritize in strict dependency order:

1. Agent loop coroutine engine (task intake, LLM call, tool use, result, repeat)
2. `LLMBackend` port interface + Claude and GPT adapters (proves abstraction)
3. `AgentResult` sealed class hierarchy
4. MCP client over stdio (enables real tool use immediately)
5. Kotlin DSL for agent definition (the "hello world" story)
6. `BudgetEnforcer` port + in-memory stub (prevents runaway loops in tests)
7. Basic chat memory (in-memory, sliding window)
8. Retry with exponential backoff on LLM calls

**Phase 2 (kore-observability + kore-storage): Month 5-6**

9. OpenTelemetry spans (every LLM call + tool use)
10. Micrometer metrics (named counters and gauges)
11. PostgreSQL audit log + Flyway migrations
12. MCP server (agents exposed as MCP tools)

**Phase 3 (kore-spring + kore-dashboard + kore-skills + kore-test): Month 6-7**

13. Spring Boot starter (auto-configuration)
14. Skills engine (YAML loader + pattern activation)
15. kore-test (MockLLMBackend + record/replay)
16. HTMX dashboard (active agents, recent runs, cost)
17. Ollama and Gemini LLM adapters
18. Pluggable event bus (Flows default)
19. Hierarchical multi-agent with structured concurrency
20. LLM fallback chains

**Defer:**
- Kafka event bus adapter (defer until first user requests it)
- RabbitMQ adapter (same)
- MCP client SSE transport (stdio covers most cases; SSE is Phase 2 or 3)
- budget-breaker real adapter (stub until Tool 05 ships)

---

## Competitive Gap Summary

| Capability | LangChain4j | Spring AI | Koog (JetBrains) | kore (target) |
|------------|-------------|-----------|-------------------|----------------|
| Kotlin DSL | No | No | Yes (graph DSL) | Yes (agent DSL) |
| MCP client | Yes | Yes | Partial | Yes (full spec) |
| MCP server | No | No | No | Yes (differentiator) |
| Multi-agent | Via LangGraph4j | Partial | Yes (graph nodes) | Yes (coroutines) |
| Skills engine | No | Generic concept | No | Yes (YAML + patterns) |
| Token budget | No | No | Partial (cost control) | Yes (port + adapter) |
| kore-test module | No | Basic | No | Yes (record/replay) |
| Pluggable event bus | No | No | No | Yes (Flows default) |
| Built-in dashboard | No | No | No | Yes (HTMX) |
| OTel + Micrometer | Via integrations | Yes | OTel only | Both |
| Spring Boot starter | Yes | Yes (is Spring) | No | Yes |
| Fallback chains | Manual | Manual | Partial | DSL-native |

---

## Sources

- [LangChain4j Agents Documentation](https://docs.langchain4j.dev/tutorials/agents/) — HIGH confidence (official docs)
- [AI4JVM Framework Landscape](https://ai4jvm.com/) — MEDIUM confidence (community maintained)
- [Koog JetBrains Framework](https://www.jetbrains.com/koog/) — HIGH confidence (official product page)
- [JVM Framework Comparison: Koog vs LangChain4j vs Google ADK](https://medium.com/@shravyaboini/jvm-ai-agent-frameworks-choosing-between-koog-langchain4j-and-google-adk-f4d569bc96d1) — MEDIUM confidence (community analysis, April 2026)
- [Microsoft Agent Framework GA](https://devblogs.microsoft.com/foundry/introducing-microsoft-agent-framework-the-open-source-engine-for-agentic-ai-apps/) — HIGH confidence (official Microsoft blog)
- [MCP Standard in 2026](https://devstarsj.github.io/2026/03/18/model-context-protocol-mcp-complete-guide-2026/) — MEDIUM confidence (community, multiple corroborating sources)
- [A2A Protocol - 150+ organizations](https://www.prnewswire.com/news-releases/a2a-protocol-surpasses-150-organizations-lands-in-major-cloud-platforms-and-sees-enterprise-production-use-in-first-year-302737641.html) — HIGH confidence (official press release, April 2026)
- [Agent FinOps: Token Cost Governance](https://cordum.io/blog/agent-finops-token-cost-governance) — MEDIUM confidence (industry analysis)
- [AI Agent Stack in 2026](https://www.tensorlake.ai/blog/the-ai-agent-stack-in-2026-frameworks-runtimes-and-production-tools) — MEDIUM confidence (industry analysis)
- [AI Agents in 2026: Practical Architecture](https://andriifurmanets.com/blogs/ai-agents-2026-practical-architecture-tools-memory-evals-guardrails) — MEDIUM confidence (practitioner article)
- [Production Pitfalls of LangChain](https://medium.com/codetodeploy/production-pitfalls-of-langchain-nobody-warns-you-about-44a86e2df29e) — MEDIUM confidence (practitioner, Jan 2026)
- [Spring AI Agent Skills Pattern](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/) — HIGH confidence (official Spring blog)
- [SKILL.md Pattern](https://bibek-poudel.medium.com/the-skill-md-pattern-how-to-write-ai-agent-skills-that-actually-work-72a3169dd7ee) — MEDIUM confidence (Feb 2026 practitioner)
- [CheckAgent Testing Framework](https://pypi.org/project/checkagent/) — MEDIUM confidence (official package page)
- [Koog Brings Enterprise AI Agents to Java](https://blog.jetbrains.com/ai/2026/03/koog-comes-to-java/) — HIGH confidence (official JetBrains blog, March 2026)
