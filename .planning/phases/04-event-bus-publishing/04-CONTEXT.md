# Phase 4: Event Bus & Publishing — Context

**Gathered:** 2026-04-15
**Status:** Ready for planning
**Source:** /gsd-discuss-phase 4 (user selected Option A across all 6 gray areas)

<domain>
## Phase Boundary

Phase 4 delivers two outcomes that together complete the v1.0 milestone:

1. **Event bus formalization and two opt-in messaging adapters.** The `EventBus` port
   (kore-core) and its default `InProcessEventBus` (Kotlin Flows `SharedFlow` with
   DROP_OLDEST) already exist and are consumed by kore-observability, kore-dashboard,
   and kore-spring from Phases 01–03. Phase 4 formalizes the contract with validation
   tests, then adds two opt-in modules — `kore-kafka` and `kore-rabbitmq` — that each
   provide an alternative `EventBus` implementation selectable via `application.yml`
   without touching any agent code.

2. **Maven Central publishing.** All modules currently in `settings.gradle.kts`
   (kore-core, kore-llm, kore-mcp, kore-observability, kore-storage, kore-skills,
   kore-spring, kore-dashboard, kore-test) plus the two new adapters (kore-kafka,
   kore-rabbitmq) are signed and published to the Sonatype Central portal under
   group `dev.unityinflow` at version `0.0.1`. After this phase, external consumers
   can do `implementation("dev.unityinflow:kore-spring:0.0.1")` in their Gradle build
   and get a working runtime.

**Not this phase (deferred or out of scope):**
- A separate DLQ/retry layer for Kafka/Rabbit publish failures — adapters use the
  underlying client library's built-in delivery guarantees only.
- Multi-tenant topic/exchange partitioning — one topic/exchange per kore deployment.
- Schema registry integration (Confluent, Apicurio) — JSON with an embedded
  `type` discriminator is sufficient for v0.0.1.
- Any cross-instance event deduplication — observers are broadcast consumers by
  design (see D-04 below).
- Budget-breaker real integration (blocked on Tool 05).
- Python/TypeScript/mobile SDKs (PROJECT.md out-of-scope).
</domain>

<decisions>
## Implementation Decisions

### D-01 — Kafka adapter shape: thin publisher + thin consumer, no Spring Kafka

The `kore-kafka` module implements `EventBus` directly on top of the official
`org.apache.kafka:kafka-clients` library. No `spring-kafka`, no `KafkaTemplate`,
no `@KafkaListener`. The module is framework-agnostic and reusable from
non-Spring applications.

- **`emit(event: AgentEvent)`** — serializes the event via kotlinx.serialization JSON
  (see D-03), wraps in a `ProducerRecord<String, ByteArray>`, and sends via
  `KafkaProducer.send()`. The producer is created once per `KafkaEventBus` instance
  and closed by the Spring `DisposableBean` / coroutine scope on shutdown.
- **`subscribe(): Flow<AgentEvent>`** — returns a cold Flow that, on collection,
  launches a background coroutine running a `KafkaConsumer.poll()` loop on
  `Dispatchers.IO`. Each polled record is deserialized and emitted to the downstream
  Flow. The loop exits on CoroutineScope cancellation.
- **Topic:** single topic per deployment, name is configurable via
  `kore.event-bus.kafka.topic` (default: `kore-agent-events`). No partitioning by
  agent ID in v0.0.1 — ordering is best-effort and observers don't rely on it.
- **Bootstrap servers:** configurable via `kore.event-bus.kafka.bootstrap-servers`
  (required, no default).
- **Consumer group:** see D-04 for the broadcast semantics.
- **Producer config:** `acks=1` (leader ack, not all in-sync replicas — agent events
  are not transactional data), `linger.ms=10` (small batch window), `compression.type=snappy`.
- **Consumer config:** `enable.auto.commit=true`, `auto.offset.reset=latest`
  (observers are live monitors, not audit consumers — they don't need to replay history).

**Why this and not Spring Kafka:** The hexagonal rule is that adapters are
framework-agnostic. `kore-kafka` should work without any Spring classes on the
classpath. `kore-spring` then has a small auto-configuration class that wires it when
the `kore-kafka` module is present. Same pattern as `kore-storage` + `kore-spring`.

### D-02 — RabbitMQ adapter shape: thin wrapper over amqp-client 5.x, no Spring AMQP

Mirror-image of D-01 for AMQP. `kore-rabbitmq` module uses `com.rabbitmq:amqp-client`
(the official Java client, NOT `spring-amqp`).

- **`emit(event: AgentEvent)`** — serializes via kotlinx.serialization JSON (D-03),
  publishes to a fanout exchange with `persistent=true` delivery mode and publisher
  confirms enabled.
- **`subscribe(): Flow<AgentEvent>`** — declares an auto-delete queue bound to the
  fanout exchange, runs a consumer in a background coroutine, manual ack on successful
  Flow emission. Queue is auto-delete + exclusive so each kore instance gets its own
  queue (see D-04).
- **Exchange:** fanout exchange, name configurable via
  `kore.event-bus.rabbitmq.exchange` (default: `kore.agent-events`). Fanout means
  every bound queue gets every message — matches the broadcast semantics of D-04.
- **Connection:** configurable via `kore.event-bus.rabbitmq.uri`
  (e.g. `amqp://user:pass@host:5672/vhost`, required, no default).
- **Delivery:** persistent + publisher confirms. If the broker is unreachable, `emit()`
  propagates the exception up — observers receive nothing until the broker recovers,
  which is correct failure mode (don't silently drop).

### D-03 — Event serialization: kotlinx.serialization JSON with polymorphic type discriminator

`AgentEvent` sealed class gets `@Serializable` on every subclass. The polymorphic
encoding uses `@JsonClassDiscriminator("type")` so a serialized event looks like:

```json
{
  "type": "LLMCallCompleted",
  "agentId": "agent-42",
  "tokenUsage": { "promptTokens": 120, "completionTokens": 45, "totalTokens": 165 }
}
```

- `kotlinx.serialization` is already a first-class citizen since Phase 3
  (Spring Boot 4 starter, see 03-CONTEXT.md).
- `AgentEvent.kt` currently lives in `kore-core` and has no serialization annotations.
  Phase 4 adds `@Serializable` to the sealed class and every data class subclass
  without introducing new dependencies in kore-core (the annotation is a compile-time
  contract; runtime serialization lives in kore-kafka / kore-rabbitmq).
- A single `Json` instance is created per adapter with `classDiscriminator = "type"`
  and `encodeDefaults = false`.
- kore-core keeps zero external runtime dependencies except kotlinx.coroutines +
  stdlib (the Phase 1 rule). `kotlinx.serialization` is compileOnly / api from
  `kore-core` — adapters bring the runtime. If the compileOnly approach breaks on
  Kotlin 2.x, fall back to moving `@Serializable` annotations into a new thin module
  `kore-core-serialization` that depends on `kore-core`. Planner picks the cleanest
  approach during research.

**Why not Protobuf:** 8 event types at <500 bytes each don't justify a schema file
and a `.proto` compile step. JSON is human-readable in the Kafka UI / RabbitMQ admin
console and debuggable in log aggregators. If a future phase needs schema evolution
discipline, the wire format can be swapped behind the adapter boundary.

### D-04 — Multi-instance semantics: broadcast, every observer sees every event

When multiple JVMs run kore (e.g., 3 dashboard pods in Kubernetes), each JVM must
independently receive every agent event. Observers in kore are not work consumers
— they are read-only reactions that power local OTel spans, local Micrometer
counters, and the local HTMX dashboard view.

- **Kafka:** each `KafkaEventBus` instance uses a unique consumer group ID derived
  from hostname + process ID. Pattern: `kore-${hostname}-${pid}`. This ensures no
  two kore instances share a consumer group, so each instance consumes all partitions
  of the topic. Consumer offset is stored in Kafka under the unique group ID but is
  effectively disposable (auto-offset `latest` on first start).
- **RabbitMQ:** each `RabbitMqEventBus` instance declares its own auto-delete exclusive
  queue bound to the shared fanout exchange. The queue is created when the instance
  starts, deleted when the connection closes. This is the standard RabbitMQ pattern
  for broadcast consumers.

**Documentation:** the kore-kafka and kore-rabbitmq READMEs must explain this
pattern with a concrete "3-pod Kubernetes" example, because it's the most common
source of confusion (devs expect work-queue semantics by default).

### D-05 — Publishing scope: all 11 modules to Maven Central at v0.0.1

All modules in `settings.gradle.kts` plus the two new ones:

1. `kore-core` — pure Kotlin port + default implementations
2. `kore-llm` — LLM backend adapters (Claude, GPT, Ollama, Gemini)
3. `kore-mcp` — MCP protocol client
4. `kore-observability` — OTel + Micrometer observers
5. `kore-storage` — Postgres audit log adapter
6. `kore-skills` — YAML skill loader
7. `kore-spring` — Spring Boot 4 auto-configuration
8. `kore-dashboard` — Ktor + HTMX dashboard
9. `kore-test` — MockLLMBackend + session recorder
10. `kore-kafka` — NEW in Phase 4
11. `kore-rabbitmq` — NEW in Phase 4

Published as individual artifacts so consumers mix and match. Developers who only
want the core runtime pull `kore-core`; developers who want the full Spring Boot
experience pull `kore-spring` and let Gradle transitive resolution bring everything
needed. The two adapter modules (kore-kafka, kore-rabbitmq) are never transitively
pulled — they're opt-in by explicit dependency per EVNT-03 / EVNT-04.

**POM metadata** per module:
- `name` — human-readable (e.g., "kore-runtime — Spring Boot integration")
- `description` — one-sentence module purpose
- `url` — https://github.com/UnityInFlow/kore
- `licenses` — MIT
- `scm` — git URL
- `developers` — Jiří Hermann
- `issueManagement` — GitHub issues

### D-06 — Version strategy: 0.0.1 stable release, then 0.0.2-SNAPSHOT on main

- Drop `-SNAPSHOT` for the release: `version = "0.0.1"` in root `build.gradle.kts`.
- Tag: `git tag v0.0.1 && git push origin v0.0.1`.
- GitHub Release with changelog pointing to the Phase 01–04 SUMMARY artifacts.
- Publish to Sonatype Central portal via `com.gradleup.nmcp` (see D-07).
- Immediately after publish success, bump to `version = "0.0.2-SNAPSHOT"` on main
  so the next commit is not a re-release.

This mirrors the CLAUDE.md release playbook used for spec-linter v0.0.1 and keeps
the ecosystem tools on parallel version cadences.

### D-07 — Sonatype Central portal plugin: com.gradleup.nmcp

Per PROJECT.md tech stack research and CLAUDE.md tech stack reference:

- **Plugin:** `com.gradleup.nmcp` — targets the new Sonatype Central portal API
  (launched 2024), NOT the legacy OSSRH Nexus endpoint.
- **Signing:** standard Gradle `signing` plugin with GPG in-memory keys
  (`signing.inMemoryKey`, `signing.password`) read from environment variables:
  `SIGNING_KEY`, `SIGNING_PASSWORD`.
- **Credentials:** Sonatype portal token from environment variables:
  `SONATYPE_USERNAME`, `SONATYPE_PASSWORD` (token, not account password).
- **Publishing coordinates:** `dev.unityinflow:<module>:0.0.1` for every module.
- **Local validation:** `./gradlew publishToMavenLocal` must produce signed artifacts
  in `~/.m2/repository/dev/unityinflow/` before any Central upload.
- **Staging flow:** `./gradlew publishAllPublicationsToSonatypeRepository` to upload
  to the staging bundle, then `./gradlew nmcpPublishAggregationToCentralPortal` to
  release. Per the plugin docs; planner confirms the exact Gradle tasks during research.

### D-08 — Auto-configuration wiring in kore-spring

`kore-spring` gains two new conditional configurations following the existing
`DashboardAutoConfiguration` pattern from Phase 3:

```kotlin
@AutoConfiguration
@ConditionalOnClass(name = ["dev.unityinflow.kore.kafka.KafkaEventBus"])
@ConditionalOnProperty(prefix = "kore.event-bus", name = ["type"], havingValue = "kafka")
class KafkaEventBusAutoConfiguration {
    @Bean
    fun kafkaEventBus(properties: KoreProperties): EventBus = KafkaEventBus(...)
}

@AutoConfiguration
@ConditionalOnClass(name = ["dev.unityinflow.kore.rabbitmq.RabbitMqEventBus"])
@ConditionalOnProperty(prefix = "kore.event-bus", name = ["type"], havingValue = "rabbitmq")
class RabbitMqEventBusAutoConfiguration { ... }
```

- The existing `@ConditionalOnMissingBean(EventBus::class) fun inProcessEventBus()`
  already in `KoreAutoConfiguration.kt:53-54` stays unchanged. When either adapter's
  `@Bean` fires first, the in-process default is suppressed by `@ConditionalOnMissingBean`.
- Activation order: `kore.event-bus.type=kafka` (or `=rabbitmq`) in `application.yml`
  MUST be present for the adapter to wire even if the module is on the classpath.
  This prevents accidental activation — the module's mere presence is necessary but
  not sufficient.
- `KoreProperties` gains nested `EventBusProperties` with sealed subtypes for the
  adapter configs (bootstrap servers, topic, URI, exchange).
- Uses `@ConditionalOnClass(name = [...])` string form to avoid the classpath
  eager-load pitfall (Pitfall 12 from 03-RESEARCH.md).

### D-09 — EVNT-01 / EVNT-02 validation tests

Neither requirement has a dedicated test today — they're implicitly tested by
Phases 02 and 03 consumers. Phase 4 formalizes them with a small test class in
`kore-core`:

- **EventBusBackpressureTest** — constructs an `InProcessEventBus`, starts a slow
  consumer (1 event/second), emits 200 events synchronously from the producer,
  asserts the consumer sees exactly 64 events (the buffer capacity) + the newest
  events (DROP_OLDEST behavior), and that the producer never suspended.
- **EventBusConcurrencyTest** — 8 concurrent producers emitting 1000 events each,
  single consumer, assert no events lost to race conditions and that the final
  count is ≥ (8 × 1000 - 8 × 64) (i.e., only the 64-buffer worst-case drops happen).
- These tests close Pitfall 9 ("missing buffer config causes suspend under load")
  from 03-RESEARCH.md and give EVNT-01 / EVNT-02 grep-verifiable coverage so
  `gsd-verify-work 4` has something concrete to check.

### D-10 — CI publishing workflow

A new GitHub Actions workflow `.github/workflows/release.yml` triggered on tag
push `v*.*.*`:

1. Checkout
2. Set up JDK 21
3. Set up Gradle cache
4. Decrypt / import signing key from secrets
5. `./gradlew build` (all modules, all tests)
6. `./gradlew publishAllPublicationsToSonatypeRepository`
7. `./gradlew nmcpPublishAggregationToCentralPortal`
8. Create GitHub Release with auto-generated changelog

**Runner:** `runs-on: [arc-runner-unityinflow]` — self-hosted per CLAUDE.md
(never `ubuntu-latest`).

**Secrets needed (set at org level, not per-repo):**
- `SIGNING_KEY` — ASCII-armored GPG private key
- `SIGNING_PASSWORD` — GPG key password
- `SONATYPE_USERNAME` — Sonatype Central portal username
- `SONATYPE_PASSWORD` — Sonatype Central portal token

**Public repo gotcha:** CLAUDE.md notes the runner group has
`allows_public_repositories: false`. If `kore-runtime` is public, either make it
private or enable public repo access on the runner group in GitHub org settings
BEFORE the release workflow runs for the first time.

### Claude's Discretion

The planner and researcher have latitude on:
- Exact Gradle plugin application pattern (plugins block vs. buildSrc vs. convention plugin)
- Whether to use a Gradle `convention plugin` shared across modules or inline
  publishing config in each module's `build.gradle.kts`
- Shutdown lifecycle for the Kafka/Rabbit consumer coroutines (SmartLifecycle?
  DisposableBean? both?) — must be graceful (wait for in-flight events)
- Exact error-handling for broker unreachable at startup — retry with backoff vs.
  fail-fast. Research this against current Kafka / RabbitMQ client behavior.
- Whether kotlinx.serialization annotations can stay in kore-core via compileOnly
  or require a thin `kore-core-serialization` module (decided during research)
- Whether to use a shared test broker via Testcontainers for kore-kafka /
  kore-rabbitmq integration tests, or mock the client API directly
- Wave structure for the 11 modules' publishing setup — probably one plan for
  plugin + signing + POM metadata applied via convention plugin, another plan
  for the CI workflow, etc.
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Upstream phase context
- `.planning/phases/01-core-runtime/01-CONTEXT.md` — hexagonal architecture, zero-dep kore-core, sealed-class AgentEvent
- `.planning/phases/02-observability-storage/02-CONTEXT.md` — EventBusMetricsObserver + EventBusSpanObserver patterns, Kafka/Rabbit must match this shape
- `.planning/phases/03-skills-spring-dashboard/03-CONTEXT.md` — @ConditionalOnClass(name=[...]) string form (Pitfall 12), Spring Boot 4 starter pattern, KoreProperties sealed nested config pattern

### Upstream phase research (pitfall registry)
- `.planning/phases/03-skills-spring-dashboard/03-RESEARCH.md` — Pitfall 9 (DROP_OLDEST buffer config), Pitfall 12 (@ConditionalOnClass eager load), Sonatype nmcp plugin guidance

### Project-level specs and requirements
- `.planning/PROJECT.md` — core value, tech stack research (nmcp plugin, kotlinx.serialization primary), out-of-scope list
- `.planning/REQUIREMENTS.md` — EVNT-01..04 acceptance criteria
- `.planning/ROADMAP.md` — Phase 4 goal, success criteria, dependencies
- `./CLAUDE.md` — Kotlin constraints, CI runner labels, release playbook, env-var secrets list, "Do not add Kafka as a hard dependency"

### Key source files the phase will modify or depend on
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentEvent.kt` — sealed class to annotate with @Serializable
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/EventBus.kt` — port contract, unchanged
- `kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InProcessEventBus.kt` — default impl, to get EventBusBackpressureTest coverage
- `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt` — add Kafka + RabbitMQ auto-configurations
- `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt` — add EventBusProperties nested config
- `kore-observability/src/main/kotlin/dev/unityinflow/kore/observability/EventBusMetricsObserver.kt` — reference for adapter consumer shape
- `build.gradle.kts` (root) — version bump, add nmcp + signing plugins
- `settings.gradle.kts` — add kore-kafka, kore-rabbitmq modules
- `gradle/libs.versions.toml` — add kafka-clients, amqp-client, nmcp plugin aliases

### External docs downstream agents must look up
- Sonatype Central portal migration guide (https://central.sonatype.org/)
- `com.gradleup.nmcp` plugin README and task list
- Apache Kafka clients 3.x / 4.x release notes for minimum-version selection
- RabbitMQ `amqp-client` 5.x API docs for publisher confirms + exclusive queue declaration

</canonical_refs>

<specifics>
## Specific Ideas

- **The release is the milestone moment.** Per CLAUDE.md's release playbook, the
  v0.0.1 publish is the marketing beat — GitHub Release, tweet, r/Kotlin post,
  GSD Discord announcement. Phase 4 planning should include a task for the
  release comms artifacts (changelog markdown, announcement draft) so this doesn't
  get forgotten at the end.

- **Kafka and RabbitMQ adapter modules are mirror-image by design.** Whatever
  structure the Kafka module adopts (package layout, test structure, README
  sections), the RabbitMQ module mirrors. If research surfaces a common interface
  that can be extracted to a `kore-messaging-adapter-common` module, planner
  evaluates but default is NO — don't create a module for two consumers.

- **Sonatype Central portal is the NEW path, not OSSRH.** The legacy
  `io.github.gradle-nexus.publish-plugin` targets OSSRH which is being deprecated.
  `com.gradleup.nmcp` is the right plugin. Reject any suggestion to use the nexus
  plugin.

- **Test strategy must not require live brokers in unit tests.** kore-kafka and
  kore-rabbitmq unit tests mock the client APIs. Integration tests using
  Testcontainers (a real Kafka / RabbitMQ container) are acceptable but marked
  `@Tag("integration")` and excluded from the default `./gradlew test` run. The
  CI publish workflow runs them; local dev doesn't need Docker running to build.
</specifics>

<deferred>
## Deferred Ideas

- **DLQ + retry for Kafka/Rabbit publish failures.** v0.0.1 relies on the
  underlying client's built-in delivery guarantees (Kafka producer retries,
  RabbitMQ publisher confirms). A dedicated DLQ layer is v0.1+ work.
- **Schema registry integration (Confluent, Apicurio).** Defer until a consumer
  actually needs schema evolution discipline across adapter versions.
- **Avro / Protobuf wire formats.** JSON ships first. If payload size or schema
  evolution becomes a real problem, revisit.
- **Multi-tenant topic / exchange partitioning.** One topic/exchange per kore
  deployment for v0.0.1.
- **`kore-messaging-adapter-common` module.** Don't extract shared helpers across
  two adapters; copy-paste is fine for two consumers.
- **NATS / Pulsar / Redis Streams adapters.** Same pattern applies if a user
  contributes one, but not in Phase 4.
- **Publishing kore-runtime as a single fat JAR / BOM.** Each module publishes
  independently in v0.0.1. A BOM module (`kore-bom`) that pins versions can come
  later when there are multiple release versions to manage.
- **Snapshots repo publishing from main branch CI.** v0.0.1 publishes stable
  only. Snapshot publishing can be added later as a separate workflow.
- **Reproducible builds / SBOM generation.** Important for enterprise consumers
  but v0.1+.
- **Signing via hardware token / YubiKey.** The in-memory GPG key path is fine
  for solo-dev v0.0.1; hardware signing is a future concern.
</deferred>

---

*Phase: 04-event-bus-publishing*
*Context gathered: 2026-04-15 via /gsd-discuss-phase 4*
*All 6 gray areas decided: Option A across the board*
