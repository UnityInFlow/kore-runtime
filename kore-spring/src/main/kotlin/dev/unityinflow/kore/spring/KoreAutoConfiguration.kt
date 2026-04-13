package dev.unityinflow.kore.spring

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties

/**
 * Spring Boot 4 auto-configuration entry point for kore-runtime.
 *
 * NOTE: This stub registers [KoreProperties] only. The full conditional bean
 * graph (event bus, budget enforcer, audit log, skill registry, LLM backends,
 * storage, observability, dashboard) is added in Task 2 of plan 03-02.
 */
@AutoConfiguration
@EnableConfigurationProperties(KoreProperties::class)
class KoreAutoConfiguration
