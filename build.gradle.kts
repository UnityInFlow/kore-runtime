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

repositories {
    mavenCentral()
}

// Single source of truth for the published coordinate (KORE-05). NOT
// gradle.properties. Set on allprojects (incl. the root) so the release.yml
// tag-vs-version guard can read it via `./gradlew properties` at the root.
allprojects {
    group = "io.github.unityinflow"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "org.jmailen.kotlinter")

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
// Pitfall 13: explicit list of the curated 10-member v0.1.0 bundle (the
// curated 9 stable modules + kore-bom). If this list is empty or partial,
// `publishAggregationToCentralPortal` silently publishes nothing or a
// partial bundle. The acceptance criterion is that the count of
// nmcp aggregation entries below equals 10 (release.yml enforces this).
//
// D-04: kore-dashboard / kore-kafka / kore-rabbitmq are EXCLUDED (experimental).
// D-05 / KORE-07: kore-budget (the published 05→08 deliverable) and kore-bom
// MUST both be present. release.yml gates the count, membership, and exclusions
// before the irreversible Central publish.

nmcpAggregation {
    centralPortal {
        username = providers.environmentVariable("SONATYPE_USERNAME")
        password = providers.environmentVariable("SONATYPE_PASSWORD")
        publishingType = "USER_MANAGED"
        publicationName = "kore-${project.version}"
    }
}

dependencies {
    // Curated v0.1.0 bundle = the 9 stable modules + kore-bom (count 10).
    // kore-dashboard / kore-kafka / kore-rabbitmq are EXCLUDED (D-04).
    nmcpAggregation(project(":kore-core"))
    nmcpAggregation(project(":kore-llm"))
    nmcpAggregation(project(":kore-mcp"))
    nmcpAggregation(project(":kore-observability"))
    nmcpAggregation(project(":kore-storage"))
    nmcpAggregation(project(":kore-skills"))
    nmcpAggregation(project(":kore-spring"))
    nmcpAggregation(project(":kore-test"))
    nmcpAggregation(project(":kore-budget")) // 05→08 published deliverable (KORE-07) — must be present
    nmcpAggregation(project(":kore-bom")) // curated BOM (D-05)
}
