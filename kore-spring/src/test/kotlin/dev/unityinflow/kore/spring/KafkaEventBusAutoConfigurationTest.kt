package dev.unityinflow.kore.spring

import dev.unityinflow.kore.core.internal.InProcessEventBus
import dev.unityinflow.kore.core.port.EventBus
import io.kotest.matchers.types.shouldBeInstanceOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Spring context tests for [KoreAutoConfiguration.KafkaEventBusAutoConfiguration]
 * (plan 04-04 / EVNT-03 / D-08).
 *
 * Uses [org.springframework.boot.test.context.assertj.AssertableApplicationContext.hasBean]
 * (via `assertThat(ctx).hasBean(...)`) to assert bean DEFINITION presence WITHOUT
 * triggering the `@Bean` factory. This is critical because
 * [dev.unityinflow.kore.kafka.KafkaEventBus] opens a real `KafkaProducer` and
 * `KafkaConsumer` against the bootstrap servers at construction — resolving
 * the bean would open a TCP socket to `localhost:9092` and fail context refresh.
 */
class KafkaEventBusAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoreAutoConfiguration::class.java))
            // Disable dashboard SmartLifecycle so nothing binds port 8090 during tests.
            .withPropertyValues("kore.dashboard.enabled=false")

    @Test
    fun `type=kafka + kore-kafka on classpath wires KafkaEventBus bean`() {
        contextRunner
            .withPropertyValues(
                "kore.event-bus.type=kafka",
                "kore.event-bus.kafka.bootstrap-servers=localhost:9092",
                "kore.event-bus.kafka.topic=kore-agent-events",
            ).run { ctx ->
                // Definition-level assertion — does NOT invoke the @Bean factory
                // and therefore does NOT open a real broker socket.
                assertThat(ctx).hasBean("kafkaEventBus")
            }
    }

    @Test
    fun `type=in-process keeps InProcessEventBus default`() {
        contextRunner
            .withPropertyValues("kore.event-bus.type=in-process")
            .run { ctx ->
                ctx.getBean(EventBus::class.java).shouldBeInstanceOf<InProcessEventBus>()
            }
    }

    @Test
    fun `type property absent keeps InProcessEventBus default`() {
        contextRunner.run { ctx ->
            ctx.getBean(EventBus::class.java).shouldBeInstanceOf<InProcessEventBus>()
        }
    }
}
