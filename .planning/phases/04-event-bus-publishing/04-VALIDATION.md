---
phase: 04
slug: event-bus-publishing
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-15
---

# Phase 04 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution of Phase 4 (Event Bus & Publishing). Derived from 04-RESEARCH.md §Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (`junit-jupiter` 5.12.0) + Kotest assertions (`kotest-assertions-core` 6.1.11) |
| **Config file** | Per-module `build.gradle.kts` with `tasks.test { useJUnitPlatform() }` (established in Phase 1) |
| **Quick run command** | `./gradlew :kore-core:test --tests "*EventBus*Test"` |
| **Full suite command** | `./gradlew test` (excludes `@Tag("integration")` by default) |
| **Integration suite command** | `./gradlew test -Dkore.integration.enabled=true` (Testcontainers Kafka/Rabbit) |
| **Phase gate command** | `./gradlew clean test publishToMavenLocal` + manual inspection of `~/.m2/repository/dev/unityinflow/` |
| **Estimated runtime** | Quick: <10s · Full (unit): ~3min · Integration: ~5min additional (Docker pull + container start) |

---

## Sampling Rate

- **After every task commit:** `./gradlew :<touched-module>:test` (<30s)
- **After every plan wave:** `./gradlew test` (full unit suite, <3min)
- **Before `/gsd-verify-work`:** `./gradlew clean test publishToMavenLocal` green AND manual POM inspection for all 11 modules
- **Before tagging v0.0.1:** Full gate + dry-run `publishAggregationToCentralPortal` with `publishingType = "USER_MANAGED"` to staging bundle (must be droppable without release)
- **Max feedback latency:** 30 seconds for unit-only, 5 minutes for integration tests

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 04-01-01 | 01 | 1 | EVNT-01 | — | EventBus port contract stable — method signatures preserved | compile | `./gradlew :kore-core:compileKotlin` | ✅ | ⬜ pending |
| 04-01-02 | 01 | 1 | EVNT-01, EVNT-02 | — | `InProcessEventBus.emit()` never suspends with slow consumer, DROP_OLDEST drops oldest 64+ events | unit | `./gradlew :kore-core:test --tests "*EventBusBackpressureTest"` | ❌ W0 | ⬜ pending |
| 04-01-03 | 01 | 1 | EVNT-02 | — | Concurrent producers lose no events beyond the 64-buffer worst case | unit | `./gradlew :kore-core:test --tests "*EventBusConcurrencyTest"` | ❌ W0 | ⬜ pending |
| 04-01-04 | 01 | 1 | EVNT-02 (D-03 wire format) | T-04-03 YAML-ish injection N/A | `AgentEvent` JSON roundtrip preserves `type` discriminator and all subclass fields | unit | `./gradlew :kore-core:test --tests "*AgentEventSerializationTest"` | ❌ W0 | ⬜ pending |
| 04-02-01 | 02 | 2 | EVNT-03 | T-04-05 broker unreachable | `KafkaEventBus.emit()` bridges `KafkaProducer.send(cb)` via `suspendCancellableCoroutine`; never blocks the coroutine scope | unit | `./gradlew :kore-kafka:test --tests "*KafkaEventBusUnitTest"` | ❌ W0 | ⬜ pending |
| 04-02-02 | 02 | 2 | EVNT-03 | T-04-05 | `KafkaEventBus.subscribe()` Flow respects cancellation, calls `poll()` on `Dispatchers.IO`, drops records via internal `MutableSharedFlow(64, DROP_OLDEST)` | unit | `./gradlew :kore-kafka:test --tests "*KafkaEventBusUnitTest"` | ❌ W0 | ⬜ pending |
| 04-02-03 | 02 | 2 | EVNT-03 | T-04-01 consumer group collision | Testcontainers integration: broadcast (2 bus instances, both receive same event), graceful shutdown, consumer group ID uniqueness | integration | `./gradlew :kore-kafka:test --tests "*KafkaEventBusIntegrationTest" -Dkore.integration.enabled=true` | ❌ W0 | ⬜ pending |
| 04-03-01 | 03 | 2 | EVNT-04 | T-04-05 | `RabbitMqEventBus.emit()` uses publisher confirms via `withContext(Dispatchers.IO) { waitForConfirmsOrDie }` | unit | `./gradlew :kore-rabbitmq:test --tests "*RabbitMqEventBusUnitTest"` | ❌ W0 | ⬜ pending |
| 04-03-02 | 03 | 2 | EVNT-04 | T-04-02 fanout binding | `RabbitMqEventBus.subscribe()` declares fanout exchange + exclusive auto-delete queue, consumes with manual ack on Flow emission success | unit | `./gradlew :kore-rabbitmq:test --tests "*RabbitMqEventBusUnitTest"` | ❌ W0 | ⬜ pending |
| 04-03-03 | 03 | 2 | EVNT-04 | T-04-02 | Testcontainers integration: broadcast semantics, graceful shutdown with in-flight ack drain | integration | `./gradlew :kore-rabbitmq:test --tests "*RabbitMqEventBusIntegrationTest" -Dkore.integration.enabled=true` | ❌ W0 | ⬜ pending |
| 04-04-01 | 04 | 3 | EVNT-03 | T-04-04 eager broker connect at bean creation | Spring context with `kore-kafka` on classpath + `kore.event-bus.type=kafka` wires `KafkaEventBus` via `@ConditionalOnClass(name=[...])` + `@ConditionalOnProperty(havingValue="kafka")`; existing `@ConditionalOnMissingBean(EventBus::class)` yields | integration | `./gradlew :kore-spring:test --tests "*KafkaEventBusAutoConfigurationTest"` | ❌ W0 | ⬜ pending |
| 04-04-02 | 04 | 3 | EVNT-04 | T-04-04 | Spring context with `kore-rabbitmq` + `kore.event-bus.type=rabbitmq` wires `RabbitMqEventBus` analogously | integration | `./gradlew :kore-spring:test --tests "*RabbitMqEventBusAutoConfigurationTest"` | ❌ W0 | ⬜ pending |
| 04-04-03 | 04 | 3 | EVNT-03, EVNT-04 | — | `KoreProperties.EventBusProperties` nested sealed config binds `kore.event-bus.kafka.*` / `kore.event-bus.rabbitmq.*` | unit | `./gradlew :kore-spring:test --tests "*KorePropertiesTest"` | ✅ (extend existing) | ⬜ pending |
| 04-05-01 | 05 | 4 | D-05 (Publishing) | T-04-06 POM validation | All 11 modules produce signed artifacts locally: `~/.m2/repository/dev/unityinflow/<module>/0.0.1/` contains `pom`, `jar`, `sources.jar`, `javadoc.jar`, all `.asc` | publish-local | `./gradlew clean publishToMavenLocal && find ~/.m2/repository/dev/unityinflow/ -name "*.asc" \| wc -l` | ❌ W0 (convention plugin) | ⬜ pending |
| 04-05-02 | 05 | 4 | D-05 (POM metadata) | T-04-06 | Each module POM contains `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, `<scm>` (verified by xmlstarlet or grep) | publish-local | `for m in ...; do xmlstarlet sel -t -v //license ~/.m2/.../${m}-0.0.1.pom; done` | ❌ W0 | ⬜ pending |
| 04-06-01 | 06 | 5 | D-10 (CI workflow) | T-04-10 runner permissions | Release workflow syntax valid, triggers on `v*.*.*` tag, uses `runs-on: [arc-runner-unityinflow]` | manual | `gh workflow view release.yml` (after first merge) | ❌ W0 | ⬜ pending |
| 04-06-02 | 06 | 5 | D-05 (staging bundle) | T-04-03 namespace verification | `publishAggregationToCentralPortal` with `publishingType=USER_MANAGED` produces staging bundle that can be inspected and dropped without release | manual | Human runs on a `v0.0.1-rc1` tag against a feature branch, then drops the bundle in Central Portal UI | ❌ W0 (one-time pre-flight) | ⬜ pending |
| 04-06-03 | 06 | 5 | D-05 (Success Criterion 4) | — | `dev.unityinflow:kore-spring:0.0.1` resolvable from Maven Central (final Success Criterion) | manual | After real release: `curl -sI https://repo.maven.apache.org/maven2/dev/unityinflow/kore-spring/0.0.1/kore-spring-0.0.1.pom` returns 200 | ❌ (post-release) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

**Code / test scaffolding (Claude-executable):**

- [ ] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/port/EventBusBackpressureTest.kt` — stubs for EVNT-01 / EVNT-02 DROP_OLDEST behavior
- [ ] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/port/EventBusConcurrencyTest.kt` — stubs for EVNT-02 concurrent producer behavior
- [ ] `kore-core/src/test/kotlin/dev/unityinflow/kore/core/AgentEventSerializationTest.kt` — stubs for D-03 JSON wire format roundtrip
- [ ] `kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusUnitTest.kt` — MockK-based producer/consumer unit test stub
- [ ] `kore-kafka/src/test/kotlin/dev/unityinflow/kore/kafka/KafkaEventBusIntegrationTest.kt` — Testcontainers Kafka integration stub, `@Tag("integration")`
- [ ] `kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusUnitTest.kt` — MockK-based Channel unit test stub
- [ ] `kore-rabbitmq/src/test/kotlin/dev/unityinflow/kore/rabbitmq/RabbitMqEventBusIntegrationTest.kt` — Testcontainers RabbitMQ integration stub, `@Tag("integration")`
- [ ] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/KafkaEventBusAutoConfigurationTest.kt` — Spring context test with mocked KafkaProducer/Consumer
- [ ] `kore-spring/src/test/kotlin/dev/unityinflow/kore/spring/RabbitMqEventBusAutoConfigurationTest.kt` — Spring context test with mocked Channel
- [ ] `buildSrc/src/main/kotlin/kore.publishing.gradle.kts` — convention plugin for shared POM + signing + Maven publication config
- [ ] `.github/workflows/release.yml` — release CI workflow on `v*.*.*` tag
- [ ] `settings.gradle.kts` — add `include("kore-kafka")` and `include("kore-rabbitmq")`
- [ ] `gradle/libs.versions.toml` — add `kafka-clients = 4.2.0`, `amqp-client = 5.29.0`, `nmcp = 1.4.4`, `testcontainers-kafka`, `testcontainers-rabbitmq`

**Pre-flight HUMAN tasks (Claude cannot perform — must be explicit checkpoint before Wave 5):**

- [ ] **PF-01: Verify `dev.unityinflow` namespace on central.sonatype.com.** Log into the Sonatype Central Portal, check that the `dev.unityinflow` namespace is claimed and verified (DNS TXT record or GitHub org verification). If not claimed, create the namespace and complete verification. **This is the #1 first-publish blocker per 04-RESEARCH.md Pitfall 3.**
- [ ] **PF-02: Confirm `kore-runtime` repo visibility + runner group permission.** If the repo is public, ensure the `arc-runner-unityinflow` runner group has `allows_public_repositories: true` in GitHub org settings. If private, no action needed. Per CLAUDE.md note.
- [ ] **PF-03: Confirm Docker available on `arc-runner-unityinflow`.** Integration tests need Docker for Testcontainers. Either verify Docker is installed OR plan 04 must gate integration tests on `-Dkore.integration.enabled=true` and skip in standard CI.
- [ ] **PF-04: Confirm GitHub org secrets exist.** `SIGNING_KEY` (ASCII-armored GPG private key), `SIGNING_PASSWORD`, `SONATYPE_USERNAME` (portal username), `SONATYPE_PASSWORD` (portal token — NOT account password). All 4 must be set at org level before first release workflow run.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Staging bundle accepted by Sonatype Central Portal before release | D-05 | No programmatic "validate without release" API; portal UI inspects bundle contents | On feature branch, tag `v0.0.1-rc1`, let release workflow push bundle, log into central.sonatype.com, inspect artifacts, drop bundle without releasing |
| Final Maven Central resolvability | D-05 Success Criterion 4 | Real Maven Central replication takes ~10-30 minutes after release | After release workflow success, wait 30 minutes, run `curl -sI https://repo.maven.apache.org/maven2/dev/unityinflow/kore-spring/0.0.1/kore-spring-0.0.1.pom` and assert 200 OK |
| GPG signature chain valid | D-05 | Signature verification requires the public key in a keyserver | `gpg --verify <artifact>.jar.asc <artifact>.jar` after importing the public key via `gpg --keyserver keyserver.ubuntu.com --recv-keys <key-id>` |
| Namespace verification on Sonatype (PF-01) | D-05 | Cannot be performed by Claude — requires human login to Central Portal | See PF-01 above |
| Runner group public-repo permission (PF-02) | D-10 | Requires GitHub org admin access | See PF-02 above |
| GitHub secrets present (PF-04) | D-10 | Requires GitHub org admin access | See PF-04 above |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify OR Wave 0 dependency OR manual test with explicit human step
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify (met — every task maps to a gradle command)
- [ ] Wave 0 covers all MISSING test file references (11 new files + convention plugin + release workflow)
- [ ] No watch-mode flags (met — all commands are single-shot)
- [ ] Feedback latency <30s quick / <3min full (met)
- [ ] Pre-flight HUMAN tasks explicitly called out as blockers for Wave 5 execution
- [ ] `nyquist_compliant: true` to be set in frontmatter after planner confirms the task→file mapping above

**Approval:** pending — will be set to `approved YYYY-MM-DD` after planner produces PLAN.md files and plan-checker confirms Nyquist Dimension 8 coverage.
