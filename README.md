# kore

**Production-grade Kotlin agent runtime for the JVM.**

kore gives Kotlin and Java teams everything they need to run AI agents in production: a coroutine-based agent loop, multi-LLM backends, MCP protocol support, budget enforcement, and first-class testing utilities — wired together with a clean Kotlin DSL.

[![Build](https://github.com/UnityInFlow/kore-runtime/actions/workflows/ci.yml/badge.svg)](https://github.com/UnityInFlow/kore-runtime/actions)
[![Maven Central](https://img.shields.io/maven-central/v/dev.unityinflow/kore-core)](https://central.sonatype.com/search?q=dev.unityinflow)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## The Problem

LangChain4j provides an LLM call abstraction but does not give you:

- A reactive execution model (coroutines, structured concurrency, cancellation propagation)
- A built-in skills system with YAML-driven pattern activation
- Token budget governance integrated into the agent loop
- An event bus for agent lifecycle events (Kotlin Flows by default; Kafka opt-in)
- OpenTelemetry spans and Micrometer metrics on every LLM call and tool use
- A testing module with scripted mock backends for deterministic agent tests

**kore fills that gap for enterprise Java/Kotlin teams.**

---

## Quick Start

Add the Spring Boot starter to your Gradle build:

```kotlin
implementation("dev.unityinflow:kore-spring-boot-starter:0.0.1-SNAPSHOT")
```

Write an agent:

```kotlin
import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.dsl.agent
import dev.unityinflow.kore.llm.claude
import dev.unityinflow.kore.mcp.mcp
import java.util.UUID

val runner = agent("my-agent") {
    model = claude(apiKey = System.getenv("ANTHROPIC_API_KEY"))
    tools(mcp("github", "npx", "-y", "@github/github-mcp-server"))
    budget(maxTokens = 10_000)
}

val result = runner.run(AgentTask(id = UUID.randomUUID().toString(), input = "Create a GitHub issue")).await()

when (result) {
    is AgentResult.Success -> println("Done: ${result.output}")
    is AgentResult.BudgetExceeded -> println("Budget exceeded after ${result.tokenUsage.totalTokens} tokens")
    is AgentResult.ToolError -> println("Tool failed: ${result.message}")
    is AgentResult.LLMError -> println("LLM error: ${result.message}")
}
```

---

## Examples

### Example 1: Fallback chain for LLM resilience

```kotlin
import dev.unityinflow.kore.core.dsl.agent
import dev.unityinflow.kore.core.internal.fallbackTo
import dev.unityinflow.kore.llm.claude
import dev.unityinflow.kore.llm.gpt

val runner = agent("resilient-agent") {
    model = claude(apiKey = System.getenv("ANTHROPIC_API_KEY")) fallbackTo
            gpt(apiKey = System.getenv("OPENAI_API_KEY"))
    budget(maxTokens = 5_000)
}
```

If the Claude backend fails (rate limit, network error, API outage), the agent automatically retries with exponential backoff, then falls back to GPT-4o without any changes to your application code.

### Example 2: Local model with Ollama

```kotlin
import dev.unityinflow.kore.core.dsl.agent
import dev.unityinflow.kore.llm.ollama

val runner = agent("local-agent") {
    model = ollama(baseUrl = "http://localhost:11434", model = "llama3")
}
```

Zero API keys, zero cloud dependencies. The same `agent { }` DSL works with any backend.

### Example 3: Deterministic testing with MockLLMBackend

```kotlin
import dev.unityinflow.kore.core.AgentResult
import dev.unityinflow.kore.core.AgentTask
import dev.unityinflow.kore.core.LLMChunk
import dev.unityinflow.kore.core.dsl.agent
import dev.unityinflow.kore.test.MockLLMBackend
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MyAgentTest {
    @Test
    fun `agent returns scripted response without network`() = runTest {
        val runner = agent("test-agent") {
            model = MockLLMBackend("mock")
                .whenCalled(
                    LLMChunk.Text("Hello from kore!"),
                    LLMChunk.Usage(inputTokens = 20, outputTokens = 10),
                    LLMChunk.Done,
                )
            budget(maxTokens = 1_000)
        }

        val result = runner.run(AgentTask(id = "test-1", input = "say hello")).await()

        val success = result.shouldBeInstanceOf<AgentResult.Success>()
        success.output shouldBe "Hello from kore!"
        success.tokenUsage.inputTokens shouldBe 20
    }
}
```

`MockLLMBackend` scripts exact `LLMChunk` sequences. No network, no flakiness, no API costs in CI. This is a key differentiator — most JVM agent frameworks have no testing story at all.

---

## Module Overview

| Module | Maven Artifact | What it provides |
|--------|---------------|-----------------|
| `kore-core` | `dev.unityinflow:kore-core` | Agent loop, DSL, sealed result types, port interfaces, in-memory stubs. No external dependencies except `kotlinx-coroutines-core`. |
| `kore-llm` | `dev.unityinflow:kore-llm` | LLM backend adapters: Claude, GPT-4, Ollama, Gemini. DSL factory functions: `claude()`, `gpt()`, `ollama()`, `gemini()`. |
| `kore-mcp` | `dev.unityinflow:kore-mcp` | MCP protocol client (stdio + SSE) and server. DSL factory functions: `mcp()`, `mcpSse()`. |
| `kore-test` | `dev.unityinflow:kore-test` | `MockLLMBackend`, `MockToolProvider`, session recording and replay for deterministic agent tests. |

### Coming in later phases

| Module | What it will provide |
|--------|---------------------|
| `kore-observability` | OpenTelemetry spans + Micrometer metrics on every LLM call and tool use |
| `kore-storage` | PostgreSQL audit log via Flyway migrations |
| `kore-spring` | Spring Boot auto-configuration starter (`kore-spring-boot-starter`) |
| `kore-skills` | YAML skill definitions with pattern-based auto-activation |
| `kore-dashboard` | HTMX dashboard: active agents, recent traces, token cost |

---

## Requirements

- Kotlin 2.0+ / JVM 21+
- Gradle 9.x (Kotlin DSL)

For LLM backends, you will need the relevant API keys (see [LlmBackends.kt](kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/LlmBackends.kt) for environment variable names).

---

## Building from Source

```bash
./gradlew build         # compile + test all modules
./gradlew test          # run all tests
./gradlew lintKotlin    # ktlint check
./gradlew formatKotlin  # ktlint format
```

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

---

## License

[MIT](LICENSE) — 2026 Jiří Hermann / UnityInFlow contributors
