plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("kore.publishing")
}

dependencies {
    api(project(":kore-core"))
    api(libs.mockk)
    api(libs.coroutines.test)
    api(libs.kotest.assertions)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(libs.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                name.set("kore-runtime — Test")
                description.set(
                    "MockLLMBackend + session recording / replay for deterministic kore-runtime agent testing.",
                )
            }
        }
    }
}
