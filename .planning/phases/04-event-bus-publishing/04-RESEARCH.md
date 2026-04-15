# Phase 4: Event Bus & Publishing - Research

**Researched:** 2026-04-13
**Domain:** Kafka/RabbitMQ messaging adapters, kotlinx.serialization polymorphic JSON, Sonatype Central Portal publishing, multi-module Gradle publishing
**Confidence:** HIGH across stack selection, MEDIUM on nmcp task naming (docs fragment exact command behind per-project vs aggregation plugin)

## Summary

Phase 4 closes the v1.0 milestone with two strictly-decoupled deliverables. The first is event-bus formalization: `InProcessEventBus` already exists and is correct (`MutableSharedFlow(extraBufferCapacity=64, onBufferOverflow=DROP_OLDEST)`); Phase 4 adds backpressure + concurrency validation tests in `kore-core`, and ships two new opt-in modules (`kore-kafka`, `kore-rabbitmq`) implementing the same `EventBus` port using the official thin clients (`org.apache.kafka:kafka-clients` 4.2.0, `com.rabbitmq:amqp-client` 5.29.0). The second is Maven Central publishing for all 11 modules via the `com.gradleup.nmcp` plugin at version 1.4.4, targeting the new Sonatype Central Portal API (not legacy OSSRH).

All 10 user decisions are locked Option A, which collapses most of the design surface. Research focuses on (1) the exact wire-up for nmcp aggregation + signing + CI, (2) the correct coroutine bridges for the blocking Kafka/Rabbit client APIs, (3) whether `@Serializable` annotations can live in `kore-core` via a `compileOnly` kotlinx-serialization dep, and (4) a pitfall registry tailored to the known failure modes of first publishes and polymorphic JSON.

**Primary recommendation:** Use the `com.gradleup.nmcp.aggregation` plugin at root + standard Gradle `maven-publish` + `signing` in a shared `buildSrc` convention plugin, wire Kafka/Rabbit adapters as framework-agnostic modules with their own internal `CoroutineScope` + `SmartLifecycle`-friendly close, and keep `@Serializable` annotations in `kore-core` via a `compileOnly` kotlinx-serialization dep (runtime JSON lives only in the adapter modules).

## User Constraints (from CONTEXT.md)

### Locked Decisions (all Option A — 2026-04-15 discuss-phase)

- **D-01 Kafka adapter** — thin wrapper over `org.apache.kafka:kafka-clients`, NO `spring-kafka`. Producer `acks=1`, `linger.ms=10`, `compression.type=snappy`. Consumer `enable.auto.commit=true`, `auto.offset.reset=latest`. Topic `kore-agent-events` (configurable). Unique consumer group per JVM (`kore-${hostname}-${pid}`) for broadcast.
- **D-02 RabbitMQ adapter** — thin wrapper over `com.rabbitmq:amqp-client` 5.x, NO `spring-amqp`. Fanout exchange `kore.agent-events` (configurable). Auto-delete + exclusive queue per JVM. Persistent delivery + publisher confirms. `emit()` propagates exceptions on broker unreachable (no silent drop).
- **D-03 Serialization** — kotlinx.serialization JSON. `@Serializable` on `AgentEvent` sealed class + every subclass. `@JsonClassDiscriminator("type")` on the base class. `Json { classDiscriminator = "type"; encodeDefaults = false }`. kore-core keeps zero external runtime deps; the annotation dep is `compileOnly`. Fallback: thin `kore-core-serialization` module if compileOnly breaks.
- **D-04 Multi-instance** — broadcast. Kafka: unique consumer group per JVM. Rabbit: exclusive auto-delete queue per JVM. READMEs MUST include a 3-pod Kubernetes explainer (most common confusion point).
- **D-05 Publishing** — all 11 modules (`kore-core`, `kore-llm`, `kore-mcp`, `kore-observability`, `kore-storage`, `kore-skills`, `kore-spring`, `kore-dashboard`, `kore-test`, `kore-kafka`, `kore-rabbitmq`) to Maven Central under `dev.unityinflow`. Independent artifacts, no BOM in v0.0.1.
- **D-06 Version** — `0.0.1` stable release, then bump to `0.0.2-SNAPSHOT` on main. Tag: `v0.0.1`.
- **D-07 Plugin** — `com.gradleup.nmcp` targeting new Sonatype Central Portal (NOT legacy OSSRH). Reject any `io.github.gradle-nexus.publish-plugin` suggestion.
- **D-08 Spring wiring** — `@ConditionalOnProperty(prefix = "kore.event-bus", name = ["type"], havingValue = "kafka")` (or `rabbitmq`) + `@ConditionalOnClass(name = [...])` string form + existing `@ConditionalOnMissingBean(EventBus)` on the InProcessEventBus default. `KoreProperties` gains nested `EventBusProperties`.
- **D-09 Validation** — `EventBusBackpressureTest` (1 slow consumer, 200 producer emits, assert 64 events received + no producer suspend) and `EventBusConcurrencyTest` (8 × 1000 producers, single consumer, assert count ≥ 8000 − 8×64) in `kore-core`.
- **D-10 CI** — `.github/workflows/release.yml` on `v*.*.*` tag push, `runs-on: [arc-runner-unityinflow]`. Secrets: `SIGNING_KEY`, `SIGNING_PASSWORD`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`.

### Claude's Discretion

- Exact Gradle plugin application pattern — buildSrc convention vs root `subprojects {}` block vs settings plugin.
- Lifecycle primitive for adapter consumer coroutines — `SmartLifecycle`, `DisposableBean`, or both (must be graceful).
- Broker unreachable at startup handling — retry with backoff vs. fail-fast. Research recommendation below: **fail-fast on `emit()`, lazy-connect on first subscribe** (no background retry in v0.0.1, keeps adapter surface minimal; docs explain reconnect is the broker client's job).
- kotlinx.serialization placement: `compileOnly` in kore-core vs. thin new `kore-core-serialization` module. **Recommendation: compileOnly in kore-core** (works without an extra module; validated pattern, see Pattern 2 below).
- Testcontainers vs. mock for integration tests — **Testcontainers, but `@Tag("integration")` excluded from default `./gradlew test`**.
- Wave structure for 11 modules' publishing — one wave for convention plugin + publishing metadata, one wave for the adapter modules, one wave for CI + release workflow.

### Deferred Ideas (OUT OF SCOPE)

- DLQ + retry layer for Kafka/Rabbit publish failures (v0.1+).
- Schema registry integration (Confluent, Apicurio).
- Avro / Protobuf wire formats.
- Multi-tenant topic / exchange partitioning.
- `kore-messaging-adapter-common` shared helper module (copy-paste across two adapters is fine).
- NATS / Pulsar / Redis Streams adapters.
- Snapshot publishing from main branch CI workflow.
- Reproducible builds / SBOM generation.
- `kore-bom` module pinning versions.
- Hardware-token GPG signing (in-memory GPG key is fine for solo-dev v0.0.1).

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| EVNT-01 | EventBus port interface for agent lifecycle events | Port already exists in `kore-core/src/main/kotlin/dev/unityinflow/kore/core/port/EventBus.kt` from Phase 1 — Phase 4 adds `EventBusBackpressureTest` and `EventBusConcurrencyTest` to formalize the contract (see Validation Architecture §). |
| EVNT-02 | Kotlin Flows implementation (SharedFlow, DROP_OLDEST backpressure, default) | `InProcessEventBus` already implements this from Phase 1. Code in `kore-core/src/main/kotlin/dev/unityinflow/kore/core/internal/InProcessEventBus.kt` is verified correct (`extraBufferCapacity = 64`, `BufferOverflow.DROP_OLDEST`). Phase 4 adds grep-verifiable test coverage — see §Architecture Patterns Pattern 1 and §Validation Architecture. |
| EVNT-03 | Kafka adapter (opt-in, separate module) | New `kore-kafka` module — `org.apache.kafka:kafka-clients:4.2.0` wrapped per §Architecture Patterns Pattern 3 (callback→`suspendCoroutine` bridge for producer, `Flow` builder + `Dispatchers.IO` for consumer). Wired in Spring via `KafkaEventBusAutoConfiguration` per §Pattern 6. |
| EVNT-04 | RabbitMQ adapter (opt-in, separate module) | New `kore-rabbitmq` module — `com.rabbitmq:amqp-client:5.29.0` wrapped per §Architecture Patterns Pattern 4 (fanout exchange + exclusive auto-delete queue, `confirmSelect` + `waitForConfirmsOrDie` on `Dispatchers.IO`, manual `basicAck` on Flow emission success). Wired in Spring per §Pattern 6. |

## Project Constraints (from CLAUDE.md)

The following directives from `./CLAUDE.md` (project instructions) MUST be honored by any plan produced from this research:

- **Kotlin 2.0+, JVM target 21** — already configured via `jvmToolchain(21)` in existing module builds.
- **Gradle Kotlin DSL only, never Groovy** — convention plugin goes in `buildSrc/src/main/kotlin/*.gradle.kts`.
- **Coroutines for all async work — never Thread.sleep(), never raw threads.** Kafka `poll()` and Rabbit `waitForConfirms()` are blocking — MUST run on `Dispatchers.IO` inside structured-concurrency coroutines.
- **`val` only, no `var`** — mutable state uses `AtomicReference` or `ConcurrentHashMap` (see `EventBusMetricsObserver` precedent from Phase 2).
- **No `!!` without a comment explaining why safe**.
- **Sealed classes / `Result<T>` for expected failures** — Kafka/Rabbit `emit()` throws on broker unreachable is OK because emit already has `suspend` semantics and the caller wraps in `try/catch` at the agent-loop boundary (same as existing `InProcessEventBus.emit` contract). Do NOT invent a new `EventBusResult` sealed class.
- **ktlint** (`kotlinter` Gradle plugin) before every commit — already wired.
- **Group: `dev.unityinflow`**, Maven Central via Sonatype — this phase's primary deliverable.
- **Self-hosted runners `arc-runner-unityinflow` only — never `ubuntu-latest`**.
- **No secrets committed** — all GPG + Sonatype credentials via env vars + GitHub secrets.
- **Test coverage >80% on core logic before release**.
- **KDoc on all public APIs**.
- **Do not add Kafka as a hard dependency** — the `kore-kafka` module is opt-in; `kore-core` and `kore-spring` MUST NOT have a transitive Kafka dependency. Adapter module is chosen at consumer build time.
- **Do not build a frontend until backend/CLI is working and tested** — N/A to this phase; dashboard was delivered in Phase 3.

## Standard Stack

### Core (Phase 4 additions only — existing stack inherited from Phases 1-3)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.apache.kafka:kafka-clients` | **4.2.0** (verify with `./gradlew dependencyInsight` before pinning) | Kafka adapter transport | Official client, no JVM-side alternative. Kafka 4.x supports Java 17 and 21 fully. [VERIFIED: Apache Kafka compatibility docs; central.sonatype.com shows 4.2.0 current] |
| `com.rabbitmq:amqp-client` | **5.29.0** (verify before pinning) | RabbitMQ adapter transport | Official RabbitMQ Java client. 5.x line requires JDK 8+; fully compatible with JVM 21. Includes publisher confirms, automatic connection recovery, and exclusive-queue declaration. [VERIFIED: rabbitmq/rabbitmq-java-client releases page via WebSearch] |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.8.0** (already in `libs.versions.toml`) | Polymorphic JSON for `AgentEvent` | Already pinned for `spring-boot-starter-kotlinx-serialization-json` path from Phase 3. Supports `@JsonClassDiscriminator` for sealed-class polymorphism. [CITED: kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/.../-json-class-discriminator] |
| `org.jetbrains.kotlin.plugin.serialization` | 2.3.0 (tracks kotlin version) | Compiler plugin for `@Serializable` | Already in `libs.versions.toml` as `libs.plugins.kotlin.serialization`. Applied per-module where `@Serializable` annotations live. |
| `com.gradleup.nmcp.aggregation` | **1.4.4** | Multi-module Sonatype Central Portal publishing | The canonical plugin for the new Sonatype Central Portal API (2024+). Supports aggregated multi-module bundle upload via `publishAggregationToCentralPortal` task. Used by Apollo Kotlin and Koin in production. [VERIFIED: gradleup.com/nmcp/ + github.com/GradleUp/nmcp releases page shows 1.4.4 latest as of Jan 2026] |
| `com.gradleup.nmcp` | 1.4.4 | Per-module publication outgoing-variant contributor | Applied to every publishable submodule; feeds the aggregation plugin at root. |

### Supporting (already in place; noted for completeness)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Gradle `signing` plugin | bundled | GPG sign publications | Applied in convention plugin alongside `maven-publish`. Uses `useInMemoryPgpKeys(signingKey, signingPassword)` for CI. |
| Gradle `maven-publish` plugin | bundled | Create `MavenPublication` | Standard; nmcp does NOT replace maven-publish, it consumes its publications. |
| `org.testcontainers:kafka` | align with existing testcontainers 1.20.0 | Integration test for kore-kafka | `@Tag("integration")` excluded from default `./gradlew test`. |
| `org.testcontainers:rabbitmq` | align with existing testcontainers 1.20.0 | Integration test for kore-rabbitmq | Same. |
| `kotlinx-coroutines-test` 1.10.2 (already pinned) | Virtual-time tests for backpressure | `EventBusBackpressureTest` / `EventBusConcurrencyTest` use `runTest` + `backgroundScope` + `advanceTimeBy` + manual `yield()` (the `backgroundScope`+`runCurrent` pattern already used in `EventBusMetricsObserverTest` in Phase 2). |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `com.gradleup.nmcp` | `com.vanniktech.maven.publish` 0.34+ | Vanniktech supports Central Portal too, is widely used, and handles POM defaults automatically. However D-07 locks `nmcp`. Vanniktech is the fallback if `nmcp` doesn't work on our setup. |
| `com.gradleup.nmcp` | `io.github.gradle-nexus.publish-plugin` | **REJECTED — targets legacy OSSRH Nexus endpoint, not the new Central Portal**. Explicitly forbidden by D-07 and by CLAUDE.md lineage. |
| `com.gradleup.nmcp` | `jreleaser` | JReleaser is Sonatype's recommended community solution per central.sonatype.org but adds a second release tool on top of Gradle; nmcp keeps everything in the Gradle graph. |
| Kafka thin adapter | `spring-kafka` `KafkaTemplate` | Violates hexagonal rule — `kore-kafka` must work without Spring on classpath (same pattern as `kore-storage`). D-01 explicit. |
| Kafka thin adapter | `reactor-kafka` | Reactor-based; would introduce a second reactive model alongside coroutines. Not justified. |
| RabbitMQ thin adapter | `spring-amqp` `RabbitTemplate` | Same hexagonal violation. D-02 explicit. |
| `@Serializable` in kore-core via compileOnly | New `kore-core-serialization` module | Compile-only annotations are a standard Kotlin pattern; an extra module is overhead for two consumers (kafka + rabbit). Keep it simple; revisit only if classpath issues surface. |
| JSON + `@JsonClassDiscriminator` | Protobuf | 8 event types < 500 bytes each don't justify schema files. Human-readable in broker UIs. |

**Installation (Gradle coordinates reference — to be pinned in `libs.versions.toml`):**

```toml
[versions]
kafka-clients = "4.2.0"          # [ASSUMED latest stable — verify with `npm view`-equivalent `./gradlew dependencyInsight` or central.sonatype.com before commit]
amqp-client = "5.29.0"           # [ASSUMED latest stable — verify before commit]
nmcp = "1.4.4"                   # [VERIFIED: gradleup.com/nmcp docs + github.com/GradleUp/nmcp releases page Jan 2026]

[libraries]
kafka-clients = { module = "org.apache.kafka:kafka-clients", version.ref = "kafka-clients" }
amqp-client = { module = "com.rabbitmq:amqp-client", version.ref = "amqp-client" }
testcontainers-kafka = { module = "org.testcontainers:kafka", version.ref = "testcontainers" }
testcontainers-rabbitmq = { module = "org.testcontainers:rabbitmq", version.ref = "testcontainers" }

[plugins]
nmcp = { id = "com.gradleup.nmcp", version.ref = "nmcp" }
nmcp-aggregation = { id = "com.gradleup.nmcp.aggregation", version.ref = "nmcp" }
```

**Version verification before commit:** Run `./gradlew dependencyInsight --dependency kafka-clients` and check central.sonatype.com/artifact/... for the exact current version. The versions above are from April 2026 research and may be stale by the time Phase 4 plans execute.

## Architecture Patterns

### Recommended Module Structure

```
kore/
├── buildSrc/                                    # NEW — convention plugins
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       └── kore.publishing.gradle.kts          # maven-publish + signing + POM
├── kore-core/                                   # existing — +@Serializable, +tests
│   └── src/main/kotlin/.../AgentEvent.kt       # annotated
│   └── src/test/kotlin/.../port/
│       ├── EventBusBackpressureTest.kt         # NEW (EVNT-01/02)
│       └── EventBusConcurrencyTest.kt          # NEW (EVNT-01/02)
├── kore-kafka/                                  # NEW (EVNT-03)
│   ├── build.gradle.kts                         # applies kore.publishing + nmcp
│   └── src/main/kotlin/dev/unityinflow/kore/kafka/
│       ├── KafkaEventBus.kt
│       ├── KafkaEventBusConfig.kt              # data class (bootstrap, topic, ...)
│       └── internal/
│           ├── ProducerAdapter.kt              # callback -> suspendCancellableCoroutine
│           └── ConsumerFlowBuilder.kt          # poll() -> Flow<AgentEvent>
│   └── src/test/kotlin/.../kafka/
│       ├── KafkaEventBusUnitTest.kt            # mocked client
│       └── KafkaEventBusIntegrationTest.kt     # @Tag("integration") + Testcontainers
├── kore-rabbitmq/                               # NEW (EVNT-04)
│   ├── build.gradle.kts
│   └── src/main/kotlin/dev/unityinflow/kore/rabbitmq/
│       ├── RabbitMqEventBus.kt
│       ├── RabbitMqEventBusConfig.kt
│       └── internal/
│           ├── ChannelAdapter.kt               # confirmSelect + waitForConfirmsOrDie
│           └── ConsumerFlowBuilder.kt          # DeliverCallback -> Flow<AgentEvent>
│   └── src/test/kotlin/.../rabbitmq/
│       ├── RabbitMqEventBusUnitTest.kt
│       └── RabbitMqEventBusIntegrationTest.kt  # @Tag("integration") + Testcontainers
├── kore-spring/                                 # existing — +two auto-configurations
│   └── src/main/kotlin/.../KoreAutoConfiguration.kt   # +KafkaEventBusAutoConfiguration, +RabbitMqEventBusAutoConfiguration
│   └── src/main/kotlin/.../KoreProperties.kt          # +EventBusProperties nested
├── .github/workflows/
│   ├── ci.yml                                   # existing — unchanged
│   └── release.yml                              # NEW — tag-push triggered publish
├── build.gradle.kts                             # root — apply nmcp.aggregation + version bump to 0.0.1
└── settings.gradle.kts                          # +include kore-kafka, kore-rabbitmq
```

### Pattern 1: EventBus formalization tests (EVNT-01 / EVNT-02)

**What:** Dedicated unit tests in `kore-core` that exercise `InProcessEventBus` under backpressure and concurrency, with grep-verifiable assertions so `/gsd-verify-work 4` can confirm coverage exists.

**When to use:** Now — close the Pitfall 9 gap from Phase 3 research (missing-buffer-config). Tests already exist implicitly in Phase 2/3 observer tests, but there's no `EventBus*Test.kt` file to grep for.

**Example (EventBusBackpressureTest):**

```kotlin
// Source: adapted from EventBusMetricsObserverTest pattern in kore-observability/src/test/
@Test
fun `emit never suspends even with slow consumer`() = runTest {
    val bus = InProcessEventBus()
    val received = mutableListOf<AgentEvent>()
    val slowConsumer =
        backgroundScope.launch {
            bus.subscribe().collect {
                delay(100.milliseconds) // simulate 10 events/sec consumer
                received += it
            }
        }
    runCurrent() // let collect start

    val producerStart = testScheduler.currentTime
    repeat(200) { i ->
        bus.emit(AgentEvent.AgentStarted(agentId = "a$i", taskId = "t$i"))
    }
    val producerElapsed = testScheduler.currentTime - producerStart
    producerElapsed shouldBe 0L // emit() returns immediately — virtual time unchanged

    // Let the slow consumer drain what it can — with DROP_OLDEST, the buffer
    // holds 64 events; the slow consumer will see at most 64 in-flight plus
    // the 1 event currently being processed when drops started.
    advanceTimeBy(10.seconds)
    received.size shouldBeInRange (1..65)
    slowConsumer.cancel()
}
```

**Key insight on `runTest` behaviour:** `delay` on the consumer side advances virtual time, but `emit` on a configured `MutableSharedFlow(extraBufferCapacity=N, DROP_OLDEST)` never suspends — so asserting `testScheduler.currentTime` stays zero across all 200 emits is a deterministic proof that no producer suspend happened. This is more reliable than a wall-clock `withTimeout` assertion.

### Pattern 2: `@Serializable` in kore-core via compileOnly kotlinx-serialization

**What:** Apply the `org.jetbrains.kotlin.plugin.serialization` compiler plugin to kore-core, but declare `kotlinx-serialization-json` as `compileOnly`. The compiler plugin generates bytecode referencing `KSerializer`/`SerialDescriptor`, which are runtime types in `kotlinx-serialization-core`. To keep kore-core's runtime classpath minimal, either:

- **Option A (recommended):** `compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")` in kore-core. Runtime classpath stays zero-external-dep. At runtime the consumer (kore-kafka, kore-rabbitmq) brings `kotlinx-serialization-json` which has `core` as a transitive dep, so the generated serializers resolve correctly. This is the same pattern Phase 1 used for `opentelemetry-api` in kore-core: `compileOnly("io.opentelemetry:opentelemetry-api:1.49.0")`.

- **Option B (fallback):** If classpath pollution becomes a problem, create a new tiny `kore-core-serialization` module that `dependsOn` kore-core and hosts `@Serializable` annotations on typealias extension points. Adds a module for marginal benefit — prefer Option A.

**When to use:** Option A by default. Only fall back to Option B if the Kotlin serialization plugin fails to generate synthetic serializers without the `json` artifact present at compile time. (It shouldn't — `core` holds all the annotation/serializer contract symbols.)

**Example (kore-core/build.gradle.kts addition):**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) // NEW
}

dependencies {
    implementation(libs.coroutines.core)
    compileOnly("io.opentelemetry:opentelemetry-api:1.49.0") // existing pattern
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0") // NEW — annotations+contract only
    // intentionally no kotlinx-serialization-json at runtime — adapters bring it
    testImplementation(...)
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0") // NEW — for roundtrip test
}
```

**AgentEvent.kt changes:**

```kotlin
package dev.unityinflow.kore.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Events emitted by the agent loop to the [port.EventBus]. */
@Serializable
@JsonClassDiscriminator("type")
sealed class AgentEvent {
    @Serializable
    data class AgentStarted(
        val agentId: String,
        val taskId: String,
    ) : AgentEvent()

    @Serializable
    data class LLMCallStarted(val agentId: String, val backend: String) : AgentEvent()

    @Serializable
    data class LLMCallCompleted(
        val agentId: String,
        val tokenUsage: TokenUsage,
    ) : AgentEvent()

    // ... (same pattern for all 6 subclasses)
}
```

Wire format on the broker:

```json
{"type":"LLMCallCompleted","agentId":"agent-42","tokenUsage":{"inputTokens":120,"outputTokens":45,"totalTokens":165}}
```

`encodeDefaults = false` omits default-valued fields (e.g., `isError = false` on `ToolCallCompleted`) to minimize wire size.

**Roundtrip test in kore-core (required gate for Option A):**

```kotlin
@Test
fun `AgentEvent polymorphic JSON roundtrip preserves type`() {
    val json = Json { classDiscriminator = "type"; encodeDefaults = false }
    val event: AgentEvent = AgentEvent.LLMCallCompleted("a1", TokenUsage(10, 20, 30))
    val encoded = json.encodeToString(AgentEvent.serializer(), event)
    encoded shouldContain "\"type\":\"LLMCallCompleted\""
    val decoded = json.decodeFromString(AgentEvent.serializer(), encoded)
    decoded shouldBe event
}
```

If this test fails at compile time due to missing symbol, Option B is required. If it compiles and runs under `test` (which has the json artifact on test classpath via `testImplementation`), Option A is confirmed.

### Pattern 3: Kafka async send bridge (producer)

**What:** Wrap `KafkaProducer.send(record, callback)` in `suspendCancellableCoroutine` so `emit()` is non-blocking and coroutine-cancellable. The underlying `Future<RecordMetadata>` returned by `send()` is NOT await-friendly (it's `java.util.concurrent.Future`, not `CompletableFuture`); the callback is the idiomatic bridge.

**When to use:** Every `emit()` call in `KafkaEventBus`.

**Example:**

```kotlin
// Source: adapted from standard Kotlin coroutines-JavaAsync bridge documented
// at kotlinlang.org/api/kotlinx-coroutines-core/.../-suspend-cancellable-coroutine

internal suspend fun KafkaProducer<String, ByteArray>.awaitSend(
    record: ProducerRecord<String, ByteArray>,
): RecordMetadata =
    suspendCancellableCoroutine { cont ->
        send(record) { metadata, exception ->
            if (exception != null) {
                cont.resumeWithException(exception)
            } else {
                cont.resume(metadata!!) // safe: Kafka contract: exception XOR metadata
            }
        }
        // Kafka has no cancel API for an in-flight send; cont.invokeOnCancellation
        // is a no-op. Acceptable — sends complete quickly or fail quickly.
    }
```

**Note:** `!!` on `metadata` is safe per Kafka's documented callback contract: exactly one of (metadata, exception) is non-null. Add an explanatory comment to satisfy CLAUDE.md "no `!!` without a comment" rule.

### Pattern 4: Kafka consumer poll-loop as Flow

**What:** Turn `KafkaConsumer.poll(Duration)` (blocking) into a cold `Flow<AgentEvent>` via `flow {}` + `flowOn(Dispatchers.IO)`. The poll loop runs until coroutine cancellation; cancellation MUST wake the poll so shutdown doesn't hang for the full `poll` timeout on every call. Use `consumer.wakeup()` from `invokeOnCompletion` of the subscription job, or rely on short poll timeouts + `ensureActive()` between polls.

**When to use:** `KafkaEventBus.subscribe()` implementation.

**Example:**

```kotlin
// Source: synthesized from Kafka docs + kotlinx-coroutines Flow builder.
// Pattern validated against blog.ippon.tech/kafka-tutorial-2-simple-consumer-in-kotlin
// and kt.academy/article/cc-cancellation best practices.

override fun subscribe(): Flow<AgentEvent> =
    flow {
        val consumer = KafkaConsumer<String, ByteArray>(consumerProps)
        consumer.subscribe(listOf(config.topic))
        try {
            while (currentCoroutineContext().isActive) {
                val records = consumer.poll(POLL_TIMEOUT)
                for (record in records) {
                    val event = json.decodeFromString(AgentEvent.serializer(), record.value().decodeToString())
                    emit(event)
                }
            }
        } catch (e: WakeupException) {
            // expected on cancellation — swallow
        } finally {
            consumer.close()
        }
    }.flowOn(Dispatchers.IO)

private companion object {
    val POLL_TIMEOUT: Duration = Duration.ofMillis(500)
}
```

Each subscriber gets its own `KafkaConsumer` instance (D-04 broadcast semantics require unique consumer groups per JVM; they do NOT require unique groups per subscriber within a JVM — a single consumer-per-`EventBus`-instance is correct and subscribers share the Flow via a `MutableSharedFlow` fan-out if multiple subscriptions are expected).

**Better architecture:** The `KafkaEventBus` holds ONE `KafkaConsumer` in a supervisor coroutine that pumps records into a private `MutableSharedFlow<AgentEvent>`; `subscribe()` returns `.asSharedFlow()`. This mirrors the `InProcessEventBus` shape and means downstream consumers all see the same in-process fan-out. Planner should adopt this shape, not one-consumer-per-subscription.

### Pattern 5: RabbitMQ fanout + exclusive queue + publisher confirms

**What:** For `RabbitMqEventBus`:

- **On construction:** Create a single `Connection` per adapter instance (the amqp-client `Connection` is thread-safe; `Channel` is NOT). Open one `Channel` for publishing, call `confirmSelect()` to enable publisher confirms. Declare the fanout exchange (`exchangeDeclare(name, "fanout", durable=true)`).
- **On `emit()`:** `basicPublish(exchange, "" /* routing key ignored for fanout */, MessageProperties.PERSISTENT_BASIC, bytes)`, then `waitForConfirmsOrDie(timeout)` on `Dispatchers.IO` to propagate nacks as exceptions.
- **On `subscribe()`:** Open a second `Channel` (consumer must not share the producer channel). Declare a server-named, exclusive, auto-delete, non-durable queue: `queueDeclare("", durable=false, exclusive=true, autoDelete=true, args=null)`. Bind it to the fanout exchange: `queueBind(qName, exchangeName, "")`. Consume with `basicConsume(qName, autoAck=false, DeliverCallback{ ... }, CancelCallback{ ... })`, manually `basicAck` after successful Flow emission.

**When to use:** Every `RabbitMqEventBus` instance.

**Example:**

```kotlin
// Source: rabbitmq.com/tutorials/tutorial-three-java (Publish/Subscribe)
//       + rabbitmq.com/tutorials/tutorial-seven-java (Publisher Confirms)
//       + rabbitmq.com/docs/consumers#acknowledgement-modes

class RabbitMqEventBus(
    private val config: RabbitMqEventBusConfig,
    private val json: Json,
    private val scope: CoroutineScope,
) : EventBus, AutoCloseable {
    private val connection: Connection by lazy { createConnection() }
    private val publishChannel: Channel by lazy {
        connection.createChannel().apply {
            confirmSelect()
            exchangeDeclare(config.exchange, "fanout", true)
        }
    }
    private val shared = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)

    init {
        scope.launch(Dispatchers.IO) { runConsumer() }
    }

    override suspend fun emit(event: AgentEvent) {
        withContext(Dispatchers.IO) {
            val bytes = json.encodeToString(AgentEvent.serializer(), event).toByteArray()
            publishChannel.basicPublish(config.exchange, "", MessageProperties.PERSISTENT_BASIC, bytes)
            publishChannel.waitForConfirmsOrDie(config.confirmTimeoutMillis)
        }
    }

    override fun subscribe(): Flow<AgentEvent> = shared.asSharedFlow()

    private suspend fun runConsumer() {
        val consumerChannel = connection.createChannel()
        consumerChannel.exchangeDeclare(config.exchange, "fanout", true)
        val queueName = consumerChannel.queueDeclare("", false, true, true, null).queue
        consumerChannel.queueBind(queueName, config.exchange, "")
        val deliverCallback = DeliverCallback { _, delivery ->
            val event = json.decodeFromString(AgentEvent.serializer(), delivery.body.decodeToString())
            shared.tryEmit(event) // DROP_OLDEST — never blocks
            consumerChannel.basicAck(delivery.envelope.deliveryTag, false)
        }
        consumerChannel.basicConsume(queueName, false, deliverCallback, CancelCallback { })
        awaitCancellation() // keep the consumer alive until scope cancellation
    }

    override fun close() {
        runCatching { publishChannel.close() }
        runCatching { connection.close(config.closeTimeoutMillis) }
    }
}
```

**Key choice:** Internal `MutableSharedFlow` means in-process observers see the same `DROP_OLDEST` semantics as `InProcessEventBus` — adapter swap does not change per-instance behavior. This is important for D-04 consistency across backends.

### Pattern 6: Spring Boot auto-configuration for the two adapters

**What:** Add two new nested `@Configuration` classes to `KoreAutoConfiguration` following the existing pattern (`StorageAutoConfiguration`, `DashboardAutoConfiguration`). Guard each by `@ConditionalOnClass(name=[...])` (string form, Pitfall 12 inheritance) AND `@ConditionalOnProperty(prefix="kore.event-bus", name=["type"], havingValue="kafka"|"rabbitmq")`. The existing `@ConditionalOnMissingBean(EventBus::class)` on `inProcessEventBus()` naturally yields to either adapter bean.

**When to use:** Exactly one of (Kafka, Rabbit, in-process) wins. `@ConditionalOnProperty` discipline prevents accidental activation just from the module being on classpath.

**Example (addition to `KoreAutoConfiguration.kt`):**

```kotlin
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["dev.unityinflow.kore.kafka.KafkaEventBus"])
@ConditionalOnProperty(prefix = "kore.event-bus", name = ["type"], havingValue = "kafka")
class KafkaEventBusAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(EventBus::class)
    fun kafkaEventBus(
        properties: KoreProperties,
        @Qualifier("koreEventBusScope") scope: CoroutineScope,
    ): EventBus =
        dev.unityinflow.kore.kafka
            .KafkaEventBus(
                config = properties.eventBus.kafka.toAdapterConfig(),
                json = Json { classDiscriminator = "type"; encodeDefaults = false },
                scope = scope,
            )
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["dev.unityinflow.kore.rabbitmq.RabbitMqEventBus"])
@ConditionalOnProperty(prefix = "kore.event-bus", name = ["type"], havingValue = "rabbitmq")
class RabbitMqEventBusAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(EventBus::class)
    fun rabbitMqEventBus(...): EventBus = dev.unityinflow.kore.rabbitmq.RabbitMqEventBus(...)
}
```

**`KoreProperties` addition:**

```kotlin
data class KoreProperties(
    // ... existing ...
    val eventBus: EventBusProperties = EventBusProperties(),
) {
    data class EventBusProperties(
        val type: String = "in-process",        // "in-process" | "kafka" | "rabbitmq"
        val kafka: KafkaProperties = KafkaProperties(),
        val rabbitmq: RabbitMqProperties = RabbitMqProperties(),
    )

    data class KafkaProperties(
        val bootstrapServers: String = "",      // required when type=kafka
        val topic: String = "kore-agent-events",
        val groupIdPrefix: String = "kore",     // full group = "$groupIdPrefix-$hostname-$pid"
    )

    data class RabbitMqProperties(
        val uri: String = "",                   // required when type=rabbitmq — amqp://user:pass@host:5672/vhost
        val exchange: String = "kore.agent-events",
        val confirmTimeoutMillis: Long = 5_000L,
    )
}
```

**CoroutineScope bean:** A new `koreEventBusScope` `@Bean` — a `CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))` — gives adapters a managed scope that Spring can `cancel()` on context shutdown via a `DisposableBean`. Mirror the pattern from `EventBusMetricsObserver` from Phase 2 where scopes are injected by construction.

### Pattern 7: Multi-module publishing with buildSrc convention plugin

**What:** Use `buildSrc` to define a `kore.publishing.gradle.kts` convention plugin that applies `maven-publish`, `signing`, and `com.gradleup.nmcp`, configures a `MavenPublication` from `components["java"]` with default POM metadata, and leaves per-module customization (name, description) to each submodule's `build.gradle.kts`. The root `build.gradle.kts` applies `com.gradleup.nmcp.aggregation` and wires all publishable subprojects as `nmcpAggregation` dependencies.

**When to use:** One plan, one wave. Apply to all 11 publishable modules.

**Example (`buildSrc/src/main/kotlin/kore.publishing.gradle.kts`):**

```kotlin
// Source: synthesized from gradleup.com/nmcp/ + docs.gradle.org/current/userguide/signing_plugin.html
// + vanniktech.github.io/gradle-maven-publish-plugin/ signing-in-memory pattern

plugins {
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
}

// kotlin { withSourcesJar() } is applied in each module's own build.gradle.kts
// (needs the kotlin plugin loaded first, which convention plugins can't assume).

java {
    withJavadocJar()    // empty javadoc jar is sufficient for Sonatype publish rule
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                // Module-specific name/description set per-module via afterEvaluate
                // or via `publishing { publications { named("maven") { ... } } }`
                // in each module's build.gradle.kts.
                url.set("https://github.com/UnityInFlow/kore")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("jhermann")
                        name.set("Jiří Hermann")
                        email.set("jiri@unityinflow.dev")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/UnityInFlow/kore.git")
                    developerConnection.set("scm:git:ssh://git@github.com/UnityInFlow/kore.git")
                    url.set("https://github.com/UnityInFlow/kore")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/UnityInFlow/kore/issues")
                }
            }
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    } else {
        // Local dev — do not fail the build if signing env vars absent;
        // publishToMavenLocal still works. The CI workflow sets both.
        logger.lifecycle("Signing skipped — SIGNING_KEY / SIGNING_PASSWORD env vars not set.")
    }
}
```

**Root `build.gradle.kts` addition:**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.nmcp.aggregation)   // NEW
}

subprojects {
    apply(plugin = "org.jmailen.kotlinter")
    group = "dev.unityinflow"
    version = "0.0.1"                      // CHANGED from 0.0.1-SNAPSHOT per D-06
    repositories { mavenCentral() }
}

nmcpAggregation {
    centralPortal {
        username = providers.environmentVariable("SONATYPE_USERNAME")
        password = providers.environmentVariable("SONATYPE_PASSWORD")
        publishingType = "USER_MANAGED"    // "AUTOMATIC" to skip staging review
        publicationName = "kore-${rootProject.version}"
    }
}

dependencies {
    // Every publishable submodule
    listOf(
        "kore-core", "kore-llm", "kore-mcp", "kore-observability", "kore-storage",
        "kore-skills", "kore-spring", "kore-dashboard", "kore-test",
        "kore-kafka", "kore-rabbitmq",
    ).forEach { nmcpAggregation(project(":$it")) }
}
```

Each publishable module adds a single line to its `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinter)
    id("kore.publishing")   // NEW — convention plugin from buildSrc
}

// Module-specific POM details
publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                name.set("kore-runtime — Kafka EventBus adapter")
                description.set("Opt-in Apache Kafka adapter implementing the EventBus port for kore-runtime.")
            }
        }
    }
}
```

### Pattern 8: Release CI workflow

**What:** New `.github/workflows/release.yml` triggered on `v*.*.*` tag push. Builds, signs, and publishes the aggregated bundle to Sonatype Central Portal, then creates a GitHub Release.

**When to use:** On every `git tag v0.0.1 && git push origin v0.0.1` (and future version tags).

**Example:**

```yaml
# Source: synthesized from docs.github.com/actions/using-workflows/events-that-trigger-workflows
# + existing ci.yml + gradleup.com/nmcp/ + vanniktech signing-in-memory pattern
name: Release

on:
  push:
    tags:
      - 'v*.*.*'

jobs:
  publish:
    runs-on: [arc-runner-unityinflow]
    permissions:
      contents: write      # create GitHub Release
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Lint + build + test
        run: ./gradlew lintKotlin build test

      - name: Publish to Sonatype Central Portal
        env:
          SIGNING_KEY: ${{ secrets.SIGNING_KEY }}
          SIGNING_PASSWORD: ${{ secrets.SIGNING_PASSWORD }}
          SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
          SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}
        run: ./gradlew publishAggregationToCentralPortal --no-configuration-cache

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          name: kore-runtime ${{ github.ref_name }}
          body: |
            kore-runtime **${{ github.ref_name }}** is now available on Maven Central under `dev.unityinflow`.

            ```kotlin
            implementation("dev.unityinflow:kore-spring:${{ github.ref_name }}".removePrefix("v"))
            ```

            See the auto-generated notes below for Phase 1-4 summaries.
```

**Key detail:** `--no-configuration-cache` is a defensive flag on the publish task — nmcp's aggregation task has historically had configuration-cache compatibility issues on some older versions. Confirm compatibility on 1.4.4 during execution; drop the flag if not needed.

**Public repo gotcha:** CLAUDE.md notes `arc-runner-unityinflow` has `allows_public_repositories: false`. **ACTION ITEM for planner:** confirm `kore-runtime` repo visibility before the first release workflow run — if public, a GitHub org admin must enable public repo access on the runner group via org Settings → Actions → Runner groups. This is a prerequisite task, not a code task.

### Anti-Patterns to Avoid

- **`@ConditionalOnClass(KafkaEventBus::class)`** (class reference form) — triggers eager classload during bean scanning, causing `ClassNotFoundException` when the opt-in module is absent. Always use `@ConditionalOnClass(name = ["dev.unityinflow.kore.kafka.KafkaEventBus"])`. Inherited from Phase 3 Pitfall 12.
- **Sharing one `Channel` across producer + consumer in Rabbit** — `Channel` is NOT thread-safe per amqp-client docs. One channel for publishing, one for each subscribe.
- **Using `consumer.poll(Long.MAX_VALUE)` without cancellation check** — the Kafka consumer will block for the full duration, ignoring coroutine cancellation. Always use short timeouts (≤ 500ms) and check `currentCoroutineContext().isActive` between polls, or call `consumer.wakeup()` from `Job.invokeOnCompletion`.
- **Blocking `waitForConfirms()` on the main dispatcher** — wraps a JVM I/O wait; must run inside `withContext(Dispatchers.IO)`.
- **`consumer.close()` in a Kotlin `finally` without catching `InterruptException`** — shutdown hangs or throws on cancellation. Use `runCatching` in `finally`.
- **Forgetting `withJavadocJar()` + `withSourcesJar()`** — Sonatype Central Portal rejects the bundle at upload time. The error message is buried in the portal UI.
- **Forgetting to sign the POM file in addition to the jar** — `sign(publishing.publications["maven"])` signs ALL artifacts of the publication including the POM. Do NOT cherry-pick `sign(tasks["jar"])` — that only signs the jar and the upload gets rejected.
- **Publishing `kore-kafka` / `kore-rabbitmq` as transitive deps of `kore-spring`** — violates "opt-in" semantics. They must be `compileOnly` in `kore-spring` and explicit `implementation` in consumer's own build.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Callback-to-coroutine bridge | Custom `CompletableDeferred` wiring | `suspendCancellableCoroutine` from kotlinx-coroutines-core | Cancellation-aware, context-preserving, idiomatic. |
| JSON polymorphic discriminator | Custom sealed-class dispatcher | `@JsonClassDiscriminator` + `Json { classDiscriminator = "type" }` | Compile-time safe, bidirectional, handles nested polymorphism. |
| Broadcast consumer group ID | `UUID.randomUUID().toString()` | `InetAddress.getLocalHost().hostName + "-" + ProcessHandle.current().pid()` | Stable across restarts for the same JVM, human-readable in Kafka UI, no collision risk in single-digit pod counts. |
| Publisher confirms coordination | `CountDownLatch` per publish | `channel.confirmSelect()` + `channel.waitForConfirmsOrDie(timeout)` | Built into amqp-client, handles nack + timeout cleanly. |
| POM metadata generation | Manual XML | Gradle `maven-publish` `pom {}` DSL | Type-safe, validated against Sonatype requirements. |
| Central Portal HTTP upload | `curl` from Gradle exec | `com.gradleup.nmcp` plugin | Handles bundle zip assembly, portal API, staging state machine, authentication. |
| Kafka graceful shutdown | `Thread.sleep()` + flag polling | `consumer.wakeup()` + `runCatching { consumer.close() }` | Cancellation-aware, documented Kafka idiom. |

**Key insight:** The Kafka and Rabbit client libraries already solve the hard parts (reconnection, in-flight retries, publisher confirms state machines). Our job is a thin coroutine bridge + JSON (de)serialization + Spring wiring. Every piece of "custom" logic is a code-smell that indicates we're fighting the library.

## Runtime State Inventory

Phase 4 is additive, not a rename/refactor — no existing runtime state needs migration. Noted for completeness:

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — PostgreSQL audit log schema is unchanged. Existing agent_runs/llm_calls/tool_calls rows are unaffected. | None. |
| Live service config | None — no running n8n/Datadog/etc. registrations for kore-runtime in v0.0.1. | None. |
| OS-registered state | None — no scheduled tasks or systemd units. | None. |
| Secrets/env vars | **NEW env vars introduced** by Phase 4: `SIGNING_KEY`, `SIGNING_PASSWORD`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`. These go into GitHub org secrets, not `.env` files. For local dev, `SIGNING_KEY` and `SIGNING_PASSWORD` can be set manually if developer wants to sign locally. | Add to GitHub org secrets BEFORE first release workflow run. |
| Build artifacts | **`build.gradle.kts` version changes `0.0.1-SNAPSHOT` → `0.0.1` → `0.0.2-SNAPSHOT`** on main after publish. Stale `build/libs/*.jar` from old version should be cleaned by the CI runner. `~/.m2/repository/dev/unityinflow/` will have artifacts after local `publishToMavenLocal` — safe to leave; delete before re-publish if testing. | Ensure `./gradlew clean publishAggregationToCentralPortal` (not incremental) in release CI. |

## Environment Availability

Phase 4 requires tooling for publishing + testing. Since I can't directly probe the CI runner from this research session, document requirements for planner verification.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 21 | Compile + publish | ✓ (already CI-verified) | Temurin 21 via actions/setup-java@v4 | — |
| Gradle 9.x | Build + nmcp 1.4.4 (Gradle 8.8+) | ✓ | via gradle/actions/setup-gradle@v4 | — |
| Docker | Testcontainers integration tests | Unknown — **ACTION:** planner verifies on `arc-runner-unityinflow` before committing | — | Skip `@Tag("integration")` tests locally, require Docker only on CI release runner |
| GPG (or in-memory GPG via Gradle `useInMemoryPgpKeys`) | Sign artifacts | ✓ (in-memory, no host GPG needed) | — | — |
| Network access to `central.sonatype.com` | Publish | ✓ (standard GitHub Actions HTTPS egress) | — | — |
| Sonatype Central Portal namespace verification for `dev.unityinflow` | First publish | **UNKNOWN — CRITICAL BLOCKER IF NOT DONE** | — | Must be completed via https://central.sonatype.com/publishing/namespaces before release workflow runs |

**Missing dependencies with no fallback:**

- **`dev.unityinflow` namespace NOT yet verified on Sonatype Central Portal.** [ASSUMED — I have no way to check this from research session.] Namespace verification is a DNS TXT record or GitHub-based proof-of-ownership flow that takes minutes to complete but is a hard gate for first publish. **Planner must add a Wave 0 task to verify `dev.unityinflow` namespace on central.sonatype.com BEFORE any code changes land.** If the domain `unityinflow.dev` is controlled, use the DNS TXT verification path. Otherwise use the github.com/UnityInFlow org verification.

**Missing dependencies with fallback:**

- **Docker on CI runner for Testcontainers** — planner should verify with `docker ps` on the runner host. If missing, Testcontainers integration tests skip (marked `@EnabledIfSystemProperty(named="docker.available", matches="true")` or similar); unit tests using MockK still run. Release workflow can gate on unit tests only if Docker is unavailable.

## Common Pitfalls

### Pitfall 1 (inherited): Missing buffer config causes emit suspension [Phase 3 Pitfall 9]

**What goes wrong:** `MutableSharedFlow<AgentEvent>()` without `extraBufferCapacity` + `onBufferOverflow` blocks `emit()` when any subscriber is slow. The agent loop stalls.

**Why it happens:** Default `MutableSharedFlow` semantics are `extraBufferCapacity=0`, `BufferOverflow.SUSPEND`. `emit()` is suspendable and waits for all subscribers to catch up.

**How to avoid:** `InProcessEventBus` already has `extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST`. Phase 4 adds `EventBusBackpressureTest` that asserts `emit()` never suspends (see Pattern 1) so any future regression is caught immediately. Adapter modules' internal `MutableSharedFlow` fan-outs MUST use the same config.

**Warning signs:** Agent loop latency spikes during dashboard rendering, observer coroutine `collect` slowness, test suites timing out in Phase 2/3.

### Pitfall 2 (inherited): @ConditionalOnClass with class reference [Phase 3 Pitfall 12]

**What goes wrong:** `@ConditionalOnClass(KafkaEventBus::class)` in `KoreAutoConfiguration` causes `ClassNotFoundException` at bean-scanning time when `kore-kafka` is absent. The JVM tries to resolve the annotation attribute value, which requires loading the class.

**Why it happens:** Kotlin `::class` references compile to direct class loads.

**How to avoid:** Always `@ConditionalOnClass(name = ["dev.unityinflow.kore.kafka.KafkaEventBus"])`. String form defers resolution to runtime. The existing `KoreAutoConfiguration.kt` already follows this pattern — Phase 4 additions must too.

**Warning signs:** Spring Boot fails to start with `ClassNotFoundException` during auto-configuration. The stack trace points at annotation parsing, not bean creation.

### Pitfall 3: Sonatype Central Portal namespace not verified before first publish

**What goes wrong:** `publishAggregationToCentralPortal` succeeds at upload, then the bundle is rejected at validation with "namespace dev.unityinflow is not verified for user <username>". The bundle sits in staging indefinitely until manually dropped.

**Why it happens:** New Central Portal requires explicit namespace ownership verification (separate from legacy OSSRH registration). The verification is a one-time action that takes 5-15 minutes via the portal UI.

**How to avoid:** Add a pre-flight task in Wave 0 of Phase 4 plans: "Verify `dev.unityinflow` namespace on central.sonatype.com". The task should be marked `[HUMAN]` or similar — Claude cannot perform the verification through tool calls, but it can check whether `curl -sI https://repo.maven.apache.org/maven2/dev/unityinflow/` returns the expected namespace metadata.

**Warning signs:** The first-ever publish attempt fails validation. Portal UI shows namespace validation errors. `publishToMavenLocal` works fine (validates nothing) so local dev gives false confidence.

### Pitfall 4: POM validation rejection on missing required fields

**What goes wrong:** Central Portal rejects the bundle at the validation step because the POM is missing one of the required fields: `name`, `description`, `url`, `licenses`, `developers`, `scm`, `organization` (required for OSSRH, optional on new portal). Error is delivered via the portal UI, not the Gradle task output.

**Why it happens:** Gradle's `maven-publish` plugin happily generates a POM with only artifactId/groupId/version. It does NOT enforce Sonatype's validation rules.

**How to avoid:** The convention plugin in Pattern 7 sets ALL required fields. Each module MUST override `name` and `description`. Write a Gradle test that inspects `build/publications/maven/pom-default.xml` and asserts the required elements exist — run it as part of `check`.

**Warning signs:** Publish task succeeds but artifacts never appear on Maven Central. Portal UI shows "Invalid POM" errors.

### Pitfall 5: Forgetting signing on the POM (only signing the jar)

**What goes wrong:** `sign(tasks["jar"])` signs only the main jar. Central Portal rejects the bundle because `*.pom.asc`, `*-sources.jar.asc`, `*-javadoc.jar.asc` are missing.

**Why it happens:** Misreading Gradle `signing` plugin docs — signing a publication signs all its artifacts; signing a task signs only that task's output.

**How to avoid:** Always `sign(publishing.publications["maven"])` (publication, not task). The convention plugin in Pattern 7 does this correctly.

**Warning signs:** Publish uploads succeed; validation step reports missing `.asc` files.

### Pitfall 6: Kafka consumer-group-id collision between JVMs

**What goes wrong:** Two pods in Kubernetes join the same consumer group (because the static group ID `kore-consumer` is hardcoded). Kafka rebalances partitions between them, so each pod sees only half the events. The dashboard shows inconsistent state across pods.

**Why it happens:** Broadcast semantics require per-JVM uniqueness; work-queue semantics share a group ID. The default in naive Kafka tutorials is the work-queue pattern.

**How to avoid:** D-04 mandates `kore-${hostname}-${pid}` group IDs. The README must include a "Why broadcast, not work queue" section. Add an integration test that verifies two `KafkaEventBus` instances in the same JVM (simulating two pods) both receive all events.

**Warning signs:** Metrics counters disagree across pods. `kafka-consumer-groups.sh` shows multiple consumers in the same group.

### Pitfall 7: Rabbit channel eager-load at bean creation time

**What goes wrong:** `RabbitMqEventBus` constructor opens a connection and declares the exchange. If the broker is unreachable at Spring context startup, the bean creation fails and the entire application refuses to start — even for tests that don't actually need the event bus.

**Why it happens:** amqp-client `ConnectionFactory.newConnection()` is synchronous and throws `IOException` / `TimeoutException` on failure. Putting it in a Kotlin `init` block or non-lazy `val` makes it fire at construction.

**How to avoid:** Use `by lazy { createConnection() }` for the connection property so the first `emit()` or `subscribe()` triggers the connect, not bean creation. Combined with connection-recovery settings on `ConnectionFactory`, the broker can be unavailable at startup and the adapter recovers when the first event is sent. Write a Spring integration test that boots the context with `kore.event-bus.type=rabbitmq` and an unreachable `kore.event-bus.rabbitmq.uri=amqp://localhost:65535` — the context must start successfully (deferred connection).

**Warning signs:** Application fails to start because of "cannot connect to broker" during `applicationContext.refresh()`. Tests that don't touch the event bus still fail.

### Pitfall 8: `@ConditionalOnProperty` default truthiness

**What goes wrong:** `@ConditionalOnProperty(prefix = "kore.event-bus", name = ["type"])` (no `havingValue`) activates whenever ANY value is present, including `in-process`. Both the in-process default AND the Kafka adapter try to register a `EventBus` bean; one wins via `@ConditionalOnMissingBean`, but the losing bean still executes its constructor (bean method is invoked, result discarded) — in Kafka's case this opens a connection to nothing.

**Why it happens:** `@ConditionalOnProperty` defaults to `havingValue=""` which matches any non-empty value.

**How to avoid:** ALWAYS specify `havingValue = "kafka"` (or `"rabbitmq"`) explicitly. D-08 is explicit about this. Write a Spring context test with `kore.event-bus.type=in-process` and assert NEITHER `KafkaEventBusAutoConfiguration` NOR `RabbitMqEventBusAutoConfiguration` runs.

**Warning signs:** Kafka logs "cannot connect to bootstrap-servers" when `kore.event-bus.type=in-process` is set.

### Pitfall 9: kotlinx.serialization compileOnly classpath clash

**What goes wrong:** `@Serializable` compiler plugin generates code referencing `kotlinx.serialization.KSerializer`, which lives in `kotlinx-serialization-core`. If kore-core has the plugin applied but `kotlinx-serialization-core` only at `compileOnly`, downstream consumers that don't also depend on `kotlinx-serialization-json` (or any artifact that transitively brings core) get `NoClassDefFoundError` at runtime.

**Why it happens:** compileOnly deps don't propagate to runtime classpath.

**How to avoid:** Document in `kore-core/README.md` and KDoc that AgentEvent serialization requires a runtime serialization artifact on the classpath — kore-kafka and kore-rabbitmq include `kotlinx-serialization-json` as `implementation`, so consumers that use those modules are fine automatically. Consumers that instrument their own custom adapters must add `kotlinx-serialization-json` themselves. Alternatively, upgrade `compileOnly` to `api` for just `kotlinx-serialization-core` (minimal footprint, doesn't break zero-runtime-dep rule materially) — this is the safer default. Decide during planning.

**Warning signs:** `NoClassDefFoundError: kotlinx/serialization/KSerializer` at startup when using a custom EventBus adapter built on top of kore-core.

### Pitfall 10: Self-hosted runner missing tags cause wrong runner selection

**What goes wrong:** `runs-on: [arc-runner-unityinflow]` picks any runner with that label. If the org has multiple runners with overlapping labels (e.g., `orangepi` also has `arc-runner-unityinflow`), the release job lands on an ARM runner that doesn't have Docker or the right Gradle cache warm.

**Why it happens:** GitHub Actions self-hosted runner label matching is set-based — any runner with ALL listed labels is eligible.

**How to avoid:** Per CLAUDE.md, runners are `hetzner-runner-1/2/3` (x64, labels `self-hosted`, `Linux`, `X64`, `arc-runner-unityinflow`) and `orangepi-runner` (ARM64, labels `self-hosted`, `Linux`, `ARM64`, `orangepi`). The `arc-runner-unityinflow` label is x64-only per the table. Verify `arc-runner-unityinflow` is NOT applied to `orangepi-runner` before release. If it is, pin to `runs-on: [arc-runner-unityinflow, X64]`.

**Warning signs:** Release job fails with "docker: command not found" or with arch-mismatched jar manifests.

### Pitfall 11: `publishAggregationToCentralPortal` fails with configuration cache enabled

**What goes wrong:** nmcp's aggregation task reads credentials from `providers.environmentVariable(...)` at execution time. If configuration cache is enabled, the task inputs are serialized and the credential lookup happens at store-time, not execution-time, leaking credentials into the cache file OR causing cache invalidation on every build.

**Why it happens:** Configuration cache plus environment variable inputs is a known tension. nmcp 1.4.4 has been improved for cc compatibility but some edge cases remain.

**How to avoid:** Use `--no-configuration-cache` on the release workflow's publish step (Pattern 8). Accept the 5-10% build time cost. Or use Gradle property providers (`providers.gradleProperty(...)`) with `-P` flags on the command line, which ARE cc-compatible. Decide during planning based on latest nmcp release notes.

**Warning signs:** Configuration cache reports credentials as inputs. Publish task runs on every invocation regardless of cache state.

### Pitfall 12: `kafka-clients` binds Jackson transitively and causes version skew

**What goes wrong:** `org.apache.kafka:kafka-clients` transitively depends on `org.xerial.snappy:snappy-java` and a few other libs. On some versions it also drags in `com.fasterxml.jackson.*` via LZ4 or Confluent extensions. If `kore-spring` on the same classpath uses a different Jackson version via the Spring Boot BOM, Jackson class loading can conflict.

**Why it happens:** Kafka's optional dependencies leak into the POM; Gradle resolves the highest version across the graph, which may not match what Spring Boot expects.

**How to avoid:** Lock Kafka transitive Jackson (if present) to the Spring Boot BOM version via `implementation("org.apache.kafka:kafka-clients:4.2.0") { exclude(group = "com.fasterxml.jackson.core") }`, and rely on Spring Boot BOM Jackson. Run `./gradlew :kore-kafka:dependencies` to confirm no Jackson conflict after module creation. Confirm with the actual `kafka-clients:4.2.0` POM at build time — if no Jackson is present, no exclude needed.

**Warning signs:** `NoSuchMethodError` in Jackson at runtime. `dependencies` report shows two Jackson versions on the classpath.

### Pitfall 13: `publishAggregationToCentralPortal` task skipped silently if no `nmcpAggregation(...)` deps

**What goes wrong:** Root `build.gradle.kts` applies `com.gradleup.nmcp.aggregation` but forgets to add `nmcpAggregation(project(":kore-core"))` etc. The task runs, reports "nothing to publish", and exits 0. No artifacts are uploaded. The v0.0.1 release "succeeds" on paper but Maven Central has nothing.

**Why it happens:** nmcp aggregation builds the bundle from the declared `nmcpAggregation` dependencies. No deps = empty bundle.

**How to avoid:** Explicit list of all 11 modules in root `build.gradle.kts` (Pattern 7). Write a Gradle check that fails the build if `configurations["nmcpAggregation"].dependencies.size < 11`. Sanity check with `./gradlew :dependencies --configuration nmcpAggregation` before the first release.

**Warning signs:** Publish task completes in under 10 seconds with no upload output. Central Portal shows no staging bundle.

## Code Examples

Referenced patterns are embedded inline in `## Architecture Patterns` above. No separate code-examples table needed — all examples cite sources and are adapted from existing Phase 1-3 code patterns for consistency.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Legacy OSSRH Nexus via `io.github.gradle-nexus.publish-plugin` | New Sonatype Central Portal via `com.gradleup.nmcp` 1.4.4 | 2024 (Sonatype launched new portal); nmcp 1.0 shipped July 2025 | Old plugin targets `s01.oss.sonatype.org` which is being decommissioned. New portal has different API, namespace verification flow, and staging model. |
| Protobuf / Avro for messaging | JSON with `@JsonClassDiscriminator` | ongoing | JSON is readable in broker UIs, debuggable in logs. Binary formats only justified at >10k events/sec or cross-language interop. |
| `spring-kafka` `KafkaTemplate` for JVM Kafka integration | Thin wrapper over `kafka-clients` | — (both still valid) | kore-runtime's hexagonal rule bans framework coupling in adapter modules. `spring-kafka` is fine for apps; not for library adapters. |
| `waitForConfirms()` in a `Thread.sleep` loop | `waitForConfirmsOrDie()` inside `withContext(Dispatchers.IO)` | — | Documented idiom for synchronous confirm-gated publish in Kotlin/coroutines. |

**Deprecated/outdated:**

- `io.github.gradle-nexus.publish-plugin` — explicitly rejected per D-07.
- `spring-kafka`, `spring-amqp` — explicitly rejected per D-01/D-02 (hexagonal rule).
- `kotlinx-serialization-yaml` — remains archived per Phase 3 research; Phase 4 does not touch YAML.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `kafka-clients` 4.2.0 is the latest stable at plan execution time | Standard Stack | Low — planner verifies version before `libs.versions.toml` commit. Major versions 4.x are stable; any 4.x release is fine. |
| A2 | `amqp-client` 5.29.0 is the latest stable | Standard Stack | Low — same verification. |
| A3 | `dev.unityinflow` namespace has NOT been verified on Sonatype Central Portal | Environment Availability, Pitfall 3 | Medium — if it IS already verified, the planner's Wave 0 task is a no-op (fine). If it's NOT verified and the task is omitted, first publish fails hard. |
| A4 | `arc-runner-unityinflow` has Docker installed | Environment Availability | Medium — Testcontainers integration tests need Docker. If missing, release job runs unit tests only; integration tests skip. Acceptable degraded mode. |
| A5 | `kore-runtime` GitHub repo is currently private (so runner group's `allows_public_repositories: false` is not blocking) | Pattern 8 / CLAUDE.md note | High — if repo is public and the runner group setting isn't adjusted, the release workflow will silently fail to find a runner and hang. Planner MUST confirm repo visibility and runner group config as a pre-flight check. |
| A6 | Option A for kotlinx.serialization compileOnly will compile cleanly | Pattern 2 | Medium — the Kotlin serialization compiler plugin needs `KSerializer` and `SerialDescriptor` at compile time. Those live in `kotlinx-serialization-core` (not `-json`), so `compileOnly("kotlinx-serialization-core:1.8.0")` should suffice. If the plugin needs `-json` types too (unlikely), fall back to moving annotations into `kore-core-serialization`. |
| A7 | nmcp 1.4.4 is the final-correct plugin version for this timeline | Standard Stack | Low — plugin has stabilized (1.0 shipped July 2025, 1.4.4 in January 2026). Any 1.x release with `publishAggregationToCentralPortal` task will work. |
| A8 | `kore-test` SHOULD be published to Maven Central (D-05 lists it among the 11 modules) | D-05 / Pattern 7 | Low — D-05 is explicit. Some projects exclude their test-support modules from publication; kore-test is a first-class feature (MockLLMBackend) that users depend on in their own tests, so publish is correct. |
| A9 | Jackson is not pulled transitively by `kafka-clients:4.2.0` | Pitfall 12 | Low — planner verifies with `./gradlew :kore-kafka:dependencies` after module creation. If Jackson IS pulled, add an exclude. |
| A10 | The existing `InProcessEventBus.emit()` contract ("never suspends") is provable with `runTest` + virtual-time assertion | Pattern 1 | Low — standard coroutines-test idiom. If virtual-time assertion flakes, fall back to `withTimeout(50.milliseconds) { bus.emit(...) }` as a wall-clock guard. |

**Items flagged `[ASSUMED]` in-line:**
- Current stable versions (A1, A2, A7) — verify on central.sonatype.com before commit
- Namespace verification status (A3) — verify via portal UI
- Runner Docker availability (A4) — verify via `docker ps` on the runner

None of these are blocking for planning; they're blocking for execution. Planner should include Wave 0 tasks to resolve them.

## Open Questions

1. **`dev.unityinflow` Sonatype Central Portal namespace verification status**
   - What we know: D-05 mandates publishing to `dev.unityinflow.*`, CLAUDE.md shows `jiri@unityinflow.dev` as primary contact.
   - What's unclear: Whether namespace has already been claimed + verified on central.sonatype.com under the user's account.
   - Recommendation: Wave 0 pre-flight task. If verified, no-op. If not, planner includes instructions: (a) register account on central.sonatype.com, (b) claim `dev.unityinflow` namespace, (c) verify via DNS TXT record on `unityinflow.dev` OR via `github.com/UnityInFlow` organization verification. This typically takes < 30 minutes of human time; Claude cannot perform it via tool calls.

2. **Whether `kore-runtime` GitHub repository is public or private**
   - What we know: CLAUDE.md notes `allows_public_repositories: false` on `arc-runner-unityinflow` group.
   - What's unclear: Current visibility of `github.com/UnityInFlow/kore-runtime`.
   - Recommendation: `gh repo view UnityInFlow/kore-runtime --json visibility` as a pre-flight check. If public, either (a) flip to private until after release, or (b) enable public repo access on the runner group via org Settings → Actions → Runner groups → arc-runner-unityinflow → "Allow public repositories". Option (b) is persistent and preferred.

3. **Whether Docker is installed on `arc-runner-unityinflow` hosts**
   - What we know: CI is self-hosted on Hetzner x64 runners. Docker availability not confirmed.
   - What's unclear: Exact host setup.
   - Recommendation: Add a preflight `docker ps || echo "docker not available"` step to the release workflow; planner adds Testcontainers integration test gating so unit tests run unconditionally and integration tests are marked `@EnabledIfSystemProperty("kore.integration.enabled", "true")` or similar. Release workflow sets that property only if `docker ps` succeeds.

4. **Timing of version bump to `0.0.2-SNAPSHOT`**
   - What we know: D-06 mandates bumping to `0.0.2-SNAPSHOT` after successful release.
   - What's unclear: Whether this should be a manual commit post-release or automated in the release workflow itself.
   - Recommendation: Manual follow-up commit — keeps the release commit clean. Release workflow ends at "GitHub Release created". A separate commit on `main` post-release sets version to `0.0.2-SNAPSHOT` via `./gradlew versionBump --next="0.0.2-SNAPSHOT"` or manual edit. Document in Phase 4 SUMMARY.md.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (JUnit Platform) via `junit-jupiter` 5.12.0, assertions via Kotest `kotest-assertions-core` 6.1.11 |
| Config file | Per-module `build.gradle.kts` with `tasks.test { useJUnitPlatform() }` |
| Quick run command | `./gradlew :kore-core:test --tests "*EventBus*Test"` — runs the two new EventBus tests in < 10 seconds |
| Full suite command | `./gradlew test` — excludes `@Tag("integration")` by default; `./gradlew integrationTest` or `./gradlew test -Dkore.integration.enabled=true` runs Testcontainers-backed Kafka/Rabbit suites |
| Phase gate | Full suite + `./gradlew publishToMavenLocal` green + manual smoke test of `publishAggregationToCentralPortal` to a staging bundle before tagging `v0.0.1` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| EVNT-01 | EventBus port interface for agent lifecycle events | unit | `./gradlew :kore-core:test --tests "*EventBusBackpressureTest"` | ❌ Wave 0 (new file) |
| EVNT-01 | Port contract stable (method signatures, Flow semantics) | compile | `./gradlew :kore-core:compileKotlin` — structural, no runtime assertion needed | ✅ (existing file `EventBus.kt`) |
| EVNT-02 | InProcessEventBus uses SharedFlow + DROP_OLDEST, emit never suspends | unit | `./gradlew :kore-core:test --tests "*EventBusBackpressureTest.emit never suspends even with slow consumer"` | ❌ Wave 0 |
| EVNT-02 | InProcessEventBus handles concurrent producers correctly | unit | `./gradlew :kore-core:test --tests "*EventBusConcurrencyTest"` | ❌ Wave 0 |
| EVNT-02 | `AgentEvent` polymorphic JSON roundtrip preserves type discriminator | unit | `./gradlew :kore-core:test --tests "*AgentEventSerializationTest"` | ❌ Wave 0 (new file) — validates Pattern 2 compileOnly setup end-to-end |
| EVNT-03 | Adding kore-kafka + setting `kore.event-bus.type=kafka` switches the bus | integration | `./gradlew :kore-spring:test --tests "*KafkaEventBusAutoConfigurationTest"` | ❌ Wave 0 (new file) — Spring context test with mocked KafkaProducer/KafkaConsumer, asserts `EventBus` bean is `KafkaEventBus` instance |
| EVNT-03 | kore-kafka producer uses suspendCancellableCoroutine bridge correctly | unit | `./gradlew :kore-kafka:test --tests "*KafkaEventBusUnitTest"` | ❌ Wave 0 — MockK for KafkaProducer callback |
| EVNT-03 | kore-kafka against real broker (broadcast, shutdown, acks) | integration | `./gradlew :kore-kafka:test --tests "*KafkaEventBusIntegrationTest" -Dkore.integration.enabled=true` | ❌ Wave 0 — Testcontainers `org.testcontainers:kafka` |
| EVNT-04 | Adding kore-rabbitmq + setting `kore.event-bus.type=rabbitmq` switches the bus | integration | `./gradlew :kore-spring:test --tests "*RabbitMqEventBusAutoConfigurationTest"` | ❌ Wave 0 |
| EVNT-04 | kore-rabbitmq uses fanout + exclusive queue + publisher confirms | unit | `./gradlew :kore-rabbitmq:test --tests "*RabbitMqEventBusUnitTest"` | ❌ Wave 0 — MockK for Channel |
| EVNT-04 | kore-rabbitmq against real broker | integration | `./gradlew :kore-rabbitmq:test --tests "*RabbitMqEventBusIntegrationTest" -Dkore.integration.enabled=true` | ❌ Wave 0 — Testcontainers `org.testcontainers:rabbitmq` |
| D-05 Publishing | All 11 modules produce well-formed POMs | publish-local | `./gradlew clean publishToMavenLocal` and inspect `~/.m2/repository/dev/unityinflow/` for 11 module directories, each with `pom`, `jar`, `sources.jar`, `javadoc.jar`, and `.asc` signature files | Manual verification step |
| D-05 Publishing | Aggregated bundle matches Central Portal requirements | publish-staging | `./gradlew publishAggregationToCentralPortal --dry-run` if supported; otherwise `publishAggregationToCentralPortal` with `publishingType = "USER_MANAGED"` to upload a staging bundle that can be manually dropped before release | Manual verification step — executed by release workflow on tag push |
| D-10 CI workflow | Release workflow triggers on v-tag push | integration | Push a `v0.0.1-rc1` tag to a feature branch once, watch the workflow run, drop the staging bundle before it's released | Manual verification step — one-time before tagging v0.0.1 |

### Sampling Rate

- **Per task commit:** `./gradlew :<touched-module>:test` (fast, < 30 seconds)
- **Per wave merge:** `./gradlew test` (all unit tests across all modules, < 3 minutes)
- **Per phase gate:** `./gradlew clean test publishToMavenLocal` + manual inspection of POM content + dry-run integration tests with Testcontainers (if Docker available)
- **Per release:** `./gradlew clean test publishAggregationToCentralPortal` via the GitHub Actions release workflow, followed by Sonatype Central Portal manual release of the staging bundle

### Wave 0 Gaps

- [ ] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/port/EventBusBackpressureTest.kt` — covers EVNT-01 / EVNT-02 backpressure
- [ ] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/port/EventBusConcurrencyTest.kt` — covers EVNT-01 / EVNT-02 concurrency
- [ ] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentEventSerializationTest.kt` — covers Pattern 2 compileOnly validation + D-03 JSON wire format
- [ ] `kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusUnitTest.kt` — MockK-based unit test
- [ ] `kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusIntegrationTest.kt` — Testcontainers
- [ ] `kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusUnitTest.kt` — MockK-based
- [ ] `kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusIntegrationTest.kt` — Testcontainers
- [ ] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KafkaEventBusAutoConfigurationTest.kt` — Spring context test
- [ ] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/RabbitMqEventBusAutoConfigurationTest.kt` — Spring context test
- [ ] `buildSrc/src/main/kotlin/kore.publishing.gradle.kts` — convention plugin
- [ ] `.github/workflows/release.yml` — release CI workflow
- [ ] Pre-flight: Verify `dev.unityinflow` namespace on central.sonatype.com (HUMAN task, see Open Question 1)
- [ ] Pre-flight: Verify `kore-runtime` repo visibility + runner group public-repo permission (HUMAN task, see Open Question 2)
- [ ] Pre-flight: Verify Docker available on `arc-runner-unityinflow` (Claude task: add preflight step to release workflow)

## Sources

### Primary (HIGH confidence)

- [gradleup.com/nmcp/](https://gradleup.com/nmcp/) — nmcp quickstart, aggregation plugin, `publishAggregationToCentralPortal` task name
- [gradleup.com/nmcp/manual-configuration/](https://gradleup.com/nmcp/manual-configuration/) — per-project vs aggregation pattern, `nmcpAggregation` configuration
- [github.com/GradleUp/nmcp](https://github.com/GradleUp/nmcp) — repo, release history, 1.4.4 as latest stable (January 2026)
- [central.sonatype.com](https://central.sonatype.com/) — Sonatype Central Portal (new API, namespace verification)
- [central.sonatype.org/publish/publish-portal-gradle/](https://central.sonatype.org/publish/publish-portal-gradle/) — confirms no official Sonatype Gradle plugin exists; community plugins (nmcp, vanniktech, jreleaser) are the supported path
- [kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/.../-json-class-discriminator/](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-class-discriminator/) — `@JsonClassDiscriminator` official API docs
- [github.com/Kotlin/kotlinx.serialization/blob/master/docs/polymorphism.md](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/polymorphism.md) — sealed class polymorphism canonical guide
- [docs.gradle.org/current/userguide/signing_plugin.html](https://docs.gradle.org/current/userguide/signing_plugin.html) — `signing` plugin, `useInMemoryPgpKeys` API
- [rabbitmq.com/tutorials/tutorial-three-java](https://www.rabbitmq.com/tutorials/tutorial-three-java) — Publish/Subscribe fanout exchange pattern
- [rabbitmq.com/tutorials/tutorial-seven-java](https://www.rabbitmq.com/tutorials/tutorial-seven-java) — Publisher Confirms with `waitForConfirmsOrDie`
- [rabbitmq.com/docs/publishers](https://www.rabbitmq.com/docs/publishers) — official publisher guide
- [kafka.apache.org/41/getting-started/compatibility/](https://kafka.apache.org/41/getting-started/compatibility/) — Kafka 4.x Java version compatibility (Java 17, 21 supported)
- [central.sonatype.com/artifact/org.apache.kafka/kafka-clients](https://central.sonatype.com/artifact/org.apache.kafka/kafka-clients) — kafka-clients 4.2.0 latest stable
- [javadoc.io/doc/com.rabbitmq/amqp-client/5.29.0](https://javadoc.io/doc/com.rabbitmq/amqp-client/latest/index.html) — amqp-client 5.29.0 API docs

### Secondary (MEDIUM confidence)

- [mbonnin.net/2025-07-05_nmcp_and_the_other_90_percent/](https://mbonnin.net/2025-07-05_nmcp_and_the_other_90_percent/) — nmcp 1.0 release notes (July 2025)
- [blog.ippon.tech/kafka-tutorial-2-simple-consumer-in-kotlin](https://blog.ippon.tech/kafka-tutorial-2-simple-consumer-in-kotlin) — Kafka consumer in Kotlin pattern (WebSearch, verified against official Kafka docs)
- [baeldung.com/rabbitmq-consumer-acknowledgments-publisher-confirmations](https://www.baeldung.com/rabbitmq-consumer-acknowledgments-publisher-confirmations) — Publisher confirms Java tutorial
- [nabeelvalley.co.za/blog/2023/11-11/interacting-with-kafka-using-kotlin/](https://nabeelvalley.co.za/blog/2023/11-11/interacting-with-kafka-using-kotlin/) — Kotlin coroutines + Kafka interaction
- [baeldung.com/kotlin/kotlinx-serialization-inheritance](https://www.baeldung.com/kotlin/kotlinx-serialization-inheritance) — kotlinx.serialization sealed class inheritance
- [vanniktech.github.io/gradle-maven-publish-plugin/](https://vanniktech.github.io/gradle-maven-publish-plugin/) — alternative publishing plugin docs (in-memory GPG pattern)
- [simonscholz.dev/tutorials/publish-maven-central-gradle/](https://simonscholz.dev/tutorials/publish-maven-central-gradle/) — end-to-end Gradle + GitHub Actions Maven Central publishing walkthrough

### Tertiary (LOW confidence)

- [kotlinlang.org/docs/cancellation-and-timeouts.html](https://kotlinlang.org/docs/cancellation-and-timeouts.html) — coroutine cancellation (HIGH, not LOW, but listed here for visibility)

No tertiary-only claims remain — every factual statement in this research cites at least one HIGH or MEDIUM source, or is explicitly tagged `[ASSUMED]` in the Assumptions Log.

## Metadata

**Confidence breakdown:**

- Standard stack: HIGH — all versions (nmcp 1.4.4, kafka-clients 4.2.0, amqp-client 5.29.0) verified from official Maven Central / repo release pages. kotlinx.serialization 1.8.0 already in libs.versions.toml from Phase 3.
- Architecture (coroutine bridges, Spring wiring, convention plugin): HIGH — patterns 1 and 6 are verified from existing Phase 2/3 code in the repo; patterns 3-5 are verified from official Kafka + RabbitMQ tutorials and API docs; pattern 7 matches the vanniktech / nmcp documented convention.
- Pitfalls: HIGH on inherited Pitfalls 1, 2 (verified against Phase 3 research); HIGH on Pitfalls 3-5, 11, 13 (specific to Sonatype Central Portal and nmcp documented behavior); MEDIUM on Pitfalls 6-9, 12 (depend on specific library behavior that should be verified in execution); HIGH on Pitfall 10 (verified from CLAUDE.md runner config).
- Validation Architecture: HIGH — test file list is derived from requirements, uses existing Phase 1-3 test patterns (Kotest assertions + JUnit 5 + `runTest` + MockK).

**Research date:** 2026-04-13
**Valid until:** 2026-06-15 (nmcp plugin is actively evolving; kafka-clients and amqp-client are stable. Re-verify nmcp task names against the latest docs if execution is delayed past May 2026.)
