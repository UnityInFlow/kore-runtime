package io.github.unityinflow.kore.spring

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Spring context tests for the hierarchical-agent depth ceiling (plan 07-04 /
 * HIER-03 / D-08).
 *
 * Proves the property binding and the factory threading without opening any
 * network socket:
 *  - default: with no `kore.hierarchy.*` config the bound
 *    [KoreProperties.hierarchy.maxDepth] is 5 (criterion #5 — existing apps
 *    behave identically).
 *  - override: `kore.hierarchy.max-depth=9` binds to 9.
 *  - factory threading: the always-present [KoreAgentFactory] bean carries the
 *    configured `maxDepth`. This is a definition-level assertion against the
 *    factory's public `maxDepth` property — no agent is run and no LLM backend
 *    is constructed (the factory's `agent { }` is never invoked), so no socket
 *    is opened.
 */
class KoreHierarchyPropertiesTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KoreAutoConfiguration::class.java))
            // Disable dashboard SmartLifecycle so nothing binds port 8090 during tests.
            .withPropertyValues("kore.dashboard.enabled=false")

    @Test
    fun `default maxDepth is 5 when no kore-hierarchy config is set`() {
        contextRunner.run { ctx ->
            ctx.getBean(KoreProperties::class.java).hierarchy.maxDepth shouldBe 5
        }
    }

    @Test
    fun `kore-hierarchy-max-depth binds the override value`() {
        contextRunner
            .withPropertyValues("kore.hierarchy.max-depth=9")
            .run { ctx ->
                ctx.getBean(KoreProperties::class.java).hierarchy.maxDepth shouldBe 9
            }
    }

    @Test
    fun `KoreAgentFactory bean carries the default maxDepth`() {
        contextRunner.run { ctx ->
            ctx.getBean(KoreAgentFactory::class.java).maxDepth shouldBe 5
        }
    }

    @Test
    fun `KoreAgentFactory bean carries the configured maxDepth override`() {
        contextRunner
            .withPropertyValues("kore.hierarchy.max-depth=9")
            .run { ctx ->
                ctx.getBean(KoreAgentFactory::class.java).maxDepth shouldBe 9
            }
    }
}
