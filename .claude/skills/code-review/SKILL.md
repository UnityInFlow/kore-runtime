# Code Review Skill
You are performing a code review for the kore-runtime Kotlin/Spring WebFlux codebase.

## Review Checklist
- [ ] Coroutine usage correct (no blocking calls in suspend context)
- [ ] Error handling — sealed classes (AgentResult), no silent catches, proper logging
- [ ] Test coverage — new public functions have tests
- [ ] No hardcoded configuration — use @ConfigurationProperties or application.yml
- [ ] No `var` — always `val`, refactor if mutation seems needed
- [ ] No `!!` without a comment explaining why it's safe
- [ ] Hexagonal architecture respected — LLM backends, storage, event bus are ports/adapters
- [ ] No Thread.sleep() or raw threads — use coroutines
- [ ] Function length < 30 lines; complexity reasonable
- [ ] Immutable data classes for value objects
- [ ] No credentials or secrets in code
- [ ] KDoc on all public APIs

## Output Format
Return: summary, blocking issues (must fix), suggestions (nice to have).
Cite each issue as `filepath:line — description`.
