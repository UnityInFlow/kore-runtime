# Domain Pitfalls

**Domain:** Kotlin/JVM agent runtime (coroutine-based, MCP protocol, multi-LLM, Spring WebFlux)
**Researched:** 2026-04-10
**Overall confidence:** HIGH — all critical pitfalls verified against official docs, GitHub issues, and 2025/2026 sources

---

## Critical Pitfalls

Mistakes that cause rewrites, data loss, or production incidents.

---

### Pitfall 1: `runBlocking` inside a suspend function (blocking the thread pool)

**What goes wrong:** A developer calls a blocking SDK (HTTP client, JDBC, file I/O) inside a suspend function without switching dispatcher. Or they wrap it in `runBlocking { }` thinking that bridges the gap. `runBlocking` inside a suspend context blocks the thread it runs on — under Spring WebFlux's Netty event loop this starves the entire reactor thread pool. Under `Dispatchers.Default` it exhausts the bounded pool (64 threads).

**Why it happens:** LLM provider SDKs (Anthropic, OpenAI Java SDK) historically shipped blocking HTTP clients. Developers copy-paste the "works in main()" example into a coroutine handler without realising the difference.

**Consequences:** Thread starvation under concurrent agent load. All agents queue behind the blocked thread. Symptoms appear only at scale and look like random timeouts rather than a code defect.

**Prevention:**
- Any blocking I/O call (LLM HTTP, JDBC, file) must be wrapped: `withContext(Dispatchers.IO) { blockingCall() }`
- Never use `runBlocking` inside a `suspend fun` — use `coroutineScope { }` to create a child scope
- In the LLMBackend port interface, mark the contract clearly: `suspend fun complete(...)` is the only accepted signature; blocking adapters must wrap internally
- Enable IntelliJ IDEA's coroutine inspections (2026.1+); they flag `runBlocking` inside suspend functions at compile time

**Detection:** Thread dump under load shows threads named `DefaultDispatcher-worker-N` all blocked on `java.net.SocketInputStream.read`. Micrometer metric `executor.active` on the IO dispatcher pegged at ceiling.

**Phase:** kore-core Phase 1. Enforce in the LLMBackend adapter contract from day one.

---

### Pitfall 2: Breaking structured concurrency by injecting `Job` into child coroutines

**What goes wrong:** Parent agent spawns child agents with `launch(SupervisorJob()) { }` or passes a custom `Job` instance as a context argument. This silently severs the parent-child relationship: cancelling the parent no longer cancels children. Child coroutines become orphaned, holding connections, tokens, and budget allocations indefinitely.

**Why it happens:** Developers want to isolate child failures from the parent (reasonable goal) but reach for `SupervisorJob` passed as an argument instead of the correct `supervisorScope { }` or `CoroutineScope(SupervisorJob())` pattern.

**Consequences:** Memory leaks. Budget enforcement fails (child keeps spending after parent is cancelled). Agent traces in OpenTelemetry show orphaned spans with no parent.

**Prevention:**
- Use `supervisorScope { }` lambda for child isolation — this preserves the parent-child relationship while letting children fail independently
- Never pass a `Job` instance as a `launch` parameter
- In hierarchical agent implementation, enforce: parent `CoroutineScope` is the authority; children are always `launch` or `async` calls on that scope
- Write a unit test: cancel parent agent, assert all child agents terminate within 100ms

**Detection:** IntelliJ 2026.1 coroutine inspection "Job misuse as coroutine argument" flags this. Runtime: active agent count in Micrometer keeps growing after cancellation.

**Phase:** kore-core Phase 1. The hierarchical agent feature depends on this being correct.

---

### Pitfall 3: OpenTelemetry context lost across coroutine suspension points

**What goes wrong:** OTel's context uses `ThreadLocal` storage. When a coroutine suspends and resumes on a different thread, the span context is gone. LLM call spans appear as root spans disconnected from the parent agent trace. Tool use spans are orphaned. The trace is useless.

**Why it happens:** OTel Java SDK's default context propagation assumes thread-per-request. Coroutines violate this assumption. The issue is invisible in tests (single-threaded dispatcher) and explodes in production.

**Consequences:** Distributed traces are broken. Cost aggregation by agent run is impossible. Compliance audit trails (EU AI Act) are incomplete.

**Prevention:**
- Add `opentelemetry-extension-kotlin` dependency to kore-observability
- Wrap every span with `span.asContextElement()` and `withContext(currentCoroutineContext() + span.asContextElement())` — or use the utility wrapper pattern
- Alternatively, enable OTel Java Agent 1.28+ with `-Dotel.instrumentation.kotlinx-coroutines.enabled=true` for automatic propagation
- Do NOT rely on `@WithSpan` alone on suspend functions without the agent — the annotation works correctly only when the Java agent's coroutine instrumentation is active
- Known Spring Framework bug (issue #36427): `Flow`-returning controller endpoints do NOT propagate `PropagationContextElement` correctly as of Spring 6.1. Workaround: use `suspend` functions for controller methods in kore-dashboard rather than `Flow` return types until the bug is fixed upstream

**Detection:** OTel trace viewer shows top-level LLM call spans with no parent. `span.spanContext().traceId` differs between the agent loop span and the LLM backend span.

**Phase:** kore-observability Phase 2. Must be solved before any trace is emitted.

---

### Pitfall 4: MCP stdio transport — stdout contamination

**What goes wrong:** The MCP spec requires that the server process MUST NOT write anything to stdout that is not a valid MCP message. On the JVM, logging frameworks (Logback, Log4j2) configured to write to stdout break the transport silently: the MCP client receives a log line, fails to parse it as JSON-RPC, and either throws or drops the message. The error is non-obvious because the process appears healthy.

**Why it happens:** The default Spring Boot logging configuration writes to stdout. Developers test the MCP server in isolation where there is no MCP client parsing the output. The failure only appears when a real client connects.

**Consequences:** MCP client receives malformed messages and either crashes, corrupts its message buffer, or silently drops responses. The stdio transport becomes unreliable.

**Prevention:**
- In kore-mcp's stdio server configuration, force all logging to stderr: `logging.config=logback-mcp.xml` where the appender targets `System.err`
- Add an integration test: launch the MCP server subprocess, send a valid `initialize` request over stdin, assert the stdout response is valid JSON-RPC with no extraneous lines
- Newline-delimited: messages are separated by `\n`, and messages MUST NOT contain embedded newlines. JSON serialization must produce single-line output — configure Jackson with `SerializationFeature.INDENT_OUTPUT` disabled for MCP use

**Detection:** Pipe the subprocess stdout through a JSON validator in CI. Any non-JSON line is a test failure.

**Phase:** kore-mcp Phase 1.

---

### Pitfall 5: LLM backend abstraction shaped around Claude's API

**What goes wrong:** The `LLMBackend` interface is designed while building the Claude adapter first. Claude-specific concepts leak into the common interface: tool use format, thinking blocks (opaque reasoning blobs), server-managed tool result IDs, cache control hints. When the GPT-4o or Gemini adapter is built, the common interface cannot be satisfied without null fields or conditional branches, making the abstraction a lie.

**Why it happens:** This is the "first provider shapes the interface" trap. LangChain (Python) and LangChain4j both suffered this — their tool call abstraction required provider-specific branches in calling code once multimodal and multi-turn scenarios appeared.

**Consequences:** Calling code in kore-core ends up with `if (backend is ClaudeBackend)` conditionals. The "all four backends from day one" credibility claim fails. Provider migration becomes a rewrite.

**Prevention:**
- Design the `LLMBackend` interface with all four providers' documentation open simultaneously. Test the interface against each provider before committing the signature
- Use an intermediate canonical message format (`CanonicalMessage`, `CanonicalToolCall`) that adapters translate to/from — never expose provider-specific types in the port
- Provider-specific features (Claude thinking blocks, GPT function calling JSON schema nuances) belong in adapter-specific extension points, not the common interface
- Make the four-backend rule a CI gate: the interface must compile and have a stub implementation for all four providers before any merge to main

**Detection:** If any adapter implementation has fields set to `null` in the common interface's data classes, or if kore-core has `is ClaudeBackend` checks, the abstraction has leaked.

**Phase:** kore-core Phase 1. The interface is the hardest thing to change later.

---

### Pitfall 6: MCP SSE transport — treating disconnection as cancellation and missing `Last-Event-ID`

**What goes wrong:** The MCP Streamable HTTP spec (2025-11-25) explicitly states: "Disconnection SHOULD NOT be interpreted as the client cancelling its request." Implementations that cancel the pending operation on SSE disconnect lose work and return no result. Additionally, clients that do not track `Last-Event-ID` and include it on reconnect lose events emitted during the disconnection window.

**Why it happens:** Natural mapping from HTTP semantics: if the connection drops, the request is gone. The SSE resumability model is not intuitive.

**Consequences:** Long-running tool calls (file operations, web search, code execution) are silently lost on network hiccup. The client retries, the server runs the operation twice. For non-idempotent tools, this is data corruption.

**Prevention:**
- Implement SSE stream resumability: assign globally unique event IDs per stream, store in-flight events in a short-lived buffer (30s), replay on reconnect using `Last-Event-ID` header
- Cancellation must be explicit: only an MCP `CancelledNotification` cancels a pending request
- Include the `retry` field (milliseconds) before closing a connection to guide client reconnect timing
- Backwards compatibility: the old HTTP+SSE transport (2024-11-05) is deprecated but still deployed. Support both during the transition period by implementing the detection handshake: POST `InitializeRequest` first; fall back to GET + SSE endpoint detection if 400/404/405

**Detection:** Integration test: start a long-running tool call, kill the SSE connection mid-stream, reconnect with `Last-Event-ID`, assert the response arrives and the tool ran exactly once.

**Phase:** kore-mcp Phase 1.

---

## Moderate Pitfalls

Mistakes that cause incorrect behavior, wasted debugging time, or technical debt.

---

### Pitfall 7: Kotlin Exposed ORM + Spring transaction manager conflict

**What goes wrong:** `newSuspendedTransaction { }` from Exposed does not integrate correctly with Spring's `SpringTransactionManager`. Exposed updates/restores only `TransactionManager` and `Transaction` in thread-locals, missing the additional Spring-managed resources. Using both Exposed's coroutine transactions and Spring's `@Transactional` annotation in the same codebase produces inconsistent behavior: operations appear to succeed but are not committed, or two transaction managers fight over the same connection.

**Why it happens:** The kore-storage module will likely use Exposed (idiomatic Kotlin ORM). If kore-spring auto-configuration also sets up Spring's transaction infrastructure, both transaction managers are active.

**Consequences:** Audit log writes appear to succeed but are silently rolled back. Agent run records are missing from PostgreSQL. Flyway migrations run fine but application transactions do not commit.

**Prevention:**
- Pick one transaction manager: either use Exposed's `newSuspendedTransaction` exclusively (no `@Transactional`) or use Spring Data with R2DBC (fully reactive) and avoid Exposed coroutine transactions
- If mixing is unavoidable, refer to Exposed GitHub issue #1722 — the workaround requires custom transaction manager delegation
- Test: write an agent run record, throw an exception mid-write, assert the partial record is NOT in the database

**Detection:** Exposed GitHub issue #1722 documents the exact symptom. If `@Transactional` methods don't roll back on exception in tests, this pitfall is active.

**Phase:** kore-storage Phase 2.

---

### Pitfall 8: Gradle multi-module version drift and `kotlin-stdlib` double-declaration

**What goes wrong:** In an 8-module project, developers declare the same library version in multiple `build.gradle.kts` files without a version catalog. Three months later, `kore-observability` is on OTel 1.34, `kore-spring` is on OTel 1.36, and runtime classpath conflicts produce `NoSuchMethodError`. A secondary problem: manually declaring `kotlin-stdlib` alongside the Kotlin Gradle Plugin causes duplicate class errors because the plugin adds stdlib automatically.

**Why it happens:** Forgetting to set up `libs.versions.toml` at project start, or partial migration. The Kotlin stdlib double-declaration is cargo-culted from older guides.

**Consequences:** Runtime classpath conflicts. Different behavior across modules. Hard-to-diagnose `ClassCastException` or `NoSuchMethodError` in production.

**Prevention:**
- Create `gradle/libs.versions.toml` at project initialization before any module is created
- All dependency versions live exclusively in the catalog — no inline version strings in any `build.gradle.kts`
- Do NOT declare `kotlin-stdlib` explicitly in any module; let the Kotlin Gradle Plugin manage it
- Use `./gradlew dependencies --configuration runtimeClasspath` in CI to detect version conflicts as a build gate

**Detection:** Run `./gradlew dependencyInsight --dependency opentelemetry-api --configuration runtimeClasspath` across modules. Any version mismatch is a pre-production failure.

**Phase:** Project setup, before Phase 1.

---

### Pitfall 9: SharedFlow as an event bus with no overflow strategy

**What goes wrong:** The pluggable event bus (Kotlin Flows default) uses a `MutableSharedFlow` with default buffer (0) and `BufferOverflow.SUSPEND`. Under burst load (many agents completing simultaneously), producers suspend waiting for slow consumers. The agent loop stalls waiting to emit an event instead of processing the next task.

**Why it happens:** `MutableSharedFlow()` with no arguments creates a rendezvous flow: zero buffer, every emission blocks until a subscriber collects it. This feels fine in tests with one agent.

**Consequences:** Agent throughput collapses under load. Timeout cascades as agents back up waiting to emit events.

**Prevention:**
- Create the event bus with an explicit buffer and overflow strategy: `MutableSharedFlow<AgentEvent>(replay = 0, extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)` or `DROP_LATEST` depending on semantics
- Document the overflow strategy choice in code comments — it is a deliberate contract
- Expose `SharedFlow.subscriptionCount` as a Micrometer gauge to monitor subscriber state

**Detection:** Under load test, `SharedFlow.subscriptionCount` drops to zero while producers are active — events are being dropped.

**Phase:** kore-core Phase 1.

---

### Pitfall 10: Kotlin DSL — missing `@DslMarker` causes outer receiver leakage

**What goes wrong:** In the `agent { }` DSL, a nested block like `tools { mcp("github") }` can accidentally call methods on the outer `AgentBuilder` receiver because Kotlin passes all enclosing receivers into lambdas. A user inside a `tools { }` block can call `model(claude())` (an `AgentBuilder` method) and it silently modifies the wrong builder, producing hard-to-debug agent misconfiguration.

**Why it happens:** Without `@DslMarker`, the Kotlin compiler does not restrict which receivers are accessible in nested scopes. This is a well-known DSL pitfall that Gradle itself suffered (see Gradle issue #23005).

**Consequences:** Silent configuration bugs. An agent might be configured with the wrong model or wrong tools with no compile error. The bug only appears at runtime when the agent behaves unexpectedly.

**Prevention:**
- Annotate all DSL receiver types with the same `@KoreDsl` annotation (itself annotated `@DslMarker`)
- Test: confirm that calling an outer-scope method inside an inner DSL block produces a compile error, not a runtime surprise
- Apply this from the very first DSL class — retrofitting `@DslMarker` to a published API is a breaking change

**Detection:** Compile-time: if the IDE shows outer receiver methods as available inside nested DSL lambdas, `@DslMarker` is missing.

**Phase:** kore-core Phase 1 (DSL design).

---

### Pitfall 11: Semaphore rate limiting that cuts coroutine concurrency unintentionally

**What goes wrong:** A `Semaphore(permits = 5)` is placed around the LLM HTTP call to rate-limit concurrent requests. But the semaphore is acquired before retries, not just for the initial call. Or the semaphore is shared across all LLM backends, meaning an Ollama call (local, no rate limit) is blocked waiting for a Claude API permit.

**Why it happens:** Rate limiting is added reactively after seeing 429 responses. The permit scope is too broad and not per-backend.

**Consequences:** Local Ollama calls are artificially serialized. Retry logic holds the semaphore during exponential backoff delay, locking out other agents for seconds.

**Prevention:**
- One `Semaphore` per LLM backend — Ollama can be `Semaphore(Int.MAX_VALUE)` (effectively unlimited), Claude can be `Semaphore(10)`
- Acquire the semaphore only for the HTTP call itself, not for the retry loop. Release before `delay()` in backoff:
  ```kotlin
  semaphore.withPermit {
      backend.httpCall(request) // only the actual HTTP call
  }
  // delay() for retry is OUTSIDE the semaphore.withPermit block
  ```
- Expose per-backend semaphore `availablePermits` as a Micrometer gauge

**Detection:** If `p99` latency for Ollama calls matches `p99` for Claude calls, the semaphore is too broad.

**Phase:** kore-core Phase 1 (LLM backend concurrency).

---

### Pitfall 12: Spring Boot starter auto-configuration that is too eager

**What goes wrong:** `kore-spring` auto-configuration activates all kore modules (OTel, PostgreSQL, MCP client, dashboard) whenever the jar is on the classpath. Users who only want the agent loop and Claude backend are forced to provide a PostgreSQL datasource and OTel exporter configuration or the context fails to start.

**Why it happens:** Spring Boot auto-configuration is easy to write but hard to scope correctly. The natural tendency is to wire everything in the `@AutoConfiguration` class.

**Consequences:** Bad developer experience. "Just add kore-spring to try it" fails with `DataSourceBeanCreationException`. The Spring Boot starter adoption drops.

**Prevention:**
- Every auto-configuration class must be conditioned: `@ConditionalOnClass(PostgresAuditRepository::class)`, `@ConditionalOnProperty("kore.storage.enabled", havingValue = "true")`, etc.
- Write a test that boots a Spring context with ONLY `kore-core` on the classpath (no PostgreSQL, no OTel) and asserts it starts in under 3 seconds with zero errors
- Follow Spring Boot's own starter design: starter POM pulls optional dependencies, auto-config activates only when those classes are present

**Detection:** Add a minimal `@SpringBootApplication` test with only `kore-spring` on the classpath and no external infrastructure. If it fails to start, auto-configuration is too eager.

**Phase:** kore-spring Phase 3.

---

## Minor Pitfalls

Annoyances and friction points that are recoverable but waste time.

---

### Pitfall 13: Maven Central publishing — 2025 SLSA provenance requirement

**What goes wrong:** Sonatype Central Portal (OSSRH replacement, mandatory since 2024) now requires verifiable SLSA provenance and stricter `groupId` domain validation. CI pipelines that worked before January 2025 fail with `422 Unprocessable Entity` on upload. GPG key not registered, or using personal dev key rather than a CI-specific build key.

**Prevention:**
- Generate a dedicated CI GPG key (`gpg --gen-key` with no expiry for the build identity)
- Store `SIGNING_KEY` (armored base64) and `SIGNING_PASSWORD` as GitHub Actions secrets
- Configure `vanniktech/gradle-maven-publish-plugin` (simpler than the raw Sonatype Gradle plugin for this use case)
- Verify `dev.unityinflow` groupId domain ownership is registered in Sonatype Central Portal before first publish attempt

**Phase:** Publishing setup, before first release.

---

### Pitfall 14: Flyway migration in integration tests — H2 vs PostgreSQL dialect mismatch

**What goes wrong:** Integration tests use H2 in-memory database for speed. PostgreSQL-specific SQL in Flyway migrations (e.g., `JSONB`, `UUID` default, `ON CONFLICT`) fails on H2. Developers either skip Flyway in tests (missing migration coverage) or write H2-compatible SQL (loses PostgreSQL features in production).

**Prevention:**
- Use Testcontainers with the official PostgreSQL image for all integration tests — never H2 for a PostgreSQL-targeted schema
- Flyway's `spring.flyway.locations` can include a `db/migration` folder and a `db/testdata` folder; separate migration scripts from test fixtures
- `@JvmStatic` is required on Testcontainers `@BeforeAll` companion object methods in Kotlin with JUnit 5

**Phase:** kore-storage Phase 2.

---

### Pitfall 15: YAML skill definition schema validation deferred to runtime

**What goes wrong:** The skills engine loads YAML files at startup or on demand. Malformed YAML (wrong field names, wrong types, invalid activation patterns) is not caught until the skill is activated in production, causing an uncaught exception in the agent loop.

**Prevention:**
- Validate all YAML skill definitions against a JSON Schema or data class schema (using Jackson's `@JsonTypeInfo` and `@Valid`) at startup, before the agent loop begins accepting tasks
- Fail fast on invalid skill definitions with a clear error message referencing the file path and field
- Include a `kore-skills-validate` Gradle task that validates all skill YAMLs in the classpath as part of the build

**Phase:** kore-skills Phase 1.

---

## Phase-Specific Warnings

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Phase 1: LLMBackend interface design | Pitfall 5 — Claude-shaped abstraction | Build all four stubs before finalizing the interface signature |
| Phase 1: Agent loop coroutines | Pitfall 1, 2 — blocking calls, broken structured concurrency | Use IntelliJ coroutine inspections; require `Dispatchers.IO` wrap in all adapters |
| Phase 1: DSL design | Pitfall 10 — `@DslMarker` missing | Add `@KoreDsl` annotation before writing the first DSL lambda |
| Phase 1: Event bus | Pitfall 9 — SharedFlow buffer | Set `extraBufferCapacity` and `onBufferOverflow` explicitly, never default |
| Phase 1: Rate limiting | Pitfall 11 — semaphore scope too broad | One semaphore per backend; acquire only around HTTP call, not retry delay |
| Phase 1: MCP stdio | Pitfall 4 — stdout contamination | Configure Logback to stderr before writing any MCP server code |
| Phase 1: MCP SSE | Pitfall 6 — disconnection as cancellation | Implement `Last-Event-ID` resumability in the first SSE implementation |
| Phase 2: OTel integration | Pitfall 3 — context loss across suspension | Add `opentelemetry-extension-kotlin` to kore-observability from day one |
| Phase 2: Storage | Pitfall 7 — Exposed + Spring TX conflict | Choose one transaction model before writing any storage code |
| Phase 2: Storage | Pitfall 14 — H2 vs PostgreSQL in tests | Use Testcontainers PostgreSQL from first test |
| Phase 3: Spring starter | Pitfall 12 — eager auto-configuration | Write the "minimal boot" test before writing any auto-config |
| Release: Maven Central | Pitfall 13 — SLSA/GPG requirements | Set up Sonatype Central Portal and GPG keys before attempting first publish |

---

## Sources

- [IntelliJ IDEA Kotlin Coroutine Inspections (March 2026)](https://blog.jetbrains.com/idea/2026/03/intellij-idea-s-new-kotlin-coroutine-inspections-explained/)
- [Kotlin Coroutines and OpenTelemetry Tracing — Nicolas Frankel](https://blog.frankel.ch/kotlin-coroutines-otel-tracing/)
- [OneUptime: Troubleshoot Kotlin Coroutine Context Loss (Feb 2026)](https://oneuptime.com/blog/post/2026-02-06-troubleshoot-kotlin-coroutine-context-loss/view)
- [Spring Framework Issue #36427 — Micrometer context propagation broken in Flow endpoints](https://github.com/spring-projects/spring-framework/issues/36427)
- [MCP Transport Specification 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [Exposed GitHub Issue #1722 — Spring TransactionManager conflict](https://github.com/JetBrains/Exposed/issues/1722)
- [Leaky Abstractions: Multimodal messages in LangChain — Sumit Gouthaman](https://sumitgouthaman.com/posts/multimodal-messages-in-langchain/)
- [Gradle Issue #23005 — @DslMarker in Gradle DSL](https://github.com/gradle/gradle/issues/23005)
- [Kotlin Documentation — Type-safe builders](https://kotlinlang.org/docs/type-safe-builders.html)
- [Kotlin Documentation — Cancellation and timeouts](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
- [OpenTelemetry Memory Leak Issue #8749 — Spring Boot WebFlux](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/8749)
- [OpenTelemetry Kotlin Flow tracking issue #8812](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/8812)
- [Applying Kotlin Structured Concurrency Part IV — Cancellation (ProAndroidDev)](https://proandroiddev.com/applying-kotlin-structured-concurrency-part-iv-coroutines-cancellation-ba51470acefe)
- [CarGurus Engineering Blog — Actor pattern for API rate limiting](https://www.cargurus.dev/kotlin-actor/actor/)
