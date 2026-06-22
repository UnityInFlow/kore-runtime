plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.dep.mgmt)
    id("kore.publishing")
}

// Import the Spring Boot BOM so jackson versions are managed centrally.
// This keeps kore-skills in sync with the versions kore-spring will use in Wave 2.
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.5")
    }
}

dependencies {
    api(project(":kore-core"))
    // Gradle-native platform so the Spring Boot BOM (which manages the Jackson
    // versions) is PUBLISHED as a constraint into this module's Gradle Module Metadata.
    // The io.spring.dependency-management plugin does not export its managed versions,
    // so before 0.1.1 a Gradle consumer saw version-less jackson deps and failed to
    // resolve them (the KORE-06 0.1.0 defect).
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.5"))
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.coroutines.core)

    testImplementation(project(":kore-test"))
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

publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                name.set("kore-runtime — Skills")
                description.set(
                    "YAML-based skill definition loader with pattern-activation matching for kore-runtime.",
                )
            }
        }
    }
}
