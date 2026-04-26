# Milestones

## v0.0.1 Initial MVP (Shipped: 2026-04-26)

**Phases completed:** 4 phases, 21 plans, 35 tasks

**Key accomplishments:**

- Gradle 9.4.1 multi-module project with version catalog, 4 subproject scaffolds (kore-core/mcp/llm/test), and GitHub Actions CI on arc-runner-unityinflow + orangepi runners
- AgentResult sealed class (5 variants), LLMChunk sealed class (4 variants), 10 domain value objects, and 5 port interfaces defining the hexagonal architecture boundary — kore-core compiles with only coroutines as external dependency
- Coroutine-based ReAct agent loop with AgentResult sealed class returns, `agent { }` DSL with @KoreDsl DslMarker, ResilientLLMBackend with fallbackTo infix, and three in-memory stub implementations — kore-core's beating heart is complete
- MockLLMBackend with scripted coroutine sequences, MockToolProvider with named handlers, SessionRecorder wrapping any LLMBackend to JSON, and SessionReplayer for deterministic CI replay — kore's unique testing story is now complete
- All four LLM adapters (Claude, GPT, Ollama, Gemini) implement the LLMBackend port interface with IO-safe coroutine wrapping, per-backend semaphore concurrency control, and zero provider type leakage — DSL factory functions `claude()`, `gpt()`, `ollama()`, `gemini()` complete the adapter layer
- One-liner:
- End-to-end integration test validates the full Phase 1 stack with in-process mocks, README hero demo matches working test syntax, ktlint clean across all 4 modules, 58 tests pass — Phase 1 complete.
- One-liner:
- PostgreSQL audit log for kore-runtime: Flyway migration, Exposed 1.0 R2DBC table objects, JSONB type mapping, and PostgresAuditLogAdapter — all 7 Testcontainers integration tests passing.
- One-liner:
- YAML skills engine backed by jackson-dataformat-yaml with classpath+filesystem loader, SkillRegistry port wired into AgentLoop with optional OTel span, and Exposed R2DBC read queries on PostgresAuditLogAdapter for dashboard consumption.
- Created:
- Ktor 3.2 CIO embedded dashboard side-car wired as a Spring SmartLifecycle bean. Three HTMX-polled fragments (`/kore/fragments/active-agents`, `/recent-runs`, `/cost-summary`) render via kotlinx.html DSL components matching UI-SPEC.md exactly — colours, typography, accessibility attributes, copy. The 3-arg constructor `(EventBus, AuditLog, DashboardProperties)` is the contract kore-spring's reflective bridge consumes today and the direct constructor call plan 03-04 will swap in.
- Final Phase 3 gate — replaces the reflective DashboardServer bridge in KoreAutoConfiguration with a direct constructor call, lands a full @SpringBootTest integration test exercising every Phase 3 module against a single user-supplied @Bean agent, and adds the README hero demo showing the one-dependency / one-bean / live-dashboard developer experience that has been Phase 3's promise from day one.
- Root cause.
- Grep-verifiable EventBus contract tests, compileOnly kotlinx.serialization with simple-name polymorphic type discriminator on AgentEvent, and pre-scaffolded kore-kafka + kore-rabbitmq module stubs so Wave 2 can run 04-02 and 04-03 in parallel without shared-file races.
- Framework-agnostic Apache Kafka implementation of EventBus with coroutine producer bridge, dispatcher-injectable consumer loop, and per-JVM broadcast consumer groups. Zero Spring dependency; wires optionally via kore-spring.
- Framework-agnostic RabbitMQ implementation of EventBus with lazy broker connection, fanout-exchange broadcast semantics, publisher confirms, and dispatcher-injectable consumer loop. Zero Spring AMQP dependency; wires optionally via kore-spring.
- Two new Spring Boot @AutoConfiguration inner classes in KoreAutoConfiguration.kt that switch the EventBus bean to KafkaEventBus or RabbitMqEventBus via `kore.event-bus.type={kafka,rabbitmq}` in application.yml, with zero transitive runtime deps and Spring context tests that never open a real broker socket.
- 1. [Rule 3 - Blocking] Added `java-library` plugin to convention plugin

---
