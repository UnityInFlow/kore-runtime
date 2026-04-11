# Contributing to kore

Thank you for your interest in contributing to kore. This document describes how to set up your development environment, the coding standards we follow, and the process for submitting changes.

---

## Development Setup

**Prerequisites:**
- JDK 21 (LTS)
- Gradle 9.x is included via the Gradle wrapper — no separate installation needed

**Clone and build:**

```bash
git clone https://github.com/UnityInFlow/kore-runtime.git
cd kore-runtime
./gradlew build
```

**Run tests:**

```bash
./gradlew test
```

**Run a specific module's tests:**

```bash
./gradlew :kore-core:test
./gradlew :kore-llm:test
./gradlew :kore-mcp:test
./gradlew :kore-test:test
```

---

## Code Style

All Kotlin code is formatted with **ktlint** via the `kotlinter-gradle` plugin.

**Check for violations (runs in CI):**

```bash
./gradlew lintKotlin
```

**Auto-fix formatting violations:**

```bash
./gradlew formatKotlin
```

Run `formatKotlin` before committing. CI runs `lintKotlin` — a formatting violation will fail the build.

---

## Coding Standards

These rules apply to all contributions. They are enforced in code review.

### Kotlin idioms

- `val` everywhere — `var` is not permitted. Refactor to avoid mutation.
- No `!!` (non-null assertion) without a comment explaining why it is safe.
- No `Thread.sleep()` or raw threads — use coroutines (`delay()`, `withContext()`, `async { }`).
- Sealed classes for domain modelling (results, errors, states) — not exceptions for expected failures.
- Coroutines for all async work — the agent loop is coroutine-based throughout.
- Immutable data classes for value objects.

### API design

- KDoc on all `public` and `internal` APIs.
- No secrets in source code — all credentials via environment variables.
- Port interfaces in `kore-core` must have zero external dependencies (the module carries only `kotlinx-coroutines-core`).

### Testing

- New features and bug fixes must include tests.
- Tests use JUnit 5 as the runner and Kotest assertions (`shouldBe`, `shouldBeInstanceOf`, etc.).
- Coroutine tests use `runTest` from `kotlinx-coroutines-test`.
- No network calls in unit tests — use `MockLLMBackend` from `kore-test`.
- Coverage target: >80% on core logic (`kore-core` and `kore-mcp`).

---

## Branch Naming

| Type | Pattern | Example |
|------|---------|---------|
| Feature | `feat/short-description` | `feat/skills-yaml-loader` |
| Bug fix | `fix/short-description` | `fix/budget-exceeded-not-returned` |
| Documentation | `docs/short-description` | `docs/mcp-client-guide` |
| Chore / tooling | `chore/short-description` | `chore/upgrade-coroutines` |

Branch from `main`. Keep branches focused — one feature or fix per PR.

---

## Commit Messages

Follow conventional commits:

```
feat: add S003 no-secrets rule
fix: budget exceeded returns wrong token count
test: add edge cases for empty tool call arguments
docs: update README with fallback chain example
chore: upgrade kotlinx-coroutines to 1.10.2
refactor: extract retry logic into RetryPolicy
```

- Use present tense ("add" not "added")
- Keep the subject line under 72 characters
- Mention the module in the body if the change is module-specific

---

## Pull Request Requirements

Before opening a PR, confirm:

- [ ] `./gradlew test` passes — all tests green
- [ ] `./gradlew lintKotlin` passes — no formatting violations
- [ ] New public APIs have KDoc comments
- [ ] No secrets, API keys, or tokens in any committed file
- [ ] No new `var` declarations
- [ ] No new `!!` assertions without explanatory comments

**PR description should include:**
- What changed and why
- Which modules are affected
- Any decisions made that are not obvious from the code

---

## Reporting Issues

Open a GitHub issue with:
- A minimal reproducible example (a failing test is ideal)
- Expected vs actual behavior
- Kotlin version, JVM version, and kore version

---

## License

By contributing to kore, you agree that your contributions will be licensed under the [MIT License](LICENSE).
