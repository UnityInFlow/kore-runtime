---
phase: 04-event-bus-publishing
reviewed: 2026-04-15T19:45:00Z
depth: standard
files_reviewed: 40
files_reviewed_list:
  - build.gradle.kts
  - buildSrc/build.gradle.kts
  - buildSrc/settings.gradle.kts
  - buildSrc/src/main/kotlin/kore.publishing.gradle.kts
  - .github/workflows/release.yml
  - .github/workflows/release-dry-run.yml
  - kore-core/build.gradle.kts
  - kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentEvent.kt
  - kore-core/src/main/kotlin/dev/unityinflow/kore/core/AgentResult.kt
  - kore-core/src/main/kotlin/dev/unityinflow/kore/core/TokenUsage.kt
  - kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentEventSerializationTest.kt
  - kore-core/src/test/kotlin/dev/unityinflow/kore/core/port/EventBusBackpressureTest.kt
  - kore-core/src/test/kotlin/dev/unityinflow/kore/core/port/EventBusConcurrencyTest.kt
  - kore-dashboard/build.gradle.kts
  - kore-kafka/build.gradle.kts
  - kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/KafkaEventBus.kt
  - kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusConfig.kt
  - kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ConsumerLoop.kt
  - kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ProducerBridge.kt
  - kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusUnitTest.kt
  - kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusIntegrationTest.kt
  - kore-llm/build.gradle.kts
  - kore-mcp/build.gradle.kts
  - kore-observability/build.gradle.kts
  - kore-rabbitmq/build.gradle.kts
  - kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBus.kt
  - kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusConfig.kt
  - kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusUnitTest.kt
  - kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusIntegrationTest.kt
  - kore-skills/build.gradle.kts
  - kore-spring/build.gradle.kts
  - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAgentFactory.kt
  - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt
  - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreDashboardAutoConfiguration.kt
  - kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreProperties.kt
  - kore-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KafkaEventBusAutoConfigurationTest.kt
  - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KorePropertiesTest.kt
  - kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/RabbitMqEventBusAutoConfigurationTest.kt
  - kore-storage/build.gradle.kts
  - kore-storage/src/test/kotlin/dev/unityinflow/kore/storage/MigrationTest.kt
  - kore-storage/src/test/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapterTest.kt
  - kore-storage/src/test/kotlin/dev/unityinflow/kore/storage/PostgresAuditLogAdapterQueryTest.kt
findings:
  critical: 1
  warning: 3
  info: 3
  total: 7
status: issues_found
---

# Phase 4: Code Review Report

**Reviewed:** 2026-04-15T19:45:00Z
**Depth:** standard
**Files Reviewed:** 40
**Status:** issues_found

## Summary

Phase 4 delivers two major capabilities: event bus formalization with Kafka/RabbitMQ adapters, and Maven Central publishing infrastructure for all 11 modules. The code is well-structured overall -- hexagonal boundaries are clean, the adapter modules have zero Spring dependencies, sealed-class serialization is correctly configured with `compileOnly` to keep kore-core's runtime classpath clean, and the publishing convention plugin handles POM metadata, signing, and nmcp aggregation correctly.

Seven issues were found. One critical issue (Kafka consumer loop dies on a single malformed message), three warnings (RabbitMQ close() triggers lazy connection init, CoroutineScope leak on shutdown, incorrect Maven version in release notes), and three informational items.

The architecture is sound: `kore-core` maintains zero external runtime dependencies, adapter modules are framework-agnostic, kore-spring wires them conditionally using the string-form `@ConditionalOnClass` pattern (Pitfall 12 defense), and the publishing infrastructure correctly gates signing behind env-var presence so local dev works without GPG keys. The KoreAgentFactory recursive call bug from commit 3c57770 is confirmed fixed. The KoreDashboardAutoConfiguration extraction from commit 864dc7d is clean.

## Critical Issues

### CR-01: Kafka ConsumerLoop crashes permanently on malformed message (poison-message vulnerability)

**File:** `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ConsumerLoop.kt:47-53`
**Issue:** When `json.decodeFromString()` throws a `SerializationException` on a malformed Kafka record, the exception propagates out of the `for (record in records)` loop, breaks out of the `while (true)` loop, hits the `finally` block, and closes the consumer permanently. A single malformed message kills the entire consumer loop for the lifetime of the JVM. This is inconsistent with the RabbitMQ adapter, which correctly handles this case with `basicNack(requeue=false)` in `RabbitMqEventBus.kt:120-126`.

**Fix:** Wrap the deserialization in a try-catch per record, matching the RabbitMQ adapter's poison-message protection pattern:
```kotlin
for (record in records) {
    try {
        val decoded =
            json.decodeFromString(
                AgentEvent.serializer(),
                record.value().decodeToString(),
            )
        target.tryEmit(decoded)
    } catch (ex: Exception) {
        // Skip malformed record — log and continue.
        // Kafka has no per-record ack/nack; the record is simply
        // dropped from the consumer's perspective. Auto-commit
        // advances the offset past it.
    }
}
```

## Warnings

### WR-01: RabbitMqEventBus.close() triggers lazy connection initialization when never used

**File:** `kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBus.kt:136-140`
**Issue:** If `close()` is called on a `RabbitMqEventBus` that never called `emit()` or `subscribe()`, the access to `publishChannel` on line 138 triggers the `by lazy` initializer chain: first `connection` (opens a TCP socket to the broker), then `publishChannel` (creates a channel, enables confirmSelect, declares the exchange). This defeats the Pitfall 7 lazy-connection defense in the shutdown path. If the broker is unreachable at shutdown time, this will throw an exception inside `runCatching` -- which is swallowed, so no crash -- but it still causes an unnecessary connection attempt.

**Fix:** Use `Lazy`'s `isInitialized()` to guard the close calls. Change the `by lazy` properties to named `Lazy` instances:
```kotlin
private val connectionLazy = lazy { factory.newConnection() }
private val connection: Connection by connectionLazy

private val publishChannelLazy = lazy {
    connection.createChannel().apply {
        confirmSelect()
        exchangeDeclare(config.exchange, "fanout", true)
    }
}
private val publishChannel: Channel by publishChannelLazy

override fun close() {
    consumerJobRef.get()?.cancel()
    if (publishChannelLazy.isInitialized()) {
        runCatching { publishChannel.close() }
    }
    if (connectionLazy.isInitialized()) {
        runCatching { connection.close(config.closeTimeoutMillis) }
    }
}
```

### WR-02: CoroutineScope bean is never cancelled on Spring context shutdown

**File:** `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt:243-258`
**Issue:** Both `KafkaEventBusScopeConfiguration` and `RabbitMqEventBusScopeConfiguration` create a `CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))` bean but neither the `@Bean` nor the scope itself has a shutdown hook. `CoroutineScope` does not implement `AutoCloseable` or `DisposableBean`, so Spring cannot automatically cancel the `SupervisorJob` at context teardown. While the adapter `@Bean(destroyMethod = "close")` cancels the consumer loops, the scope's `SupervisorJob` itself is never cancelled. Any other coroutines launched on this shared scope (e.g., by user code referencing `@Qualifier("koreEventBusScope")`) would leak.

**Fix:** Wrap the scope bean in a `DisposableBean` or return a `CloseableCoroutineScope`:
```kotlin
@Bean("koreEventBusScope", destroyMethod = "close")
@ConditionalOnMissingBean(name = ["koreEventBusScope"])
fun koreEventBusScope(): CloseableCoroutineScope =
    CloseableCoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))

// In a shared location:
class CloseableCoroutineScope(
    context: kotlin.coroutines.CoroutineContext,
) : CoroutineScope, AutoCloseable {
    override val coroutineContext = context
    override fun close() {
        coroutineContext[Job]?.cancel()
    }
}
```

### WR-03: Release notes body shows Maven version with `v` prefix

**File:** `.github/workflows/release.yml:76`
**Issue:** The release body uses `${{ github.ref_name }}` for the Maven dependency version, which will produce `v0.0.1` (with the `v` prefix from the git tag). The actual Maven artifact version is `0.0.1` (no `v`). Users who copy the snippet will get `implementation("dev.unityinflow:kore-spring:v0.0.1")` which will fail to resolve.

**Fix:** Strip the `v` prefix by adding a step that extracts the version, or use an inline expression:
```yaml
      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ github.ref_name }}
          name: kore-runtime ${{ github.ref_name }}
          generate_release_notes: true
          draft: false
          prerelease: false
          body: |
            kore-runtime **${{ github.ref_name }}** is now available on Maven Central under `dev.unityinflow`.

            ```kotlin
            dependencies {
                implementation("dev.unityinflow:kore-spring:${GITHUB_REF_NAME#v}")
            }
            ```
```
Or add a prior step:
```yaml
      - name: Extract version
        id: version
        run: echo "version=${GITHUB_REF_NAME#v}" >> "$GITHUB_OUTPUT"
```
Then reference `${{ steps.version.outputs.version }}` in the body.

## Info

### IN-01: ProducerBridge invokeOnCancellation is a no-op by design -- worth documenting the tradeoff

**File:** `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ProducerBridge.kt:22-32`
**Issue:** The `suspendCancellableCoroutine` usage has no `invokeOnCancellation` handler. The KDoc comment correctly explains why (Kafka has no in-flight cancel API). However, if the coroutine is cancelled while waiting for the Kafka callback, the callback will still fire and call `cont.resume()` or `cont.resumeWithException()` on an already-cancelled continuation. `suspendCancellableCoroutine` handles this safely (it swallows the call), but the scenario means a completed Kafka send might appear to "fail" from the caller's perspective (the caller sees `CancellationException` while the record was actually written to the broker). This is an inherent limitation, not a bug -- but worth a brief note for production operators.

**Fix:** No code change needed. Consider adding a one-line comment in the KDoc: "A cancelled coroutine may not observe a successful send that completed concurrently -- the record is still committed to Kafka."

### IN-02: EventBusConcurrencyTest survival floor assertion could be more precise

**File:** `kore-core/src/test/kotlin/dev/unityinflow/kore/core/port/EventBusConcurrencyTest.kt:56`
**Issue:** The assertion `received.size shouldBeGreaterThanOrEqual 64` technically also passes when `received.size` is 0 if the buffer capacity were changed to 0. The comment explains why the floor was relaxed from 7488 to 64 (runTest single-scheduler semantics), and the uniqueness check on line 59 provides the real safety net. This is cosmetic -- the test is functionally correct and documents its reasoning well.

**Fix:** No change required. The comment at line 50-55 adequately explains the rationale.

### IN-03: Duplicate `group = "dev.unityinflow"` in adapter module build files

**File:** `kore-kafka/build.gradle.kts:8` and `kore-rabbitmq/build.gradle.kts:8`
**Issue:** Both adapter modules explicitly set `group = "dev.unityinflow"`, but this is already set for all subprojects in the root `build.gradle.kts:17` (`subprojects { group = "dev.unityinflow" }`). The duplicate declaration is harmless but adds noise. The same pattern exists in `kore-observability/build.gradle.kts:9` and `kore-storage/build.gradle.kts:9` (pre-existing from prior phases).

**Fix:** Remove the duplicate `group` lines from the individual module build files. The root `subprojects` block already handles this.

---

_Reviewed: 2026-04-15T19:45:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
