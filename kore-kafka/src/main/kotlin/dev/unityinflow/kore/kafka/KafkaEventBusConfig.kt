package dev.unityinflow.kore.kafka

import java.net.InetAddress

/**
 * Configuration for [KafkaEventBus].
 *
 * D-01: acks=1, linger.ms=10, compression.type=snappy.
 * D-04 broadcast semantics: consumer group ID is `${groupIdPrefix}-${hostname}-${pid}`
 * so every JVM consuming the topic sees every event (no work-queue sharing).
 */
data class KafkaEventBusConfig(
    val bootstrapServers: String,
    val topic: String = "kore-agent-events",
    val groupIdPrefix: String = "kore",
    val producerAcks: String = "1",
    val producerLingerMs: Int = 10,
    val producerCompressionType: String = "snappy",
    val consumerAutoOffsetReset: String = "latest",
    val consumerEnableAutoCommit: Boolean = true,
    val pollTimeoutMillis: Long = 500L,
) {
    /**
     * Computes a JVM-unique consumer group ID for broadcast semantics (D-04 / Pitfall 6).
     * Kafka consumers within the same group share partitions (work queue), so kore gives
     * every JVM its own group — every JVM receives every event.
     */
    fun resolveGroupId(): String {
        val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrElse { "unknown-host" }
        val pid = ProcessHandle.current().pid()
        return "$groupIdPrefix-$hostname-$pid"
    }
}
