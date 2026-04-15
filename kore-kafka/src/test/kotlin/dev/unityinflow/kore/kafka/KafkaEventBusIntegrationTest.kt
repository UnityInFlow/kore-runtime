package dev.unityinflow.kore.kafka

import dev.unityinflow.kore.core.AgentEvent
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@OptIn(ExperimentalCoroutinesApi::class)
class KafkaEventBusIntegrationTest {
    companion object {
        @JvmStatic
        val kafka: KafkaContainer =
            KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0")).also { it.start() }
    }

    @Test
    fun `two bus instances both receive the same event (broadcast)`() =
        runTest {
            val config =
                KafkaEventBusConfig(
                    bootstrapServers = kafka.bootstrapServers,
                    topic = "kore-test-${System.currentTimeMillis()}",
                    groupIdPrefix = "kore-broadcast-test",
                )
            val bus1 = KafkaEventBus(config, scope = backgroundScope)
            val bus2 = KafkaEventBus(config, scope = backgroundScope)

            val event = AgentEvent.AgentStarted(agentId = "a1", taskId = "t1")
            val received1 = async { withTimeout(30.seconds) { bus1.subscribe().first() } }
            val received2 = async { withTimeout(30.seconds) { bus2.subscribe().first() } }
            // Give consumers a moment to join
            delay(2000)
            bus1.emit(event)
            received1.await() shouldBe event
            received2.await() shouldBe event
            bus1.close()
            bus2.close()
        }
}
