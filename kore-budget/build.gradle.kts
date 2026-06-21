plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinter)
    id("kore.publishing")
}

group = "io.github.unityinflow"

dependencies {
    implementation(project(":kore-core"))
    implementation("io.github.unityinflow:budget-breaker:0.0.1")
    implementation(libs.coroutines.core)

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

publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                name.set("kore-runtime — Budget enforcement adapter")
                description.set(
                    "budget-breaker (Tool 05) implementation of the kore-runtime BudgetEnforcer port.",
                )
            }
        }
    }
}
