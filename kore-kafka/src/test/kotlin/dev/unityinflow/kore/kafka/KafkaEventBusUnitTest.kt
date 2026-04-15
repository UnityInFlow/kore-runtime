package dev.unityinflow.kore.kafka

import dev.unityinflow.kore.core.AgentEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KafkaEventBusUnitTest {
    @Test
    fun `consumer group id follows hostname-pid pattern`() =
        runTest {
            val config =
                KafkaEventBusConfig(
                    bootstrapServers = "localhost:9092",
                    topic = "kore-agent-events",
                    groupIdPrefix = "kore",
                )
            val groupId = config.resolveGroupId()
            groupId shouldStartWith "kore-"
            // prefix-hostname-pid — hostname itself may contain dashes, so just assert >= 3 parts.
            (groupId.split("-").size >= 3) shouldBe true
        }

    @Test
    fun `emit bridges producer send callback via suspendCancellableCoroutine`() =
        runTest {
            val producer = mockk<KafkaProducer<String, ByteArray>>(relaxed = true)
            val recordSlot = slot<ProducerRecord<String, ByteArray>>()
            val callbackSlot = slot<Callback>()
            every { producer.send(capture(recordSlot), capture(callbackSlot)) } answers {
                // Simulate success callback
                val metadata = RecordMetadata(TopicPartition("kore-agent-events", 0), 0L, 0, 0L, 0, 0)
                callbackSlot.captured.onCompletion(metadata, null)
                mockk(relaxed = true)
            }
            val bus =
                KafkaEventBus.createForTest(
                    config = KafkaEventBusConfig("localhost:9092", "kore-agent-events"),
                    producer = producer,
                    consumer = null,
                    scope = backgroundScope,
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            bus.emit(AgentEvent.AgentStarted(agentId = "a1", taskId = "t1"))

            verify(exactly = 1) { producer.send(any<ProducerRecord<String, ByteArray>>(), any()) }

            // Structural JSON assertion — resilient to field-order changes.
            val body = String(recordSlot.captured.value())
            val parsed = Json.parseToJsonElement(body).jsonObject
            parsed["type"]?.jsonPrimitive?.content shouldBe "AgentStarted"
            parsed["agentId"]?.jsonPrimitive?.content shouldBe "a1"
            parsed["taskId"]?.jsonPrimitive?.content shouldBe "t1"
        }

    @Test
    fun `emit propagates producer exception as suspend failure`() =
        runTest {
            val producer = mockk<KafkaProducer<String, ByteArray>>(relaxed = true)
            val callbackSlot = slot<Callback>()
            every { producer.send(any<ProducerRecord<String, ByteArray>>(), capture(callbackSlot)) } answers {
                callbackSlot.captured.onCompletion(null, RuntimeException("broker unreachable"))
                mockk(relaxed = true)
            }
            val bus =
                KafkaEventBus.createForTest(
                    config = KafkaEventBusConfig("localhost:9092", "kore-agent-events"),
                    producer = producer,
                    consumer = null,
                    scope = backgroundScope,
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            try {
                bus.emit(AgentEvent.AgentStarted(agentId = "a1", taskId = "t1"))
                error("expected exception")
            } catch (ex: RuntimeException) {
                ex.message shouldBe "broker unreachable"
            }
        }
}
