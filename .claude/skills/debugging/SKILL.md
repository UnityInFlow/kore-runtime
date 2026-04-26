# Debugging Skill
You are debugging an issue in the kore-runtime Kotlin/Spring WebFlux codebase.

## Process
1. **Reproduce** — write a failing test that demonstrates the bug
2. **Isolate** — use a sub-agent to trace the call chain from entry point to failure
3. **Hypothesize** — form a single testable hypothesis before changing code
4. **Fix** — make the minimal change that fixes the failing test
5. **Verify** — run `./gradlew test` to confirm fix and no regressions
6. **Document** — add a comment if the bug was non-obvious

## Common kore-runtime Pitfalls
- Blocking call inside a coroutine scope (use withContext(Dispatchers.IO) for blocking I/O)
- Missing suspend modifier on function that calls suspend functions
- MCP protocol message serialization/deserialization mismatch
- Agent loop not breaking on BudgetExceeded result
- OpenTelemetry span not closed on error path

## What NOT to do
- Do not change multiple things at once — one hypothesis, one change, one test
- Do not add try/catch to silence the error — find the root cause
- Do not skip writing the regression test
