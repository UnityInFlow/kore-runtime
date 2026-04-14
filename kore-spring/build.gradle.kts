plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.dep.mgmt)
    id("org.jetbrains.kotlin.plugin.spring") version
        libs.versions.kotlin
            .get()
}

// Import the Spring Boot BOM so all Spring Boot / actuator versions
// stay in sync with kore-skills (Wave 1) and Wave 2 dashboard.
// We deliberately do NOT apply the spring-boot application plugin —
// kore-spring is a starter library, not an executable application.
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.5")
    }
}

dependencies {
    api(project(":kore-core"))

    // Optional kore modules — present at compile time so direct constructor
    // references work, gated by @ConditionalOnClass(name=[...]) at runtime
    // (RESEARCH.md Pattern 6 / Pitfall 12).
    compileOnly(project(":kore-llm"))
    compileOnly(project(":kore-skills"))
    compileOnly(project(":kore-observability"))
    compileOnly(project(":kore-storage"))
    // kore-dashboard is present at compile time so DashboardAutoConfiguration
    // can call the DashboardServer constructor directly (plan 03-04 swap from
    // reflective bridge). Still gated by @ConditionalOnClass at runtime so the
    // host can exclude kore-dashboard via Gradle exclusion if desired.
    compileOnly(project(":kore-dashboard"))

    // The compileOnly project deps above only expose their own classes.
    // KoreAutoConfiguration also references symbols from THEIR compileOnly
    // transitive deps (R2dbcDatabase from exposed-r2dbc, Tracer from
    // opentelemetry-api), so we must redeclare them as compileOnly here too.
    // At runtime the host application supplies these libraries via the
    // Spring Boot 4 BOM (otel) or Exposed (storage), gated by @ConditionalOnClass.
    compileOnly(libs.exposed.r2dbc)
    compileOnly("io.opentelemetry:opentelemetry-api:1.49.0")

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    implementation(libs.coroutines.core)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // kore-llm on test classpath so the @ConditionalOnClass(name = ["...ClaudeBackend"])
    // gate fires and we can assert auto-wired LLM backend bean creation (D-15).
    testImplementation(project(":kore-llm"))
    // kore-dashboard on test classpath so the full Spring context integration
    // test can assert the DashboardServer bean is wired end-to-end (plan 03-04).
    testImplementation(project(":kore-dashboard"))
    // kore-skills on test classpath so the SkillRegistryAdapter @ConditionalOnClass
    // gate fires for the integration test.
    testImplementation(project(":kore-skills"))
    // kore-test provides MockLLMBackend for the integration test's @Bean agent.
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
