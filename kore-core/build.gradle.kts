plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.coroutines.core)

    // OpenTelemetry is optional (D-11 graceful degradation): callers only
    // depend on it if they opt in via the AgentLoop `tracer` constructor
    // parameter. compileOnly keeps kore-core's runtime classpath clean.
    compileOnly("io.opentelemetry:opentelemetry-api:1.49.0")

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testImplementation(project(":kore-test"))
    // For the AgentLoopSkillTest OTel span-export assertion
    testImplementation("io.opentelemetry:opentelemetry-api:1.49.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk:1.49.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.49.0")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
