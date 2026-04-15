# kore-rabbitmq

Opt-in RabbitMQ adapter for [kore-runtime](https://github.com/UnityInFlow/kore) `EventBus`.

## What it is

`RabbitMqEventBus` implements `dev.unityinflow.kore.core.port.EventBus` over the official
`com.rabbitmq:amqp-client` library. No Spring AMQP, no Spring Rabbit — it is a thin
coroutine bridge that works from plain Kotlin applications or Spring Boot.

## Installation

```kotlin
dependencies {
    implementation("dev.unityinflow:kore-spring:0.0.1")
    implementation("dev.unityinflow:kore-rabbitmq:0.0.1")
}
```

```yaml
kore:
  event-bus:
    type: rabbitmq
    rabbitmq:
      uri: amqp://guest:guest@rabbitmq.my-namespace.svc:5672/
      exchange: kore.agent-events
```

## Broadcast, not work queue — 3-pod Kubernetes explainer

Each kore JVM declares its own **exclusive auto-delete queue** bound to a shared
fanout exchange (`kore.agent-events`). This means:

- **Pod 1** declares queue `amq.gen-aaa111` bound to `kore.agent-events`
- **Pod 2** declares queue `amq.gen-bbb222` bound to `kore.agent-events`
- **Pod 3** declares queue `amq.gen-ccc333` bound to `kore.agent-events`

Because it is a **fanout** exchange, every message is delivered to every bound queue.
Every pod receives every event. The `exclusive=true, autoDelete=true` queue settings
mean the queue is destroyed when the pod disconnects — no stale queues accumulate.

This is the standard RabbitMQ pattern for broadcast/pub-sub. If you want work-queue
semantics, use `spring-amqp` directly.

## Connection is lazy

Calling `RabbitMqEventBus(config, scope)` does NOT open a broker connection. The first
`emit()` or `subscribe()` triggers the connection via `by lazy`. This means your
Spring application starts up even when the broker is temporarily unreachable, and
`emit()` will throw on the first call if the broker is still down — your code handles
the failure at the event boundary, not at context startup.

## Publisher confirms

`emit()` calls `channel.waitForConfirmsOrDie(timeoutMillis)` inside
`withContext(ioDispatcher)`. If the broker nacks the message or the timeout expires,
an `IOException` propagates out of `emit()`. This is intentional — silent drops are
worse than loud failures for an event bus.

## Running integration tests

```bash
./gradlew :kore-rabbitmq:integrationTest
```

Docker required.
