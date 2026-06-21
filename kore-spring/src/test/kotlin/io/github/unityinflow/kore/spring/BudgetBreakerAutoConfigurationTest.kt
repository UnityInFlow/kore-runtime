package io.github.unityinflow.kore.spring

import io.github.unityinflow.kore.budget.BudgetBreakerAdapter
import io.github.unityinflow.kore.core.internal.InMemoryBudgetEnforcer
import io.github.unityinflow.kore.core.port.BudgetEnforcer
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Spring context tests for [KoreAutoConfiguration.BudgetBreakerAutoConfiguration]
 * (plan 06-02 / BUDG-05 / D-01 / D-06).
 *
 * Proves the bean-selection matrix: the real [BudgetBreakerAdapter] is the sole
 * [BudgetEnforcer] bean ONLY when `kore-budget` is on the classpath AND
 * `kore.budget.enabled=true`; in every other case the zero-config
 * [InMemoryBudgetEnforcer] default wins (existing apps behave identically).
 *
 * Unlike the Kafka test, [BudgetBreakerAdapter] opens no socket at construction,
 * so each scenario resolves the single bean via `getBean(BudgetEnforcer::class.java)`
 * and asserts its concrete type — which also implicitly asserts exactly ONE
 * `BudgetEnforcer` bean exists (`getBean` throws on duplicates), the T-06-05
 * tampering regression guard.
 */
class BudgetBreakerAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoreAutoConfiguration::class.java))
            // Disable dashboard SmartLifecycle so nothing binds port 8090 during tests.
            .withPropertyValues("kore.dashboard.enabled=false")

    @Test
    fun `enabled=true + kore-budget present wires BudgetBreakerAdapter as the sole BudgetEnforcer`() {
        contextRunner
            .withPropertyValues("kore.budget.enabled=true")
            .run { ctx ->
                ctx.getBean(BudgetEnforcer::class.java).shouldBeInstanceOf<BudgetBreakerAdapter>()
            }
    }

    @Test
    fun `enabled unset keeps InMemoryBudgetEnforcer default`() {
        contextRunner.run { ctx ->
            ctx.getBean(BudgetEnforcer::class.java).shouldBeInstanceOf<InMemoryBudgetEnforcer>()
        }
    }

    @Test
    fun `enabled=false keeps InMemoryBudgetEnforcer default`() {
        contextRunner
            .withPropertyValues("kore.budget.enabled=false")
            .run { ctx ->
                ctx.getBean(BudgetEnforcer::class.java).shouldBeInstanceOf<InMemoryBudgetEnforcer>()
            }
    }

    @Test
    fun `kore-budget absent keeps InMemoryBudgetEnforcer default even with enabled=true`() {
        // FilteredClassLoader hides BudgetBreakerAdapter from the classpath, simulating
        // an app that depends on kore-spring but NOT kore-budget. The @ConditionalOnClass
        // gate must keep the in-memory default AND the context must still start (T-06-04).
        contextRunner
            .withPropertyValues("kore.budget.enabled=true")
            .withClassLoader(FilteredClassLoader(BudgetBreakerAdapter::class.java))
            .run { ctx ->
                ctx.getBean(BudgetEnforcer::class.java).shouldBeInstanceOf<InMemoryBudgetEnforcer>()
            }
    }
}
