---
phase: 04-event-bus-publishing
fixed_at: 2026-04-15T20:15:00Z
review_path: .planning/phases/04-event-bus-publishing/04-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 4: Code Review Fix Report

**Fixed at:** 2026-04-15T20:15:00Z
**Source review:** .planning/phases/04-event-bus-publishing/04-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4
- Fixed: 4
- Skipped: 0

## Fixed Issues

### CR-01: Kafka ConsumerLoop crashes permanently on malformed message (poison-message vulnerability)

**Files modified:** `kore-kafka/src/main/kotlin/dev/unityinflow/kore/kafka/internal/ConsumerLoop.kt`
**Commit:** 44878fa
**Applied fix:** Wrapped `json.decodeFromString()` in a per-record `try-catch` for `SerializationException`. On deserialization failure, the malformed record is logged with topic/partition/offset context via SLF4J and skipped. The consumer loop continues processing remaining records. Added `LoggerFactory` logger as a private companion object. SLF4J is available transitively through `kafka-clients`.

### WR-01: RabbitMqEventBus.close() triggers lazy connection initialization when never used

**Files modified:** `kore-rabbitmq/src/main/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBus.kt`
**Commit:** 768ffa7
**Applied fix:** Changed `by lazy { ... }` properties to named `Lazy` instances (`connectionLazy`, `publishChannelLazy`) with delegation via `by connectionLazy` / `by publishChannelLazy`. Updated `close()` to guard `publishChannel.close()` and `connection.close()` with `publishChannelLazy.isInitialized()` and `connectionLazy.isInitialized()` respectively. If neither was initialized, `close()` only cancels the consumer job without triggering any connection to the broker.

### WR-02: CoroutineScope bean is never cancelled on Spring context shutdown

**Files modified:** `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/CloseableCoroutineScope.kt`, `kore-spring/src/main/kotlin/dev/unityinflow/kore/spring/KoreAutoConfiguration.kt`
**Commit:** ba57bbb
**Applied fix:** Created `CloseableCoroutineScope` class in kore-spring that implements both `CoroutineScope` (by delegation) and `AutoCloseable` (cancels the `Job` on close). Updated both `KafkaEventBusScopeConfiguration` and `RabbitMqEventBusScopeConfiguration` to return `CloseableCoroutineScope` instances and added `destroyMethod = "close"` to the `@Bean` annotations. Spring now cancels the `SupervisorJob` (and all child coroutines) at context shutdown.

### WR-03: Release notes body shows Maven version with `v` prefix

**Files modified:** `.github/workflows/release.yml`
**Commit:** 17d59c6
**Applied fix:** Added an "Extract version" step that strips the `v` prefix from `GITHUB_REF_NAME` and writes it to `$GITHUB_OUTPUT`. The Maven dependency snippet in the release body now uses `${{ steps.version.outputs.version }}` (e.g., `0.0.1`) instead of `${{ github.ref_name }}` (e.g., `v0.0.1`). The release title and prose description still use `github.ref_name` (with `v` prefix) which is correct for display purposes.

---

_Fixed: 2026-04-15T20:15:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
