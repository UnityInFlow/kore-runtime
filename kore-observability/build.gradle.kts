plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinter)
    jacoco
}

group = "dev.unityinflow"

dependencies {
    implementation(project(":kore-core"))
    implementation(libs.otel.extension.kotlin)
    implementation(libs.coroutines.core)
    // OTel API and Micrometer provided by consumer (kore-spring via BOM) at runtime
    // For standalone compilation + tests, declare as compileOnly + testImplementation
    compileOnly("io.opentelemetry:opentelemetry-api:1.49.0")
    compileOnly("io.micrometer:micrometer-core:1.16.0")
    testImplementation("io.opentelemetry:opentelemetry-api:1.49.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.49.0")
    testImplementation("io.micrometer:micrometer-core:1.16.0")
    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
