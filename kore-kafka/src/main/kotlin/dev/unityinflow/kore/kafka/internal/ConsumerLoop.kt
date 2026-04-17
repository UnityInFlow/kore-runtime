package dev.unityinflow.kore.kafka.internal

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.kafka.KafkaEventBusConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration

/**
 * Runs a Kafka consumer poll loop on the injected [ioDispatcher], decoding each
 * record into an [AgentEvent] via kotlinx.serialization and emitting it to [target]
 * using `tryEmit` (DROP_OLDEST semantics — fan-out buffer is 64 per Pitfall 1 / D-09).
 *
 * Dispatcher injection is critical for testability: when a `runTest`-compatible
 * `TestDispatcher` is passed, the consumer loop runs under the TestCoroutineScheduler
 * and `verify { ... }` assertions in unit tests are deterministic.
 *
 * The loop exits cleanly on coroutine cancellation (poll timeout is short, 500ms default,
 * and [WakeupException] is swallowed — see §Anti-Patterns kafka poll without cancellation).
 */
internal class ConsumerLoop(
    private val consumer: KafkaConsumer<String, ByteArray>,
    private val config: KafkaEventBusConfig,
    private val json: Json,
    private val target: MutableSharedFlow<AgentEvent>,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private companion object

    fun start(scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            try {
                consumer.subscribe(listOf(config.topic))
                while (true) {
                    ensureActive()
                    val records =
                        try {
                            consumer.poll(Duration.ofMillis(config.pollTimeoutMillis))
                        } catch (_: WakeupException) {
                            break
                        }
                    for (record in records) {
                        try {
                            val decoded =
                                json.decodeFromString(
                                    AgentEvent.serializer(),
                                    record.value().decodeToString(),
                                )
                            target.tryEmit(decoded)
                        } catch (ex: SerializationException) {
                            // Skip malformed record and continue.
                            // Kafka has no per-record ack/nack; auto-commit
                            // advances the offset past it.
                            System.err.println(
                                "kore-kafka: skipping malformed record" +
                                    " topic=${record.topic()}" +
                                    " partition=${record.partition()}" +
                                    " offset=${record.offset()}" +
                                    ": ${ex.message}",
                            )
                        }
                    }
                }
            } finally {
                runCatching {
                    consumer.close(CloseOptions.timeout(Duration.ofSeconds(5)))
                }
            }
        }
    }

    fun wakeup() {
        runCatching { consumer.wakeup() }
    }
}
