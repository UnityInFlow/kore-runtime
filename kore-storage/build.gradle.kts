import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import java.util.concurrent.atomic.AtomicInteger

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinter)
    jacoco
    id("kore.publishing")
}

group = "io.github.unityinflow"

dependencies {
    implementation(project(":kore-core"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.exposed.java.time)
    implementation(libs.r2dbc.postgresql) // compile dep: JsonbTypeMapper wraps Json.of() from r2dbc-postgresql
    runtimeOnly(libs.postgresql.jdbc) // Flyway needs JDBC driver (Pitfall 14 / D-20)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.serialization.core) // AgentEvent/AgentResult @Serializable <clinit> needs this at runtime
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Tag-filtered integration test task (CI-01, D-09/D-10/D-11). Runs ONLY the
// @Tag("integration") Testcontainers tests against real PostgreSQL. Reuses the
// existing src/test source set (D-09 — no source-set move) and is deliberately
// decoupled from build/check (D-11) so the default `./gradlew build` stays fast
// and Docker-free. Fails loudly (GradleException) if the tag filter matches 0
// tests, making a silently-empty filter impossible (CI-01 headline behavior).
tasks.register<Test>("integrationTest") {
    description = "Runs kore-storage Testcontainers integration tests (CI-01)."
    group = "verification"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    useJUnitPlatform {
        includeTags("integration")
    }

    // Zero-test guard (D-10 / CI-02). `val` holding a mutable counter is
    // CLAUDE.md no-var compliant. Use addTestListener(TestListener) — NOT
    // afterSuite {}, which is deprecated in Gradle 9, removed in Gradle 10,
    // and config-cache-incompatible (RESEARCH Pattern 1).
    val executed = AtomicInteger(0)
    addTestListener(
        object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) = Unit

            override fun afterSuite(
                suite: TestDescriptor,
                result: TestResult,
            ) = Unit

            override fun beforeTest(testDescriptor: TestDescriptor) = Unit

            override fun afterTest(
                testDescriptor: TestDescriptor,
                result: TestResult,
            ) {
                executed.incrementAndGet()
            }
        },
    )

    doLast {
        if (executed.get() == 0) {
            throw GradleException(
                "integrationTest executed 0 tests — the 'integration' tag filter matched nothing. " +
                    "Failing loudly (CI-01). Check @Tag(\"integration\") on kore-storage test classes.",
            )
        }
    }
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                name.set("kore-runtime — Storage")
                description.set(
                    "PostgreSQL audit log adapter via Exposed + Flyway for kore-runtime.",
                )
            }
        }
    }
}
