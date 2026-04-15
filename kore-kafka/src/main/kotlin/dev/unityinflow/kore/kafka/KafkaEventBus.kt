package dev.unityinflow.kore.kafka

import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.port.EventBus
import dev.unityinflow.kore.kafka.internal.ConsumerLoop
import dev.unityinflow.kore.kafka.internal.awaitSend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

/**
 * Opt-in Kafka implementation of [EventBus] (EVNT-03 / D-01).
 *
 * Broadcast semantics per D-04: every JVM uses its own consumer group ID
 * `${prefix}-${hostname}-${pid}`. This means 3 pods in Kubernetes each receive
 * every event — this is intentional for observer use cases (dashboard, metrics,
 * spans). See README for discussion.
 *
 * Hexagonal: zero Spring dependency. kore-spring wires this via `@ConditionalOnClass`
 * + `@ConditionalOnProperty(havingValue = "kafka")` in plan 04-04.
 *
 * Dispatcher injection: the `ioDispatcher` parameter on the companion factories
 * defaults to [Dispatchers.IO] but can be overridden in tests to a `TestDispatcher`
 * so the consumer loop runs under `runTest`'s virtual scheduler.
 *
 * Single public construction path: use `KafkaEventBus(config, scope)` via the
 * companion `invoke` — the primary constructor is internal.
 */
class KafkaEventBus internal constructor(
    private val config: KafkaEventBusConfig,
    private val producer: KafkaProducer<String, ByteArray>,
    private val consumerLoop: ConsumerLoop?,
    private val shared: MutableSharedFlow<AgentEvent>,
    private val json: Json,
) : EventBus,
    AutoCloseable {
    override suspend fun emit(event: AgentEvent) {
        val bytes = json.encodeToString(AgentEvent.serializer(), event).toByteArray()
        val record = ProducerRecord<String, ByteArray>(config.topic, bytes)
        producer.awaitSend(record)
    }

    override fun subscribe(): Flow<AgentEvent> = shared.asSharedFlow()

    override fun close() {
        consumerLoop?.wakeup()
        runCatching { producer.close() }
    }

    companion object {
        /**
         * Primary public factory — constructs producer + consumer, starts consumer loop
         * on [Dispatchers.IO], returns a ready [KafkaEventBus].
         */
        operator fun invoke(
            config: KafkaEventBusConfig,
            scope: CoroutineScope,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): KafkaEventBus {
            val producer = buildProducer(config)
            val consumer = buildConsumer(config)
            val shared =
                MutableSharedFlow<AgentEvent>(
                    replay = 0,
                    extraBufferCapacity = 64,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            val json =
                Json {
                    classDiscriminator = "type"
                    encodeDefaults = false
                }
            val loop = ConsumerLoop(consumer, config, json, shared, ioDispatcher)
            val bus = KafkaEventBus(config, producer, loop, shared, json)
            loop.start(scope)
            return bus
        }

        /**
         * Test-only factory: inject mocked producer/consumer and a test dispatcher.
         * When [ioDispatcher] is a `TestDispatcher`, the consumer loop runs under the
         * `runTest` scheduler — required for deterministic MockK verify assertions.
         */
        internal fun createForTest(
            config: KafkaEventBusConfig,
            producer: KafkaProducer<String, ByteArray>,
            consumer: KafkaConsumer<String, ByteArray>?,
            scope: CoroutineScope,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): KafkaEventBus {
            val shared =
                MutableSharedFlow<AgentEvent>(
                    replay = 0,
                    extraBufferCapacity = 64,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            val json =
                Json {
                    classDiscriminator = "type"
                    encodeDefaults = false
                }
            val loop = consumer?.let { ConsumerLoop(it, config, json, shared, ioDispatcher) }
            val bus = KafkaEventBus(config, producer, loop, shared, json)
            loop?.start(scope)
            return bus
        }

        private fun buildProducer(config: KafkaEventBusConfig): KafkaProducer<String, ByteArray> {
            val props =
                Properties().apply {
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
                    put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                    put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
                    put(ProducerConfig.ACKS_CONFIG, config.producerAcks)
                    put(ProducerConfig.LINGER_MS_CONFIG, config.producerLingerMs)
                    put(ProducerConfig.COMPRESSION_TYPE_CONFIG, config.producerCompressionType)
                }
            return KafkaProducer(props)
        }

        private fun buildConsumer(config: KafkaEventBusConfig): KafkaConsumer<String, ByteArray> {
            val props =
                Properties().apply {
                    put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
                    put(ConsumerConfig.GROUP_ID_CONFIG, config.resolveGroupId())
                    put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                    put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
                    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.consumerAutoOffsetReset)
                    put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, config.consumerEnableAutoCommit)
                }
            return KafkaConsumer(props)
        }
    }
}
