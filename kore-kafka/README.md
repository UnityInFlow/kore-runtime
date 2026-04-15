# kore-kafka

Opt-in Apache Kafka adapter for [kore-runtime](https://github.com/UnityInFlow/kore) `EventBus`.

## What it is

`KafkaEventBus` implements `dev.unityinflow.kore.core.port.EventBus` over the official
`org.apache.kafka:kafka-clients` library. It is a thin coroutine bridge over Kafka — it
does NOT depend on Spring Kafka, Spring AMQP, or any framework. It works from plain
Kotlin applications as well as Spring Boot.

## Installation

```kotlin
dependencies {
    implementation("dev.unityinflow:kore-spring:0.0.1")
    implementation("dev.unityinflow:kore-kafka:0.0.1")
}
```

In `application.yml`:

```yaml
kore:
  event-bus:
    type: kafka
    kafka:
      bootstrap-servers: kafka.my-namespace.svc.cluster.local:9092
      topic: kore-agent-events
      group-id-prefix: kore
```

No agent code changes required — kore-spring auto-configuration switches the `EventBus`
bean to `KafkaEventBus` when the module is on the classpath AND `kore.event-bus.type=kafka`.

## Broadcast, not work queue — 3-pod Kubernetes explainer

Each kore JVM instance creates a **unique Kafka consumer group ID** of the form
`${groupIdPrefix}-${hostname}-${pid}`. This means:

- **Pod 1** subscribes as consumer group `kore-pod-1-12345`
- **Pod 2** subscribes as `kore-pod-2-67890`
- **Pod 3** subscribes as `kore-pod-3-23456`

Because no two pods share a group ID, **every event emitted is delivered to every pod**.
This is intentional — observers in kore (dashboard, Micrometer metrics, OTel spans)
are read-only reactions, not work consumers. Each pod needs its own view of events.

### Why not work queue semantics?

The default Kafka tutorial pattern is "one group ID per application, partitions are
load-balanced across pods". That's a *work queue* — each event is processed by exactly
one pod. kore's event bus is for *observation*, not processing, so every pod needs
every event. Hence the per-JVM group ID.

If you want work-queue semantics for your own application events, use `spring-kafka`
directly — kore-kafka is not the right fit.

## Configuration reference

See `KafkaEventBusConfig.kt` for the full list. Defaults:

| Property | Default | Notes |
|----------|---------|-------|
| `bootstrap-servers` | (required) | Kafka broker list |
| `topic` | `kore-agent-events` | Single topic per deployment |
| `group-id-prefix` | `kore` | Full group = `${prefix}-${hostname}-${pid}` |
| `producer.acks` | `1` | Leader ack only (agent events are not transactional data) |
| `producer.linger-ms` | `10` | Small batch window |
| `producer.compression-type` | `snappy` | |
| `consumer.auto-offset-reset` | `latest` | Observers are live monitors, not audit consumers |
| `consumer.enable-auto-commit` | `true` | |

## Running integration tests

Integration tests use Testcontainers and a real Kafka broker. They are `@Tag("integration")`
and excluded from the default `./gradlew test` run. To run them locally:

```bash
./gradlew :kore-kafka:integrationTest
```

Docker must be running.
