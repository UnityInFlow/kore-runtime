# API Feature Development Skill
Follow this 7-phase workflow for new features in kore-runtime.

## Phases
1. **Discovery** — read existing similar modules; identify patterns in hexagonal architecture
2. **Exploration** — use a sub-agent to trace the relevant port/adapter boundaries
3. **Clarifying Questions** — surface ambiguities before writing any code
4. **Architecture** — define contracts (port interfaces, sealed result types, DTOs)
   -> If decision has architectural impact: create ADR draft in docs/adr/
5. **Implementation** — port interface -> adapter -> service -> tests for each layer
6. **Quality Review** — run `./gradlew ktlintCheck test` and fix all issues
7. **PR Preparation** — write changelog entry, update KDoc, run /skill pr-artifacts

## Kore-Runtime Patterns
- All LLM backends implement the same port interface
- AgentResult sealed class: Success | BudgetExceeded | ToolError | LLMError
- Event bus is pluggable: Kotlin Flows default, Kafka opt-in
- Every LLM call and tool use gets an OpenTelemetry span
- Coroutines everywhere — no blocking calls

## Response Template
After each phase, state: what was done, what was decided, what's next.
