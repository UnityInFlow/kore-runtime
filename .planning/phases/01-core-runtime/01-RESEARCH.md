# Phase 1: Core Runtime - Research

**Researched:** 2026-04-10
**Domain:** Kotlin coroutine-based AI agent runtime — agent loop, DSL, LLM backends, MCP protocol, budget enforcement, testing utilities
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**DSL Surface Area**
- D-01: Full lifecycle DSL — one `agent { }` block defines everything: model, tools, budget, retries, result handling, lifecycle hooks, child agent spawning
- D-02: Use `@DslMarker` annotation to prevent accidental nesting mistakes
- D-03: The DSL is the primary API. No separate builder pattern for v0.1 — DSL covers all cases

**LLM Interface Shape**
- D-04: Unified Flow-based interface — all LLM responses are `Flow<LLMChunk>`. Non-streaming backends emit one text chunk + usage + done. Single `call()` method on `LLMBackend`
- D-05: `LLMChunk` sealed class: `Text`, `ToolCall`, `Usage`, `Done`
- D-06: `LLMBackend` is stateless — receives full message list each call. Agent loop accumulates messages
- D-07: Design `LLMBackend` interface against all 4 providers simultaneously — never shape it around one provider
- D-08: Claude and GPT use official SDKs (Anthropic Java SDK 2.20.0, OpenAI Java SDK 4.30.0). Ollama and Gemini use LangChain4j as HTTP transport only

**MCP Client Lifecycle**
- D-09: MCP servers configured in `application.yml` as a named list with transport type (stdio/SSE), command/URL, and environment variables
- D-10: Lazy connection — connect on first tool call, not at startup
- D-11: Auto-reconnect with exponential backoff on failure. Return `ToolError` only if reconnection exhausted
- D-12: MCP initialize handshake (capability negotiation) completes before tools/list

**MCP Server**
- D-13: kore agents exposed as MCP tools — external MCP clients can invoke kore agents
- D-14: MCP server transport: stdio + Ktor-based SSE (from MCP Kotlin SDK 0.11.1)

**Module Boundaries**
- D-15: kore-core has zero external dependencies except kotlinx.coroutines + stdlib
- D-16: kore-test built alongside kore-core (not after) — agent loop cannot be validated without MockLLMBackend
- D-17: MCP protocol implementation in kore-mcp module using official MCP Kotlin SDK 0.11.1

**Agent Execution Model**
- D-18: Coroutine per agent with semaphore-based LLM rate limiting (configurable per backend)
- D-19: Hierarchical agents: parent spawns children with structured concurrency. Cancel parent = cancel children
- D-20: LLM retry with configurable exponential backoff + fallback chain (`claude() fallbackTo gpt() fallbackTo ollama()`)
- D-21: Agent loop never throws — all failures are AgentResult sealed class variants. Only CancellationException is re-thrown
- D-22: Parallel tool dispatch: `coroutineScope { toolCalls.map { async { dispatch(it) } }.awaitAll() }`

**Budget Enforcement**
- D-23: BudgetEnforcer port interface in kore-core
- D-24: InMemoryBudgetEnforcer stub — tracks tokens per agent, returns BudgetExceeded when limit reached
- D-25: Budget check before each LLM call in the agent loop

**Testing (kore-test)**
- D-26: MockLLMBackend for scripted responses in unit tests
- D-27: MockMcpServer for scripted tool/resource responses
- D-28: Session recording mode — capture real LLM interactions to file
- D-29: Session replay mode — deterministic replay in CI without API keys

### Claude's Discretion
- Message history management approach (agent loop owns it vs separate Conversation object)
- Exact kore-core module contents (interfaces + loop together, or interfaces-only with separate engine module)
- InMemoryBudgetEnforcer internal data structures
- MockLLMBackend API details (fluent builder vs DSL)

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope

</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CORE-01 | Agent executes a coroutine-based loop: task intake -> LLM call -> tool use -> result -> loop | Agent loop data flow in ARCHITECTURE.md; coroutine scope pattern with SupervisorJob |
| CORE-02 | AgentResult sealed class hierarchy: Success | BudgetExceeded | ToolError | LLMError | Cancelled | Sealed class pattern defined; exact variant types documented |
| CORE-03 | Agent defined via Kotlin DSL: `agent("name") { model = claude(); tools = mcp("server") }` | `@DslMarker` pattern; Kotlin type-safe builders documented |
| CORE-04 | Each agent runs as a coroutine with semaphore-based LLM rate limiting per backend | Semaphore pattern per backend; `Semaphore.withPermit { }` usage; Pitfall 11 prevention |
| CORE-05 | Parent agent can spawn child agents with structured concurrency (cancel parent cancels children) | `supervisorScope { }` pattern; child launch in parent scope; Pitfall 2 prevention |
| CORE-06 | LLM retry with configurable exponential backoff | `ResilientLLMBackend` wrapper pattern; retry outside semaphore scope |
| CORE-07 | LLM fallback chain: `claude() fallbackTo gpt() fallbackTo ollama()` | Infix function pattern; `ResilientLLMBackend` with primary + fallbacks list |
| LLM-01 | Claude adapter via Anthropic Java SDK (tool calling, streaming) | Anthropic Java SDK 2.20.0; blocking I/O in `withContext(Dispatchers.IO)` |
| LLM-02 | GPT adapter via OpenAI Java SDK (tool calling, streaming) | OpenAI Java SDK 4.30.0; same pattern as Claude adapter |
| LLM-03 | Ollama adapter via LangChain4j transport (local inference) | LangChain4j 1.0.x `langchain4j-ollama`; unlimited semaphore for local |
| LLM-04 | Gemini adapter via LangChain4j transport (tool calling) | LangChain4j 1.0.x `langchain4j-google-ai-gemini` |
| LLM-05 | LLMBackend port interface designed against all four providers simultaneously | Flow-based interface (D-04, D-05); canonical message format; Pitfall 5 prevention |
| MCP-01 | MCP client: call tools on MCP servers over stdio transport | MCP Kotlin SDK 0.11.1 `StdioClientTransport`; stdout contamination prevention (Pitfall 4) |
| MCP-02 | MCP client: call tools on MCP servers over SSE transport | MCP Kotlin SDK 0.11.1 `SseClientTransport`; `Last-Event-ID` resumability (Pitfall 6) |
| MCP-03 | MCP client: read resources from MCP servers | MCP SDK `resources/read` call; same transport as tools |
| MCP-04 | MCP client: use prompt templates from MCP servers | MCP SDK `prompts/get` call; capability negotiated at init (D-12) |
| MCP-05 | MCP server: expose kore agents as callable MCP tools to external clients | MCP Kotlin SDK `StdioServerTransport` + Ktor SSE server (D-14) |
| MCP-06 | MCP capability negotiation (initialize handshake) before tool/resource listing | MCP SDK handles initialize handshake; D-12 mandates completion before tools/list |
| BUDG-01 | BudgetEnforcer port interface for token budget enforcement | Port interface in kore-core; zero external deps; suspend function signature |
| BUDG-02 | InMemoryBudgetEnforcer stub implementation with configurable token limits | Concurrent map per agent ID; atomic token accumulation |
| BUDG-03 | Token counting on every LLM call (input + output tokens tracked) | `LLMChunk.Usage` carries token counts; accumulate in agent loop |
| BUDG-04 | Agent loop checks budget before each LLM call, returns BudgetExceeded if exceeded | Pre-call `BudgetEnforcer.checkBudget()` → early return with `AgentResult.BudgetExceeded` |
| TEST-01 | MockLLMBackend for scripted LLM responses in unit tests | Implements `LLMBackend` port; scripted `Flow<LLMChunk>` sequence |
| TEST-02 | MockMcpServer for scripted tool/resource responses in tests | Implements `ToolProvider` port; scripted response map |
| TEST-03 | Session recording mode: capture real LLM interactions to file | JSON serialization of `LLMChunk` sequences; file sink in `MockLLMBackend` |
| TEST-04 | Session replay mode: deterministic replay of recorded sessions in CI | File source in `MockLLMBackend`; `kotlinx-coroutines-test` for `runTest` |

</phase_requirements>

---

## Summary

Phase 1 builds the innermost two modules of an 8-module hexagonal architecture: `kore-core` (domain types, port interfaces, agent loop) and `kore-mcp` (MCP client and server), plus `kore-test` (MockLLMBackend, MockMcpServer, session record/replay). All key decisions are locked in CONTEXT.md — the key technical question is how to implement them correctly, not which path to take.

The dominant technical challenge is the `LLMBackend` interface design. Decision D-04 specifies a `Flow<LLMChunk>` interface where `LLMChunk` is a sealed class (`Text | ToolCall | Usage | Done`). Both the Anthropic and OpenAI Java SDKs return streaming responses but with provider-specific types — the adapter layer must translate these to the canonical `LLMChunk` format before the chunks reach the agent loop. The risk of inadvertently shaping the interface around Claude's API (Pitfall 5) is the most consequential design risk in Phase 1.

The MCP implementation uses the official MCP Kotlin SDK 0.11.1 which handles all JSON-RPC framing. Two critical operational concerns require explicit attention: (1) stdout contamination for stdio transport (Pitfall 4 — any JVM logging to stdout corrupts the MCP message stream) and (2) SSE disconnection semantics (Pitfall 6 — disconnect must not cancel in-flight operations). Both have well-documented mitigations in PITFALLS.md. The environment has Java 24 (not 21 LTS as required) — the Gradle toolchain declaration must pin JVM 21, which Gradle 9.1.0 supports correctly.

**Primary recommendation:** Build in strict order — `kore-core` port interfaces first (especially `LLMBackend` with all four provider docs open), then `kore-test` alongside core so the agent loop is testable from wave 1, then `kore-mcp`. Never let the agent loop compile without a stub `LLMBackend` implementation to exercise it.

---

## Project Constraints (from CLAUDE.md)

Directives extracted from `./CLAUDE.md` and parent ecosystem `CLAUDE.md` that constrain all implementation decisions:

| Directive | Source | Enforcement Point |
|-----------|--------|-------------------|
| Kotlin 2.0+, JVM target 21 | CLAUDE.md | `build.gradle.kts` `jvmToolchain(21)` |
| Gradle Kotlin DSL only, never Groovy | CLAUDE.md | Project scaffold |
| JUnit 5 + Kotest matchers (not Kotest FunSpec) | CLAUDE.md | Test files |
| Coroutines everywhere — never `Thread.sleep()` or raw threads | CLAUDE.md | Code review checklist |
| Immutable data classes — `val` only, no `var` | CLAUDE.md | All source files |
| Sealed classes for domain modelling | CLAUDE.md | AgentResult, LLMChunk, etc. |
| `Result<T>` or sealed classes instead of exceptions for expected failures | CLAUDE.md | AgentLoop.run() signature |
| `ktlint` before every commit | CLAUDE.md | CI + pre-commit hook |
| Group `dev.unityinflow` | CLAUDE.md | `build.gradle.kts` |
| Maven Central via Sonatype for library modules | CLAUDE.md | Publishing config |
| Test coverage >80% on core logic before release | CLAUDE.md | CI gate |
| No wildcard tool permissions | ecosystem CLAUDE.md | N/A — config files |
| Secrets never committed — env vars only | ecosystem CLAUDE.md | All files |
| KDoc on all public APIs | CLAUDE.md | All public interfaces/classes |
| No `!!` without comment | CLAUDE.md | Code review |
| Self-hosted runners `[arc-runner-unityinflow]` — never `ubuntu-latest` | CLAUDE.md | CI workflow |

---

## Standard Stack

### Core (Phase 1 modules: kore-core, kore-mcp, kore-test)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin | 2.3.0 | Primary language | Coroutines, sealed classes, DSL, data classes. 2.3 is latest stable. Project constraint: 2.0+ |
| JVM toolchain | 21 (LTS) | JVM target | Spring Boot 4 requires 17+, recommends 21. LTS. Virtual threads available. |
| Gradle (Kotlin DSL) | 9.4.1 | Build system | Latest stable. Kotlin DSL is default. Version catalogs native. Project constraint: never Groovy. |
| kotlinx-coroutines-core | 1.10.2 | Agent loop, structured concurrency, Flow, Semaphore, Channel | Stable companion to Kotlin 2.1. All primitives needed for the agent loop. |
| kotlinx-serialization-json | 1.8.0 | JSON for MCP messages, session recording | Compile-time safe, no reflection, Spring Boot 4 first-class. |
| anthropic-java SDK | 2.20.0 | Claude LLM adapter (official) | Only official JVM Claude SDK. Kotlin-compatible. Decision D-08. |
| openai-java SDK | 4.30.0 | GPT LLM adapter (official) | Only official JVM OpenAI SDK. Written partly in Kotlin. Decision D-08. |
| langchain4j-ollama | 1.0.x | Ollama adapter transport | GA since May 2025. Thinner than Spring AI — no framework coupling. Decision D-08. |
| langchain4j-google-ai-gemini | 1.0.x | Gemini adapter transport | Same GA release. Same pattern as Ollama adapter. Decision D-08. |
| io.modelcontextprotocol:kotlin-sdk | 0.11.1 | MCP client + server (official) | Only coroutine-native official JVM MCP SDK. Supports all required transports. Decision D-17. |

### Testing

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| JUnit 5 (junit-jupiter) | 5.12.0 | Test runner | All tests — required by project constraints |
| Kotest assertions (kotest-assertions-core) | 6.1.11 | Fluent matchers (`shouldBe`, `shouldContain`) | All assertions — do NOT use Kotest FunSpec/StringSpec as test framework |
| MockK | 1.14.0 | Kotlin-aware mocking | Any mock needed beyond `MockLLMBackend`; `coEvery`/`coVerify` for coroutines |
| kotlinx-coroutines-test | 1.10.2 | `runTest`, `TestCoroutineScheduler` | All suspend function tests and flow tests |

### Code Quality

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| ktlint (via kotlinter-gradle) | 1.5.0 | Formatting + style | Every commit — project constraint |
| kotlinter-gradle | 5.x | Gradle ktlint plugin | Root `build.gradle.kts` — `id("org.jmailen.kotlinter")` |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Official Anthropic/OpenAI SDKs | LangChain4j for all 4 providers | LangChain4j for Claude/GPT leaks `ChatLanguageModel` into port interface; decision D-08 locks in official SDKs |
| LangChain4j for Ollama/Gemini | Spring AI | Spring AI's `ChatClient` abstraction leaks Spring types into LLMBackend port; LangChain4j is thinner |
| MCP Kotlin SDK | io.modelcontextprotocol.sdk:mcp (Java) | Java SDK is Reactor-based — introduces second reactive model alongside coroutines |
| kotlinx.serialization | Jackson | Jackson requires reflection; kotlinx.serialization is compile-time safe and Spring Boot 4 first-class |
| MockK | Mockito | Mockito has poor Kotlin ergonomics — no native coroutine support without plugins |

### Installation — Gradle Coordinates

```kotlin
// gradle/libs.versions.toml
[versions]
kotlin = "2.3.0"
coroutines = "1.10.2"
serialization = "1.8.0"
anthropic = "2.20.0"
openai = "4.30.0"
langchain4j = "1.0.1"
mcp-kotlin-sdk = "0.11.1"
junit5 = "5.12.0"
kotest = "6.1.11"
mockk = "1.14.0"

[libraries]
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
anthropic-java = { module = "com.anthropic:anthropic-java", version.ref = "anthropic" }
openai-java = { module = "com.openai:openai-java", version.ref = "openai" }
langchain4j-ollama = { module = "dev.langchain4j:langchain4j-ollama", version.ref = "langchain4j" }
langchain4j-gemini = { module = "dev.langchain4j:langchain4j-google-ai-gemini", version.ref = "langchain4j" }
mcp-kotlin-sdk = { module = "io.modelcontextprotocol:kotlin-sdk", version.ref = "mcp-kotlin-sdk" }
junit5 = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit5" }
kotest-assertions = { module = "io.kotest:kotest-assertions-core", version.ref = "kotest" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }

// kore-core/build.gradle.kts
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    // NO external deps beyond these — decision D-15
}

// kore-mcp/build.gradle.kts
dependencies {
    implementation(project(":kore-core"))
    implementation(libs.mcp.kotlin.sdk)
    // Ktor is a transitive dep of MCP Kotlin SDK (Ktor-based SSE server)
}

// kore-test/build.gradle.kts
dependencies {
    api(project(":kore-core"))
    api(libs.mockk)
    api(libs.coroutines.test)
    api(libs.kotest.assertions)
}

// kore-llm-claude/build.gradle.kts (or inline in kore-mcp as adapter)
dependencies {
    implementation(project(":kore-core"))
    implementation(libs.anthropic.java)
}

// kore-llm-gpt/build.gradle.kts
dependencies {
    implementation(project(":kore-core"))
    implementation(libs.openai.java)
}

// kore-llm-ollama/build.gradle.kts
dependencies {
    implementation(project(":kore-core"))
    implementation(libs.langchain4j.ollama)
}

// kore-llm-gemini/build.gradle.kts
dependencies {
    implementation(project(":kore-core"))
    implementation(libs.langchain4j.gemini)
}
```

**Note on LLM adapter module placement:** The 8-module spec does not explicitly list separate `kore-llm-*` modules. CONTEXT.md D-15 requires kore-core to have zero external deps. The LLM adapters (which need Anthropic/OpenAI SDKs) cannot live in kore-core. They belong in either (a) separate `kore-llm-claude`, `kore-llm-gpt` etc. modules, or (b) collapsed into `kore-mcp` alongside the MCP adapters. The planner must choose — both are architecturally valid. Option (a) is cleaner for hexagonal purity; option (b) reduces module count. [ASSUMED] based on hexagonal pattern; user should confirm module names.

---

## Architecture Patterns

### Recommended Project Structure

```
kore/
├── gradle/
│   └── libs.versions.toml          # ALL version strings live here only
├── kore-core/
│   └── src/main/kotlin/dev/unityinflow/kore/core/
│       ├── AgentResult.kt          # sealed class hierarchy
│       ├── AgentTask.kt            # input data class
│       ├── AgentContext.kt         # execution context (messages, tools, budget)
│       ├── ConversationMessage.kt  # canonical message format
│       ├── ToolDefinition.kt       # tool descriptor used by LLM
│       ├── ToolCall.kt / ToolResult.kt
│       ├── LLMChunk.kt             # sealed: Text | ToolCall | Usage | Done (D-05)
│       ├── loop/
│       │   └── AgentLoop.kt        # core loop implementation
│       ├── dsl/
│       │   ├── KoreDsl.kt          # @DslMarker annotation (D-02)
│       │   ├── AgentBuilder.kt     # DSL receiver
│       │   └── agent.kt            # top-level agent { } function
│       └── ports/
│           ├── LLMBackend.kt       # interface: call() -> Flow<LLMChunk>
│           ├── BudgetEnforcer.kt   # interface: checkBudget(), record()
│           ├── ToolProvider.kt     # interface: listTools(), callTool()
│           ├── EventBus.kt         # interface: emit(), subscribe()
│           └── AuditLog.kt         # interface: record() (Phase 2 impl)
├── kore-mcp/
│   └── src/main/kotlin/dev/unityinflow/kore/mcp/
│       ├── client/
│       │   ├── McpClientAdapter.kt  # implements ToolProvider
│       │   └── McpConnectionPool.kt # lazy connect, auto-reconnect (D-10, D-11)
│       └── server/
│           └── McpServerAdapter.kt  # exposes agents as MCP tools (D-13, D-14)
├── kore-test/
│   └── src/main/kotlin/dev/unityinflow/kore/test/
│       ├── MockLLMBackend.kt        # implements LLMBackend, scripted responses (D-26)
│       ├── MockMcpServer.kt         # scripted tool/resource responses (D-27)
│       ├── AgentSessionRecorder.kt  # capture to file (D-28)
│       └── AgentSessionReplayer.kt  # deterministic replay (D-29)
└── build.gradle.kts                 # root build with version catalog
```

### Pattern 1: Flow-Based LLMBackend Port Interface

**What:** All LLM communication goes through a single `call()` method returning `Flow<LLMChunk>`. The agent loop collects the flow, accumulating chunks into conversation history.

**When to use:** Everywhere the agent loop invokes an LLM. Non-streaming providers emit one `Text` chunk + one `Usage` chunk + one `Done` chunk.

```kotlin
// Source: CONTEXT.md D-04, D-05; ARCHITECTURE.md Pattern 1
// [VERIFIED: decision-locked in CONTEXT.md]

@KoreDsl  // mark to prevent outer scope leakage in DSL
interface LLMBackend {
    val name: String
    fun call(
        messages: List<ConversationMessage>,
        tools: List<ToolDefinition>,
        config: LLMConfig
    ): Flow<LLMChunk>  // cold flow — each call() creates a new stream
}

sealed class LLMChunk {
    data class Text(val content: String) : LLMChunk()
    data class ToolCall(val id: String, val name: String, val arguments: String) : LLMChunk()
    data class Usage(val inputTokens: Int, val outputTokens: Int) : LLMChunk()
    data object Done : LLMChunk()
}
```

**Adapter wrapping pattern** (blocking SDK → Flow):

```kotlin
// [ASSUMED] — pattern for bridging blocking HTTP client to Flow
class ClaudeBackend(
    private val client: AnthropicClient,
    private val semaphore: Semaphore
) : LLMBackend {
    override val name = "claude"
    override fun call(messages: List<ConversationMessage>, tools: List<ToolDefinition>, config: LLMConfig): Flow<LLMChunk> = flow {
        semaphore.withPermit {
            withContext(Dispatchers.IO) {
                // Anthropic SDK blocking call here
                val response = client.messages().create(...)
                // translate to LLMChunk emissions
                emit(LLMChunk.Text(response.content))
                emit(LLMChunk.Usage(response.usage.inputTokens, response.usage.outputTokens))
                emit(LLMChunk.Done)
            }
        }
    }
}
```

### Pattern 2: AgentResult — No Exceptions from the Loop

**What:** The `AgentLoop.run()` function is a `suspend fun` that returns `AgentResult`. All errors are typed variants. Only `CancellationException` propagates.

```kotlin
// Source: ARCHITECTURE.md Pattern 2; CONTEXT.md D-21
// [VERIFIED: decision-locked in CONTEXT.md]

sealed class AgentResult {
    data class Success(val output: String, val tokenUsage: TokenUsage) : AgentResult()
    data class BudgetExceeded(val spent: TokenBudget, val limit: TokenBudget) : AgentResult()
    data class ToolError(val toolName: String, val cause: Throwable) : AgentResult()
    data class LLMError(val backend: String, val cause: Throwable) : AgentResult()
    data class Cancelled(val reason: String) : AgentResult()
}

// Agent loop error handling shape:
suspend fun run(task: AgentTask): AgentResult {
    return try {
        runLoop(task)
    } catch (e: CancellationException) {
        throw e  // ALWAYS rethrow — structured concurrency requires it
    } catch (e: BudgetExceededException) {
        AgentResult.BudgetExceeded(e.spent, e.limit)
    } catch (e: ToolExecutionException) {
        AgentResult.ToolError(e.toolName, e)
    } catch (e: Exception) {
        AgentResult.LLMError(currentBackend.name, e)
    }
}
```

### Pattern 3: Kotlin DSL with @DslMarker

**What:** The `agent { }` builder uses `@DslMarker` to prevent outer receiver leakage. Nested blocks cannot accidentally call methods on outer builders.

```kotlin
// Source: CONTEXT.md D-01, D-02; Kotlin docs type-safe builders
// [CITED: https://kotlinlang.org/docs/type-safe-builders.html]

@DslMarker
annotation class KoreDsl

@KoreDsl
class AgentBuilder internal constructor(val name: String) {
    var model: LLMBackend? = null
    private val _tools = mutableListOf<ToolProvider>()
    var budget: TokenBudget? = null

    fun tools(vararg providers: ToolProvider) { _tools.addAll(providers) }

    @KoreDsl
    class ToolsBuilder {
        fun mcp(serverName: String): ToolProvider = McpToolProvider(serverName)
    }

    internal fun build(): AgentConfig = AgentConfig(name, model!!, _tools.toList(), budget)
}

fun agent(name: String, block: AgentBuilder.() -> Unit): AgentRunner {
    val builder = AgentBuilder(name)
    builder.block()
    return AgentRunner(builder.build())
}

// Infix fallback chain (D-20, D-07):
infix fun LLMBackend.fallbackTo(other: LLMBackend): LLMBackend =
    ResilientLLMBackend(primary = this, fallbacks = listOf(other))
```

### Pattern 4: Structured Concurrency for Hierarchical Agents

**What:** Parent agents create child agents within their own `CoroutineScope`. `supervisorScope` isolates child failures. Cancelling parent cancels all children.

```kotlin
// Source: ARCHITECTURE.md hierarchical agents; CONTEXT.md D-19
// [VERIFIED: from ARCHITECTURE.md verified sources]

class AgentRunner(val config: AgentConfig) {
    // Scope is lifecycle-managed (Spring @Bean with destroy method)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
        // Note: OpenTelemetryContextElement added in Phase 2 (kore-observability)
    )

    fun run(task: AgentTask): Deferred<AgentResult> = scope.async {
        config.semaphore.withPermit { agentLoop.run(task) }
    }

    // Child spawning from within a running agent:
    suspend fun spawnChild(task: AgentTask): AgentResult = supervisorScope {
        // launched in current coroutine scope (inherits parent's Job chain)
        async { childRunner.run(task).await() }.await()
    }
}
```

### Pattern 5: Parallel Tool Dispatch

```kotlin
// Source: CONTEXT.md D-22; ARCHITECTURE.md agent loop
// [VERIFIED: decision-locked in CONTEXT.md]

val results: List<ToolResult> = coroutineScope {
    toolCalls.map { call ->
        async { toolRegistry.dispatch(call) }
    }.awaitAll()
}
```

### Pattern 6: MCP Client Lazy Connection

```kotlin
// Source: CONTEXT.md D-10, D-11; ARCHITECTURE.md McpClientAdapter
// [VERIFIED: decision-locked in CONTEXT.md]

class McpClientAdapter(private val config: McpServerConfig) : ToolProvider {
    private var client: McpClient? = null
    private val mutex = Mutex()

    private suspend fun getOrConnect(): McpClient = mutex.withLock {
        client ?: connectWithRetry().also { client = it }
    }

    private suspend fun connectWithRetry(): McpClient {
        var delay = 1.seconds
        repeat(maxRetries) { attempt ->
            try {
                return McpClient(transport).also { it.initialize() }
            } catch (e: Exception) {
                if (attempt == maxRetries - 1) throw e
                delay(delay)
                delay = (delay * 2).coerceAtMost(30.seconds)
            }
        }
        throw ConnectionExhaustedException()
    }

    override suspend fun callTool(name: String, args: Map<String, Any>): ToolResult {
        return try {
            getOrConnect().call("tools/call", ToolCallRequest(name, args))
                .let { ToolResult.Success(it) }
        } catch (e: Exception) {
            ToolResult.Error(toolName = name, cause = e)
        }
    }
}
```

### Pattern 7: MockLLMBackend for Testing

```kotlin
// Source: CONTEXT.md D-26, D-29; kore-test module design
// [VERIFIED: decision-locked in CONTEXT.md]

class MockLLMBackend : LLMBackend {
    override val name = "mock"
    private val responses = ArrayDeque<Flow<LLMChunk>>()
    private val capturedCalls = mutableListOf<List<ConversationMessage>>()

    fun respondWith(vararg chunks: LLMChunk): MockLLMBackend {
        responses.addLast(flowOf(*chunks))
        return this
    }

    override fun call(messages: List<ConversationMessage>, tools: List<ToolDefinition>, config: LLMConfig): Flow<LLMChunk> {
        capturedCalls.add(messages.toList())
        return responses.removeFirstOrNull() ?: error("No more mock responses configured")
    }

    fun verifyCallCount(expected: Int) { capturedCalls.size shouldBe expected }
}

// Usage in test:
@Test
fun `agent returns Success when LLM produces final answer`() = runTest {
    val mock = MockLLMBackend()
        .respondWith(LLMChunk.Text("The answer is 42"), LLMChunk.Usage(10, 5), LLMChunk.Done)

    val result = agent("test") { model = mock }.run(AgentTask("What is the answer?")).await()

    result shouldBeInstanceOf AgentResult.Success::class
    mock.verifyCallCount(1)
}
```

### Anti-Patterns to Avoid

- **`runBlocking` inside suspend functions:** Starves the thread pool. Use `withContext(Dispatchers.IO)` for blocking SDK calls.
- **`GlobalScope.launch` for agents:** Orphaned coroutines, no structured cancellation. All agents must run in a lifecycle-managed scope.
- **Exceptions escaping `AgentLoop.run()`:** Use `AgentResult` sealed class. Only re-throw `CancellationException`.
- **Interface shaped around Claude:** Design `LLMBackend` with all 4 provider docs open simultaneously. Test all 4 stubs before locking the interface.
- **`Job` as coroutine argument for children:** Severs parent-child relationship. Use `supervisorScope { }` for child failure isolation.
- **`MutableSharedFlow()` with zero buffer in EventBus:** Backpressures agent loop. Use `extraBufferCapacity = 64, DROP_OLDEST`.
- **Stdout writes in stdio MCP server:** Corrupts JSON-RPC message stream. Force all logging to stderr.
- **Kotlin stdlib declared explicitly in `build.gradle.kts`:** Gradle Kotlin plugin adds it automatically; double declaration causes classpath errors.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| MCP JSON-RPC framing + transport | Custom JSON-RPC client | `io.modelcontextprotocol:kotlin-sdk:0.11.1` | Handles JSON-RPC 2.0, SSE, stdio, capability negotiation, `Last-Event-ID` resumability, all edge cases |
| MCP server over SSE | Custom Ktor SSE server | MCP Kotlin SDK Ktor plugin | SDK provides Ktor integration with correct message framing |
| Blocking SDK → coroutine bridging | Custom adapter | `withContext(Dispatchers.IO) { }` | Standard Kotlin pattern; Dispatchers.IO is sized for blocking I/O |
| LLM retry with exponential backoff | Custom retry loop | Wrap in `ResilientLLMBackend` with `delay()` outside semaphore | Avoids holding semaphore permit during backoff delay |
| Concurrent flow collection | Custom threading | `coroutineScope { list.map { async { ... } }.awaitAll() }` | Structured concurrency, automatic cancellation, exception propagation |
| Mutex-based lazy initialization | `@Volatile` + double-checked locking | `Mutex().withLock { }` | Coroutine-safe, no memory model surprises |

**Key insight:** The MCP Kotlin SDK handles all protocol complexity. Writing custom JSON-RPC-over-SSE with proper `Last-Event-ID` tracking and reconnect semantics would take weeks and contain subtle bugs. The SDK is official and maintained by JetBrains.

---

## Common Pitfalls

### Pitfall 1: LLMBackend Interface Shaped Around Claude
**What goes wrong:** Designing the `LLMBackend` interface while only looking at the Anthropic SDK. Claude-specific concepts (thinking blocks, cache control, server-managed tool result IDs) leak into the shared interface. GPT/Gemini adapters require null fields or impossible-to-satisfy contracts.
**Why it happens:** Claude is the first adapter built. Its SDK is the most familiar.
**How to avoid:** Write stub implementations for all four adapters (Claude, GPT, Ollama, Gemini) before finalizing the `LLMBackend` interface signature. Use the `LLMChunk` sealed class as the canonical output — never expose provider-specific response types.
**Warning signs:** Any adapter has null fields in the common interface. Any `kore-core` code has `is ClaudeBackend` checks.

### Pitfall 2: `runBlocking` in Suspend Context (Thread Starvation)
**What goes wrong:** LLM SDK calls (Anthropic/OpenAI are blocking HTTP) called inside a coroutine on `Dispatchers.Default` without `withContext(Dispatchers.IO)`.
**Why it happens:** Copy-pasting SDK examples that work in `main()` but block the coroutine dispatcher under load.
**How to avoid:** All blocking I/O inside `withContext(Dispatchers.IO) { }`. Never `runBlocking` inside a suspend function.
**Warning signs:** Thread dump shows `DefaultDispatcher-worker-N` threads blocked on `SocketInputStream.read`.

### Pitfall 3: Breaking Structured Concurrency with `Job` as Argument
**What goes wrong:** `launch(SupervisorJob()) { childAgent.run(task) }` — silently severs parent-child relationship. Cancelling parent does not cancel children.
**Why it happens:** Developer wants to isolate child failures (reasonable) but uses wrong mechanism.
**How to avoid:** Use `supervisorScope { }` lambda. Never pass `Job` as a `launch()` argument. Write a test: cancel parent, assert all children terminate in 100ms.
**Warning signs:** Active agent count in Micrometer keeps growing after parent cancellation.

### Pitfall 4: stdout Contamination in MCP stdio Server
**What goes wrong:** JVM logging (Logback defaults to stdout) writes to the same stdout that the MCP stdio transport reads for JSON-RPC messages. Client receives a log line, fails to parse it, corrupts or drops messages.
**Why it happens:** Spring Boot default logging goes to stdout. Not obvious until a real MCP client connects.
**How to avoid:** Configure Logback to stderr for any component that uses stdio MCP transport. Add integration test that validates stdout contains only valid JSON-RPC.
**Warning signs:** MCP client receives `Cannot deserialize JSON` errors or silent response drops.

### Pitfall 5: Semaphore Held During Retry Backoff
**What goes wrong:** `semaphore.withPermit { retryLoop() }` — permit is held during `delay()` backoff, blocking other agents from making LLM calls for seconds.
**Why it happens:** Natural to wrap the entire retry block in the semaphore.
**How to avoid:** Acquire semaphore only around the HTTP call itself, not the retry loop. Release before `delay()`.
**Warning signs:** Under load, p99 LLM latency equals `maxRetries * maxBackoff` instead of actual LLM response time.

### Pitfall 6: Gradle `libs.versions.toml` Not Created First
**What goes wrong:** Version strings scattered across 8+ `build.gradle.kts` files. Module version drift causes runtime `NoSuchMethodError` or `ClassCastException`.
**Why it happens:** Easy to skip version catalog setup in early modules, then it becomes tech debt.
**How to avoid:** Create `gradle/libs.versions.toml` in the very first task (project scaffold). All modules reference it from day one.
**Warning signs:** Any `build.gradle.kts` contains inline version strings like `"1.10.2"`.

### Pitfall 7: MutableSharedFlow with Zero Buffer
**What goes wrong:** `MutableSharedFlow<AgentEvent>()` with default `extraBufferCapacity = 0`. Agent loop suspends on `emit()` if no collectors are active, stalling all agents.
**Why it happens:** Default `SharedFlow()` constructor creates a rendezvous flow.
**How to avoid:** Always create with `MutableSharedFlow(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)`.
**Warning signs:** Agent throughput drops suddenly under moderate load.

---

## Code Examples

### Agent Loop Core Shape

```kotlin
// Source: ARCHITECTURE.md data flow diagram; CONTEXT.md locked decisions
// [VERIFIED: from ARCHITECTURE.md verified research]

class AgentLoop(
    private val llmBackend: LLMBackend,
    private val toolRegistry: ToolRegistry,
    private val budgetEnforcer: BudgetEnforcer,
    private val eventBus: EventBus
) {
    suspend fun run(task: AgentTask, context: AgentContext): AgentResult {
        val messages = mutableListOf<ConversationMessage>(
            ConversationMessage.User(task.input)
        )

        while (true) {
            // Budget check before each LLM call (BUDG-04, D-25)
            val budget = budgetEnforcer.checkBudget(context.agentId)
            if (budget.isExceeded) {
                return AgentResult.BudgetExceeded(budget.spent, budget.limit)
            }

            // Collect LLM response stream (D-04)
            val chunks = mutableListOf<LLMChunk>()
            try {
                llmBackend.call(messages, context.tools, context.config).collect { chunk ->
                    chunks.add(chunk)
                    if (chunk is LLMChunk.Usage) {
                        budgetEnforcer.record(context.agentId, chunk.inputTokens + chunk.outputTokens)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return AgentResult.LLMError(llmBackend.name, e)
            }

            val toolCalls = chunks.filterIsInstance<LLMChunk.ToolCall>()
            val textChunks = chunks.filterIsInstance<LLMChunk.Text>()

            if (toolCalls.isEmpty()) {
                // Final answer
                val output = textChunks.joinToString("") { it.content }
                return AgentResult.Success(output, budgetEnforcer.currentUsage(context.agentId))
            }

            // Parallel tool dispatch (D-22)
            val results: List<ToolResult> = coroutineScope {
                toolCalls.map { call ->
                    async {
                        try {
                            toolRegistry.dispatch(call)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ToolResult.Error(call.name, e)
                        }
                    }
                }.awaitAll()
            }

            // Append assistant message + tool results to history
            messages.add(ConversationMessage.Assistant(textChunks, toolCalls))
            results.forEach { result ->
                messages.add(ConversationMessage.Tool(result))
            }
        }
    }
}
```

### InMemoryBudgetEnforcer (BUDG-01, BUDG-02)

```kotlin
// Source: CONTEXT.md D-23, D-24, D-25
// [VERIFIED: decision-locked in CONTEXT.md]

class InMemoryBudgetEnforcer(
    private val limits: Map<String, TokenBudget>  // agentId -> limit
) : BudgetEnforcer {
    private val usage = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun checkBudget(agentId: String): BudgetStatus {
        val limit = limits[agentId] ?: return BudgetStatus.Unlimited
        val spent = usage.getOrPut(agentId) { AtomicInteger(0) }.get()
        return if (spent >= limit.tokens) {
            BudgetStatus.Exceeded(TokenBudget(spent), limit)
        } else {
            BudgetStatus.Available(TokenBudget(spent), limit)
        }
    }

    override suspend fun record(agentId: String, tokens: Int) {
        usage.getOrPut(agentId) { AtomicInteger(0) }.addAndGet(tokens)
    }
}
```

### Session Recording (TEST-03, TEST-04)

```kotlin
// Source: CONTEXT.md D-28, D-29
// [ASSUMED] — implementation shape not specified in CONTEXT.md, this is Claude's Discretion

@Serializable
data class RecordedSession(
    val agentId: String,
    val calls: List<RecordedCall>
)

@Serializable
data class RecordedCall(
    val inputMessages: List<ConversationMessage>,
    val outputChunks: List<LLMChunk>
)

class RecordingLLMBackend(private val delegate: LLMBackend) : LLMBackend {
    override val name = delegate.name
    private val calls = mutableListOf<RecordedCall>()

    override fun call(messages: List<ConversationMessage>, tools: List<ToolDefinition>, config: LLMConfig): Flow<LLMChunk> = flow {
        val chunks = mutableListOf<LLMChunk>()
        delegate.call(messages, tools, config).collect { chunk ->
            chunks.add(chunk)
            emit(chunk)
        }
        calls.add(RecordedCall(messages.toList(), chunks.toList()))
    }

    fun saveSession(agentId: String, path: Path) {
        val session = RecordedSession(agentId, calls.toList())
        path.writeText(Json.encodeToString(session))
    }
}
```

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java JDK | Build and runtime | YES | OpenJDK 24.0.2 (Corretto) | — |
| Gradle | Build system | YES | 9.1.0 | — |
| Docker | Integration tests (Testcontainers, Phase 2) | YES | 28.5.1 | — |
| Git | Version control, CI | YES | 2.50.1 | — |
| Kotlin CLI | N/A — Gradle provides Kotlin | Not installed | — | Use Gradle (expected) |

**Critical version mismatch — requires Gradle toolchain:**

The installed JDK is version 24, but project constraints require JVM target 21 (LTS). This is a **blocking discrepancy** that must be addressed in the project scaffold:

```kotlin
// settings.gradle.kts — required for all modules
kotlin {
    jvmToolchain(21)
}
```

With Gradle 9.1.0's Java toolchain provisioning, Gradle will auto-download JDK 21 if not found locally. This is the correct fix — do NOT change the project's JVM target to 24.

**Gradle version note:** Project stack requires Gradle 9.4.1, but 9.1.0 is installed globally. Gradle Wrapper (`gradlew`) will download 9.4.1 automatically when the wrapper is configured in the project — this is expected behavior and is not a blocker.

**Missing dependencies with no fallback:**
- None for Phase 1 (no PostgreSQL, no external MCP servers needed for unit tests — MockMcpServer handles that)

**Missing dependencies with fallback:**
- Real LLM API keys (Anthropic, OpenAI, Gemini): not required for unit tests (MockLLMBackend). Required only for integration tests — session recording for CI replay can be done once.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (junit-jupiter:5.12.0) |
| Config file | `kore-core/src/test/resources/junit-platform.properties` — Wave 0 gap |
| Quick run command | `./gradlew :kore-core:test :kore-test:test` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CORE-01 | Agent loop executes full cycle: task -> LLM call -> tool use -> result -> loop | unit | `./gradlew :kore-core:test --tests "*.AgentLoopTest"` | Wave 0 gap |
| CORE-02 | AgentResult sealed class has all variants | unit | `./gradlew :kore-core:test --tests "*.AgentResultTest"` | Wave 0 gap |
| CORE-03 | DSL compiles and builds AgentConfig correctly | unit | `./gradlew :kore-core:test --tests "*.AgentDslTest"` | Wave 0 gap |
| CORE-04 | Semaphore limits concurrent LLM calls per backend | unit | `./gradlew :kore-core:test --tests "*.SemaphoreRateLimitTest"` | Wave 0 gap |
| CORE-05 | Cancelling parent cancels all children within 100ms | unit | `./gradlew :kore-core:test --tests "*.HierarchicalAgentTest"` | Wave 0 gap |
| CORE-06 | Retry with backoff makes N attempts before LLMError | unit | `./gradlew :kore-core:test --tests "*.RetryBackoffTest"` | Wave 0 gap |
| CORE-07 | Fallback chain switches to secondary on primary failure | unit | `./gradlew :kore-core:test --tests "*.FallbackChainTest"` | Wave 0 gap |
| LLM-01 | Claude adapter translates LLMChunk correctly | unit | `./gradlew :kore-mcp:test --tests "*.ClaudeBackendTest"` | Wave 0 gap |
| LLM-02 | GPT adapter translates LLMChunk correctly | unit | `./gradlew :kore-mcp:test --tests "*.GptBackendTest"` | Wave 0 gap |
| LLM-03 | Ollama adapter translates LLMChunk correctly | unit | `./gradlew :kore-mcp:test --tests "*.OllamaBackendTest"` | Wave 0 gap |
| LLM-04 | Gemini adapter translates LLMChunk correctly | unit | `./gradlew :kore-mcp:test --tests "*.GeminiBackendTest"` | Wave 0 gap |
| LLM-05 | All 4 adapters satisfy LLMBackend interface with no null fields | unit | `./gradlew :kore-core:test --tests "*.LLMBackendInterfaceTest"` | Wave 0 gap |
| MCP-01 | MCP client calls tool over stdio transport | integration | `./gradlew :kore-mcp:test --tests "*.McpStdioClientTest"` | Wave 0 gap |
| MCP-02 | MCP client calls tool over SSE transport | integration | `./gradlew :kore-mcp:test --tests "*.McpSseClientTest"` | Wave 0 gap |
| MCP-03 | MCP client reads resource from server | integration | `./gradlew :kore-mcp:test --tests "*.McpResourceClientTest"` | Wave 0 gap |
| MCP-04 | MCP client uses prompt template from server | integration | `./gradlew :kore-mcp:test --tests "*.McpPromptClientTest"` | Wave 0 gap |
| MCP-05 | MCP server accepts tool call from external client | integration | `./gradlew :kore-mcp:test --tests "*.McpServerTest"` | Wave 0 gap |
| MCP-06 | Initialize handshake completes before tools/list | integration | `./gradlew :kore-mcp:test --tests "*.McpInitHandshakeTest"` | Wave 0 gap |
| BUDG-01 | BudgetEnforcer interface is satisfied | unit | `./gradlew :kore-core:test --tests "*.BudgetEnforcerTest"` | Wave 0 gap |
| BUDG-02 | InMemoryBudgetEnforcer tracks tokens per agent | unit | `./gradlew :kore-core:test --tests "*.InMemoryBudgetEnforcerTest"` | Wave 0 gap |
| BUDG-03 | Token count from LLMChunk.Usage is recorded | unit | `./gradlew :kore-core:test --tests "*.TokenCountingTest"` | Wave 0 gap |
| BUDG-04 | Loop returns BudgetExceeded when limit reached | unit | `./gradlew :kore-core:test --tests "*.BudgetExceededTest"` | Wave 0 gap |
| TEST-01 | MockLLMBackend returns scripted responses | unit | `./gradlew :kore-test:test --tests "*.MockLLMBackendTest"` | Wave 0 gap |
| TEST-02 | MockMcpServer returns scripted tool results | unit | `./gradlew :kore-test:test --tests "*.MockMcpServerTest"` | Wave 0 gap |
| TEST-03 | Session recorder captures LLM calls to file | unit | `./gradlew :kore-test:test --tests "*.SessionRecorderTest"` | Wave 0 gap |
| TEST-04 | Session replayer produces deterministic output in runTest | unit | `./gradlew :kore-test:test --tests "*.SessionReplayerTest"` | Wave 0 gap |

### Sampling Rate
- **Per task commit:** `./gradlew :kore-core:test` (fast — no network, no containers)
- **Per wave merge:** `./gradlew test` (all modules)
- **Phase gate:** Full suite green + ktlintCheck passes before `/gsd-verify-work`

### Wave 0 Gaps

All test infrastructure is missing — greenfield project.

- [ ] `gradle/libs.versions.toml` — version catalog (required before any module)
- [ ] `settings.gradle.kts` — multi-module project declaration
- [ ] `build.gradle.kts` (root) — kotlinter, kotlin plugin, spring plugin `apply false`
- [ ] `kore-core/build.gradle.kts` — coroutines-core, serialization-json deps
- [ ] `kore-mcp/build.gradle.kts` — kore-core dep, mcp-kotlin-sdk dep
- [ ] `kore-test/build.gradle.kts` — kore-core dep, mockk, coroutines-test, kotest as `api`
- [ ] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentLoopTest.kt`
- [ ] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentResultTest.kt`
- [ ] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/dsl/AgentDslTest.kt`
- [ ] `kore-test/src/test/kotlin/dev/unityinflow/kore/test/MockLLMBackendTest.kt`

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No — Phase 1 is library code, no HTTP endpoints yet | — |
| V3 Session Management | No — agent sessions are in-memory, no HTTP session | — |
| V4 Access Control | No — no multi-tenant access control in Phase 1 | — |
| V5 Input Validation | Yes — agent task input, MCP tool arguments | Validate at `AgentTask` constructor; MCP SDK validates JSON-RPC framing |
| V6 Cryptography | No — no cryptographic operations in Phase 1 | — |

### Known Threat Patterns for This Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Prompt injection via agent task input | Tampering | Validate and sanitize `AgentTask.input` before injecting into LLM messages; sanitize tool outputs before appending to conversation history |
| MCP tool argument injection | Tampering | MCP SDK validates JSON-RPC structure; validate tool argument types in `ToolDefinition` before dispatching |
| API key exposure in logs | Information Disclosure | Never log full LLM request/response bodies; redact credentials from `AgentTask` and `LLMConfig`; all API keys via environment variables per CLAUDE.md constraint |
| Runaway token budget (resource exhaustion) | Denial of Service | `BudgetEnforcer.checkBudget()` before every LLM call; configurable per-agent limits in `InMemoryBudgetEnforcer` |
| Child agent resource leak after parent cancel | Denial of Service | Structured concurrency with `SupervisorJob`; write cancellation propagation test per Pitfall 2 prevention |

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| LangChain4j as full agent framework | kore's own coroutine agent loop; LangChain4j as HTTP transport only | 2025 — LangChain4j 1.0 GA | LangChain4j agentic modules now exist but conflict with kore's architecture; use only for provider adapters |
| Spring Sleuth for tracing | Micrometer Tracing (Spring Boot 4 replaces Sleuth) | 2025 — Spring Boot 4 | No Sleuth dependency; use Micrometer `ObservationRegistry` API (Phase 2) |
| MCP HTTP+SSE (2024-11-05) | MCP Streamable HTTP (2025-11-25) | Nov 2025 | Both transports must be supported during transition; MCP SDK 0.11.1 handles detection |
| Gradle 8 with manual version strings | Gradle 9 + version catalogs (libs.versions.toml native) | Gradle 9 release | Version catalogs are now built-in, not a plugin; eliminates version drift |
| Exposed as experimental ORM | Exposed 1.0 GA with R2DBC (Phase 2 relevant) | Jan 2026 | Stable API, R2DBC support; safe to use for kore-storage in Phase 2 |

**Deprecated/outdated:**
- Spring Sleuth: removed in Spring Boot 4. Replaced by Micrometer Tracing.
- MCP HTTP+SSE (2024-11-05 spec): deprecated but still deployed; MCP SDK handles backward compat.
- Manual Gradle version strings: replaced by `libs.versions.toml` in Gradle 9.
- `io.modelcontextprotocol.sdk:mcp` (Java SDK): Reactor-based, deprecated in favor of Kotlin SDK for JVM coroutine projects.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | LLM adapters (Claude, GPT, Ollama, Gemini) should each be separate modules rather than collapsed into kore-mcp | Standard Stack (installation note) | Module structure differs from what the planner creates; resolves by having user confirm module naming during planning |
| A2 | `MockLLMBackend` API is a fluent builder (`.respondWith(...)` chaining) rather than DSL | Code Examples — MockLLMBackend | Minor API surface difference; does not affect correctness |
| A3 | `ConversationHistory` is owned by the agent loop (mutable list in loop, not a separate class) | Architecture — agent loop code example | If separate Conversation class is preferred, the loop code shape changes |
| A4 | Session recording uses `kotlinx.serialization` JSON output to a `Path` | Code Examples — session recording | If a different format is chosen (binary, CBOR), serialization dependencies change |
| A5 | Anthropic and OpenAI Java SDKs are blocking HTTP and must be wrapped in `withContext(Dispatchers.IO)` | Standard Stack note; Pitfall 2 | If SDKs have added async/coroutine support since training, Dispatchers.IO wrapping is still safe but not strictly required |

---

## Open Questions

1. **LLM adapter module placement**
   - What we know: kore-core must have zero external deps (D-15). LLM SDKs are external deps.
   - What's unclear: Should each LLM provider be a separate module (`kore-llm-claude`, `kore-llm-gpt`, etc.) or should all 4 adapters live together in one `kore-adapters` module?
   - Recommendation: Separate modules (`kore-llm-claude` etc.) are cleanest hexagonally and allow users to include only the providers they need. The planner should lock this in.

2. **`infix fun LLMBackend.fallbackTo(other: LLMBackend)` return type**
   - What we know: D-20 specifies the syntax `claude() fallbackTo gpt() fallbackTo ollama()`.
   - What's unclear: Does `fallbackTo` return an `LLMBackend` (wraps in `ResilientLLMBackend`) or does it return a `FallbackChain` builder that needs a terminal call?
   - Recommendation: Return `LLMBackend` (specifically `ResilientLLMBackend`) — this makes chaining work without a terminal call. Infix functions chain left-to-right: `(claude() fallbackTo gpt()) fallbackTo ollama()`.

3. **`AgentContext` vs `AgentConfig` distinction**
   - What we know: ARCHITECTURE.md uses `AgentContext` for execution state, REQUIREMENTS.md uses `AgentConfig` for DSL output.
   - What's unclear: Are these the same class or different? Does `AgentConfig` (static from DSL) become `AgentContext` (runtime state including conversation history)?
   - Recommendation: Split into two: `AgentConfig` (immutable, from DSL, created once) and `AgentContext` (mutable runtime state per run — current messages, budget usage). The loop receives both.

---

## Sources

### Primary (HIGH confidence)
- CONTEXT.md — All D-* decisions are locked; research verifies how to implement them, not whether to
- `.planning/research/STACK.md` — Technology versions verified against official release pages 2026-04-10
- `.planning/research/ARCHITECTURE.md` — Component boundaries with sources cited
- `.planning/research/PITFALLS.md` — 15 domain pitfalls with official source citations
- MCP Kotlin SDK 0.11.1 official docs: https://kotlin.sdk.modelcontextprotocol.io/
- Kotlin type-safe builders (DslMarker): https://kotlinlang.org/docs/type-safe-builders.html

### Secondary (MEDIUM confidence)
- Structured concurrency + SupervisorJob patterns (ProAndroidDev, verified against Kotlin coroutines docs)
- SharedFlow EventBus pattern (Roman Elizarov's canonical article, Kotlin coroutines lead)
- Semaphore rate limiting pattern (Shreyas Patil blog, verified against kotlinx.coroutines API)

### Tertiary (LOW confidence)
- Session recording/replay API shape (A2, A3, A4 in assumptions) — Claude's Discretion area, no external source to verify against

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all versions in STACK.md were verified against official sources on 2026-04-10
- Architecture: HIGH — patterns are derived from CONTEXT.md locked decisions and ARCHITECTURE.md which cites official sources
- Pitfalls: HIGH — PITFALLS.md cites official issues and blog posts from 2025-2026
- Code examples: MEDIUM — shapes are derived from locked decisions and architectural patterns; exact method signatures in assumptions log

**Research date:** 2026-04-10
**Valid until:** 2026-05-10 (30 days — stable ecosystem, but Anthropic/OpenAI SDK beta versions move fast; confirm exact patch versions at build time)
