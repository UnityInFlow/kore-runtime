// Bare-minimum module stub for plan 04-01 Task 3. Wave 2 plan 04-03 will add
// the amqp-client + serialization-json implementation deps, Testcontainers
// integration tests, and the RabbitMqEventBus source files. Do NOT add
// external client deps here — they belong in 04-03's scoped edits to this
// file only.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinter)
}

group = "dev.unityinflow"

dependencies {
    implementation(project(":kore-core"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
