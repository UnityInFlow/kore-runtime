plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinter) apply false
    // Sonatype Central Portal aggregation (D-05 / D-07). The nmcp plugin
    // marker is already on the build classpath via buildSrc's dependency on
    // `com.gradleup.nmcp:nmcp:1.4.4` (applied in the kore.publishing
    // convention plugin), so we apply the aggregation sibling plugin
    // WITHOUT a version to avoid "plugin already on classpath with unknown
    // version" resolution conflict (deviation — Rule 3 blocking fix).
    id("com.gradleup.nmcp.aggregation")
}

subprojects {
    apply(plugin = "org.jmailen.kotlinter")

    group = "dev.unityinflow"
    version = "0.0.1-SNAPSHOT" // Bumped to "0.0.1" in plan 04-06 at release time

    repositories {
        mavenCentral()
    }
}

// ── Sonatype Central Portal aggregation (D-05 / D-07 / Pattern 7) ──────────
//
// nmcp's aggregation plugin collects the `maven` publication from each
// subproject listed below and bundles them for a SINGLE upload to the
// Sonatype Central Portal. `publishingType = "USER_MANAGED"` means the
// staging bundle is NOT auto-released — it must be manually released via
// the portal UI after inspection (D-07 + Pitfall 4 defense).
//
// Pitfall 13: explicit list of all 11 publishable modules. If this list
// is empty or partial, `publishAggregationToCentralPortal` silently
// publishes nothing or a partial bundle. The acceptance criterion is
// `grep -c "nmcpAggregation(project" build.gradle.kts` == 11.

nmcpAggregation {
    centralPortal {
        username = providers.environmentVariable("SONATYPE_USERNAME")
        password = providers.environmentVariable("SONATYPE_PASSWORD")
        publishingType = "USER_MANAGED"
        publicationName = "kore-${project.version}"
    }
}

dependencies {
    // Every publishable module — D-05 lists all 11.
    nmcpAggregation(project(":kore-core"))
    nmcpAggregation(project(":kore-llm"))
    nmcpAggregation(project(":kore-mcp"))
    nmcpAggregation(project(":kore-observability"))
    nmcpAggregation(project(":kore-storage"))
    nmcpAggregation(project(":kore-skills"))
    nmcpAggregation(project(":kore-spring"))
    nmcpAggregation(project(":kore-dashboard"))
    nmcpAggregation(project(":kore-test"))
    nmcpAggregation(project(":kore-kafka"))
    nmcpAggregation(project(":kore-rabbitmq"))
}
