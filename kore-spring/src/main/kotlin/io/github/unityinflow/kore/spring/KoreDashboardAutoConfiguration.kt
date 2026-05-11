package io.github.unityinflow.kore.spring

import io.github.unityinflow.kore.core.port.AuditLog
import io.github.unityinflow.kore.core.port.EventBus
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Extracted from KoreAutoConfiguration to a separate top-level file because the
 * [KoreDashboardPropertiesAdapter] implements [io.github.unityinflow.kore.dashboard.DashboardServer.DashboardProperties]
 * — the JVM resolves extends/implements clauses eagerly at class load time, BEFORE
 * Spring's @ConditionalOnClass check runs. When kore-dashboard is absent from the
 * classpath, keeping this as an inner class of KoreAutoConfiguration causes
 * NoClassDefFoundError on `DashboardServer$DashboardProperties` during context refresh.
 *
 * Registered as a separate entry in META-INF/spring/...AutoConfiguration.imports.
 */
@AutoConfiguration(after = [KoreAutoConfiguration::class])
@ConditionalOnClass(name = ["io.github.unityinflow.kore.dashboard.DashboardServer"])
class KoreDashboardAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(io.github.unityinflow.kore.dashboard.DashboardServer::class)
    fun dashboardServer(
        eventBus: EventBus,
        auditLog: AuditLog,
        properties: KoreProperties,
    ): io.github.unityinflow.kore.dashboard.DashboardServer =
        io.github.unityinflow.kore.dashboard.DashboardServer(
            eventBus = eventBus,
            auditLog = auditLog,
            properties = KoreDashboardPropertiesAdapter(properties.dashboard),
        )

    private class KoreDashboardPropertiesAdapter(
        private val delegate: KoreProperties.DashboardProperties,
    ) : io.github.unityinflow.kore.dashboard.DashboardServer.DashboardProperties {
        override val port: Int get() = delegate.port
        override val path: String get() = delegate.path
        override val enabled: Boolean get() = delegate.enabled
    }
}
