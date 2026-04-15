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
    implementation(libs.kafka.clients) {
        // Pitfall 12: avoid transitive Jackson version skew with Spring Boot BOM users.
        exclude(group = "com.fasterxml.jackson.core")
    }
    implementation(libs.serialization.json)

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(project(":kore-test"))
    testImplementation(libs.testcontainers.kafka)
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
    description = "Runs Testcontainers-backed integration tests for kore-kafka."
    group = "verification"
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                name.set("kore-runtime — Kafka EventBus adapter")
                description.set(
                    "Opt-in Apache Kafka implementation of the kore-runtime EventBus port.",
                )
            }
        }
    }
}
