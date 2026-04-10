# Architecture Patterns: kore-runtime

**Domain:** Production Kotlin AI agent runtime
**Researched:** 2026-04-10
**Overall confidence:** HIGH (MCP protocol from official docs; coroutine/hexagonal patterns from verified Kotlin ecosystem sources; agent loop from multiple corroborating sources)

---

## Recommended Architecture

kore uses hexagonal (ports-and-adapters) architecture at the Gradle module level. Each Gradle module maps to either the domain core, a port interface set, or an adapter. Dependencies flow strictly inward — adapters depend on core, core depends on nothing external.

```
┌─────────────────────────────────────────────────────────────────────┐
│  External Entry Points                                               │
│  (Spring @RestController, HTMX controller, CLI runner)              │
└────────────────────────┬────────────────────────────────────────────┘
                         │ calls
┌────────────────────────▼────────────────────────────────────────────┐
│  kore-spring  (Spring Boot auto-configuration starter)              │
│  Wires all adapters, exposes @ConfigurationProperties               │
└────────────────────────┬────────────────────────────────────────────┘
                         │ depends on all
        ┌────────────────┼────────────────────┐
        │                │                    │
┌───────▼──────┐  ┌──────▼──────┐  ┌─────────▼──────┐
│  kore-mcp    │  │  kore-      │  │  kore-storage  │
│  (adapters:  │  │  observ-    │  │  (adapter:     │
│   MCP client │  │  ability    │  │   PostgreSQL   │
│   MCP server)│  │  (OTel,     │  │   + Flyway)    │
│              │  │   Micrometer│  │                │
└──────┬───────┘  └──────┬──────┘  └────────┬───────┘
       │                 │                  │
       └─────────────────┼──────────────────┘
                         │ all depend on
┌────────────────────────▼────────────────────────────────────────────┐
│  kore-core   (domain + application layer)                           │
│                                                                     │
│  Ports (interfaces only — no implementations):                      │
│    LLMBackend  BudgetEnforcer  EventBus  AuditLog                   │
│                                                                     │
│  Domain:                                                            │
│    AgentLoop  AgentDSL  AgentResult  ToolRegistry                   │
│    AgentContext  ConversationHistory                                 │
│                                                                     │
│  Implementations (no external deps):                                │
│    InMemoryAuditLog  InProcessEventBus (SharedFlow)                 │
│    InMemoryBudgetEnforcer (stub)                                    │
└─────────────────────────────────────────────────────────────────────┘
                         │
        ┌────────────────┼────────────────────┐
        │                │                    │
┌───────▼──────┐  ┌──────▼──────┐  ┌─────────▼──────┐
│  kore-skills │  │  kore-dash  │  │  kore-test     │
│  (YAML skill │  │  board      │  │  (testing       │
│   loader,    │  │  (Ktor       │  │   utilities,   │
│   pattern    │  │   embedded  │  │   MockLLM-     │
│   matcher)   │  │   HTMX)     │  │   Backend)     │
└──────────────┘  └─────────────┘  └────────────────┘
```

---

## Component Boundaries

### kore-core

**Responsibility:** The agent's brain. Contains:
- The `AgentLoop` — the coroutine that drives Thought → Action → Observation cycles
- The Kotlin DSL (`agent { }` builder)
- All domain types: `AgentResult`, `AgentContext`, `ConversationMessage`, `ToolCall`, `ToolResult`
- Port interfaces — `LLMBackend`, `BudgetEnforcer`, `EventBus`, `ToolProvider`, `AuditLog`
- Default in-memory/in-process implementations of all ports (stubs that work out of the box)
- Concurrency primitives: per-agent `CoroutineScope` with `SupervisorJob`, `Semaphore` for LLM rate limiting

**Communicates with:** Nothing external. All dependencies are either Kotlin stdlib, kotlinx.coroutines, or internal domain types.

**Why separate:** kore-core must be testable with zero infrastructure. `kore-test` consumers import only `kore-core`.

---

### kore-mcp

**Responsibility:** Full MCP protocol implementation — both client (kore connects to external MCP servers) and server (kore exposes agents as MCP tools).

**Internal structure:**
- `McpClientAdapter`: implements `ToolProvider` port from kore-core. Connects to external MCP servers via STDIO or Streamable HTTP. Maintains one `McpClient` per `McpServer` connection. Handles initialize → tools/list → tools/call lifecycle.
- `McpServerAdapter`: exposes running kore agents as MCP tools. Incoming `tools/call` → dispatches to `AgentLoop`. Supports both STDIO and HTTP+SSE transports.
- Transport layer: STDIO for local process servers; Streamable HTTP for remote servers. Both use JSON-RPC 2.0 over the same data layer.

**Communicates with:**
- `kore-core` (implements `ToolProvider`, calls `AgentLoop`)
- External MCP servers (over STDIO or HTTP)
- External MCP clients calling into kore's own server endpoint

**Why separate:** MCP has its own SDK dependency surface and connection management lifecycle. Isolating it means kore-core users who don't need MCP can exclude the module.

---

### kore-observability

**Responsibility:** Instrumentation adapters. Wraps kore-core operations with OpenTelemetry spans and Micrometer metrics.

**Internal structure:**
- `TracingAgentLoopDecorator`: wraps `AgentLoop` calls, opens a span per LLM call, per tool call, per skill activation. Propagates `OpenTelemetryContextElement` across coroutine boundaries — critical because coroutines switch threads on suspension, and OTel context lives in `ThreadLocal` by default.
- `MetricsAdapter`: implements hooks on `AgentLoop` events; registers Micrometer counters and timers for agent runs, token counts, error rates.

**Key pitfall addressed:** OTel context is `ThreadLocal`-based. When a coroutine suspends and resumes on a different thread, the context is lost unless wrapped in `OpenTelemetryContextElement` (implements `ThreadContextElement<Context>`). Every `launch` and `async` in an agent's scope must carry this element.

**Communicates with:** `kore-core` (decorates domain services), OTel SDK, Micrometer registry.

---

### kore-storage

**Responsibility:** PostgreSQL audit log. Persistent record of every agent run, LLM call, and tool call.

**Internal structure:**
- `PostgresAuditLogAdapter`: implements `AuditLog` port. Writes to `agent_runs`, `llm_calls`, `tool_calls` tables.
- Flyway for schema migrations. Migrations live in `kore-storage/src/main/resources/db/migration/`.
- Exposed (JetBrains ORM) or Spring Data R2DBC for reactive writes — R2DBC preferred to stay fully non-blocking with WebFlux.

**Communicates with:** `kore-core` (implements `AuditLog` port), PostgreSQL.

---

### kore-skills

**Responsibility:** Declarative skill system. YAML skill definitions describe activation patterns; the engine matches against agent context and injects skill prompts/tools automatically.

**Internal structure:**
- `SkillLoader`: reads YAML from classpath or filesystem. Parses into `Skill` domain objects.
- `SkillActivationEngine`: pattern-matches incoming task/context against skill activation criteria. Returns activated skills to `AgentLoop`.
- Skills declare: `name`, `description`, `activationPatterns`, `systemPromptFragment`, `additionalTools`.

**Communicates with:** `kore-core` (plugs in as a `SkillProvider`).

---

### kore-dashboard

**Responsibility:** HTMX admin UI embedded in-process via Ktor. Active agents, recent runs, cost summary.

**Internal structure:**
- Ktor embedded server (not Spring MVC — no additional Spring dependency).
- Server-rendered HTML with HTMX for partial updates. No frontend build step.
- Reads live data from `kore-core` event streams (subscribes to `EventBus`).

**Communicates with:** `kore-core` (reads agent state, subscribes to EventBus), `kore-storage` (queries recent runs).

---

### kore-spring

**Responsibility:** Spring Boot auto-configuration. Makes `kore-core` + all adapters work from `application.yml` with one starter dependency.

**Internal structure:**
- `@AutoConfiguration` class wiring all beans conditionally.
- `KoreProperties` — `@ConfigurationProperties(prefix = "kore")` covering LLM backend selection, budget limits, MCP server URLs, OTel toggle.
- Actuator endpoint for agent health.

**Communicates with:** All other modules (wires them together). Spring Boot context.

---

### kore-test

**Responsibility:** Test utilities for agent authors. Eliminates the need for real LLM API calls in unit tests.

**Internal structure:**
- `MockLLMBackend`: implements `LLMBackend` port. Configurable response sequences. Assertion helpers for verifying tool calls made.
- `AgentSessionRecorder`: records a live agent session to a JSON fixture.
- `AgentSessionReplayer`: replays a recorded session deterministically — feeds pre-recorded LLM responses, verifies tool calls match.

**Communicates with:** `kore-core` only (implements `LLMBackend`). No infrastructure dependencies.

---

## Data Flow: The Agent Loop

The agent loop is the critical path. Every other component plugs into it.

```
Task Input
    │
    ▼
AgentLoop.run(task, context)   ← coroutine launched with SupervisorJob
    │
    ├─ SkillActivationEngine.match(context)   ← enriches system prompt + tools
    │
    ├─ BudgetEnforcer.checkBudget()           ← returns AgentResult.BudgetExceeded early
    │
    ├─── LLM CALL ────────────────────────────────────────────────────────────┐
    │    LLMBackend.complete(messages, tools)                                  │
    │    [Claude / GPT / Ollama / Gemini adapter]                              │
    │    ← AgentResult.LLMError on failure → retry with backoff, then fallback │
    └─────────────────────────────────────────────────────────────────────────┘
          │
          ▼
    LLM response: either FinalAnswer or ToolCalls
          │
          ├── FinalAnswer → emit Success → loop ends
          │
          └── ToolCalls → for each tool call (can run in parallel with async):
                │
                ├── ToolRegistry.dispatch(toolCall)
                │     ├── McpToolProvider → external MCP server call
                │     ├── LocalTool → direct invocation
                │     └── AgentTool → spawn child agent (structured concurrency)
                │
                ├── ToolResult → append to ConversationHistory
                │
                └── loop back to LLM CALL with updated history
          │
    AuditLog.record(agentRun)              ← async, does not block loop
    EventBus.emit(AgentEvent)              ← async, notifies dashboard / metrics
    OpenTelemetry span closed
```

**Key invariant:** The loop never throws. All failures are `AgentResult` sealed class variants. The caller always gets a typed result.

```kotlin
sealed class AgentResult {
    data class Success(val output: String, val tokenUsage: TokenUsage) : AgentResult()
    data class BudgetExceeded(val spent: TokenBudget, val limit: TokenBudget) : AgentResult()
    data class ToolError(val toolName: String, val cause: Throwable) : AgentResult()
    data class LLMError(val backend: String, val cause: Throwable) : AgentResult()
    data class Cancelled(val reason: String) : AgentResult()
}
```

---

## Data Flow: MCP Client

```
AgentLoop wants to call a tool registered from MCP
    │
    ▼
ToolRegistry.dispatch("github_create_issue", args)
    │
    ▼
McpClientAdapter.callTool("github_create_issue", args)
    │
    ├── looks up which McpClient owns "github_create_issue"
    │   (populated during tools/list at connection init)
    │
    ▼
McpClient.call("tools/call", { name: "github_create_issue", arguments: args })
    │
    [JSON-RPC 2.0 over STDIO or HTTP+SSE transport]
    │
    ▼
MCP Server response: ToolResult content
    │
    ▼
ToolResult back to AgentLoop
```

---

## Data Flow: Event Bus

The event bus is purely in-process. `kore-core` defines the `EventBus` port:

```kotlin
interface EventBus {
    suspend fun emit(event: AgentEvent)
    fun subscribe(): Flow<AgentEvent>
}
```

Default implementation uses `MutableSharedFlow(extraBufferCapacity = 64)`. The dashboard and observability module subscribe. Kafka adapter lives in a separate optional module — it implements the same `EventBus` interface and is selected via Spring auto-configuration conditional.

```
AgentLoop
    │ emit(AgentEvent.LLMCallCompleted(...))
    ▼
MutableSharedFlow<AgentEvent>
    │
    ├── TracingObserver.subscribe()   → closes OTel span, records metrics
    ├── DashboardFeed.subscribe()     → pushes HTMX partial update via SSE
    └── KafkaEventBusAdapter          → (opt-in) publishes to Kafka topic
        (only wired when kore.events.kafka.enabled=true)
```

---

## Data Flow: Hierarchical Agents

Parent agents spawn child agents via `AgentTool`. Structured concurrency ensures child lifetimes are bounded by the parent:

```
ParentAgentLoop (CoroutineScope with SupervisorJob)
    │
    ├── calls ToolRegistry.dispatch("research_agent", task)
    │
    ▼
AgentTool.invoke(task)
    │ launch { childAgentLoop.run(task) }  ← child launched in parent's scope
    │
    ├── ChildAgentLoop runs independently
    ├── Parent waits for child result (async/await)
    ├── Parent cancelled → all children cancelled (structured concurrency)
    └── Child failed → SupervisorJob → parent continues (sibling isolation)
```

---

## Component Communication Matrix

| From | To | Mechanism | Direction |
|------|----|-----------|-----------|
| kore-spring | kore-core | Bean wiring | Config time |
| kore-spring | kore-mcp | Bean wiring | Config time |
| kore-spring | kore-observability | Bean wiring | Config time |
| kore-spring | kore-storage | Bean wiring | Config time |
| kore-spring | kore-skills | Bean wiring | Config time |
| kore-core (AgentLoop) | LLMBackend port | Suspend function call | Runtime |
| kore-core (AgentLoop) | ToolProvider port | Suspend function call | Runtime |
| kore-core (AgentLoop) | EventBus port | SharedFlow emit | Runtime async |
| kore-core (AgentLoop) | AuditLog port | Suspend function call | Runtime |
| kore-core (AgentLoop) | BudgetEnforcer port | Suspend function call | Runtime |
| kore-mcp | kore-core (ToolProvider port) | Implements port | Inward |
| kore-observability | kore-core | Decorates AgentLoop | Inward |
| kore-storage | kore-core (AuditLog port) | Implements port | Inward |
| kore-skills | kore-core (SkillProvider port) | Implements port | Inward |
| kore-dashboard | kore-core (EventBus) | Flow subscription | Inward |
| kore-test | kore-core (LLMBackend port) | Implements port | Inward |

**Dependency direction rule:** Every arrow points inward toward kore-core. kore-core has zero knowledge of its adapters.

---

## Concurrency Model

### Per-Agent CoroutineScope

Each running agent instance owns a `CoroutineScope` with a `SupervisorJob` + `Dispatchers.Default`. The `SupervisorJob` is critical: a tool call failing does not cancel the entire agent. The loop catches tool errors and converts them to `AgentResult.ToolError`.

```kotlin
class AgentRunner(
    private val loop: AgentLoop,
    private val semaphore: Semaphore  // shared per LLM backend
) {
    private val scope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.Default +
        OpenTelemetryContextElement()  // propagate OTel across thread switches
    )

    fun run(task: AgentTask): Deferred<AgentResult> =
        scope.async {
            semaphore.withPermit {  // max N concurrent LLM calls per backend
                loop.run(task)
            }
        }
}
```

### Semaphore-Based LLM Rate Limiting

Each `LLMBackend` gets a configurable `Semaphore`. Default: 10 concurrent calls for cloud backends (Anthropic, OpenAI), 2 for local Ollama. Configured via `kore.backends.claude.maxConcurrency=10`.

### Parallel Tool Calls

When an LLM response contains multiple tool calls, they run concurrently inside `coroutineScope { }`:

```kotlin
val results: List<ToolResult> = coroutineScope {
    toolCalls.map { call ->
        async { toolRegistry.dispatch(call) }
    }.awaitAll()
}
```

---

## Suggested Build Order

The dependency graph determines order. Build innermost first.

### Phase 1: kore-core + kore-mcp (Month 4)

Build these together because the agent loop and MCP client are validated as a pair. The agent loop without tools is untestable at the domain level. With MockLLMBackend from kore-test, you can run the full loop immediately.

```
kore-core ──→ kore-test (simultaneously — test utilities built alongside core)
kore-core ──→ kore-mcp (depends on kore-core ports)
```

Deliverable: agent loop runs, calls Claude via an adapter, calls an MCP tool, returns `AgentResult.Success`.

### Phase 2: kore-observability + kore-storage (Month 5)

These are pure adapters — they implement ports defined in kore-core. No new agent loop logic. Both can be built in parallel.

```
kore-core ──→ kore-observability (implements AuditLog + hooks into EventBus)
kore-core ──→ kore-storage       (implements AuditLog + PostgreSQL)
```

Deliverable: every LLM call is a named OTel span. Agent runs persist to PostgreSQL.

### Phase 3: kore-spring + kore-dashboard + kore-skills (Month 6)

kore-spring wires everything. kore-dashboard reads from EventBus (kore-core) and kore-storage. kore-skills adds the activation engine.

```
kore-core + all adapters ──→ kore-spring
kore-core (EventBus)     ──→ kore-dashboard
kore-core                ──→ kore-skills
```

Deliverable: `implementation("dev.unityinflow:kore-spring-boot-starter:0.1.0")` in a Spring Boot project — agent runs with zero additional wiring.

### Full Build Order Summary

```
1. kore-core          ← no external deps, domain types + port interfaces
2. kore-test          ← depends on kore-core only (build with core)
3. kore-mcp           ← depends on kore-core
4. kore-observability ← depends on kore-core
5. kore-storage       ← depends on kore-core
6. kore-skills        ← depends on kore-core
7. kore-dashboard     ← depends on kore-core, kore-storage
8. kore-spring        ← depends on all above (integration glue)
```

---

## Patterns to Follow

### Pattern 1: Port Interface in kore-core, Implementation in Adapter Module

```kotlin
// In kore-core — zero external dependencies
interface LLMBackend {
    val name: String
    suspend fun complete(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig
    ): LLMResponse
}

// In kore-mcp — depends on kore-core + MCP SDK
class ClaudeAnthropicBackend(
    private val client: AnthropicClient,
    private val semaphore: Semaphore
) : LLMBackend {
    override val name = "claude"
    override suspend fun complete(...): LLMResponse = semaphore.withPermit {
        // implementation
    }
}
```

### Pattern 2: AgentResult Sealed Class — No Exceptions Escaping the Loop

Never throw from `AgentLoop.run()`. All expected failures are modeled as `AgentResult` subtypes. The loop catches internal exceptions and converts:

```kotlin
} catch (e: LLMRateLimitException) {
    AgentResult.LLMError(backend = llmBackend.name, cause = e)
} catch (e: ToolTimeoutException) {
    AgentResult.ToolError(toolName = currentTool, cause = e)
} catch (e: CancellationException) {
    throw e  // always rethrow CancellationException — structured concurrency requires it
}
```

### Pattern 3: LLM Retry + Fallback Chain

```kotlin
class ResilientLLMBackend(
    private val primary: LLMBackend,
    private val fallbacks: List<LLMBackend>,
    private val retryPolicy: RetryPolicy
) : LLMBackend {
    override suspend fun complete(...): LLMResponse {
        return retryWithFallback(listOf(primary) + fallbacks) { backend ->
            backend.complete(messages, tools, config)
        }
    }
}
```

### Pattern 4: OpenTelemetry Context Propagation Across Coroutines

Every `CoroutineScope` managing agent work must include `OpenTelemetryContextElement`. This is the only reliable way to preserve OTel context across thread switches when coroutines suspend.

```kotlin
val agentScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + OpenTelemetryContextElement()
)
```

### Pattern 5: SharedFlow EventBus as Default

```kotlin
class InProcessEventBus : EventBus {
    private val _events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override suspend fun emit(event: AgentEvent) { _events.emit(event) }
    override fun subscribe(): Flow<AgentEvent> = _events.asSharedFlow()
}
```

`DROP_OLDEST` backpressure strategy is intentional: dashboard lagging behind should not backpressure the agent loop.

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Adapter Code in kore-core

**What goes wrong:** Importing `AnthropicClient` or `org.springframework.*` in kore-core.
**Why bad:** kore-core becomes unrunnable without external JARs. kore-test stops being lightweight.
**Instead:** Keep kore-core's only dependencies as `kotlinx.coroutines-core` + Kotlin stdlib. All framework imports live in adapter modules.

### Anti-Pattern 2: Exceptions as Control Flow from AgentLoop

**What goes wrong:** `throw BudgetExceededException()` escaping the loop.
**Why bad:** Callers must know exception types; coroutine cancellation interacts badly with non-`CancellationException` throws; composability breaks.
**Instead:** Return `AgentResult.BudgetExceeded`. Throw only `CancellationException` (and always re-throw it).

### Anti-Pattern 3: Blocking Calls on Coroutine Dispatchers

**What goes wrong:** `Thread.sleep()`, JDBC blocking calls, or `Response.execute()` on `Dispatchers.Default`.
**Why bad:** Starves the coroutine thread pool. Agent throughput collapses under load.
**Instead:** All blocking I/O inside `withContext(Dispatchers.IO) { ... }`. JDBC via R2DBC or `withContext(Dispatchers.IO)` explicitly.

### Anti-Pattern 4: GlobalScope for Agent Coroutines

**What goes wrong:** `GlobalScope.launch { agentLoop.run(task) }`.
**Why bad:** No structured concurrency — agents leak on application shutdown. Cancellation doesn't propagate. No parent-child relationship for hierarchical agents.
**Instead:** All agents run in scopes managed by `AgentRunner` which is a Spring-managed singleton with a lifecycle-aware scope.

### Anti-Pattern 5: MutableSharedFlow With Zero Buffer in EventBus

**What goes wrong:** `MutableSharedFlow<AgentEvent>()` with default `extraBufferCapacity = 0`.
**Why bad:** `emit()` suspends if no collectors are active or collectors are slow. This backpressures the agent loop — a dashboard bug stalls all agents.
**Instead:** `extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST`.

---

## Scalability Considerations

| Concern | Single JVM (default) | Scaled-out (opt-in) |
|---------|---------------------|---------------------|
| LLM concurrency | Semaphore per backend | Same — semaphore is per-node |
| Event distribution | SharedFlow in-process | Kafka EventBus adapter |
| Audit log | PostgreSQL (single writer) | Same — R2DBC connection pool |
| Agent state | In-memory per JVM | External session store (kore-storage extension) |
| MCP server routing | Single endpoint | Load balancer in front of stateless instances |

---

## Sources

- MCP Architecture (official): [https://modelcontextprotocol.io/docs/learn/architecture](https://modelcontextprotocol.io/docs/learn/architecture) — HIGH confidence
- MCP Transport (STDIO vs Streamable HTTP): same official source — HIGH confidence
- Hexagonal Architecture Kotlin/Gradle reference: [https://github.com/dustinsand/hex-arch-kotlin-spring-boot](https://github.com/dustinsand/hex-arch-kotlin-spring-boot) — HIGH confidence
- Kotlin SupervisorJob and structured concurrency: [https://medium.com/@adityamishra2217/understanding-supervisorscope-supervisorjob-coroutinescope-and-job-in-kotlin](https://medium.com/@adityamishra2217/understanding-supervisorscope-supervisorjob-coroutinescope-and-job-in-kotlin-a-deep-dive-into-bcd0b80f8c6f) — HIGH confidence
- Semaphore-based LLM rate limiting: [https://blog.shreyaspatil.dev/leveraging-the-semaphore-concept-in-coroutines-to-limit-the-parallelism](https://blog.shreyaspatil.dev/leveraging-the-semaphore-concept-in-coroutines-to-limit-the-parallelism) — HIGH confidence
- OTel context propagation across coroutines: [https://oneuptime.com/blog/post/2026-02-06-opentelemetry-kotlin-coroutines-spring-boot/view](https://oneuptime.com/blog/post/2026-02-06-opentelemetry-kotlin-coroutines-spring-boot/view) — HIGH confidence (February 2026 source, current)
- Orchestrator-worker agent patterns: [https://arize.com/blog/orchestrator-worker-agents-a-practical-comparison-of-common-agent-frameworks/](https://arize.com/blog/orchestrator-worker-agents-a-practical-comparison-of-common-agent-frameworks/) — MEDIUM confidence (multi-framework comparison, language-agnostic)
- ReAct agent loop: [https://www.letta.com/blog/letta-v1-agent](https://www.letta.com/blog/letta-v1-agent) — MEDIUM confidence
- Spring WebFlux Kotlin coroutines: [https://spring.io/blog/2019/04/12/going-reactive-with-spring-coroutines-and-kotlin-flow/](https://spring.io/blog/2019/04/12/going-reactive-with-spring-coroutines-and-kotlin-flow/) — HIGH confidence (Spring official)
- SharedFlow event bus: [https://elizarov.medium.com/shared-flows-broadcast-channels-899b675e805c](https://elizarov.medium.com/shared-flows-broadcast-channels-899b675e805c) — HIGH confidence (Roman Elizarov, Kotlin coroutines lead)
- Koog (JetBrains Kotlin agent framework, comparison reference): [https://blog.jetbrains.com/kotlin/2025/09/the-kotlin-ai-stack-build-ai-agents-with-koog-code-smarter-with-junie-and-more/](https://blog.jetbrains.com/kotlin/2025/09/the-kotlin-ai-stack-build-ai-agents-with-koog-code-smarter-with-junie-and-more/) — MEDIUM confidence (JetBrains official but limited architectural detail)
