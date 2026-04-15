package dev.unityinflow.kore.rabbitmq

/**
 * Configuration for [RabbitMqEventBus].
 *
 * D-02: fanout exchange, persistent delivery, publisher confirms. URI format:
 * `amqp://user:pass@host:5672/vhost`. D-04 broadcast semantics: every JVM declares
 * its own exclusive auto-delete queue so every JVM sees every event.
 */
data class RabbitMqEventBusConfig(
    val uri: String,
    val exchange: String = "kore.agent-events",
    val confirmTimeoutMillis: Long = 5_000L,
    val closeTimeoutMillis: Int = 5_000,
)
