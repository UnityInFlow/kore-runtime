plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":kore-core"))
    implementation(libs.coroutines.core)
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.serialization.json)
    // Ktor CIO engine needed to instantiate HttpClient for SseClientTransport
    // SSE plugin is bundled inside ktor-client-core (already transitive from mcp-kotlin-sdk)
    implementation(libs.ktor.client.cio)

    testImplementation(project(":kore-test"))
    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
