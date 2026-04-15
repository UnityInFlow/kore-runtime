package dev.unityinflow.kore.rabbitmq

import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.MessageProperties
import dev.unityinflow.kore.core.AgentEvent
import dev.unityinflow.kore.core.port.EventBus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in RabbitMQ implementation of [EventBus] (EVNT-04 / D-02).
 *
 * - Fanout exchange with durable=true (messages survive broker restart; queues don't).
 * - Persistent delivery + publisher confirms (waitForConfirmsOrDie propagates nacks as
 *   exceptions — correct failure mode per D-02, do not silently drop).
 * - Per-JVM exclusive auto-delete queue (D-04 broadcast).
 * - Lazy connection (Pitfall 7): construction does NOT open a broker connection;
 *   the first emit() or subscribe() opens the connection via `by lazy`. This allows
 *   Spring context startup to succeed even when the broker is temporarily unreachable.
 * - Dispatcher injection: [ioDispatcher] defaults to [Dispatchers.IO] but tests pass a
 *   `TestDispatcher` so the consumer loop runs under `runTest`'s virtual scheduler.
 *
 * Hexagonal: zero Spring AMQP dependency. kore-spring wires this via
 * `@ConditionalOnClass` + `@ConditionalOnProperty(havingValue = "rabbitmq")` in
 * plan 04-04. Mirror-image shape of kore-kafka's [dev.unityinflow.kore.kafka.KafkaEventBus].
 *
 * Single public construction path: use `RabbitMqEventBus(config, scope)` via the
 * companion `invoke` — the primary constructor is internal.
 */
class RabbitMqEventBus internal constructor(
    private val config: RabbitMqEventBusConfig,
    private val factory: ConnectionFactory,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EventBus,
    AutoCloseable {
    private val json =
        Json {
            classDiscriminator = "type"
            encodeDefaults = false
        }

    // Lazy connection — not opened until first emit() or subscribe(). Pitfall 7.
    private val connection: Connection by lazy { factory.newConnection() }

    // Publish channel — amqp-client Channel is NOT thread-safe, so we use a
    // dedicated channel for publishing separate from the consumer channel.
    private val publishChannel: Channel by lazy {
        connection.createChannel().apply {
            confirmSelect()
            exchangeDeclare(config.exchange, "fanout", true)
        }
    }

    private val shared =
        MutableSharedFlow<AgentEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val consumerJobRef = AtomicReference<Job?>(null)

    override suspend fun emit(event: AgentEvent) {
        val bytes = json.encodeToString(AgentEvent.serializer(), event).toByteArray()
        withContext(ioDispatcher) {
            publishChannel.basicPublish(
                config.exchange,
                "", // routing key ignored for fanout
                MessageProperties.PERSISTENT_BASIC,
                bytes,
            )
            publishChannel.waitForConfirmsOrDie(config.confirmTimeoutMillis)
        }
    }

    override fun subscribe(): Flow<AgentEvent> {
        // Lazy start: first call to subscribe() launches the consumer coroutine.
        if (consumerJobRef.get() == null) {
            val job = scope.launch(ioDispatcher) { runConsumer() }
            if (!consumerJobRef.compareAndSet(null, job)) {
                // Lost the race — cancel our job, another subscribe() beat us.
                job.cancel()
            }
        }
        return shared.asSharedFlow()
    }

    private suspend fun runConsumer() {
        val consumerChannel: Channel = connection.createChannel()
        consumerChannel.exchangeDeclare(config.exchange, "fanout", true)
        val queueName = consumerChannel.queueDeclare("", false, true, true, null).queue
        consumerChannel.queueBind(queueName, config.exchange, "")

        val deliverCallback =
            DeliverCallback { _, delivery ->
                try {
                    val event =
                        json.decodeFromString(
                            AgentEvent.serializer(),
                            delivery.body.decodeToString(),
                        )
                    shared.tryEmit(event) // DROP_OLDEST — never blocks
                    consumerChannel.basicAck(delivery.envelope.deliveryTag, false)
                } catch (ex: Exception) {
                    // Reject without requeue on decode failure — malformed messages should
                    // not loop back into the queue.
                    runCatching {
                        consumerChannel.basicNack(delivery.envelope.deliveryTag, false, false)
                    }
                }
            }
        consumerChannel.basicConsume(queueName, false, deliverCallback, CancelCallback { })
        try {
            awaitCancellation()
        } finally {
            runCatching { consumerChannel.close() }
        }
    }

    override fun close() {
        consumerJobRef.get()?.cancel()
        runCatching { publishChannel.close() }
        runCatching { connection.close(config.closeTimeoutMillis) }
    }

    companion object {
        /**
         * Primary public factory — uses `ConnectionFactory` configured from
         * [RabbitMqEventBusConfig.uri].
         */
        operator fun invoke(
            config: RabbitMqEventBusConfig,
            scope: CoroutineScope,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): RabbitMqEventBus {
            val factory =
                ConnectionFactory().apply {
                    setUri(config.uri)
                }
            return RabbitMqEventBus(config, factory, scope, ioDispatcher)
        }

        /**
         * Test-only factory: inject a mocked ConnectionFactory and a test dispatcher.
         * When [ioDispatcher] is a `TestDispatcher`, the consumer loop runs under the
         * `runTest` scheduler — required for deterministic MockK verify assertions.
         */
        internal fun createForTest(
            config: RabbitMqEventBusConfig,
            factory: ConnectionFactory,
            scope: CoroutineScope,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): RabbitMqEventBus = RabbitMqEventBus(config, factory, scope, ioDispatcher)
    }
}
