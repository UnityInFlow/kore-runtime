plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinter)
    jacoco
    id("kore.publishing")
}

group = "dev.unityinflow"

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
