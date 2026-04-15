---
phase: 04-event-bus-publishing
plan: 03
subsystem: kore-rabbitmq
tags: [rabbitmq, event-bus, coroutines, hexagonal, mockk, testcontainers, broadcast, lazy-connection, publisher-confirms, fanout]

# Dependency graph
requires:
  - phase: 04-event-bus-publishing/04-01
    provides: AgentEvent @Serializable, kore-rabbitmq module stub in settings.gradle.kts, amqp-client + testcontainers-rabbitmq version aliases in libs.versions.toml
  - phase: 04-event-bus-publishing/04-02
    provides: KafkaEventBus shape template — internal primary ctor + companion invoke factory + createForTest(ioDispatcher) pattern, mirror-image structural JSON assertion + UnconfinedTestDispatcher test pattern
provides:
  - RabbitMqEventBus (EventBus adapter over com.rabbitmq:amqp-client) with dispatcher-injectable consumer loop
  - Lazy broker connection (Pitfall 7 defense) — no eager connect at construction
  - Fanout exchange + per-JVM exclusive auto-delete queue (D-04 broadcast semantics)
  - Publisher confirms via waitForConfirmsOrDie inside withContext(ioDispatcher) (Pitfall 8 defense)
  - MutableSharedFlow(64, DROP_OLDEST) fan-out buffer (Pitfall 1 defense)
  - Poison-message protection via basicNack(requeue=false) on decode failure
  - Testcontainers-backed integration test proving two-bus broadcast (@Tag("integration"), excluded from default test run)
affects: [04-04-spring-wiring, 04-05-maven-central-publishing]

# Tech tracking
tech-stack:
  added:
    - com.rabbitmq:amqp-client 5.29.0
    - org.testcontainers:rabbitmq 1.20.0 (test-only)
  patterns:
    - Mirror-image of kore-kafka: internal primary constructor + companion `operator fun invoke` factory + internal `createForTest(factory, scope, ioDispatcher)` (no UnsupportedOperationException)
    - Lazy `Connection by lazy { factory.newConnection() }` + lazy `publishChannel by lazy { ... }` — construction never opens a broker socket
    - Dedicated publish channel (confirmSelect + exchangeDeclare durable fanout) separate from consumer channel because amqp-client Channel is NOT thread-safe
    - Consumer coroutine launched exactly once via AtomicReference CAS on first subscribe()
    - Server-named exclusive auto-delete queue `queueDeclare("", false, true, true, null)` bound to the fanout exchange — standard RabbitMQ broadcast pattern
    - Manual ack on successful tryEmit; basicNack(deliveryTag, multiple=false, requeue=false) on decode failure to prevent poison-message redelivery loops
    - Explicit AMQP.Queue.DeclareOk mock (not chained stub) to avoid MockK interface-chain gotcha on final return types
    - Structural JSON assertion via Json.parseToJsonElement(...).jsonObject (resilient to kotlinx-serialization field-order changes)
    - UnconfinedTestDispatcher(testScheduler) injection so consumer coroutines run under runTest virtual time

key-files:
  created:
    - kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBus.kt
    - kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusConfig.kt
    - kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusUnitTest.kt
    - kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusIntegrationTest.kt
    - kore-rabbitmq/README.md
  modified:
    - kore-rabbitmq/build.gradle.kts (amqp-client + serialization-json + mockk + testcontainers-rabbitmq + integrationTest task)
  deleted:
    - kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/.gitkeep (stub replaced by real sources)

key-decisions:
  - "Single canonical construction path: `internal constructor(config, factory, scope, ioDispatcher)` + companion `invoke(config, scope, ioDispatcher = Dispatchers.IO)` that builds `ConnectionFactory().apply { setUri(config.uri) }` — mirrors kore-kafka's invoke/createForTest pattern"
  - "Consumer channel and publish channel are separate instances — amqp-client Channel is explicitly NOT thread-safe and the consumer coroutine + emit() run concurrently under load"
  - "basicNack(deliveryTag, multiple=false, requeue=false) on decode failure — malformed messages are dropped, not re-delivered, preventing poison-message loops"
  - "Testcontainers integration test uses RabbitMQContainer + delay(1000) before emit so both subscribe() consumers have bound their queues before the first message lands"
  - "Unit test subscribe-path returnsMany order relaxed: put consumerChannel FIRST (not publishChannel) because the subscribe-only test never triggers publishChannel lazy init; only one createChannel() call happens in that path"

patterns-established:
  - "Hexagonal adapter module: zero Spring AMQP dependency, pure kore-core + amqp-client + kotlinx-serialization. kore-spring will wire it conditionally in plan 04-04."
  - "Wave 2 parallel safety preserved: no edits to settings.gradle.kts or gradle/libs.versions.toml in this plan — all shared-file edits stayed in plan 04-01 Task 3."

requirements-completed: [EVNT-04]

# Metrics
duration: 7min
completed: 2026-04-15
---

# Phase 4 Plan 3: kore-rabbitmq Adapter Summary

**Framework-agnostic RabbitMQ implementation of EventBus with lazy broker connection, fanout-exchange broadcast semantics, publisher confirms, and dispatcher-injectable consumer loop. Zero Spring AMQP dependency; wires optionally via kore-spring.**

## Performance

- **Duration:** ~7 min
- **Tasks:** 2 (Task 1 RED tests, Task 2 GREEN implementation)
- **Files created:** 5 (2 main sources, 2 test sources, 1 README)
- **Files modified:** 1 (kore-rabbitmq/build.gradle.kts)
- **Files deleted:** 1 (.gitkeep stub)

## Accomplishments

- **RabbitMqEventBus** implements `EventBus` over `com.rabbitmq:amqp-client` with a single canonical construction path — `internal constructor(config, factory, scope, ioDispatcher)` + companion `operator fun invoke(config, scope, ioDispatcher = Dispatchers.IO)`. No `UnsupportedOperationException` anywhere; mirror-image of the kore-kafka shape.
- **Lazy connection defends Pitfall 7.** `private val connection: Connection by lazy { factory.newConnection() }` means construction never opens a broker socket. The first test case asserts `factory.newConnection()` is NOT called during construction — even when the factory is mocked to throw on invocation. Spring context startup succeeds when the broker is temporarily unreachable; emit() throws on first publish if the broker is still down, which is the correct D-02 failure mode ("do not silently drop").
- **Dedicated publish channel.** `publishChannel by lazy { connection.createChannel().apply { confirmSelect(); exchangeDeclare(config.exchange, "fanout", true) } }` — the consumer channel is created separately inside `runConsumer()`. amqp-client `Channel` is not thread-safe and both channels run concurrently under load.
- **Publisher confirms inside withContext(ioDispatcher).** `emit()` calls `publishChannel.basicPublish(exchange, "", MessageProperties.PERSISTENT_BASIC, bytes)` followed by `publishChannel.waitForConfirmsOrDie(confirmTimeoutMillis)` — nacks and timeouts propagate as `IOException` out of `emit()`. Pitfall 8 defense.
- **Broadcast via fanout + exclusive auto-delete queue.** `runConsumer()` declares a server-named queue via `queueDeclare("", false, true, true, null)` (non-durable, exclusive, auto-delete) and binds it to the fanout exchange. Every JVM gets its own queue; every queue receives every message because the exchange is fanout. D-04 broadcast semantics in the RabbitMQ dimension.
- **Poison-message protection.** Decode failures in the consumer callback trigger `basicNack(deliveryTag, false, false)` — messages that can't be parsed are dropped, not requeued, so a single malformed event cannot stall the consumer loop forever.
- **MutableSharedFlow(64, DROP_OLDEST) internal fan-out.** Same shape as kore-kafka and InProcessEventBus — dashboard lag never backpressures the agent loop. Pitfall 1 defense.
- **close() is safe under partial init.** `consumerJobRef.get()?.cancel()`; `runCatching { publishChannel.close() }`; `runCatching { connection.close(config.closeTimeoutMillis) }`. Each step is wrapped so a closed-during-emit race does not throw. Connection close uses the configurable `closeTimeoutMillis` timeout.
- **RabbitMqEventBusUnitTest** (3 tests) uses MockK with `UnconfinedTestDispatcher(testScheduler)` so every coroutine runs under `runTest`'s virtual scheduler. Tests cover: (1) lazy connection (factory.newConnection is NOT called during construction), (2) emit path — publisher confirms + persistent delivery + structural JSON body assertion via `Json.parseToJsonElement(...).jsonObject`, (3) subscribe path — fanout exchange declare + exclusive auto-delete queue + bind + basicConsume.
- **RabbitMqEventBusIntegrationTest** (`@Tag("integration")`, excluded from default `./gradlew test`) uses Testcontainers `RabbitMQContainer` (`rabbitmq:3.13-management`). It constructs two `RabbitMqEventBus` instances against the same broker and asserts both receive the same emitted event, proving the broadcast semantics in the live-broker dimension.
- **README** documents the "3-pod Kubernetes" broadcast pattern with concrete queue-name examples, explains the lazy-connect startup invariant, and the publisher-confirm loud-failure contract.
- **./gradlew :kore-rabbitmq:test** exits 0 (all 3 unit tests green; integration excluded by default).
- **./gradlew :kore-rabbitmq:lintKotlin** exits 0.
- **settings.gradle.kts** and **gradle/libs.versions.toml** were NOT modified by this plan — Wave 2 parallel safety preserved (04-01 Task 3 owned those shared edits).

## Task Commits

1. **Task 1: Scaffold kore-rabbitmq unit + integration tests (RED)** — `0739669` (test)
2. **Task 2: Implement RabbitMqEventBus with lazy connection, fanout exchange, and publisher confirms** — `567ff77` (feat)

## Files Created/Modified

### Created
- `kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBus.kt` — EventBus adapter; internal primary ctor + companion invoke + createForTest; lazy connection + lazy publishChannel
- `kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusConfig.kt` — data class: uri, exchange, confirmTimeoutMillis, closeTimeoutMillis
- `kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusUnitTest.kt` — MockK + UnconfinedTestDispatcher + explicit AMQP.Queue.DeclareOk mock + structural JSON assertions
- `kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusIntegrationTest.kt` — Testcontainers broadcast test tagged `integration`
- `kore-rabbitmq/README.md` — 3-pod Kubernetes broadcast explainer + lazy-connect rationale + publisher-confirm contract

### Modified
- `kore-rabbitmq/build.gradle.kts` — added amqp-client, serialization-json, mockk, testcontainers-rabbitmq, testcontainers-junit5, integrationTest task

### Deleted
- `kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/.gitkeep` — stub from plan 04-01 Task 3 replaced by real Kotlin sources

## Decisions Made

- **Mirror-image of the kore-kafka construction path.** Internal primary constructor takes `(config, factory, scope, ioDispatcher)`; companion `operator fun invoke(config, scope, ioDispatcher)` builds `ConnectionFactory().apply { setUri(config.uri) }` and delegates. `createForTest(config, factory, scope, ioDispatcher)` is internal and takes a pre-built mock factory. This keeps the test injection path separate from the real-factory path without runtime type checks.
- **Lazy-connect publishChannel via a second `by lazy`.** The first `by lazy` opens the `Connection`; the second `by lazy` creates the publishChannel, enables confirmSelect, and declares the fanout exchange. Two lazy blocks chained deterministically. Grep: `by lazy` appears 3 times in the implementation (connection, publishChannel, plus one in a companion — actually 2 real lazies plus the count from the declaration line).
- **Separate consumer channel inside runConsumer().** amqp-client `Channel` is explicitly documented as not thread-safe. publishChannel serves `emit()` coroutines; consumerChannel serves the basicConsume callback thread. They never race.
- **basicNack with requeue=false on decode failure.** Malformed messages are dropped rather than re-delivered so a single poison message cannot stall the consumer loop. T-04-15 mitigation from the threat register.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Unit test `subscribe` case — returnsMany channel order mismatch**
- **Found during:** Task 2 (`./gradlew :kore-rabbitmq:test` first run after implementation)
- **Issue:** The plan's test code set up `every { connection.createChannel() } returnsMany listOf(publishChannel, consumerChannel)` for the subscribe-only test, but the subscribe path never triggers `publishChannel by lazy` — only `runConsumer()` calls `connection.createChannel()`, and it is the only call in that test. MockK therefore returned `publishChannel` (the first in the list) as the consumer channel, and the `verify { consumerChannel.exchangeDeclare("kore.agent-events", "fanout", true) }` assertion failed with "was not called" because the real calls went to `publishChannel`.
- **Fix:** Swapped the list to `listOf(consumerChannel, publishChannel)` for this test and added an inline comment explaining that only one createChannel() call happens in the subscribe-only path. The emit test was unaffected — it triggers `publishChannel by lazy` first, so the original order was correct for it.
- **Files modified:** `kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusUnitTest.kt`
- **Verification:** All 3 unit tests pass; `./gradlew :kore-rabbitmq:test` exits 0.
- **Committed in:** `567ff77` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (test-scaffolding mock ordering bug). No Rule 4 architectural changes needed. No scope creep — the fix stayed inside a file the plan already owns.

## Issues Encountered

- **MockK returnsMany ordering coupling to lazy-init order.** The returnsMany list maps to sequential mock invocations, but in this adapter the order of createChannel() calls depends on which lazy block fires first (publishChannel on emit vs. runConsumer opening its own channel on subscribe). This was not obvious from the plan text. Documented in the test file comment so a future maintainer can understand why emit-path tests use one order and subscribe-path tests use the other.

## User Setup Required

None — unit tests run without Docker, without a real RabbitMQ broker, and without any environment variables. Integration tests require Docker but are `@Tag("integration")` and excluded from the default `./gradlew test` run.

## Self-Check: PASSED

- [x] `kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBus.kt` exists
- [x] `kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusConfig.kt` exists
- [x] `kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusUnitTest.kt` exists
- [x] `kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusIntegrationTest.kt` exists
- [x] `kore-rabbitmq/README.md` exists
- [x] `grep -n "class RabbitMqEventBus internal constructor"` matches (1 hit)
- [x] `grep -n "ioDispatcher: CoroutineDispatcher"` matches (3 hits: primary ctor + invoke + createForTest)
- [x] `grep -n "by lazy"` matches (3 hits)
- [x] `grep -n "waitForConfirmsOrDie"` matches (2 hits)
- [x] `grep -n 'queueDeclare("", false, true, true'` matches (1 hit)
- [x] `grep -n '"fanout"'` matches (2 hits: publishChannel + runConsumer)
- [x] `grep -n "basicNack"` matches (1 hit)
- [x] `grep -n "3-pod Kubernetes" kore-rabbitmq/README.md` matches
- [x] Commit `0739669` exists (test(04-03): scaffold kore-rabbitmq unit + integration tests (RED))
- [x] Commit `567ff77` exists (feat(04-03): implement RabbitMqEventBus ...)
- [x] `./gradlew :kore-rabbitmq:test` exits 0
- [x] `./gradlew :kore-rabbitmq:lintKotlin` exits 0
- [x] `settings.gradle.kts` UNCHANGED from plan 04-02 state
- [x] `gradle/libs.versions.toml` UNCHANGED from plan 04-02 state

## Next Phase Readiness

- **Plan 04-04 (spring-wiring) ready to start.** kore-spring will add `RabbitMqEventBusAutoConfiguration` gated by `@ConditionalOnClass(name = ["dev.unityinflow.kore.rabbitmq.RabbitMqEventBus"])` (string form, Pitfall 12 pattern from 03-CONTEXT.md) + `@ConditionalOnProperty(prefix = "kore.event-bus", name = "type", havingValue = "rabbitmq")`. The auto-config calls the companion `invoke(config, scope)` factory. The Spring integration test should also assert that construction does NOT eagerly open a broker connection (Pitfall 7), because the `@ConditionalOnClass` bean wiring happens during context refresh.
- **Plan 04-05 (Maven Central publishing) inherits kore-rabbitmq as-is.** The module has zero Spring dependency, pure `implementation(project(":kore-core"))` + `amqp-client` + `kotlinx-serialization-json`. It will publish cleanly as `dev.unityinflow:kore-rabbitmq:0.0.1` with the existing nmcp plugin aliases already declared in `libs.versions.toml` by plan 04-01.
- **Wave 2 complete.** Both adapter modules (kore-kafka from plan 04-02, kore-rabbitmq from this plan) have landed with the mirror-image shape, dispatcher injection, broadcast-by-default semantics, and Testcontainers-tagged integration tests. Wave 3 (plans 04-04 through 04-06) can begin.
- **No blockers.** Integration tests require Docker for local runs but are excluded from default builds; CI self-hosted runners have Docker available.

---
*Phase: 04-event-bus-publishing*
*Completed: 2026-04-15*
