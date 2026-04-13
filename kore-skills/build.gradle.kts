plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.dep.mgmt)
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
