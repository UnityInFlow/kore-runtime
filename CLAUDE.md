# kore-runtime — Kotlin Orchestration Runtime for Agents

## Project Overview

**Tool 08** in the [UnityInFlow](https://github.com/UnityInFlow) ecosystem.

Production-grade Kotlin agent runtime with coroutine-based agent loop, MCP protocol client, hexagonal architecture, multi-LLM backends, skills engine, budget-breaker integration, OpenTelemetry observability, and PostgreSQL audit logging. The flagship JVM tool in the ecosystem.

**Phase:** 3 | **Stack:** Kotlin + Spring WebFlux | **Maven:** `dev.unityinflow:kore-*`

## Status

Planned — blocked on `budget-breaker` (Tool 05) for token budget integration.

## Reference Documents

- `08-kore-runtime.md` — Feature spec, key features checklist, multi-module architecture (kore-core, kore-mcp, kore-skills, kore-observability, kore-storage, kore-dashboard, kore-spring), implementation todos (Months 4-7)
- `claude-code-harness-engineering-guide-v2.md` — Harness engineering patterns and best practices

Read these before making architectural or scope decisions.

## Tooling

| Tool | Status | Usage |
|---|---|---|
| **GSD** | Installed (global) | `/gsd:new-project` to scaffold when ready. `/gsd:plan-phase` and `/gsd:execute-phase` for structured development. |
| **RTK** | Active (v0.34.2) | Automatic via hooks. Compresses gradle, git output. ~80% token savings. |
| **Superpowers** | Active (v5.0.5) | Auto-triggers brainstorming, TDD, planning, code review, debugging skills. |

## Constraints

### Kotlin (inherited from ecosystem CLAUDE.md)
- Kotlin 2.0+, JVM target 21
- Gradle (Kotlin DSL only — never Groovy)
- Test with JUnit 5 + Kotest matchers
- Coroutines for all async work — never `Thread.sleep()` or raw threads
- Immutable data classes preferred over mutable state
- Sealed classes for domain modelling (results, errors, states)
- `Result<T>` or sealed classes instead of exceptions for expected failure cases
- No `var` — always `val`, refactor if mutation seems needed
- No `!!` without a comment explaining why it's safe
- `ktlint` before every commit
- Group: `dev.unityinflow`
- Maven Central publishing via Sonatype for all library modules

### Architecture
- Hexagonal architecture: LLM backends, event buses, storage are ports/adapters
- Pluggable event bus: Kotlin Flows (default), Kafka (opt-in), RabbitMQ (opt-in)
- AgentResult sealed class: Success | BudgetExceeded | ToolError | LLMError

### General
- Test coverage >80% on core logic before release
- No secrets committed — all credentials via environment variables
- KDoc documentation on all public APIs

## Acceptance Criteria — v0.0.1

- [ ] Kotlin coroutine agent loop: task intake -> LLM call -> tool use -> result -> loop
- [ ] MCP protocol client over stdio and SSE
- [ ] LLM backends: Claude, GPT, Ollama, Gemini
- [ ] Skills engine: YAML skill loader with activation context matching
- [ ] budget-breaker integration: zero-config token budget enforcement
- [ ] OpenTelemetry span on every LLM call, tool use, skill activation
- [ ] PostgreSQL audit log via Flyway migrations
- [ ] Spring Boot starter: auto-configuration from application.yml
- [ ] HTMX dashboard: active agents, recent traces, cost summary
- [ ] AgentResult sealed class hierarchy
- [ ] All modules published to Maven Central

## Development Workflow

When ready to build:

1. `/gsd:new-project` — describe kore-runtime, feed existing spec
2. `/gsd:discuss-phase 1` — lock in decisions for Month 4 (kore-core + kore-mcp: agent loop, sealed classes, LLM backends, MCP client)
3. `/gsd:plan-phase 1` — atomic task plans with file paths
4. `/gsd:execute-phase 1` — parallel execution with fresh context windows
5. `/gsd:discuss-phase 2` — lock in decisions for Month 5 (kore-observability + kore-storage: OTel, Micrometer, PostgreSQL, Flyway)
6. `/gsd:discuss-phase 3` — lock in decisions for Month 6 (kore-spring + kore-dashboard: auto-config, actuator, HTMX)
7. Continue with plan + execute for each phase

Superpowers skills (TDD, code review, debugging) activate automatically during execution.

## Key Dependencies (for reference, not installed yet)

- `kotlinx-coroutines-core` — coroutine support
- `spring-boot-starter-webflux` — reactive web
- `exposed` — PostgreSQL ORM
- `flyway-core` — database migrations
- `opentelemetry-sdk` — observability
- `micrometer-core` — metrics
- `budget-breaker` — token budget enforcement (Tool 05)

---

## CI / Self-Hosted Runners

Use UnityInFlow org-level self-hosted runners. Never use `ubuntu-latest`.

```yaml
runs-on: [arc-runner-unityinflow]
```

Available runners: `hetzner-runner-1/2/3` (X64), `orangepi-runner` (ARM64).

---

## Do Not

- Do not add Kafka as a hard dependency — Kotlin Flows by default, Kafka is opt-in
- Do not use `var` in Kotlin — always `val`
- Do not use `!!` without a comment explaining why it's safe
- Do not use `Thread.sleep()` or raw threads — use coroutines
- Do not build a frontend until the backend/CLI is working and tested
- Do not commit secrets or API keys
- Do not skip writing tests
- Do not inline the reference docs into this file — read them by path

<!-- GSD:project-start source:PROJECT.md -->
## Project

**kore-runtime**

A production-grade Kotlin agent runtime for the JVM. kore lets developers define, run, and observe AI agents using a Kotlin DSL, with coroutine-based execution, MCP protocol support, multi-LLM backends, and Spring Boot integration. It fills the gap between LangChain4j's LLM call abstraction and what enterprises actually need to run agents in production.

**Core Value:** The agent loop must work — task intake, LLM call, tool use, result handling, loop. A developer adds one Spring Boot dependency, writes an `agent { }` block, and has a production-ready agent running with observability and budget control. Everything else is additive.

### Constraints

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
<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->
## Technology Stack

## Recommended Stack
### Language and Runtime
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Kotlin | 2.3.x (stable as of Dec 2025) | Primary language | Coroutines, sealed classes, DSLs, data classes. Kotlin 2.3 is fully compatible with Gradle 9. Kotlin 2.0+ required by project constraints. |
| JVM | 21 (LTS) | Target JVM | LTS release. Virtual threads available. Spring Boot 4 requires Java 17 minimum, recommends 21. |
| Gradle | 9.4.1 (stable Mar 2026) | Build tool | Kotlin DSL is now the default. Gradle 9 embeds Kotlin 2.2 runtime, supports version catalogs natively. Kotlin DSL required by project constraints — never Groovy. |
### Core Runtime Framework
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Spring Boot | 4.0.5 (stable, latest patch Mar 2026) | Application container, auto-config | Spring Boot 4 ships with Spring Framework 7, native OpenTelemetry starter, first-class Kotlin serialization, JVM 21 support. The kore-spring module is a Spring Boot auto-configuration starter — Spring Boot is non-negotiable for that module. |
| Spring WebFlux | bundled with Boot 4 | Reactive HTTP endpoints | Coroutine-native: `suspend` functions and `Flow` return types work natively in `@RestController`. Reactor runs under the hood but surfaces as idiomatic Kotlin. Required for non-blocking SSE endpoints (MCP transport). |
| kotlinx-coroutines-core | 1.10.2 | Structured concurrency, agent loops | Stable companion to Kotlin 2.1. `CoroutineScope`, `Semaphore`, `Flow`, `Channel` — all primitives needed for the agent loop. Coroutines-reactor bridge (`kotlinx-coroutines-reactor`) for Spring WebFlux integration. |
| kotlinx-coroutines-reactor | 1.10.2 | Bridge Reactor ↔ coroutines | Required to call `suspend` functions from Reactor publishers and vice versa. Included in the coroutines BOM. |
### LLM Client Libraries
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| anthropic-sdk-java | 2.20.0 (beta) | Claude backend adapter | Official Anthropic Java SDK. Kotlin-compatible. Maven: `com.anthropic:anthropic-java`. Wraps this behind `LLMBackend` interface — callers never touch the SDK directly. |
| openai-java (official) | 4.30.0 (beta) | GPT backend adapter | Official OpenAI Java SDK. Written partly in Kotlin internally. Maven: `com.openai:openai-java`. Same port-adapter boundary applies. |
| LangChain4j | 1.0.x (GA, May 2025) | Ollama and Gemini adapters | LangChain4j 1.0 reached GA in May 2025. Its `langchain4j-ollama` and `langchain4j-google-ai-gemini` modules are the pragmatic choice for Ollama and Gemini. Avoids writing low-level HTTP clients for these two. DO NOT use LangChain4j as the primary agent framework — use it only as an HTTP transport adapter behind the `LLMBackend` interface. Using LangChain4j for agent orchestration would conflict with kore's own agent loop architecture. |
| Ktor HTTP Client (bundled) | 3.2+ | Raw HTTP escape hatch | If a provider adds an endpoint that LangChain4j doesn't support yet, use Ktor client directly. Already a transitive dep through kore-dashboard. |
### MCP Protocol
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| io.modelcontextprotocol:kotlin-sdk | 0.11.1 | MCP client + server | Official MCP Kotlin SDK. Maintained in collaboration with JetBrains. Kotlin Multiplatform. Supports ALL required transports: StdioClientTransport, SseClientTransport, StreamableHttpClientTransport on the client side; StdioServerTransport and Ktor-based SSE/WebSocket on the server side. Coroutine-native. Maven: `io.modelcontextprotocol:kotlin-sdk:0.11.1`. This is the only credible JVM MCP implementation that is both official and coroutine-native. |
### Dashboard
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Ktor (embedded server) | 3.2.0+ | HTTP server for HTMX dashboard | Ktor 3.2 added a first-class HTMX module (`ktor-server-htmx`) with tight integration with the routing DSL. Coroutine-native throughout. Lightweight — avoids pulling in full Spring Boot for the dashboard module. kore-dashboard is an optional side-car, not the main application server. |
| Ktor HTMX module | 3.2.0+ | HTMX-aware routing | `hx {}` DSL block, automatic HTMX header handling. No JavaScript build step. Exactly what the constraints require. |
| kotlinx.html | bundled | Server-side HTML DSL | Kotlin-idiomatic HTML generation. No template engine needed. Works with Ktor and produces type-safe HTML. |
### Database
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| JetBrains Exposed | 1.0.x (GA, Jan 2026) | ORM + SQL DSL for audit log | Exposed 1.0 reached GA in January 2026, introducing a stable API (no breaking changes until 2.0), R2DBC support, GraalVM native image support, and Spring Boot 3 + 4 compatibility. Kotlin-first by design — no JPA annotation noise. Use the DSL API (not DAO) for reactive paths. |
| exposed-r2dbc | 1.0.x | Reactive PostgreSQL driver bridge | R2DBC support was the headline feature of Exposed 1.0. Required for non-blocking audit log writes in the WebFlux/coroutine context. |
| r2dbc-postgresql | 1.0.7.RELEASE | Reactive PostgreSQL driver | The standard reactive PostgreSQL driver. Used by exposed-r2dbc under the hood. |
| Flyway | 12.x (latest: 12.3.0) | Database migrations | Spring Boot 4 changed Flyway auto-configuration — use `spring-boot-starter-flyway`, not just `flyway-core`. Flyway 12 is the current major version. SQL migration files in `src/main/resources/db/migration`. |
### Observability
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| opentelemetry-sdk | 2.x (via Spring Boot 4 starter) | Distributed tracing spans | Spring Boot 4 ships `spring-boot-starter-opentelemetry` — this is the new recommended path (replaces the previous community starter). Includes the OTel API, Micrometer tracing bridge, and OTLP exporters. |
| micrometer-core | 1.16+ (bundled via Boot 4) | Metrics: counters, timers, gauges | Micrometer is the metrics facade. Spring Boot 4 includes Micrometer 1.16. Use the Observation API (`ObservationRegistry`) rather than raw meters — it handles both metrics and tracing in one call. |
| micrometer-tracing | bundled | Trace context propagation | Spring Boot 4 replaces Sleuth with Micrometer Tracing. Bridges to OpenTelemetry exporter. |
### Testing
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| JUnit 5 (JUnit Platform) | 5.12+ (bundled via Boot 4) | Test runner | Required by project constraints. Spring Boot 4 includes JUnit 5. |
| Kotest (assertions only) | 6.1.11 (stable Apr 2026) | Fluent assertion matchers | `kotest-assertions-core` gives `shouldBe`, `shouldContain`, `shouldBeInstanceOf` and power-assert integration. Use as assertion library alongside JUnit 5 as runner — NOT as the test framework (no Kotest `FunSpec` etc.). This avoids learning a second test framework while getting Kotlin-idiomatic assertions. Kotest 6.1.11 requires Kotlin 2.2 and JDK 11+. |
| Testcontainers | 1.20+ | PostgreSQL integration tests | Industry standard for spinning up real PostgreSQL in CI. Spring Boot 4 has a known issue with Testcontainers auto-detection for Postgres (`ConnectionDetailsFactory` error) — use `@ServiceConnection` explicitly rather than auto-detection until fixed. |
| MockK | 1.14+ | Mocking Kotlin classes | Kotlin-aware mocking library. Works with coroutines (`coEvery`, `coVerify`). Required for `MockLLMBackend` in kore-test. Mockito has poor Kotlin ergonomics — never use it on this project. |
| kotlinx-coroutines-test | 1.10.2 | Coroutine testing utilities | `runTest`, `TestCoroutineDispatcher`, `advanceTimeBy`. Required for any suspend function test. Same version as coroutines-core. |
### Code Quality
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| ktlint | 1.5.0 (via kotlinter-gradle plugin) | Code formatting + style | ktlint 1.5 is supported by `kotlinter-gradle` (the lighter, faster alternative to `JLLeitschuh/ktlint-gradle`). Required before every commit per project constraints. |
| kotlinter-gradle | 5.x | Gradle plugin wrapper for ktlint | `id("org.jmailen.kotlinter")` in `build.gradle.kts`. Adds `lintKotlin` and `formatKotlin` tasks. Simpler setup than JLLeitschuh plugin which has fallen behind on maintenance. |
### Serialization
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| kotlinx.serialization (kotlinx-serialization-json) | 1.8+ | JSON serialization | Spring Boot 4 makes `kotlinx.serialization` a first-class citizen with a dedicated starter (`spring-boot-starter-kotlinx-serialization-json`). For Kotlin data classes with no inheritance complexity, it is strictly superior to Jackson: zero reflection, compile-time safety, no annotation ceremony. Use for all kore-internal DTOs and API responses. |
| Jackson (jackson-module-kotlin) | 3.x (bundled via Boot 4) | Fallback for Java interop | Jackson 3 is included in Spring Boot 4 with new group ID (`tools.jackson`). Keep it on the classpath as fallback for Java types that predate kotlinx.serialization. Do not configure it as primary JSON handler. |
### Event Bus
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Kotlin Flow (kotlinx-coroutines-core) | 1.10.2 | Default in-process event bus | `SharedFlow` and `StateFlow` provide hot stream semantics for agent events. Zero additional dependencies. Cancellation-aware via structured concurrency. This is the default per project constraints. |
| Spring Messaging (opt-in) | bundled | Kafka / RabbitMQ adapter contracts | When opt-in adapters are built, use Spring Messaging abstractions so the adapter boundary is uniform. Do not add Kafka or RabbitMQ as hard dependencies. |
### Publishing
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Gradle Maven Publish Plugin | bundled with Gradle 9 | Maven Central publication | Standard plugin for JVM library publishing. Combined with `signing` plugin for GPG signing. Required for Sonatype Maven Central. |
| Sonatype Portal Publisher | community plugin | Automates Central uploads | `com.gradleup.nmcp` (New Maven Central Publisher) or `io.github.gradle-nexus.publish-plugin` are the two current options. The new Sonatype Central portal (launched 2024) requires different upload mechanics than the old Nexus. Use `com.gradleup.nmcp` — it targets the new portal API directly. |
## Alternatives Considered
| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Agent framework layer | Custom (kore's own loop) | Koog (JetBrains, Alpha) | kore IS the agent runtime. Adopting Koog would mean building a wrapper around someone else's runtime, not a runtime. Koog is also Alpha status. |
| Agent framework layer | Custom | LangChain4j agentic modules | Same problem. LangChain4j 1.0 introduced `langchain4j-agentic` but its agent model conflicts with kore's coroutine-per-agent architecture. |
| LLM adapters (Ollama/Gemini) | LangChain4j adapters | Spring AI | Spring AI ties provider adapters to the Spring AI `ChatClient` abstraction which would leak into kore's `LLMBackend` port. LangChain4j's `ChatLanguageModel` is simpler and thinner. |
| MCP client/server | official kotlin-sdk | io.modelcontextprotocol.sdk:mcp (Java) | Java SDK is Reactor-based. Would introduce a second reactive model. Kotlin SDK is coroutine-native and maintained by JetBrains. |
| Dashboard server | Ktor (embedded) | Spring Boot MVC / Thymeleaf | Spring Boot already used for kore-spring. A second Spring context for the dashboard adds overhead. Ktor embedded server is lighter, coroutine-native, and now has a first-class HTMX module. |
| ORM | Exposed | jOOQ | jOOQ requires schema code generation. Overhead not justified for kore's simple audit log schema. Exposed 1.0 is now stable with R2DBC support. |
| ORM | Exposed | Spring Data R2DBC | Spring Data R2DBC is fine but adds Spring Data annotations and reactive types to the entity layer. Exposed provides a cleaner Kotlin DSL without that coupling. |
| Mocking | MockK | Mockito | Mockito has poor Kotlin ergonomics (open classes, final classes, no `suspend` support without plugins). MockK is Kotlin-native. |
| Code style | ktlint via kotlinter | Detekt | Detekt is a static analysis tool, not a formatter. ktlint enforces formatting. They are complementary — Detekt can be added later for complexity rules if needed. |
| Serialization (primary) | kotlinx.serialization | Jackson | Spring Boot 4 makes kotlinx.serialization first-class. For pure Kotlin data classes, it is safer (compile-time, no reflection). Jackson remains as fallback. |
## Installation — Gradle Coordinates Reference
## Confidence Assessment
| Component | Confidence | Source |
|-----------|------------|--------|
| Kotlin 2.3 / JVM 21 / Gradle 9.4.1 | HIGH | Official release pages verified |
| Spring Boot 4.0.5 | HIGH | spring.io official release blog |
| kotlinx-coroutines 1.10.2 | HIGH | GitHub releases page verified |
| MCP kotlin-sdk 0.11.1 | HIGH | Official SDK docs page (kotlin.sdk.modelcontextprotocol.io) verified |
| Anthropic Java SDK 2.20.0 | HIGH | Maven Central / GitHub releases verified |
| OpenAI Java SDK 4.30.0 | HIGH | Maven Central verified |
| LangChain4j 1.0.x | HIGH | Official docs + InfoQ news release confirmed May 2025 |
| Exposed 1.0 | HIGH | Official JetBrains blog post Jan 2026 |
| Flyway 12.3.0 | HIGH | GitHub releases + Maven Central |
| Kotest 6.1.11 | HIGH | GitHub releases verified |
| Ktor 3.2.0 + HTMX module | HIGH | Official Kotlin blog post Jun 2025 |
| OTel via Boot 4 starter | HIGH | spring.io blog Nov 2025 |
| Koog (NOT used) | HIGH | JetBrains blog confirmed Alpha status |
## Sources
- [LangChain4j GitHub](https://github.com/langchain4j/langchain4j/)
- [LangChain4j 1.0 GA — InfoQ](https://www.infoq.com/news/2025/05/java-news-roundup-may12-2025/)
- [LangChain4j vs Spring AI 2026](https://www.javacodegeeks.com/2026/03/choosing-a-java-llm-integration-strategy-in-2026-spring-ai-1-1-vs-langchain4j-vs-direct-api-calls.html)
- [MCP Kotlin SDK — official docs](https://kotlin.sdk.modelcontextprotocol.io/)
- [MCP Kotlin SDK — GitHub](https://github.com/modelcontextprotocol/kotlin-sdk)
- [Koog — JetBrains AI Blog](https://blog.jetbrains.com/ai/2025/05/meet-koog-empowering-kotlin-developers-to-build-ai-agents/)
- [Koog — GitHub (0.7.3 Alpha)](https://github.com/JetBrains/koog)
- [Spring Boot 4.0.0 available](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/)
- [Spring Boot 4.0.5 patch](https://spring.io/blog/2026/03/26/spring-boot-4-0-5-available-now/)
- [Kotlin Spring Boot 4 serialization](https://spring.io/blog/2025/12/18/next-level-kotlin-support-in-spring-boot-4/)
- [OpenTelemetry with Spring Boot](https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/)
- [Exposed 1.0 released](https://blog.jetbrains.com/kotlin/2026/01/exposed-1-0-is-now-available/)
- [Ktor 3.2.0 with HTMX](https://blog.jetbrains.com/kotlin/2025/06/ktor-3-2-0-is-now-available/)
- [kotlinx-coroutines GitHub](https://github.com/Kotlin/kotlinx.coroutines)
- [Kotest releases](https://github.com/kotest/kotest/releases)
- [Gradle 9.4.1 releases](https://gradle.org/releases/)
- [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java)
- [OpenAI Java SDK](https://github.com/openai/openai-java)
- [Flyway Spring Boot 4.x changes](https://pranavkhodanpur.medium.com/flyway-migrations-in-spring-boot-4-x-what-changed-and-how-to-configure-it-correctly-dbe290fa4d47)
- [First JVM-native agent frameworks — Java Code Geeks](https://www.javacodegeeks.com/2026/03/the-first-jvm-native-ai-agent-frameworks-and-why-rod-johnson-built-one-of-them.html)
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

| Skill | Description | Path |
|-------|-------------|------|
| api-feature |  | `.claude/skills/api-feature/SKILL.md` |
| code-review |  | `.claude/skills/code-review/SKILL.md` |
| debugging |  | `.claude/skills/debugging/SKILL.md` |
| pr-artifacts |  | `.claude/skills/pr-artifacts/SKILL.md` |
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
