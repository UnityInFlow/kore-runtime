package dev.unityinflow.kore.rabbitmq

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
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.seconds

/**
 * Testcontainers-backed broadcast test — two RabbitMqEventBus instances against the
 * same broker should each receive the emitted event (D-04 broadcast semantics).
 *
 * Tagged `integration` so `./gradlew :kore-rabbitmq:test` excludes it; run via
 * `./gradlew :kore-rabbitmq:integrationTest` (requires Docker).
 */
@Tag("integration")
@Testcontainers
@OptIn(ExperimentalCoroutinesApi::class)
class RabbitMqEventBusIntegrationTest {
    companion object {
        @JvmStatic
        @Container
        val rabbit: RabbitMQContainer = RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"))
    }

    @Test
    fun `two bus instances both receive the same event (broadcast)`() =
        runTest {
            val config =
                RabbitMqEventBusConfig(
                    uri = rabbit.amqpUrl,
                    exchange = "kore.test.${System.currentTimeMillis()}",
                )
            val bus1 = RabbitMqEventBus(config, scope = backgroundScope)
            val bus2 = RabbitMqEventBus(config, scope = backgroundScope)

            val event = AgentEvent.AgentStarted(agentId = "a1", taskId = "t1")
            val r1 = async { withTimeout(30.seconds) { bus1.subscribe().first() } }
            val r2 = async { withTimeout(30.seconds) { bus2.subscribe().first() } }
            delay(1000) // let consumers bind
            bus1.emit(event)
            r1.await() shouldBe event
            r2.await() shouldBe event

            bus1.close()
            bus2.close()
        }
}
