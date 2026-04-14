# Phase 3 — Deferred Items

Out-of-scope discoveries logged during plan execution. **Not fixed** in the
plans they were found in; tracked here for a future cleanup pass.

## From Plan 03-04

### Pre-existing lint failures in HeroDemoTest.kt (Phase 1)

`./gradlew :kore-core:lintKotlinTest` fails with two ktlint violations on
`kore-core/src/test/kotlin/dev/unityinflow/kore/core/integration/HeroDemoTest.kt`:

```
HeroDemoTest.kt:115:22: [standard:multiline-expression-wrapping] A multiline expression should start on a new line
HeroDemoTest.kt:117:36: [standard:no-multi-spaces] Unnecessary long whitespace
```

- **Source:** Committed in `825cab3 test(01-07): add HeroDemoTest end-to-end integration test` (Phase 1)
- **Why deferred:** Out of scope for plan 03-04 (Phase 3 Spring/dashboard wiring). The plan executor prompt explicitly notes that pre-existing edits to `HeroDemoTest.kt` exist in a stash and should be left alone.
- **Suggested fix:** Run `./gradlew :kore-core:formatKotlin` and commit the formatter output as a `chore(lint)` follow-up after the stash is reapplied or discarded.

### LangChain4j version skew (kore-llm)

`kore-llm` has a transitive version skew between `langchain4j-ollama 0.26.1` and
`langchain4j-google-ai-gemini 0.36.1` resolving to incompatible
`langchain4j-core` versions, causing `NoSuchMethodError` if the OllamaBackend is
constructed eagerly at startup.

- **Workaround in place:** `OllamaLlmAutoConfiguration` is gated on
  `kore.llm.ollama.enabled=true` (default `false`) so adding kore-llm to the
  classpath does not eagerly construct an OllamaChatModel. Documented in plan
  03-02 SUMMARY and STATE.md decisions.
- **Why deferred:** Root-cause fix requires a coordinated bump of all four
  langchain4j adapters to the same `langchain4j-core` line, which may break
  Gemini integration tests. Tracked for v0.1 / Phase 4.
- **Suggested fix:** Open a tracking issue against kore-llm to upgrade to
  `langchain4j 1.0.x` and verify the Gemini + Ollama backends still pass their
  contract tests under the unified core version.
