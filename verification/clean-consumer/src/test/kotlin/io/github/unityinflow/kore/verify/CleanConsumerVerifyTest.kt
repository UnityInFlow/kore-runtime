package io.github.unityinflow.kore.verify

import io.github.unityinflow.kore.core.port.EventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration

/**
 * Clean-consumer verification test (KORE-06).
 *
 * This test is the automated proof that "published" means *clean-consumer-resolvable from Maven
 * Central*. Unlike kore's in-repo tests, this build:
 *
 * - resolves `io.github.unityinflow:kore-spring` and `io.github.unityinflow:kore-core` from Maven
 *   Central ONLY (no local-repo lookup, no sibling-module dependency), with their versions aligned
 *   by the `io.github.unityinflow:kore-bom` `platform(...)` import (D-05), and
 * - does NOT import the auto-config class explicitly. Instead it uses [EnableAutoConfiguration],
 *   which forces Spring to discover auto-configurations from the classpath via each jar's
 *   `META-INF/spring/...AutoConfiguration.imports`. That exercises the published kore-spring jar's
 *   imports file itself (`KoreAutoConfiguration`), not merely the auto-config class.
 *
 * It then asserts the [EventBus] bean auto-registers — `KoreAutoConfiguration` registers an
 * `InProcessEventBus` as the default `EventBus`. A non-null bean confirms the published kore-spring
 * jar's `AutoConfiguration.imports` was discovered on the classpath, `KoreAutoConfiguration` ran,
 * and the kore-core `EventBus` port resolved — i.e. the published curated set + BOM boot a Spring
 * context end-to-end straight off Maven Central.
 *
 * NOTE: this build will NOT compile/resolve until the coordinates are actually on Central
 * (Plan 04, after the human Publish). That failure-until-published behaviour IS the verification
 * mechanism — it is run via `scripts/verify-published.sh` once the artifacts have propagated.
 */
@SpringBootTest(
    classes = [CleanConsumerVerifyTest.TestConfig::class],
    // kore-spring is a library, not a web app on its own — no web environment needed here.
    properties = ["spring.main.web-application-type=none"],
)
class CleanConsumerVerifyTest {

    @Autowired
    lateinit var eventBus: EventBus

    @Test
    fun `EventBus auto-registers from the published kore-spring jar`() {
        // If this is non-null, the published kore-spring jar's AutoConfiguration.imports was
        // discovered on the classpath and KoreAutoConfiguration ran — the published curated set
        // + kore-bom are consumable from Central.
        assertThat(eventBus).isNotNull
    }

    /**
     * Minimal consumer configuration.
     *
     * [EnableAutoConfiguration] (NOT an explicit per-class auto-config import) makes Spring scan
     * the classpath's `AutoConfiguration.imports` files — proving the published jar's imports entry.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class TestConfig
}
