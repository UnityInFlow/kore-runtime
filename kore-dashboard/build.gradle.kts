plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":kore-core"))

    // Ktor 3.2 embedded server + HTML builder for kotlinx.html DSL.
    // ktor-server-htmx is intentionally NOT pulled in: HTMX attributes are
    // emitted as plain `hx-*` attributes via kotlinx.html `attributes[]`,
    // which works without installing the plugin (D-30, D-32). Avoiding the
    // plugin keeps us off `@OptIn(ExperimentalKtorApi::class)` (RESEARCH.md).
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.coroutines.core)

    // Spring SmartLifecycle interface only (no full Spring Boot dependency
    // here). kore-spring depends on kore-dashboard and brings full Spring
    // Framework at runtime via the Spring Boot 4.0.5 BOM.
    compileOnly("org.springframework:spring-context:7.0.0")

    testImplementation(project(":kore-test"))
    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testImplementation("io.ktor:ktor-server-test-host:3.2.0")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.2.0")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
