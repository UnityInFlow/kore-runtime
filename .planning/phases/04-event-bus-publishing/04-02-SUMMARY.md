---
phase: 04-event-bus-publishing
plan: 02
subsystem: kore-kafka
tags: [kafka, event-bus, coroutines, hexagonal, mockk, testcontainers, broadcast]

# Dependency graph
requires:
  - phase: 04-event-bus-publishing/04-01
    provides: AgentEvent @Serializable + @JsonClassDiscriminator, kore-kafka module stub in settings.gradle.kts, kafka-clients + testcontainers-kafka version aliases in libs.versions.toml
provides:
  - KafkaEventBus (EventBus adapter over Apache Kafka clients) with dispatcher-injectable consumer loop
  - Broadcast semantics via `${prefix}-${hostname}-${pid}` consumer group IDs (D-04 / Pitfall 6)
  - Producer bridge using suspendCancellableCoroutine (never blocks caller thread)
  - MutableSharedFlow(64, DROP_OLDEST) fan-out buffer (Pitfall 1 defense)
  - Jackson exclusion on kafka-clients (Pitfall 12 defense vs Spring Boot BOM)
  - Testcontainers-backed integration test proving multi-bus broadcast (@Tag("integration"), excluded from default test run)
affects: [04-04-spring-wiring, 04-05-maven-central-publishing]

# Tech tracking
tech-stack:
  added:
    - org.apache.kafka:kafka-clients 4.2.0 (Jackson excluded)
    - org.testcontainers:kafka 1.20.0 (test-only)
  patterns:
    - Internal primary constructor + companion `operator fun invoke` factory (no secondary-constructor UnsupportedOperationException hack)
    - Internal `createForTest(producer, consumer, scope, ioDispatcher)` factory for deterministic MockK tests
    - UnconfinedTestDispatcher(testScheduler) injection so consumer coroutines run under runTest virtual time
    - Structural JSON assertions via Json.parseToJsonElement(...).jsonObject (not byte-equal strings)
    - KafkaConsumer.close(CloseOptions.timeout(...)) (non-deprecated API)

key-files:
  created:
    - kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/KafkaEventBus.kt
    - kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusConfig.kt
    - kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ProducerBridge.kt
    - kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ConsumerLoop.kt
    - kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusUnitTest.kt
    - kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusIntegrationTest.kt
    - kore-kafka/README.md
  modified:
    - kore-kafka/build.gradle.kts (kafka-clients + Jackson exclude, serialization-json, mockk, testcontainers-kafka, integrationTest task)
  deleted:
    - kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/.gitkeep (stub replaced by real sources)

key-decisions:
  - "Single canonical construction path: `internal constructor` + companion `invoke(config, scope, ioDispatcher = Dispatchers.IO)` — the earlier secondary-constructor + UnsupportedOperationException shape from iteration 1 was rejected"
  - "CloseOptions.timeout(Duration) instead of deprecated consumer.close(Duration) — kafka-clients 4.x deprecated the Duration overload and requires the CloseOptions builder"
  - "Consumer group ID format `kore-<hostname>-<pid>` via InetAddress.getLocalHost().hostName + ProcessHandle.current().pid(); hostnames may contain dashes so the unit test asserts `split('-').size >= 3` instead of `== 3`"
  - "Jackson excluded from kafka-clients to prevent transitive version skew with Spring Boot 4 BOM (Pitfall 12)"
  - "Tests use UnconfinedTestDispatcher(testScheduler) rather than real Dispatchers.IO so MockK verify blocks see deterministic coroutine state under runTest"

patterns-established:
  - "Hexagonal adapter module: zero Spring dependency, pure kore-core + kafka-clients + kotlinx-serialization. kore-spring will wire it conditionally in plan 04-04."
  - "Mirror template for kore-rabbitmq (plan 04-03): same internal-ctor + companion invoke shape, same dispatcher injection, same broadcast-by-default semantics."

requirements-completed: [EVNT-03]

# Metrics
duration: 8min
completed: 2026-04-15
---

# Phase 4 Plan 2: kore-kafka Adapter Summary

**Framework-agnostic Apache Kafka implementation of EventBus with coroutine producer bridge, dispatcher-injectable consumer loop, and per-JVM broadcast consumer groups. Zero Spring dependency; wires optionally via kore-spring.**

## Performance

- **Duration:** ~8 min
- **Tasks:** 2 (Task 1 RED tests, Task 2 GREEN implementation)
- **Files created:** 7 (4 main sources, 2 test sources, 1 README)
- **Files modified:** 1 (kore-kafka/build.gradle.kts)
- **Files deleted:** 1 (.gitkeep stub)

## Accomplishments

- **KafkaEventBus** implements `EventBus` over `org.apache.kafka:kafka-clients` with a single canonical construction path — `internal constructor` + companion `operator fun invoke(config, scope, ioDispatcher = Dispatchers.IO)`. There is no `UnsupportedOperationException` anywhere in the module; `grep -c "UnsupportedOperationException" kore-kafka/src/main/kotlin/**` returns 0.
- **ProducerBridge** bridges `KafkaProducer.send(record, callback)` into a suspending function using `suspendCancellableCoroutine`. The caller never blocks; the Kafka callback contract (exactly one of metadata/exception non-null) is documented inline.
- **ConsumerLoop** runs on an injectable `CoroutineDispatcher`, uses a 500ms poll timeout, handles `WakeupException`, calls `ensureActive()` per iteration, decodes records via `kotlinx.serialization` JSON, and `tryEmit`s into an internal `MutableSharedFlow(replay=0, extraBufferCapacity=64, onBufferOverflow=DROP_OLDEST)`. The fan-out buffer defends Pitfall 1; the loop cleanup uses `consumer.close(CloseOptions.timeout(Duration.ofSeconds(5)))` (non-deprecated API).
- **KafkaEventBusConfig.resolveGroupId()** returns `"${groupIdPrefix}-${InetAddress.getLocalHost().hostName}-${ProcessHandle.current().pid()}"` — every JVM gets a unique consumer group so Kafka delivers every event to every pod (broadcast, not work queue; defends Pitfall 6 and enables D-04 multi-instance observer semantics).
- **Jackson exclude on kafka-clients** (`exclude(group = "com.fasterxml.jackson.core")`) prevents transitive version skew with Spring Boot 4 BOM (Pitfall 12 defense).
- **KafkaEventBusUnitTest** (3 tests) uses MockK with `UnconfinedTestDispatcher(testScheduler)` so all coroutine work runs under `runTest`'s virtual scheduler. Tests cover: (1) consumer group ID format, (2) producer send callback bridge + structural JSON body assertion, (3) producer exception propagation via `suspendCancellableCoroutine`. JSON assertions use `Json.parseToJsonElement(...).jsonObject` — resilient to kotlinx-serialization field-order changes.
- **KafkaEventBusIntegrationTest** (`@Tag("integration")`, excluded from default `./gradlew test`) uses Testcontainers `KafkaContainer` (`apache/kafka:3.8.0`). It constructs two `KafkaEventBus` instances against the same broker and asserts both receive the same emitted event, proving the broadcast semantics in the live-broker dimension.
- **README** documents the "3-pod Kubernetes" broadcast pattern with concrete group-ID examples, explains why kore-kafka is not a work-queue adapter, and references `spring-kafka` as the correct tool if work-queue semantics are desired.
- **./gradlew :kore-kafka:test** exits 0 (all 3 unit tests green; integration excluded by default).
- **./gradlew :kore-kafka:lintKotlin** exits 0.
- **settings.gradle.kts and gradle/libs.versions.toml** were NOT modified by this plan — all shared-file edits were completed by plan 04-01 Task 3. Wave 2 parallel safety preserved for plan 04-03 (kore-rabbitmq).

## Task Commits

1. **Task 1: Scaffold kore-kafka unit + integration tests (RED)** — `526335b` (test)
2. **Task 2: Implement KafkaEventBus with suspendCancellableCoroutine bridge and broadcast consumer group** — `3686e22` (feat)

## Files Created/Modified

### Created
- `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/KafkaEventBus.kt` — EventBus adapter; `internal constructor` + companion `invoke` + `createForTest`
- `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusConfig.kt` — data class config; `resolveGroupId()` helper
- `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ProducerBridge.kt` — `awaitSend` extension on `KafkaProducer<String, ByteArray>` via `suspendCancellableCoroutine`
- `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ConsumerLoop.kt` — dispatcher-injectable poll loop, `WakeupException` handling
- `kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusUnitTest.kt` — MockK + `UnconfinedTestDispatcher` + structural JSON assertions
- `kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusIntegrationTest.kt` — Testcontainers broadcast test tagged `integration`
- `kore-kafka/README.md` — 3-pod Kubernetes broadcast explainer + configuration reference

### Modified
- `kore-kafka/build.gradle.kts` — added `kafka-clients` (Jackson excluded), `serialization-json`, `mockk`, `testcontainers-kafka`, `testcontainers-junit5`, `integrationTest` task

### Deleted
- `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/.gitkeep` — stub from plan 04-01 Task 3 replaced by real Kotlin sources

## Decisions Made

- **Internal primary constructor + companion `invoke` as the single public construction path.** Rejected the iteration 1 secondary-constructor + UnsupportedOperationException shape. The internal-ctor pattern makes `createForTest` the canonical test-injection path and lets the main `invoke` factory build the real producer/consumer without runtime type checks.
- **CloseOptions.timeout(Duration) on KafkaConsumer.close()** — kafka-clients 4.x deprecated the `Duration`-overloaded close. The new API uses a `CloseOptions` builder, so `consumer.close(CloseOptions.timeout(Duration.ofSeconds(5)))` replaces `consumer.close(Duration.ofSeconds(5))`. This fixes the only deprecation warning in the module without changing behavior.
- **Unit test assertion relaxed from `split("-").size == 3` to `>= 3`.** The plan's original assertion assumed hostnames contain no dashes, but real hostnames (e.g., `MacBook-Pro-de-Jiri.local`) do. The relaxed assertion still proves the prefix-hostname-pid format because the prefix ("kore") and the pid are fixed-shape tokens.
- **Jackson excluded on kafka-clients only, not globally.** The exclusion lives in the `kore-kafka/build.gradle.kts` `kafka-clients` dependency declaration, not at the root build level, so it is scoped to this module's classpath only. Pitfall 12 defense is surgical.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] KafkaConsumer.close(Duration) is deprecated in kafka-clients 4.x**
- **Found during:** Task 2 (first `./gradlew :kore-kafka:compileKotlin` after implementation)
- **Issue:** The plan text uses `consumer.close(Duration.ofSeconds(5))` in ConsumerLoop, but kafka-clients 4.2.0 deprecated that overload with `@Deprecated`. Compile succeeded with a warning; the CLAUDE.md rule is "`./gradlew ktlintFormat` before every commit" but deprecation warnings are also a code-quality hygiene concern.
- **Fix:** Imported `org.apache.kafka.clients.consumer.CloseOptions` and changed the call to `consumer.close(CloseOptions.timeout(Duration.ofSeconds(5)))`. The CloseOptions builder is the non-deprecated replacement.
- **Files modified:** `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ConsumerLoop.kt`
- **Verification:** `./gradlew :kore-kafka:compileKotlin :kore-kafka:test` — zero deprecation warnings on the close call.
- **Committed in:** `3686e22` (Task 2 commit)

**2. [Rule 1 - Bug] Consumer group ID unit test assertion was too strict**
- **Found during:** Task 1 scaffolding (anticipated during test authoring — real hostnames can contain dashes)
- **Issue:** The plan's exact test code asserts `groupId.split("-").size shouldBe 3`, assuming the hostname is a single dash-free token. On macOS, the hostname is typically `MacBook-Pro-de-Jiri.local` (3 dashes), which would fail this assertion on the developer machine and any CI runner whose hostname contains dashes.
- **Fix:** Changed to `(groupId.split("-").size >= 3) shouldBe true`. The format prefix-hostname-pid is still asserted (prefix-and-pid are two fixed tokens, so `>= 3` is sufficient) and `groupId shouldStartWith "kore-"` pins the prefix.
- **Files modified:** `kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusUnitTest.kt`
- **Verification:** Unit test passes green under `./gradlew :kore-kafka:test`.
- **Committed in:** `526335b` (Task 1 commit — caught before RED commit landed)

---

**Total deviations:** 2 auto-fixed (1 deprecation, 1 test assertion robustness fix). Both stay inside files the plan already owns; no scope creep. No Rule 4 architectural changes needed.

## Issues Encountered

- **ktlint reformatting on first run.** The initial source files contained function signatures and class signatures that ktlint wanted reformatted (import ordering, function-signature whitespace, multi-supertype wrapping). Running `./gradlew :kore-kafka:formatKotlin` auto-fixed every issue; no manual edits needed. Documented here so a future reader knows the current file shape is ktlint-canonical for Kotlin 2.3 / kotlinter 5.0.0.

## User Setup Required

None — the unit tests run without Docker, without a real Kafka broker, and without any environment variables. Integration tests require Docker but are `@Tag("integration")` and excluded from the default `./gradlew test` run.

## Self-Check: PASSED

- [x] `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/KafkaEventBus.kt` exists (verified via Read during authoring)
- [x] `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusConfig.kt` exists
- [x] `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ProducerBridge.kt` exists
- [x] `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ConsumerLoop.kt` exists
- [x] `kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusUnitTest.kt` exists
- [x] `kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusIntegrationTest.kt` exists
- [x] `kore-kafka/README.md` exists
- [x] `grep -c "UnsupportedOperationException" kore-kafka/src/main/kotlin/**` returns 0
- [x] `grep -n "class KafkaEventBus internal constructor"` matches line 44
- [x] `grep -n "ioDispatcher: CoroutineDispatcher"` matches in KafkaEventBus.kt (invoke + createForTest)
- [x] `grep -n "com.fasterxml.jackson" kore-kafka/build.gradle.kts` matches the exclude clause
- [x] `grep -n "3-pod Kubernetes" kore-kafka/README.md` matches
- [x] Commit `526335b` exists (test(04-02): scaffold ... RED)
- [x] Commit `3686e22` exists (feat(04-02): implement KafkaEventBus ...)
- [x] `./gradlew :kore-kafka:test` exits 0
- [x] `./gradlew :kore-kafka:lintKotlin` exits 0
- [x] `settings.gradle.kts` UNCHANGED from plan 04-01 state
- [x] `gradle/libs.versions.toml` UNCHANGED from plan 04-01 state

## Next Phase Readiness

- **Plan 04-03 (kore-rabbitmq) ready to run in parallel.** kore-kafka did not touch `settings.gradle.kts` or `gradle/libs.versions.toml`. The mirror template is established: internal-ctor + companion invoke, dispatcher injection, broadcast-by-default semantics, Jackson-exclude pattern (if applicable), Testcontainers integration test tagged `integration`.
- **Plan 04-04 (spring-wiring) can start wiring `KafkaEventBusAutoConfiguration` once 04-03 lands.** The autoconfig will reference `dev.unityinflow.kore.kafka.KafkaEventBus` via `@ConditionalOnClass(name = [...])` (string form, Pitfall 12 pattern from 03-CONTEXT.md), call the companion `invoke(config, scope)` factory, and gate bean creation behind `@ConditionalOnProperty(prefix = "kore.event-bus", name = "type", havingValue = "kafka")`.
- **Plan 04-05 (Maven Central publishing) inherits kore-kafka as-is.** The module has zero Spring dependency, pure `implementation(project(":kore-core"))` + `kafka-clients` + `kotlinx-serialization-json`. It will publish cleanly as `dev.unityinflow:kore-kafka:0.0.1` with the existing nmcp plugin aliases already declared in `libs.versions.toml`.
- **No blockers.** Integration tests require Docker for local runs but are excluded from default builds; CI self-hosted runners have Docker available.

---
*Phase: 04-event-bus-publishing*
*Completed: 2026-04-15*
