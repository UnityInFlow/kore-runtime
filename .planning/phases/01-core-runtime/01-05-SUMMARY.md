---
phase: 01-core-runtime
plan: "05"
subsystem: kore-llm
tags: [kotlin, llm-adapters, claude, openai, ollama, gemini, langchain4j, tdd, coroutines, hexagonal]

# Dependency graph
requires:
  - 01-02 (LLMBackend port interface, LLMChunk, ConversationMessage, ToolDefinition, LLMConfig)
  - 01-03 (ResilientLLMBackend + fallbackTo infix — LLM adapters plug into the resilience layer)
provides:
  - ClaudeBackend: Anthropic Java SDK 0.1.0 adapter implementing LLMBackend
  - OpenAiBackend: OpenAI Java SDK 4.30.0 adapter implementing LLMBackend
  - OllamaBackend: LangChain4j 0.26.1 HTTP transport adapter implementing LLMBackend
  - GeminiBackend: LangChain4j 0.36.1 HTTP transport adapter implementing LLMBackend
  - DSL factory functions: claude(), gpt(), ollama(), gemini() in LlmBackends.kt
affects:
  - 01-06+ (Any plan using agent { model = claude() } DSL — factory functions now exist)
  - All plans using fallbackTo infix (claude() fallbackTo gpt() now works end-to-end)

# Tech tracking
tech-stack:
  added:
    - com.anthropic:anthropic-java-core:0.1.0 (actual available version; plan referenced 2.20.0)
    - com.anthropic:anthropic-java-client-okhttp:0.1.0 (OkHttp transport for Anthropic)
    - com.openai:openai-java:4.30.0 (as planned)
    - dev.langchain4j:langchain4j-ollama:0.26.1 (actual; plan referenced 1.0.1)
    - dev.langchain4j:langchain4j-google-ai-gemini:0.36.1 (actual; plan referenced 1.0.1)
  patterns:
    - TDD RED/GREEN: test files written before production code in both tasks
    - Visitor pattern for ContentBlock: isText()/isToolUse() + asText()/asToolUse() (Anthropic SDK 0.1.0)
    - Optional.orElse(null) pattern: convert Java Optional to nullable Kotlin before calling suspend emit()
    - Injectable ChatLanguageModel: OllamaBackend and GeminiBackend accept ChatLanguageModel in constructor for testability
    - Per-backend Semaphore: each backend instance gets its own Semaphore — not shared (D-18, T-05-04)
    - withContext(Dispatchers.IO) around all SDK blocking calls (Pitfall 1, T-05-03)

key-files:
  created:
    - kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/ClaudeBackend.kt
    - kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/OpenAiBackend.kt
    - kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/OllamaBackend.kt
    - kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/GeminiBackend.kt
    - kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/LlmBackends.kt
    - kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/ClaudeBackendTest.kt
    - kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/OpenAiBackendTest.kt
    - kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/OllamaBackendTest.kt
    - kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/GeminiBackendTest.kt
  modified:
    - gradle/libs.versions.toml (version corrections: anthropic 2.20.0→0.1.0; langchain4j 1.0.1→0.26.1/0.36.1; added anthropic-java-okhttp entry)
    - kore-llm/build.gradle.kts (added anthropic-java-okhttp dependency)

key-decisions:
  - "anthropic-java version corrected from 2.20.0 to 0.1.0 — planned version does not exist on Maven Central (latest is 0.1.0)"
  - "langchain4j version corrected from 1.0.1 to 0.26.1 (ollama) and 0.36.1 (gemini) — planned version does not exist on Maven Central"
  - "Gradle resolves langchain4j-core to 0.36.1 for both ollama and gemini via version conflict resolution — API compatible"
  - "anthropic-java-core + anthropic-java-client-okhttp are two separate artifacts in 0.1.0 — core is the main dep, okhttp is needed for AnthropicOkHttpClient in LlmBackends.kt"
  - "ContentBlock uses Visitor pattern (isText()/asText()) in Anthropic SDK 0.1.0 — not Kotlin when/is pattern (sealed class is Java, not sealed in Kotlin)"
  - "ChatLanguageModel injected as constructor parameter to OllamaBackend and GeminiBackend — enables MockK mocking without network"
  - "Optional.ifPresent() is not suspend-safe; used Optional.orElse(null) + Kotlin null checks before calling emit()"

requirements-completed:
  - LLM-01
  - LLM-02
  - LLM-03
  - LLM-04
  - LLM-05

# Metrics
duration: 15min
completed: 2026-04-10
---

# Phase 1 Plan 05: LLM Backend Adapters Summary

**All four LLM adapters (Claude, GPT, Ollama, Gemini) implement the LLMBackend port interface with IO-safe coroutine wrapping, per-backend semaphore concurrency control, and zero provider type leakage — DSL factory functions `claude()`, `gpt()`, `ollama()`, `gemini()` complete the adapter layer**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-04-10T19:18:00Z
- **Completed:** 2026-04-10T19:34:06Z
- **Tasks:** 2
- **Files modified:** 11

## Accomplishments

- ClaudeBackend: Anthropic Java SDK 0.1.0 adapter with ContentBlock Visitor pattern, system prompt extraction, semaphore-guarded Dispatchers.IO wrapping
- OpenAiBackend: OpenAI Java SDK 4.30.0 adapter with per-role message param types, Optional.orElse(null) before suspend emit, tool call translation
- OllamaBackend: LangChain4j 0.26.1 transport adapter with injectable ChatLanguageModel, ConversationMessage→langchain4j mapping, full type isolation
- GeminiBackend: LangChain4j 0.36.1 transport adapter — same pattern as OllamaBackend, full type isolation
- LlmBackends.kt: claude(), gpt(), ollama(), gemini() factory functions using the correct SDK builder APIs
- 20 TDD tests pass: 5 per backend (name, text chunk, tool call, usage, Done), all using MockK with no real API calls
- kore-core runtimeClasspath unchanged — all SDK dependencies remain in kore-llm

## API Reference

### DSL Factory Functions (LlmBackends.kt)

```kotlin
// Claude (Anthropic Java SDK 0.1.0)
fun claude(apiKey: String, model: String = "claude-3-5-sonnet-20241022", maxConcurrency: Int = 10): ClaudeBackend

// GPT (OpenAI Java SDK 4.30.0)
fun gpt(apiKey: String, model: String = "gpt-4o", maxConcurrency: Int = 10): OpenAiBackend

// Ollama (LangChain4j 0.26.1)
fun ollama(baseUrl: String = "http://localhost:11434", model: String = "llama3", maxConcurrency: Int = 2): OllamaBackend

// Gemini (LangChain4j 0.36.1)
fun gemini(apiKey: String, model: String = "gemini-1.5-flash", maxConcurrency: Int = 10): GeminiBackend
```

### Backend Class Signatures

```kotlin
class ClaudeBackend(client: AnthropicClient, defaultModel: String, semaphore: Semaphore) : LLMBackend
class OpenAiBackend(client: OpenAIClient, defaultModel: String, semaphore: Semaphore) : LLMBackend
class OllamaBackend(chatModel: ChatLanguageModel, semaphore: Semaphore) : LLMBackend
class GeminiBackend(chatModel: ChatLanguageModel, semaphore: Semaphore) : LLMBackend
```

## Task Commits

Each task was committed atomically:

1. **Task 1: ClaudeBackend and OpenAiBackend with TDD tests** — `f2fcb76` (feat)
2. **Task 2: OllamaBackend, GeminiBackend, and DSL factory functions** — `8bb6e63` (feat)

## Files Created

- `kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/ClaudeBackend.kt` — Anthropic adapter, Visitor pattern for ContentBlock
- `kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/OpenAiBackend.kt` — OpenAI adapter, per-role message params
- `kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/OllamaBackend.kt` — LangChain4j ollama transport adapter
- `kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/GeminiBackend.kt` — LangChain4j gemini transport adapter
- `kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/LlmBackends.kt` — DSL factory functions
- `kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/ClaudeBackendTest.kt` — 5 TDD tests
- `kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/OpenAiBackendTest.kt` — 5 TDD tests
- `kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/OllamaBackendTest.kt` — 5 TDD tests
- `kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/GeminiBackendTest.kt` — 5 TDD tests

## Decisions Made

- **anthropic-java version**: Corrected 2.20.0 → 0.1.0. The planned version 2.20.0 does not exist on Maven Central; 0.1.0 is the only available version.
- **langchain4j version**: Corrected 1.0.1 → 0.26.1 (ollama) and 0.36.1 (gemini). Version 1.0.1 does not exist on Maven Central.
- **Two langchain4j version catalog entries**: `langchain4j-ollama` and `langchain4j-gemini` use separate version refs since they are on different release trains. Gradle resolves the shared `langchain4j-core` transitive to 0.36.1 for both.
- **ContentBlock Visitor**: Anthropic SDK 0.1.0 uses isText()/asText() Visitor pattern, not Kotlin sealed classes. The plan's template code using `is ContentBlock.Text` is not the correct API for this version.
- **Optional.orElse(null)**: Java's `Optional.ifPresent(Consumer)` cannot call Kotlin suspend functions. Used `Optional.orElse(null)` + Kotlin null checks before every `emit()` call.
- **ChatLanguageModel as constructor param**: OllamaBackend and GeminiBackend accept the interface (not concrete classes) so MockK can mock them without needing a running Ollama/Gemini server.

## Deviations from Plan

### Auto-fixed Issues (Rule 3 — Blocking)

**1. [Rule 3 - Blocking] Dependency versions don't exist on Maven Central**
- **Found during:** Task 1 dependency resolution
- **Issue:** `anthropic-java:2.20.0` returns 404 from Maven Central (latest is `0.1.0`). `langchain4j:1.0.1` similarly doesn't exist (latest stable is `0.26.1` for ollama, `0.36.1` for gemini).
- **Fix:** Updated `gradle/libs.versions.toml` to use correct available versions: anthropic `0.1.0`, added separate `langchain4j-ollama` and `langchain4j-gemini` version refs
- **Files modified:** `gradle/libs.versions.toml`, `kore-llm/build.gradle.kts`

**2. [Rule 3 - Blocking] anthropic-java-core is split from anthropic-java-client-okhttp**
- **Found during:** Task 1 implementation
- **Issue:** The Anthropic SDK 0.1.0 is two artifacts: `anthropic-java-core` (interfaces) and `anthropic-java-client-okhttp` (transport). The plan's single `anthropic-java` dependency only resolves to a thin redirector JAR.
- **Fix:** Added both `libs.anthropic.java` (core) and `libs.anthropic.java.okhttp` dependencies to `kore-llm/build.gradle.kts`
- **Files modified:** `gradle/libs.versions.toml`, `kore-llm/build.gradle.kts`

**3. [Rule 1 - Bug] ContentBlock API is Visitor pattern, not Kotlin sealed class**
- **Found during:** Task 1 compilation
- **Issue:** Plan template used `is ContentBlock.Text` and `is ContentBlock.ToolUse` (Kotlin sealed class pattern). SDK 0.1.0 uses `isText()`, `asText()`, `isToolUse()`, `asToolUse()` Visitor methods.
- **Fix:** ClaudeBackend uses `when { block.isText() -> ... block.isToolUse() -> ... }` pattern
- **Files modified:** `ClaudeBackend.kt`

**4. [Rule 1 - Bug] Optional.ifPresent() not suspend-safe in OpenAiBackend**
- **Found during:** Task 1 compilation
- **Issue:** `completion.content().ifPresent { text -> emit(...) }` fails: "Suspension functions can only be called within coroutine body"
- **Fix:** Changed to `val text = completion.content().orElse(null); if (text != null) emit(...)` pattern throughout OpenAiBackend
- **Files modified:** `OpenAiBackend.kt`

---

**Total deviations:** 4 auto-fixed (Rules 1 and 3 — dependency version corrections and SDK API differences)
**Impact on plan:** All adapters implement the identical LLMBackend contract as planned. No API changes to kore-core.

## Threat Mitigations Applied

Per the plan's threat model:

- **T-05-01** (Information Disclosure — API key logging): API keys are constructor parameters; `KDoc` on all backends and factory functions explicitly notes "API keys MUST be provided via constructor — never logged". No key values appear in LLMChunk content.
- **T-05-03** (DoS — blocking calls): All SDK HTTP calls in all 4 backends wrapped in `withContext(Dispatchers.IO)`. Verified by `grep -l "withContext(Dispatchers.IO)" *.kt` returning all 4 files.
- **T-05-04** (DoS — Semaphore not per-backend): `Semaphore` is a constructor parameter on all 4 backends. Each factory function creates a new `Semaphore(maxConcurrency)`. OllamaBackend defaults to `Semaphore(2)` for limited local concurrency.
- **T-05-05** (Spoofing — LangChain4j type leakage): `grep -r "dev.langchain4j" kore-core/src/` returns nothing. All LangChain4j types (`ChatLanguageModel`, `AiMessage`, `ToolExecutionRequest`) are fully internal to OllamaBackend and GeminiBackend.

## Known Stubs

None — all 4 adapters are fully functional implementations. The factory functions build real SDK clients. Tests mock the SDK layer, not the adapters themselves.

## Threat Flags

None — kore-llm creates no new network endpoints, auth paths, or trust boundary changes beyond what the SDK dependencies already establish. The adapters are outbound HTTP clients, not servers.

## Next Phase Readiness

- `claude()`, `gpt()`, `ollama()`, `gemini()` factory functions can now be used in the `agent { model = claude(...) }` DSL
- `ResilientLLMBackend.fallbackTo` chains work: `claude() fallbackTo gpt() fallbackTo ollama()`
- kore-test `SessionRecorder` can now wrap any of these real backends to record sessions for CI replay
- No blockers for Plan 06 or later plans — kore-llm module is complete for Phase 1

---
## Self-Check: PASSED

- FOUND: kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/ClaudeBackend.kt
- FOUND: kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/OpenAiBackend.kt
- FOUND: kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/OllamaBackend.kt
- FOUND: kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/GeminiBackend.kt
- FOUND: kore-llm/src/main/kotlin/dev/unityinflow/kore/llm/LlmBackends.kt
- FOUND: kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/ClaudeBackendTest.kt
- FOUND: kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/OpenAiBackendTest.kt
- FOUND: kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/OllamaBackendTest.kt
- FOUND: kore-llm/src/test/kotlin/dev/unityinflow/kore/llm/GeminiBackendTest.kt
- FOUND commit f2fcb76 (Task 1)
- FOUND commit 8bb6e63 (Task 2)

---
*Phase: 01-core-runtime*
*Completed: 2026-04-10*
