package dev.unityinflow.kore.spring

import dev.unityinflow.kore.core.internal.InProcessEventBus
import dev.unityinflow.kore.core.port.EventBus
import io.kotest.matchers.types.shouldBeInstanceOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Spring context tests for [KoreAutoConfiguration.RabbitMqEventBusAutoConfiguration]
 * (plan 04-04 / EVNT-04 / D-08).
 *
 * [dev.unityinflow.kore.rabbitmq.RabbitMqEventBus] uses lazy connection (Pitfall 7),
 * so in principle we COULD resolve the bean here. The same `assertThat(ctx).hasBean(...)`
 * definition-level assertion is used as in [KafkaEventBusAutoConfigurationTest] for
 * consistency AND to stay resilient to future refactors that might move the lazy
 * boundary.
 */
class RabbitMqEventBusAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoreAutoConfiguration::class.java))
            .withPropertyValues("kore.dashboard.enabled=false")

    @Test
    fun `type=rabbitmq + kore-rabbitmq on classpath wires RabbitMqEventBus bean`() {
        contextRunner
            .withPropertyValues(
                "kore.event-bus.type=rabbitmq",
                "kore.event-bus.rabbitmq.uri=amqp://localhost:5672",
                "kore.event-bus.rabbitmq.exchange=kore.agent-events",
            ).run { ctx ->
                assertThat(ctx).hasBean("rabbitMqEventBus")
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
}
