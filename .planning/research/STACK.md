# Technology Stack

**Project:** kore-runtime
**Researched:** 2026-04-10
**Overall confidence:** HIGH (most claims verified against official docs or release pages)

---

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

**Decision note on Koog:** JetBrains released Koog (0.7.3, Alpha) at KotlinConf 2025 — a Kotlin-native coroutine-first agent framework that supports Claude, OpenAI, Ollama, Gemini, and others. Koog is Alpha and a JetBrains incubator project. kore is building a runtime, not adopting someone else's runtime. Koog could be used as a reference implementation for coroutine agent loop patterns, but should not be added as a dependency.

**Decision note on Spring AI:** Spring AI 2.0.0-M2 supports Claude, GPT, Ollama, Gemini natively. Acceptable alternative to LangChain4j for provider adapters. However, Spring AI is opinionated about the agent model and would leak Spring-specific types into the `LLMBackend` interface. LangChain4j's `ChatLanguageModel` is a thinner abstraction that maps more cleanly to a port interface. If Spring AI stabilizes its interfaces before kore-core is written, reconsider.

### MCP Protocol

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| io.modelcontextprotocol:kotlin-sdk | 0.11.1 | MCP client + server | Official MCP Kotlin SDK. Maintained in collaboration with JetBrains. Kotlin Multiplatform. Supports ALL required transports: StdioClientTransport, SseClientTransport, StreamableHttpClientTransport on the client side; StdioServerTransport and Ktor-based SSE/WebSocket on the server side. Coroutine-native. Maven: `io.modelcontextprotocol:kotlin-sdk:0.11.1`. This is the only credible JVM MCP implementation that is both official and coroutine-native. |

**Note:** A separate `io.modelcontextprotocol.sdk:mcp` Java SDK exists (backed by Spring). Do NOT use it — it is Reactor-based and would introduce a second reactive model alongside the coroutine model. The Kotlin SDK already depends on Ktor for transport, which aligns with the dashboard module.

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

**Why not jOOQ:** jOOQ is an excellent choice for complex SQL and database-first schemas. For kore's audit log (simple INSERT + SELECT queries, schema-first via Flyway migrations), Exposed provides equal safety with much less setup. jOOQ requires code generation from the schema; Exposed does not. If query complexity grows, jOOQ is a credible migration target.

### Observability

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| opentelemetry-sdk | 2.x (via Spring Boot 4 starter) | Distributed tracing spans | Spring Boot 4 ships `spring-boot-starter-opentelemetry` — this is the new recommended path (replaces the previous community starter). Includes the OTel API, Micrometer tracing bridge, and OTLP exporters. |
| micrometer-core | 1.16+ (bundled via Boot 4) | Metrics: counters, timers, gauges | Micrometer is the metrics facade. Spring Boot 4 includes Micrometer 1.16. Use the Observation API (`ObservationRegistry`) rather than raw meters — it handles both metrics and tracing in one call. |
| micrometer-tracing | bundled | Trace context propagation | Spring Boot 4 replaces Sleuth with Micrometer Tracing. Bridges to OpenTelemetry exporter. |

**Note on OTel Kotlin SDK:** OpenTelemetry released a Kotlin Multiplatform SDK in early 2026, primarily targeting Android/mobile. For a Spring Boot server application, use the Java SDK via the Spring Boot starter — it is more mature and has zero-code instrumentation via the Java agent.

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

---

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

---

## Installation — Gradle Coordinates Reference

```kotlin
// build.gradle.kts (root)

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jmailen.kotlinter") version "5.0.0"
    id("org.springframework.boot") version "4.0.5" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

// kore-core/build.gradle.kts
dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

    // LLM clients
    implementation("com.anthropic:anthropic-java:2.20.0")
    implementation("com.openai:openai-java:4.30.0")
    implementation("dev.langchain4j:langchain4j-ollama:1.0.1")       // version confirm at release time
    implementation("dev.langchain4j:langchain4j-google-ai-gemini:1.0.1")

    // MCP
    implementation("io.modelcontextprotocol:kotlin-sdk:0.11.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
    testImplementation("io.kotest:kotest-assertions-core:6.1.11")
    testImplementation("io.mockk:mockk:1.14.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// kore-storage/build.gradle.kts
dependencies {
    implementation("org.jetbrains.exposed:exposed-core:1.0.0")
    implementation("org.jetbrains.exposed:exposed-r2dbc:1.0.0")
    implementation("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
    implementation("org.flywaydb:flyway-core:12.3.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.3.0")
}

// kore-dashboard/build.gradle.kts
dependencies {
    implementation("io.ktor:ktor-server-cio:3.2.0")
    implementation("io.ktor:ktor-server-htmx:3.2.0")
    implementation("io.ktor:ktor-server-html-builder:3.2.0")
}

// kore-spring/build.gradle.kts
plugins {
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
}
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}

// kore-test/build.gradle.kts
dependencies {
    api("io.mockk:mockk:1.14.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    api("io.kotest:kotest-assertions-core:6.1.11")
    testImplementation("org.testcontainers:postgresql:1.20.0")
}
```

**Note:** Confirm exact minor versions of LangChain4j 1.0.x patch releases at build time — the search revealed 1.0.0 GA shipped May 2025; the project uses `1.0.x` (latest patch). Anthropic SDK (`2.20.0`) and OpenAI SDK (`4.30.0`) are beta; pin exact versions and upgrade deliberately.

---

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

---

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
