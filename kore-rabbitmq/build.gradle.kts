plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinter)
    id("kore.publishing")
}

group = "dev.unityinflow"

dependencies {
    implementation(project(":kore-core"))
    implementation(libs.coroutines.core)
    implementation(libs.amqp.client)
    implementation(libs.serialization.json)

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(project(":kore-test"))
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform {
        // Integration tests excluded from default run per 04-VALIDATION.md
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed integration tests for kore-rabbitmq."
    group = "verification"
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                name.set("kore-runtime — RabbitMQ EventBus adapter")
                description.set(
                    "Opt-in RabbitMQ implementation of the kore-runtime EventBus port using fanout exchanges and publisher confirms.",
                )
            }
        }
    }
}
