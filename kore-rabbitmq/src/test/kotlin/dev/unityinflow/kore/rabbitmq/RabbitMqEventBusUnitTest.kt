package dev.unityinflow.kore.rabbitmq

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import dev.unityinflow.kore.core.AgentEvent
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RabbitMqEventBus] using MockK and `UnconfinedTestDispatcher`.
 *
 * Pitfall 7 defense: construction must NOT open a broker connection.
 * H3 fix: dispatcher is injected so the consumer coroutine runs under runTest's
 * virtual scheduler (Dispatchers.IO is not virtualized by runTest).
 * MockK chained-stub gotcha (H3): use an explicit intermediate mock for
 * [AMQP.Queue.DeclareOk] instead of chaining `queueDeclare(...).queue`.
 * H4 fix: JSON body assertions are structural via `Json.parseToJsonElement`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RabbitMqEventBusUnitTest {
    @Test
    fun `construction does not open connection eagerly (Pitfall 7)`() =
        runTest {
            val factory = mockk<ConnectionFactory>(relaxed = true)
            // Factory's newConnection() would throw if called — but we assert it is NOT called.
            every { factory.newConnection() } answers { error("eager connection is forbidden") }

            // Construction must not open a connection.
            RabbitMqEventBus.createForTest(
                config =
                    RabbitMqEventBusConfig(
                        uri = "amqp://localhost:5672",
                        exchange = "kore.agent-events",
                    ),
                factory = factory,
                scope = backgroundScope,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
            verify(exactly = 0) { factory.newConnection() }
        }

    @Test
    fun `emit publishes persistent message with correct body (structural JSON)`() =
        runTest {
            val factory = mockk<ConnectionFactory>(relaxed = true)
            val connection = mockk<Connection>(relaxed = true)
            val publishChannel = mockk<Channel>(relaxed = true)
            val consumerChannel = mockk<Channel>(relaxed = true)

            every { factory.newConnection() } returns connection
            every { connection.createChannel() } returnsMany listOf(publishChannel, consumerChannel)

            // Explicit intermediate mock for AMQP.Queue.DeclareOk — avoids MockK chained-stub
            // gotcha where `queueDeclare(...).queue` returns an unmocked final type.
            val declareOk = mockk<AMQP.Queue.DeclareOk>()
            every { declareOk.queue } returns "q-exclusive"
            every {
                consumerChannel.queueDeclare(any(), any(), any(), any(), any())
            } returns declareOk

            val bodySlot = slot<ByteArray>()
            every {
                publishChannel.basicPublish(
                    any<String>(),
                    any<String>(),
                    any(),
                    capture(bodySlot),
                )
            } returns Unit

            val bus =
                RabbitMqEventBus.createForTest(
                    config =
                        RabbitMqEventBusConfig(
                            uri = "amqp://localhost:5672",
                            exchange = "kore.agent-events",
                        ),
                    factory = factory,
                    scope = backgroundScope,
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            bus.emit(AgentEvent.AgentStarted(agentId = "a1", taskId = "t1"))

            verify { publishChannel.confirmSelect() }
            verify { publishChannel.exchangeDeclare("kore.agent-events", "fanout", true) }
            verify { publishChannel.basicPublish("kore.agent-events", "", any(), any()) }
            verify { publishChannel.waitForConfirmsOrDie(any<Long>()) }

            // Structural JSON assertion — resilient to field-order changes.
            val parsed = Json.parseToJsonElement(String(bodySlot.captured)).jsonObject
            parsed["type"]?.jsonPrimitive?.content shouldBe "AgentStarted"
            parsed["agentId"]?.jsonPrimitive?.content shouldBe "a1"
            parsed["taskId"]?.jsonPrimitive?.content shouldBe "t1"
        }

    @Test
    fun `subscribe declares exclusive auto-delete queue bound to fanout exchange`() =
        runTest {
            val factory = mockk<ConnectionFactory>(relaxed = true)
            val connection = mockk<Connection>(relaxed = true)
            val publishChannel = mockk<Channel>(relaxed = true)
            val consumerChannel = mockk<Channel>(relaxed = true)

            every { factory.newConnection() } returns connection
            every { connection.createChannel() } returnsMany listOf(publishChannel, consumerChannel)

            val declareOk = mockk<AMQP.Queue.DeclareOk>()
            every { declareOk.queue } returns "q-exclusive"
            every {
                consumerChannel.queueDeclare(any(), any(), any(), any(), any())
            } returns declareOk

            val bus =
                RabbitMqEventBus.createForTest(
                    config =
                        RabbitMqEventBusConfig(
                            uri = "amqp://localhost:5672",
                            exchange = "kore.agent-events",
                        ),
                    factory = factory,
                    scope = backgroundScope,
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            // Trigger consumer start by subscribing + launching a collector under the
            // virtualized dispatcher. UnconfinedTestDispatcher runs the consumer immediately.
            val flow = bus.subscribe()
            val job = launch { flow.collect { /* no-op */ } }
            runCurrent()
            advanceUntilIdle()

            verify { consumerChannel.exchangeDeclare("kore.agent-events", "fanout", true) }
            verify { consumerChannel.queueDeclare("", false, true, true, null) }
            verify { consumerChannel.queueBind("q-exclusive", "kore.agent-events", "") }
            verify { consumerChannel.basicConsume("q-exclusive", false, any<DeliverCallback>(), any<CancelCallback>()) }
            job.cancel()
        }
}
